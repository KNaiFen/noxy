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
    private record Target(MinecraftServer server, ResourceKey<Level> dimension, int x, int z, int view, int radius, boolean paused,dev.voxydistant.movement.MovementPrediction.Snapshot motion) {}
    public record Status(int generating, int converting, long completed, double perSecond, long bytes, String reason, String failure,long checks,long submitted,long cancelled) {}
    private static volatile Target target;
    private static volatile Status status = new Status(0, 0, 0, 0, 0, "等待单人世界", "",0,0,0);
    private static volatile boolean stopping;
    private record Offer(WorldEngine engine,ResourceKey<Level> dimension){}
    private static final java.util.concurrent.atomic.AtomicReference<Offer> offered=new java.util.concurrent.atomic.AtomicReference<>();
    private static final List<Session> retired = new ArrayList<>();
    private static volatile Session session;

    public static Status status() { return status; }

    public static void clientTick() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) {
            target = null;
            var old=offered.getAndSet(null);if(old!=null)old.engine().releaseRef();
            return;
        }
        if (stopping) return;
        var server = mc.getSingleplayerServer();
        var offer=offered.get();
        if(offer!=null&&offer.dimension()!=mc.level.dimension()&&offered.compareAndSet(offer,null)){offer.engine().releaseRef();offer=null;}
        var active=session;
        if(active!=null&&active.level.dimension()==mc.level.dimension()){
            if(offer!=null&&offered.compareAndSet(offer,null))offer.engine().releaseRef();
        }else if(offer==null) {
            var engine=VoxyBridge.acquire(mc.level);if(engine!=null)offered.set(new Offer(engine,mc.level.dimension()));
        }
        var previous=target;
        var motion=dev.voxydistant.movement.MovementPrediction.CLIENT.snapshot();
        if(motion.world()!=mc.level){target=null;return;}
        target = new Target(server, mc.level.dimension(), mc.player.chunkPosition().x, mc.player.chunkPosition().z,
                Math.max(mc.options.renderDistance().get(), server.getPlayerList().getViewDistance()),
                Math.min(DistantConfig.RADIUS.get(), VoxyBridge.radiusChunks()),
                mc.isPaused() || mc.screen != null && mc.screen.isPauseScreen(),motion);
        // IntegratedServer skips Forge tick events while paused, but still drains its task queue.
        if(target.paused()&&(previous==null||!previous.paused()||status.generating()>0||status.converting()>0))server.execute(()->serverTick(server));
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
            var offer=offered.get();if(offer==null||offer.dimension()!=desired.dimension()||!offered.compareAndSet(offer,null))return;
            session = new Session(server.getLevel(desired.dimension()), offer.engine());
        }
        session.tick(desired);
    }

    public static synchronized void serverStopping(MinecraftServer server) {
        if (session != null && session.level.getServer() == server) {
            session.close();
            retired.add(session);
            session = null;
        }
        var offer=offered.getAndSet(null);if(offer!=null)offer.engine().releaseRef();
        target = null;
    }

    public static void beforeVoxyShutdown() {
        MinecraftServer server;
        synchronized (GenerationController.class) {
            stopping = true;
            server = session == null ? null : session.level.getServer();
            target = null;
            var offer=offered.getAndSet(null);if(offer!=null)offer.engine().releaseRef();
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
        private final LocalDiscovery discovery=new LocalDiscovery();
        private final ConcurrentLinkedQueue<Conversion> finished = new ConcurrentLinkedQueue<>();
        private final AtomicLong memory = new AtomicLong();
        private final ThreadPoolExecutor workers;
        private final long started = System.nanoTime();
        private long completed,submitted,cancelled,ticks, lastSubmissionTick = System.nanoTime();
        private double submissionTokens;
        private int centerX = Integer.MIN_VALUE, centerZ, radius = -1, view = -1;
        private long movementRevision=-1;
        private boolean tickOverloaded;
        private String failure = "";
        private boolean released;

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
            boolean enabled = DistantConfig.ENABLED.get() && VoxyBridge.enabled();
            if (enabled&&!desired.paused()&&(centerX==Integer.MIN_VALUE||Math.abs((long)centerX-desired.x())>32||Math.abs((long)centerZ-desired.z())>32||radius != desired.radius() || view != desired.view()||movementRevision!=desired.motion().revision())) {
                centerX = desired.x(); centerZ = desired.z(); radius = desired.radius(); view = desired.view();
                long timing=dev.voxydistant.DebugLog.start();movementRevision=desired.motion().revision();discovery.move(desired.motion().shape(radius,2048),view);dev.voxydistant.DebugLog.end(dev.voxydistant.DebugLog.Metric.LOCAL_RANGE,timing);
                for (var iterator = generating.values().iterator(); iterator.hasNext();) {
                    var pending = iterator.next();
                    if (!inRange(pending.pos)) { release(pending);discovery.retry(pending.pos); iterator.remove(); }
                }
            }
            double ms = level.getServer().getAverageTickTime();
            if (ms > 45) tickOverloaded = true;
            else if (ms < 35) tickOverloaded = false;
            if (!enabled||desired.paused()) {
                for (var pending : generating.values()) release(pending);
                generating.clear(); discovery.clear();
                for (var conversion : converting.values()) conversion.cancelled = true;
            }
            if(!enabled||desired.paused())movementRevision=-1;
            String reason = !enabled ? "已禁用" : desired.paused() ? "暂停菜单" : tickOverloaded ? "服务器 tick 超过预算"
                    : VoxyBridge.backedUp(engine) ? "Voxy 摄取或保存积压" : "生成中";
            if (enabled && !desired.paused() && !VoxyBridge.backedUp(engine)) snapshot(limits.snapshotMillis());
            int cap = DistantConfig.QUEUE.get();
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
                else while (submissionTokens >= 1 && discovery.candidateCount()>0 && generating.size() < limits.concurrency()
                        && generating.size() + converting.size() + retiringColumns < cap
                        && memory.get() + retiringBytes + columnBytes <= memoryLimit) {
                    var pos = discovery.poll();if(pos==null)break;
                    if(!inRange(pos)||generating.containsKey(pos.toLong())||converting.containsKey(pos.toLong()))continue;
                    long dx=(long)pos.x-desired.x(),dz=(long)pos.z-desired.z();
                    if(Math.max(Math.abs(dx),Math.abs(dz))<=view||dx*dx+dz*dz>2048L*2048||!level.getWorldBorder().isWithinBounds(pos))continue;
                    if(VoxyBridge.coverage(engine).hasColumn(pos.x,pos.z,level.getMinSection(),level.getMaxSection()))continue;
                    // Reserving the whole column before adding a ticket bounds even partial snapshots.
                    level.getChunkSource().addRegionTicket(TICKET, pos, 0, pos); // FULL level 33; not ticking
                    memory.addAndGet(columnBytes);
                    var pending = new Pending(pos, columnBytes, VoxyBridge.coverage(engine).revision());
                    generating.put(pos.toLong(), pending);
                    submitted++;if(dev.voxydistant.DebugLog.verbose())dev.voxydistant.DebugLog.log("LOCAL submit x={} z={} player_x={} player_z={} amount={} submitted={}",pos.x,pos.z,desired.x(),desired.z(),desired.motion().amount(),submitted);
                    submissionTokens--;
                }
                if (!discovery.busy() && generating.isEmpty() && converting.isEmpty()) reason = "范围内已完成";
            }
            status = new Status(generating.size(), converting.size() + retired.stream().mapToInt(s -> s.converting.size()).sum(), completed,
                    completed / Math.max(1.0, (System.nanoTime() - started) / 1e9),
                    memory.get() + retired.stream().mapToLong(s -> s.memory.get()).sum(), reason, failure,discovery.checks,submitted,cancelled);
            if(++ticks%100==0&&dev.voxydistant.DebugLog.enabled()){dev.voxydistant.DebugLog.log("LOCAL discovery checks={} boundary_checks={} submitted={} cancelled={} completed={}",discovery.checks,discovery.boundaryChecks,submitted,cancelled,completed);dev.voxydistant.DebugLog.timings("LOCAL");}
        }

        private boolean inRange(ChunkPos pos) {
            return discovery.contains(pos.x,pos.z);
        }

        private void refill(int cap) {
            if(!discovery.busy())return;
            long timing=dev.voxydistant.DebugLog.start();
            int remaining = Math.max(0, cap - generating.size() - converting.size());
            discovery.step(DistantConfig.LOCAL_SCAN_COLUMNS_PER_TICK.get(),remaining,(x,z)->{var pos=new ChunkPos(x,z);long key=pos.toLong();return !failed.contains(key)&&!generating.containsKey(key)&&!converting.containsKey(key)&&level.getWorldBorder().isWithinBounds(pos)&&!VoxyBridge.coverage(engine).hasColumn(x,z,level.getMinSection(),level.getMaxSection());});
            dev.voxydistant.DebugLog.end(dev.voxydistant.DebugLog.Metric.LOCAL_DISCOVERY,timing);
        }

        private void snapshot(double millis) {
            long timing=dev.voxydistant.DebugLog.start();
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
            dev.voxydistant.DebugLog.end(dev.voxydistant.DebugLog.Metric.LOCAL_SNAPSHOT,timing);
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
                        level.getMinSection(), level.getMaxSection())) {completed++;if(dev.voxydistant.DebugLog.verbose())dev.voxydistant.DebugLog.log("LOCAL complete x={} z={} elapsed_ms={}",result.pos.x,result.pos.z,(System.nanoTime()-result.pending.started)/1e6);}
                else if (!workers.isShutdown()) discovery.retry(result.pos);
            }
        }

        private void release(Pending pending) {
            cancelled++;
            level.getChunkSource().removeRegionTicket(TICKET, pending.pos, 0, pending.pos);
            memory.addAndGet(-pending.bytes);
        }

        void close() {
            for (var pending : generating.values()) release(pending);
            generating.clear(); discovery.clear();
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
                        dev.voxydistant.DebugLog.end(dev.voxydistant.DebugLog.Metric.LOCAL_CONVERT,dev.voxydistant.DebugLog.enabled()?start:0);
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
