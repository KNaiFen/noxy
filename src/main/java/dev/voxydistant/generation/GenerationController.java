package dev.voxydistant.generation;

import dev.voxydistant.compat.VoxyBridge;
import dev.voxydistant.config.DistantConfig;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/** Client publishes immutable intent; only the server thread owns tickets and live chunks. */
public final class GenerationController {
    private static final Logger LOG = LogUtils.getLogger();
    private static final TicketType<ChunkPos> TICKET = TicketType.create("voxy_distant", Comparator.comparingLong(ChunkPos::toLong));
    private record Target(MinecraftServer server, ResourceKey<Level> dimension, int x, int z, int view, int radius, boolean paused) {}
    public record Status(int generating, int converting, long completed, double perSecond, long bytes, String reason, String failure) {}
    private static volatile Target target;
    private static volatile Status status = new Status(0, 0, 0, 0, 0, "等待单人世界", "");
    private static volatile boolean stopping;
    private static WorldEngine offeredEngine;
    private static ResourceKey<Level> offeredDimension;
    private static final List<Session> retired = new ArrayList<>();
    private static Session session;

    public static Status status() { return status; }

    public static synchronized void clientTick() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) {
            target = null;
            if (offeredEngine != null) { offeredEngine.releaseRef(); offeredEngine = null; }
            return;
        }
        if (stopping) return;
        var server = mc.getSingleplayerServer();
        if (offeredEngine != null && offeredDimension != mc.level.dimension()) {
            offeredEngine.releaseRef(); offeredEngine = null;
        }
        if (session == null || session.level.dimension() != mc.level.dimension()) {
            if (offeredEngine == null) offeredEngine = VoxyBridge.acquire(mc.level);
            offeredDimension = mc.level.dimension();
        }
        target = new Target(server, mc.level.dimension(), mc.player.chunkPosition().x, mc.player.chunkPosition().z,
                Math.max(mc.options.renderDistance().get(), server.getPlayerList().getViewDistance()),
                Math.min(DistantConfig.RADIUS.get(), VoxyBridge.radiusChunks()),
                mc.isPaused() || mc.screen != null && mc.screen.isPauseScreen());
    }

    public static synchronized void serverTick(MinecraftServer server) {
        if (stopping || server.isDedicatedServer()) return;
        retired.removeIf(Session::releaseIfFinished);
        var desired = target;
        if (session != null && (desired == null || desired.server() != server || desired.dimension() != session.level.dimension())) {
            session.close();
            retired.add(session);
            session = null;
        }
        if (desired == null || desired.server() != server) return;
        if (session == null) {
            if (offeredEngine == null) return;
            session = new Session(server.getLevel(desired.dimension()), offeredEngine);
            offeredEngine = null;
        }
        session.tick(desired);
    }

    public static synchronized void serverStopping(MinecraftServer server) {
        if (session != null && session.level.getServer() == server) {
            session.close();
            retired.add(session);
            session = null;
        }
        if (offeredEngine != null) { offeredEngine.releaseRef(); offeredEngine = null; }
        target = null;
    }

    public static void beforeVoxyShutdown() {
        MinecraftServer server;
        synchronized (GenerationController.class) {
            stopping = true;
            server = session == null ? null : session.level.getServer();
            target = null;
            if (offeredEngine != null) { offeredEngine.releaseRef(); offeredEngine = null; }
        }
        // Shutdown only: await resource cleanup, never wait for generation futures.
        // During ordinary gameplay every step is polled from server ticks.
        if (server != null) {
            if (server.isSameThread() || server.isStopped()) serverStopping(server);
            else server.submit(() -> serverStopping(server)).join();
        }
        synchronized (GenerationController.class) {
            for (var old : retired) old.awaitClosed();
            retired.clear();
            stopping = false;
        }
    }

    private static final class Session {
        private final ServerLevel level;
        private final WorldEngine engine;
        private final Map<Long, Pending> generating = new LinkedHashMap<>();
        private final Map<Long, Conversion> converting = new HashMap<>();
        private final Set<Long> failed = new HashSet<>();
        private final ArrayDeque<ChunkPos> candidates = new ArrayDeque<>();
        private final ConcurrentLinkedQueue<Conversion> finished = new ConcurrentLinkedQueue<>();
        private final AtomicLong memory = new AtomicLong();
        private final ThreadPoolExecutor workers;
        private final long started = System.nanoTime();
        private long completed, lastSubmissionTick = System.nanoTime();
        private double submissionTokens;
        private int centerX = Integer.MIN_VALUE, centerZ, radius = -1, view = -1;
        private NearbyChunks scan;
        private boolean exhausted, tickOverloaded;
        private String failure = "";
        private boolean released;
        private boolean wasEnabled = true;

        Session(ServerLevel level, WorldEngine engine) {
            this.level = level;
            this.engine = engine;
            int threads = DistantConfig.limits().threads();
            workers = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
                var thread = new Thread(task, "Voxy Distant conversion");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
        }

        void tick(Target desired) {
            var limits = DistantConfig.limits();
            if (limits.threads() != workers.getCorePoolSize()) {
                if (limits.threads() > workers.getMaximumPoolSize()) workers.setMaximumPoolSize(limits.threads());
                workers.setCorePoolSize(limits.threads());
                workers.setMaximumPoolSize(limits.threads());
            }
            drainFinished();
            if (centerX != desired.x() || centerZ != desired.z() || radius != desired.radius() || view != desired.view()) {
                centerX = desired.x(); centerZ = desired.z(); radius = desired.radius(); view = desired.view();
                resetScan();
                for (var iterator = generating.values().iterator(); iterator.hasNext();) {
                    var pending = iterator.next();
                    if (!inRange(pending.pos)) { release(pending); iterator.remove(); }
                }
                for (var conversion : converting.values()) if (!inRange(conversion.pos)) conversion.cancelled = true;
            }
            double ms = level.getServer().getAverageTickTime();
            if (ms > 45) tickOverloaded = true;
            else if (ms < 35) tickOverloaded = false;
            boolean enabled = DistantConfig.ENABLED.get() && VoxyBridge.enabled();
            if (!enabled) {
                for (var pending : generating.values()) release(pending);
                generating.clear(); candidates.clear();
                if (wasEnabled) resetScan();
                for (var conversion : converting.values()) conversion.cancelled = true;
            }
            wasEnabled = enabled;
            String reason = !enabled ? "已禁用" : desired.paused() ? "暂停菜单" : tickOverloaded ? "服务器 tick 超过预算"
                    : VoxyBridge.backedUp(engine) ? "Voxy 摄取或保存积压" : "生成中";
            if (enabled && !desired.paused() && !VoxyBridge.backedUp(engine)) snapshot(limits.snapshotMillis());
            int cap = DistantConfig.QUEUE.get();
            if (!candidates.isEmpty() && candidates.size() + generating.size() + converting.size() > cap) resetScan();
            long now = System.nanoTime();
            submissionTokens = Math.min(Math.max(1, limits.perSecond() / 20.0),
                    submissionTokens + (now - lastSubmissionTick) / 1e9 * limits.perSecond());
            lastSubmissionTick = now;
            if (reason.equals("生成中")) {
                refill(cap);
                long memoryLimit = DistantConfig.MEMORY.get() * 1024L * 1024L;
                long columnBytes = level.getSectionsCount() * ChunkSnapshot.RESERVED_BYTES;
                long retiringBytes = retired.stream().mapToLong(s -> s.memory.get()).sum();
                int retiringColumns = retired.stream().mapToInt(s -> s.converting.size()).sum();
                if (memory.get() + retiringBytes + columnBytes > memoryLimit) reason = "快照内存上限";
                else if (generating.size() + converting.size() >= cap) reason = "待处理列上限";
                else if (generating.size() >= limits.concurrency()) reason = "生成并发上限";
                else while (submissionTokens >= 1 && !candidates.isEmpty() && generating.size() < limits.concurrency()
                        && generating.size() + converting.size() + retiringColumns < cap
                        && memory.get() + retiringBytes + columnBytes <= memoryLimit) {
                    var pos = candidates.removeFirst();
                    // Reserving the whole column before adding a ticket bounds even partial snapshots.
                    level.getChunkSource().addRegionTicket(TICKET, pos, 0, pos); // FULL level 33; not ticking
                    memory.addAndGet(columnBytes);
                    var pending = new Pending(pos, columnBytes, VoxyBridge.coverage(engine).revision());
                    generating.put(pos.toLong(), pending);
                    submissionTokens--;
                }
                if (exhausted && generating.isEmpty() && converting.isEmpty()) reason = "范围内已完成";
            }
            status = new Status(generating.size(), converting.size() + retired.stream().mapToInt(s -> s.converting.size()).sum(), completed,
                    completed / Math.max(1.0, (System.nanoTime() - started) / 1e9),
                    memory.get() + retired.stream().mapToLong(s -> s.memory.get()).sum(), reason, failure);
        }

        private void resetScan() {
            candidates.clear(); scan = new NearbyChunks(radius, view); exhausted = false;
        }

        private boolean inRange(ChunkPos pos) {
            long dx = (long) pos.x - centerX, dz = (long) pos.z - centerZ;
            return Math.max(Math.abs(dx), Math.abs(dz)) > view && dx * dx + dz * dz <= (long) radius * radius;
        }

        private void refill(int cap) {
            // At most 4096 inexpensive coverage checks per tick, bounded queue.
            int remaining = Math.max(0, cap - generating.size() - converting.size());
            for (int checked = 0; checked < 4096 && candidates.size() < remaining && !exhausted; checked++) {
                var offset = scan.next();
                if (offset == null) { exhausted = true; break; }
                var pos = new ChunkPos(centerX + offset.x(), centerZ + offset.z());
                long key = pos.toLong();
                if (!inRange(pos) || failed.contains(key) || generating.containsKey(key) || converting.containsKey(key)
                        || !level.getWorldBorder().isWithinBounds(pos)
                        || VoxyBridge.coverage(engine).hasColumn(pos.x, pos.z, level.getMinSection(), level.getMaxSection())) continue;
                candidates.add(pos);
            }
        }

        private void snapshot(double millis) {
            long end = System.nanoTime() + (long) (millis * 1_000_000);
            for (var iterator = generating.values().iterator(); iterator.hasNext() && System.nanoTime() < end;) {
                var pending = iterator.next();
                var chunk = level.getChunkSource().getChunkNow(pending.pos.x, pending.pos.z);
                if (System.nanoTime() - pending.started > TimeUnit.MINUTES.toNanos(5)) {
                    failure = pending.pos + "：等待 FULL 区块或光照超过 5 分钟";
                    LOG.error(failure);
                    failed.add(pending.pos.toLong()); release(pending); iterator.remove(); continue;
                }
                if (chunk == null || !chunk.isLightCorrect()) continue;
                while (pending.sections.size() < chunk.getSectionsCount() && System.nanoTime() < end) {
                    int index = pending.sections.size();
                    int y = chunk.getMinSection() + index;
                    var section = chunk.getSections()[index];
                    var pos = SectionPos.of(pending.pos, y);
                    var light = level.getLightEngine();
                    var block = light.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
                    var sky = light.getLayerListener(LightLayer.SKY).getDataLayerData(pos);
                    var biomes = section.getBiomes().recreate();
                    for (int by = 0; by < 4; by++) for (int bz = 0; bz < 4; bz++) for (int bx = 0; bx < 4; bx++)
                        biomes.getAndSetUnchecked(bx, by, bz, section.getBiomes().get(bx, by, bz));
                    pending.sections.add(new ChunkSnapshot(pending.pos.x, y, pending.pos.z,
                            section.getStates().copy(), biomes,
                            block == null ? null : block.copy(), sky == null ? null : sky.copy()));
                }
                if (pending.sections.size() == chunk.getSectionsCount()) {
                    level.getChunkSource().removeRegionTicket(TICKET, pending.pos, 0, pending.pos);
                    var conversion = new Conversion(pending);
                    converting.put(pending.pos.toLong(), conversion);
                    workers.execute(conversion);
                    iterator.remove();
                }
            }
        }

        private void drainFinished() {
            Conversion result;
            while ((result = finished.poll()) != null) {
                converting.remove(result.pos.toLong());
                if (result.error != null) {
                    failed.add(result.pos.toLong());
                    failure = result.pos + ": " + result.error;
                    LOG.error("Distant conversion failed at {}", result.pos, result.error);
                } else if (!result.cancelled && VoxyBridge.coverage(engine).hasColumn(result.pos.x, result.pos.z,
                        level.getMinSection(), level.getMaxSection())) completed++;
                else if (!workers.isShutdown()) resetScan();
            }
        }

        private void release(Pending pending) {
            level.getChunkSource().removeRegionTicket(TICKET, pending.pos, 0, pending.pos);
            memory.addAndGet(-pending.bytes);
        }

        void close() {
            for (var pending : generating.values()) release(pending);
            generating.clear(); candidates.clear();
            for (var conversion : converting.values()) conversion.cancelled = true;
            workers.shutdown();
        }

        boolean releaseIfFinished() {
            if (!workers.isTerminated()) return false;
            drainFinished();
            if (!released) { engine.releaseRef(); released = true; }
            return true;
        }

        void awaitClosed() {
            try {
                // Only cancellation cleanup: conversion checks cancellation between sections.
                while (!workers.awaitTermination(1, TimeUnit.SECONDS)) LOG.debug("Waiting for distant conversion cleanup");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted during Voxy Distant shutdown", e); }
            releaseIfFinished();
        }

        private static final class Pending {
            final ChunkPos pos;
            final long bytes, revision;
            final long started = System.nanoTime();
            final List<ChunkSnapshot> sections = new ArrayList<>();
            Pending(ChunkPos pos, long bytes, long revision) { this.pos = pos; this.bytes = bytes; this.revision = revision; }
        }

        private final class Conversion implements Runnable {
            final ChunkPos pos;
            final Pending pending;
            volatile boolean cancelled;
            Throwable error;
            Conversion(Pending pending) { this.pending = pending; this.pos = pending.pos; }
            public void run() {
                try {
                    for (var snapshot : pending.sections) {
                        if (cancelled) break;
                        long start = System.nanoTime();
                        VoxyBridge.ingest(engine, snapshot, pending.revision);
                        double duty = DistantConfig.limits().dutyCycle();
                        if (!cancelled && duty < 1) LockSupport.parkNanos((long) ((System.nanoTime() - start) * (1 / duty - 1)));
                    }
                    VoxyBridge.coverage(engine).checkpoint(engine.storage::flush);
                } catch (RuntimeException | Error e) { error = e; }
                finally {
                    pending.sections.clear();
                    memory.addAndGet(-pending.bytes);
                    finished.add(this);
                }
            }
        }
    }

    private GenerationController() {}
}
