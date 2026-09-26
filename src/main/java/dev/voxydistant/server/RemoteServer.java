package dev.voxydistant.server;

import dev.voxydistant.DebugLog;
import static dev.voxydistant.DebugLog.Metric.*;

import com.mojang.logging.LogUtils;
import dev.voxydistant.config.*;
import dev.voxydistant.data.*;
import dev.voxydistant.generation.ChunkSnapshot;
import dev.voxydistant.network.*;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.network.chat.Component;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/** Server-thread session ownership; workers see snapshots and immutable keys only. */
public final class RemoteServer {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    private static final TicketType<ChunkPos> TICKET = TicketType.create("voxy_distant_remote", Comparator.comparingLong(ChunkPos::toLong));
    private static RemoteServer instance;
    private final MinecraftServer server;
    private final LinkedHashMap<UUID, Session> players = new LinkedHashMap<>();
    private final LinkedHashMap<Key, Work> work = new LinkedHashMap<>();
    private final LinkedHashMap<Key, Long> dirty = new LinkedHashMap<>();
    private final Set<Key> missingChecks = new LinkedHashSet<>();
    private final LinkedHashMap<Key, LodDatabase.Pending> refreshPending = new LinkedHashMap<>(), missingPending = new LinkedHashMap<>();
    private final ConcurrentHashMap<Key, Long> versions = new ConcurrentHashMap<>();
    private final Map<Key,Integer> lastChangeTick=new HashMap<>();
    private final Set<Key> lightChanges=ConcurrentHashMap.newKeySet();
    private final Set<Key> savedUnloads=new HashSet<>();
    private final ThreadPoolExecutor workers;
    private final ByteBudget totalBudget = new ByteBudget();
    private final LoadGovernor governor = new LoadGovernor();
    private final AtomicLong sequence = new AtomicLong(System.currentTimeMillis() << 16);
    private final AtomicLong dirtyWrites=new AtomicLong();
    private volatile LodDatabase database;
    private UUID world;
    private boolean stopping;
    private long configRevision;
    private ConfigChange configChange;
    private record ConfigChange(ServerSettings.Snapshot before, ServerSettings.Snapshot after,
                                java.util.function.BooleanSupplier allowed, java.util.function.Consumer<String> done) {}
    private boolean reopening;
    private long memory, cacheReadMemory, batchMemory, sentBytes, completed, hits, misses, transferSequence, last = System.nanoTime();
    private int cacheActive, generationActive;
    private double cacheTokens;
    private double backgroundTokens;
    private int backgroundActive, backgroundScanTick;
    private boolean backgroundScanning;
    private byte[] refreshCursor, missingCursor;
    private long backgroundCompleted, backgroundRetried;
    private long debugAt;
    private final long started=System.nanoTime();
    private double tokens;
    private int cursor, sendCursor, ticks;
    private String failure = "", reason = "初始化数据库";
    private final long[] sentLevels = new long[5];
    private final long[] batchFlushes=new long[4]; // full, budget, tail, tick
    private long debugHits,debugMisses,debugTicks;
    private final Map<String,Integer> debugReasons=new LinkedHashMap<>();
    private final Map<String,Integer> debugWaitTicks=new LinkedHashMap<>();
    private LoadGovernor.Settings debugThrottleSettings;
    private LoadGovernor.State debugThrottleState;
    private double debugMinTps=20,debugMaxMspt,debugMaxTickMillis;
    private long debugLastTick;
    private int debugMissing,debugStale;
    private MaintenanceTask maintenance;
    private MaintenanceTask lastMaintenance;
    private volatile boolean maintenancePaused;
    private volatile Thread maintenanceScanner;
    private volatile boolean importStorageFailed;
    private boolean exclusiveImport() { return maintenance!=null && maintenance.type==MaintenanceType.IMPORT; }
    private record ImportDimension(RegionNbtImporter parser, int minSection, int sections) {}

    private enum MaintenanceType { PREGEN, IMPORT }
    private static final class MaintenanceEntry {
        final Key key;
        final Path regionFile;
        final ChunkPos position;
        MaintenanceEntry(Key key) { this.key = key; this.regionFile = null; this.position = null; }
        MaintenanceEntry(Key key, Path regionFile, ChunkPos position) {
            this.key = key; this.regionFile = regionFile; this.position = position;
        }
    }
    private static final class MaintenanceTask {
        final MaintenanceType type; final ArrayBlockingQueue<MaintenanceEntry> queue = new ArrayBlockingQueue<>(4096);
        final ArrayDeque<MaintenanceEntry> pending = new ArrayDeque<>();
        volatile boolean scanning, cancelled;
        volatile long scanned, queued, completed, skipped, failed, total, scanFailures, unreadable;
        int radius, centerX, centerZ;
        volatile int active;
        final long started=System.nanoTime();
        long ended, version, importingAt, drainedAt, syncedAt;
        volatile long importMemory;
        volatile String phase="准备", error="";
        ExecutorService importWorkers;
        Map<ResourceKey<Level>,ImportDimension> dimensions;
        int importThreads;
        long importBudget;
        final java.util.concurrent.atomic.LongAdder readNanos=new java.util.concurrent.atomic.LongAdder(), convertNanos=new java.util.concurrent.atomic.LongAdder(), encodeNanos=new java.util.concurrent.atomic.LongAdder(), writeNanos=new java.util.concurrent.atomic.LongAdder();
        ResourceKey<Level> dimension;
        Iterator<MaintenanceSupport.Coordinate> coordinates;
        CommandSourceStack source;
        MaintenanceTask(MaintenanceType type) { this.type = type; }
        boolean done() { return cancelled || (!scanning && queue.isEmpty() && pending.isEmpty() && active == 0); }
        synchronized void scannedSlot() { scanned++; total++; }
        synchronized void queuedCandidate() { queued++; }
        synchronized void skipped() { skipped++; }
        synchronized void unreadable(MaintenanceEntry entry, Exception error) {
            skipped++; unreadable++;
            if (unreadable <= 3) LOG.warn("Skipping unreadable import chunk {} from {}",entry.position,entry.regionFile,error);
            if (unreadable == 3) LOG.warn("Further unreadable import chunk warnings suppressed; totals remain in stats and the final report");
        }
        synchronized void failed() { failed++; }
        synchronized void scanFailed() { failed++; scanFailures++; }
        synchronized void completed() { completed++; }
        MaintenanceEntry nextPregen() {
            if (coordinates == null || !coordinates.hasNext()) return null;
            var coordinate = coordinates.next();
            return new MaintenanceEntry(new Key(dimension, centerX + coordinate.dx(), centerZ + coordinate.dz()));
        }
    }
    public record Key(ResourceKey<Level> dimension, int x, int z) {
        long pos() { return ChunkPos.asLong(x,z); }
        byte[] key(int kind) { return LodDatabase.key(dimension.location().toString(), pos(), kind); }
    }
    private record Demand(Session session, Protocol.Want want) {}
    private static final class Work {
        final Key key; final List<Demand> demands = new ArrayList<>(); final List<ChunkSnapshot> snapshots = new ArrayList<>();
        final long bytes, started = System.nanoTime();
        long version, generationStarted; int stage; // 0 cache IO, 1 waiting full/light, 2 conversion, 3 encoding, 4 waiting generation capacity, 5 saved-chunk read
        String waitReason="cache";
        long waitSince=started;
        boolean ticket, cacheSlot, generationSlot;
        MaintenanceTask maintenance;
        int backgroundKind;
        long backgroundVersion;
        boolean backgroundReading, backgroundResolved, waitingForeground;
        int maintenanceResult = 1; // failed until a cache hit, successful write, or skipped input
        Work(Key key, long bytes) { this.key = key; this.bytes = bytes; }
    }
    private static final class Transfer {
        final long id; final Key key; final int level, epoch; final long version,requestId; final ColumnCodec.Encoded data;
        final List<Protocol.Member> members;
        int offset,fragments;long firstSent; final long created = System.nanoTime();
        Transfer(long id, Key key, int level, long version, int epoch, long requestId,ColumnCodec.Encoded data) {
            this.id=id; this.key=key; this.level=level; this.version=version; this.epoch=epoch; this.requestId=requestId;this.data=data;
            this.members=List.of();
        }
        Transfer(long id,ResourceKey<Level> dimension,int epoch,List<Protocol.Member> members,ColumnCodec.Encoded data) {
            this.id=id;this.epoch=epoch;this.members=members;this.data=data;var first=members.getFirst();
            key=new Key(dimension,first.x(),first.z());level=first.level();version=first.version();requestId=first.requestId();
        }
        long reservation(int sections) { return members.isEmpty()?Protocol.reservation(data.bytes().length,data.rawLength(),level,sections):Protocol.batchReservation(data.bytes().length,data.rawLength(),members,sections); }
    }
    private record Ready(Protocol.Member member,byte[] raw,int tick) {long charge(){return 4L*(raw.length+4)+1024;}}
    private static final class Credit {
        final long reservation,created,requestId;final Key key;final List<Protocol.Member> members;long submitted;boolean aborting;
        Credit(long reservation,long created,Transfer transfer){this.reservation=reservation;this.created=created;key=transfer.key;requestId=transfer.requestId;members=transfer.members;}
        boolean matches(Key key,long requestId){return members.isEmpty()?this.key.equals(key)&&this.requestId==requestId:members.stream().anyMatch(m->m.x()==key.x&&m.z()==key.z&&m.requestId()==requestId);}
        long reservation(){return reservation;}
        long created(){return created;}
    }
    private static final class Session {
        final ServerPlayer player; final LinkedHashMap<Long, Protocol.Want> pending = new LinkedHashMap<>();
        final ArrayDeque<Transfer> send = new ArrayDeque<>(); final Map<Long,Credit> inflight = new HashMap<>();
        final LinkedHashMap<Long,Ready> ready=new LinkedHashMap<>();
        boolean batching;long batchCredit,encodingBytes;int lastReadyAgedTick,lastSendAgedTick;
        final ByteBudget budget = new ByteBudget();
        final Set<Long> vanilla=new HashSet<>();
        ResourceKey<Level> dimension; int epoch, radius, view, bandwidth, capacity, advertisedCapacity, active, cacheActive, generating;
        long bytes, cacheReadBytes, reserved, sent, requestTick; int requestsThisTick;
        long payload,debugPayload,debugSent,debugAt;
        final long[] replies=new long[3];
        final Map<String,Integer> replyReasons=new LinkedHashMap<>();
        final Map<Long,Long> latestRequestIds=new HashMap<>();
        long latestRequestId;
        final Map<String,Integer> blocks=new LinkedHashMap<>();
        void blocked(String reason){if(DebugLog.enabled())blocks.merge(reason,1,Integer::sum);}
        int deficit;
        Session(ServerPlayer player) { this.player=player; this.dimension=player.level().dimension(); }
    }
    public static void register() {
        var bus=MinecraftForge.EVENT_BUS;
        bus.addListener(RemoteServer::start); bus.addListener(RemoteServer::tickEvent); bus.addListener(RemoteServer::stop);
        bus.addListener(RemoteServer::join); bus.addListener(RemoteServer::leave); bus.addListener(RemoteServer::dimension);
        bus.addListener(RemoteServer::chunkLoaded); bus.addListener(RemoteServer::commands);
    }
    private RemoteServer(MinecraftServer server) {
        this.server=server; int threads=DistantConfig.serverLimits().threads();
        workers=new ThreadPoolExecutor(threads,threads,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4096),r -> {
            var t=new Thread(r,"Voxy Distant server"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t;
        });
        var path=server.getWorldPath(LevelResource.ROOT).resolve("data/voxy-distant");
        workers.execute(() -> {
            try { var db=new LodDatabase(path,DistantConfig.SERVER_CACHE_MIB.get()*1048576L); UUID id=db.worldId();db.countCachedColumns();database=db; complete(() -> { world=id;for(Session s:players.values())hello(s.player); }); }
            catch (RuntimeException e) { complete(() -> fail(e)); }
        });
    }
    private static void start(ServerStartedEvent e) {
        DistantConfig.validate();
        validateSendMemory(e.getServer(),DistantConfig.PLAYER_SEND_MIB.get(),DistantConfig.TOTAL_SEND_MIB.get());
        instance=new RemoteServer(e.getServer());
    }
    private static void validateSendMemory(MinecraftServer server,int playerMiB,int totalMiB) {
        for(ServerLevel level:server.getAllLevels()) {
            long required=(level.getSectionsCount()*ChunkSnapshot.RESERVED_BYTES+1048575)/1048576;
            if(playerMiB<required||totalMiB<required)
                throw new IllegalArgumentException("维度 "+level.dimension().location()+" 的缓存读取至少需要每人和全服发送内存各 "+required+" MiB");
        }
    }
    private static void tickEvent(TickEvent.ServerTickEvent e) { if (e.phase==TickEvent.Phase.END && instance!=null) instance.tick(); }
    private static void join(PlayerEvent.PlayerLoggedInEvent e) { if (instance!=null && e.getEntity() instanceof ServerPlayer p) instance.addPlayer(p); }
    private static void leave(PlayerEvent.PlayerLoggedOutEvent e) { if (instance!=null) instance.removePlayer(e.getEntity().getUUID()); }
    private static void dimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (instance!=null && e.getEntity() instanceof ServerPlayer p) { instance.removePlayer(p.getUUID()); instance.addPlayer(p); }
    }
    private static void chunkLoaded(ChunkEvent.Load e) {
        if (instance!=null && e.getLevel() instanceof ServerLevel l && e.getChunk() instanceof LevelChunk c)
            instance.missingChecks.add(new Key(l.dimension(),c.getPos().x,c.getPos().z));
    }
    private void addPlayer(ServerPlayer p) {
        if (!Protocol.CHANNEL.isRemotePresent(p.connection.connection)) return;
        // Integrated owner's existing direct ingestion remains full precision, with no loopback transport.
        if (!server.isDedicatedServer() && server.isSingleplayerOwner(p.getGameProfile())) return;
        players.computeIfAbsent(p.getUUID(),id->new Session(p)); if (world!=null) hello(p);
    }
    private void hello(ServerPlayer p) {
        if(DebugLog.enabled())DebugLog.log("SERVER hello player={} world={} dimension={} bands={}",p.getUUID(),world,p.level().dimension().location(),DistantConfig.BANDS.get());
        Protocol.send(p,new Protocol.Hello(world,p.level().dimension().location().toString(),DistantConfig.SERVER_RADIUS.get(),p.level().getMinSection(),p.level().getMaxSection(),List.copyOf(DistantConfig.BANDS.get())));
        if(exclusiveImport()||importStorageFailed)Protocol.send(p,new Protocol.Maintenance(true));
    }
    private void removePlayer(UUID id) {
        Session old=players.remove(id); if (old==null) return;
        for (Work w:work.values()) w.demands.removeIf(d -> {if(d.session!=old)return false;detach(w,d);return true;});
    }
    public static void requests(ServerPlayer player, Protocol.Requests request) {
        if (instance!=null && !instance.stopping) {instance.accept(player,request);instance.schedule(DistantConfig.serverLimits());}
    }
    private void accept(ServerPlayer player, Protocol.Requests r) {
        if(exclusiveImport()||importStorageFailed)return;
        Session s=players.get(player.getUUID()); if (s==null) return;
        ColumnCodec.bounded(r.radius(),0,2048); ColumnCodec.bounded(r.view(),0,64);
        ColumnCodec.bounded(r.bandwidth(),0,131072); ColumnCodec.bounded(r.capacity(),0,1<<30);
        boolean newEpoch=s.epoch!=r.epoch();
        if (newEpoch) {
            if(r.epoch()<s.epoch)return;
            s.pending.clear();s.latestRequestIds.clear();s.latestRequestId=0; s.send.clear(); s.inflight.clear();s.ready.clear();s.batchCredit=0; s.bytes=0; s.reserved=0;
            for (Work w:work.values()) w.demands.removeIf(d -> {if(d.session!=s)return false;detach(w,d);return true;});
            s.epoch=r.epoch(); s.active=0;
        }
        s.radius=Math.min(r.radius(),DistantConfig.SERVER_RADIUS.get()); s.view=Math.min(r.view(),server.getPlayerList().getViewDistance());
        s.bandwidth=r.bandwidth();s.advertisedCapacity=r.capacity();s.capacity=creditLimit(s);
        for(var cancel:r.cancels())cancel(s,cancel);
        if (s.requestTick!=ticks) { s.requestTick=ticks; s.requestsThisTick=0; }
        if ((s.requestsThisTick+=r.wants().size())>256) {
            s.blocked("request_rate");
            for(var want:r.wants())reply(s,want,1,"request_rate");
            return;
        }
        if(DebugLog.enabled()&&(newEpoch||DebugLog.verbose()))DebugLog.log("SERVER request player={} epoch={} radius={} view={} wants={} advertised_credit={} effective_credit={} bandwidth_kib={}",player.getUUID(),s.epoch,s.radius,s.view,r.wants().size(),r.capacity(),s.capacity,s.bandwidth);
        // A client may enable reception after vanilla chunks arrived, or rebuild its world on Hello.
        // Replay their revisions so it can ingest the already loaded chunks into the new LOD world.
        if(newEpoch)for(long pos:s.vanilla)if(ChunkMap.isChunkInRange(ChunkPos.getX(pos),ChunkPos.getZ(pos),player.chunkPosition().x,player.chunkPosition().z,s.view))vanillaSent(player,ChunkPos.getX(pos),ChunkPos.getZ(pos));
        int pending=-1;
        for (var want:r.wants()) {
            ColumnCodec.bounded(want.level(),0,5);
            if(DebugLog.verbose())DebugLog.log("SERVER want player={} epoch={} x={} z={} request_id={} version={} level={}",player.getUUID(),s.epoch,want.x(),want.z(),want.requestId(),want.version(),want.level());
            long pos=ChunkPos.asLong(want.x(),want.z());
            // Client request IDs rise across the epoch; keep the watermark after a coordinate is released.
            if(want.requestId()!=0&&want.requestId()<=s.latestRequestId)continue;
            s.latestRequestId=Math.max(s.latestRequestId,want.requestId());
            if(!DistantConfig.SERVER_ENABLED.get()){s.blocked("disabled");reply(s,want,1,"disabled");continue;}
            if(s.vanilla.contains(ChunkPos.asLong(want.x(),want.z()))&&ChunkMap.isChunkInRange(want.x(),want.z(),player.chunkPosition().x,player.chunkPosition().z,s.view)){reply(s,want,2,"vanilla");continue;}
            if(pending<0){pending=0;for(Work active:work.values())if(active.backgroundKind==0||!active.demands.isEmpty())pending++;
                for(Session other:players.values())pending+=other.pending.size();}
            boolean queued=s.pending.containsKey(pos);
            if (!queued&&(s.pending.size()>=DistantConfig.PLAYER_REQUEST_QUEUE.get() || pending>=DistantConfig.SERVER_QUEUE.get())) {s.blocked("request_queue");reply(s,want,1,"request_queue");continue;}
            if (inRange(s,want.x(),want.z())) {s.pending.put(pos,want);s.latestRequestIds.put(pos,want.requestId());if(!queued)pending++;}
            else reply(s,want,2,"out_of_range");
        }
    }
    private boolean inRange(Session s,int x,int z) {
        long dx=(long)x-s.player.chunkPosition().x,dz=(long)z-s.player.chunkPosition().z;
        return s.radius>0 && dx*dx+dz*dz<=(long)s.radius*s.radius && s.player.serverLevel().getWorldBorder().isWithinBounds(new ChunkPos(x,z));
    }
    private void reply(Session s,Protocol.Want w,int status) {reply(s,w,status,status==0?"unchanged":status==2?"unavailable":"retry");}
    private void reply(Session s,Protocol.Want w,int status,String cause) {
        s.replies[status]++;
        if(DebugLog.enabled())s.replyReasons.merge(cause,1,Integer::sum);
        if(DebugLog.verbose())DebugLog.log("SERVER reply player={} epoch={} x={} z={} request_id={} version={} level={} status={} reason={}",s.player.getUUID(),s.epoch,w.x(),w.z(),w.requestId(),w.version(),w.level(),status,cause);
        Protocol.send(s.player,new Protocol.Reply(s.epoch,w.x(),w.z(),w.version(),w.level(),w.requestId(),status));
    }
    public static void receipt(ServerPlayer player,Protocol.Receipt receipt) {
        if (instance==null) return; Session s=instance.players.get(player.getUUID());
        if (s==null || s.epoch!=receipt.epoch()) return;
        Credit t=s.inflight.remove(receipt.transfer()); if(t!=null){
            s.reserved-=t.reservation();
            if(DebugLog.enabled())DebugLog.end(SERVER_CREDIT_ROUND_TRIP,t.created());
            if(DebugLog.verbose())DebugLog.log("SERVER receipt player={} epoch={} transfer={} credit_cycle_ms={} after_submit_ms={} released_bytes={} reserved_bytes={}",player.getUUID(),s.epoch,receipt.transfer(),DebugLog.millis(System.nanoTime()-t.created),t.submitted==0?-1:DebugLog.millis(System.nanoTime()-t.submitted),t.reservation,s.reserved);
            instance.flushSends(System.nanoTime());instance.schedule(DistantConfig.serverLimits());
        }
    }
    public static void markDirty(ServerLevel level,int x,int z) {
        RemoteServer self=instance;
        if (self==null || self.stopping || !level.getServer().isSameThread()) return;
        Key key=new Key(level.dimension(),x,z);
        Integer previousTick=self.lastChangeTick.put(key,self.ticks);if(previousTick!=null&&previousTick==self.ticks)return;
        long revision=self.sequence.incrementAndGet(); self.versions.put(key,revision); self.dirty.put(key,revision);
    }
    public static void vanillaSent(ServerPlayer player,int x,int z) {
        RemoteServer self=instance; if (self==null) return;
        // Initial chunk packets can precede Forge's PlayerLoggedInEvent.
        if(!self.players.containsKey(player.getUUID()))self.addPlayer(player);
        if(!self.players.containsKey(player.getUUID()))return;
        Key key=new Key(player.level().dimension(),x,z);
        long version=self.versions.computeIfAbsent(key,k -> self.sequence.incrementAndGet());
        self.players.get(player.getUUID()).vanilla.add(key.pos());
        if(!self.exclusiveImport()&&!self.importStorageFailed)Protocol.send(player,new Protocol.Dirty(key.dimension.location().toString(),x,z,version,true));
    }
    public static void lightDirty(ServerLevel level,int x,int z){var s=instance;if(s!=null&&!s.stopping)s.lightChanges.add(new Key(level.dimension(),x,z));}
    public static void chunkSaved(ServerLevel level,int x,int z){var s=instance;if(s!=null&&!s.stopping)s.savedUnloads.add(new Key(level.dimension(),x,z));}
    private void tick() {
        ticks++;
        for(var it=savedUnloads.iterator();it.hasNext();){Key k=it.next();if(server.getLevel(k.dimension).getChunkSource().getChunkNow(k.x,k.z)==null&&!work.containsKey(k)&&!dirty.containsKey(k)&&dirtyWrites.get()==0){versions.remove(k);it.remove();}}
        lastChangeTick.clear();
        // Light-engine storage removal also emits updates after a chunk has unloaded.
        int lightBudget=256;for(var it=lightChanges.iterator();it.hasNext()&&lightBudget-->0;){Key k=it.next();it.remove();var level=server.getLevel(k.dimension);if(level.getChunkSource().getChunkNow(k.x,k.z)!=null)markDirty(level,k.x,k.z);}
        PriorityState.tick(server);
        if(reopening)return;
        if(database==null || world==null) {
            if(configChange!=null)advanceConfigChange();
            if(importStorageFailed&&exclusiveImport())processImport(maintenance);
            return;
        }
        if(ticks==1 || ticks%40==0) for(Session s:players.values()) if(s.radius==0) hello(s.player);
        var limits=DistantConfig.serverLimits();
        if(workers.getCorePoolSize()!=limits.threads()) {
            workers.setMaximumPoolSize(Math.max(limits.threads(),workers.getCorePoolSize())); workers.setCorePoolSize(limits.threads()); workers.setMaximumPoolSize(limits.threads());
        }
        long now=System.nanoTime(); double mspt=server.getAverageTickTime();
        var throttleSettings=DistantConfig.throttleSettings();
        double factor=governor.tick(now,mspt,throttleSettings);
        if(DebugLog.enabled()){
            debugMinTps=Math.min(debugMinTps,governor.tps());debugMaxMspt=Math.max(debugMaxMspt,mspt);
            if(debugLastTick!=0)debugMaxTickMillis=Math.max(debugMaxTickMillis,DebugLog.millis(now-debugLastTick));
            debugLastTick=now;
            if(!throttleSettings.equals(debugThrottleSettings)||debugThrottleState!=governor.state()){
                DebugLog.log("SERVER throttle world={} tick={} from={} to={} factor={} tps={} tick_tps={} mspt={} slow_ticks={} pause_mspt_ticks={} pause_tps_ticks={} healthy_seconds={} settings={}",world,ticks,debugThrottleState,governor.state(),factor,governor.tps(),governor.tickTps(),mspt,governor.slowTicks(),governor.pauseTicks(),governor.lowTpsTicks(),governor.healthySeconds(now),throttleSettings);
                debugThrottleSettings=throttleSettings;debugThrottleState=governor.state();
            }
        }else debugLastTick=0;
        double burst=Math.max(1,limits.perSecond()/20.0),elapsed=(now-last)/1e9;
        tokens=Math.min(burst,tokens+elapsed*limits.perSecond()*factor);
        backgroundTokens=Math.min(Math.max(1,DistantConfig.BACKGROUND_RATE.get()/20.0),
                backgroundTokens+elapsed*DistantConfig.BACKGROUND_RATE.get()*factor);
        cacheTokens=Math.min(burst,cacheTokens+elapsed*limits.perSecond());last=now;
        reason=!DistantConfig.SERVER_ENABLED.get()?"已禁用":factor==0?"TPS/MSPT 节流":PriorityState.foregroundPending()>0?"原版区块优先":players.isEmpty()?"无远景订阅":"工作中";
        if(dirtyWrites.get()>8 || workers.getQueue().size()>DistantConfig.SERVER_QUEUE.get())reason="保存积压";
        if(configChange!=null){
            reason="正在应用服务端配置";
            // Waiting for vanilla generation owns no database operation. Release our ticket
            // and retry after reopening instead of making a settings save wait for terrain.
            for(Work w:List.copyOf(work.values()))if(w.stage==1||w.stage==4||w.stage==5){
                release(w);
                if(w.maintenance!=null){
                    var task=w.maintenance;
                    task.pending.addFirst(new MaintenanceEntry(w.key));
                    task.active--;w.maintenance=null;
                }
                finish(w,1);
            }
        }
        admitGeneration(limits);
        processWork(limits.snapshotMillis());
        schedule(limits);
        processMaintenance(limits);
        scanBackground();
        scheduleBackground(limits);
        if(exclusiveImport())reason="独占导入 · "+maintenance.phase;
        else if(importStorageFailed)reason="导入存储失败，远景服务暂停";
        if(configChange!=null){
            reason="正在应用服务端配置";
            advanceConfigChange();
            if(reopening)return;
        }
        if(configChange==null && ticks%DistantConfig.DIRTY_TICKS.get()==0) flushDirty();
        if(dirtyWrites.get()>8 || workers.getQueue().size()>DistantConfig.SERVER_QUEUE.get())reason="保存积压";
        schedule(limits);
        for(Session s:players.values())flushBatch(s);
        flushSends(now);
        if(DebugLog.enabled())debugReasons.merge(reason,1,Integer::sum);
        if(DebugLog.enabled()&&now-debugAt>=TimeUnit.SECONDS.toNanos(DistantConfig.DEBUG_INTERVAL.get())){
            long interval=debugAt==0?0:now-debugAt;
            int[] stages=new int[6];for(Work w:work.values())stages[w.stage]++;
            DebugLog.log("SERVER interval world={} interval_ms={} ticks={} tps={} mspt={} throttle_factor={} reason_ticks={} cache_hits={} cache_misses={} stages_cache_wait_convert_encode={} foreground_pending={} threads={} rate={} duty={} compression={} max_batch={} total_mbps={} player_kib={}",world,DebugLog.millis(interval),ticks-debugTicks,governor.tps(),mspt,factor,debugReasons,hits-debugHits,misses-debugMisses,Arrays.toString(stages),PriorityState.foregroundPending(),limits.threads(),limits.perSecond(),limits.dutyCycle(),DistantConfig.COMPRESSION_LEVEL.get(),DistantConfig.MAX_BATCH_COLUMNS.get(),DistantConfig.TOTAL_MBPS.get(),DistantConfig.PLAYER_KBPS.get());
            var waits=new LinkedHashMap<String,Integer>();var oldestWait=new LinkedHashMap<String,Double>();
            for(Work w:work.values())if(w.stage==1||w.stage==4){waits.merge(w.waitReason,1,Integer::sum);oldestWait.merge(w.waitReason,DebugLog.millis(System.nanoTime()-w.waitSince),Math::max);}
            work.values().stream().filter(w->w.stage==1||w.stage==4).sorted(Comparator.comparingLong(w->w.waitSince)).limit(4).forEach(w->{
                var level=server.getLevel(w.key.dimension);
                DebugLog.log("SERVER waiting_column dimension={} x={} z={} reason={} wait_ms={} work_ms={} generation_ms={} ticket={} snapshot_sections={} chunk_status={}",w.key.dimension.location(),w.key.x,w.key.z,w.waitReason,DebugLog.millis(now-w.waitSince),DebugLog.millis(now-w.started),w.generationStarted==0?0:DebugLog.millis(now-w.generationStarted),w.ticket,w.snapshots.size(),level.getChunkSource().getChunkDebugData(new ChunkPos(w.key.x,w.key.z)).replace('\n',' ').replaceAll("§.",""));
            });
            DebugLog.log("SERVER waits world={} current={} oldest_ms={} work_ticks={} cache_missing={} cache_stale={} min_tps={} max_mspt={} max_tick_ms={} throttle_state={} slow_ticks={} pause_mspt_ticks={} pause_tps_ticks={} healthy_seconds={} settings={}",world,waits,oldestWait,debugWaitTicks,debugMissing,debugStale,debugMinTps,debugMaxMspt,debugMaxTickMillis,governor.state(),governor.slowTicks(),governor.pauseTicks(),governor.lowTpsTicks(),governor.healthySeconds(now),throttleSettings);
            debugWaitTicks.clear();debugMissing=debugStale=0;debugMinTps=20;debugMaxMspt=debugMaxTickMillis=0;
            debugHits=hits;debugMisses=misses;debugTicks=ticks;debugReasons.clear();
            debugAt=now;
            DebugLog.log("SERVER state reason={} mspt={} tokens={} work={}/{} worker_queue={} worker_active={} main_tasks={} dirty_writes={} snapshot_bytes={} queued_payload_bytes={} sent_accounted_bytes={} cache_active={} generation_active={} cache_read_bytes={} cache_tokens={}",reason,mspt,tokens,work.size(),DistantConfig.SERVER_QUEUE.get(),workers.getQueue().size(),workers.getActiveCount(),server.getPendingTasksCount(),dirtyWrites.get(),memory,totalQueuedBytes(),sentBytes,cacheActive,generationActive,cacheReadMemory,cacheTokens);
            DebugLog.log("SERVER background pending_refresh={} pending_missing={} active={} completed={} retried={} tokens={}",refreshPending.size(),missingPending.size(),backgroundActive,backgroundCompleted,backgroundRetried,backgroundTokens);
            for(Session s:players.values())DebugLog.log("SERVER player={} epoch={} pending={} active={} cache_active={} generation_active={} lane_limit={} send_queue={} inflight={} reserved_bytes={} effective_credit={} player_send_limit={} total_send_limit={} bandwidth_available={} total_bandwidth_available={}",s.player.getUUID(),s.epoch,s.pending.size(),s.active,s.cacheActive,s.generating,DistantConfig.PLAYER_CONCURRENCY.get(),s.send.size(),s.inflight.size(),s.reserved,s.capacity,DistantConfig.PLAYER_SEND_MIB.get()*1048576L,DistantConfig.TOTAL_SEND_MIB.get()*1048576L,s.budget.available(),totalBudget.available());
            for(Session s:players.values()){
                long duration=s.debugAt==0?0:now-s.debugAt,oldest=0;for(Credit c:s.inflight.values())oldest=Math.max(oldest,now-c.created);
                Transfer head=s.send.peek();
                DebugLog.log("SERVER player_interval player={} world={} dimension={} epoch={} interval_ms={} payload_bytes={} payload_mbps={} accounted_mbps={} ready_columns={} batching={} batch_credit={} encoding_bytes={} oldest_inflight_ms={} head_transfer={} head_offset={} head_payload_bytes={} head_age_ms={} replies_unchanged_retry_unavailable={} blocked_attempts={} client_kib={}",s.player.getUUID(),world,s.dimension.location(),s.epoch,DebugLog.millis(duration),s.payload-s.debugPayload,DebugLog.mbps(s.payload-s.debugPayload,duration),DebugLog.mbps(s.sent-s.debugSent,duration),s.ready.size(),s.batching,s.batchCredit,s.encodingBytes,DebugLog.millis(oldest),head==null?0:head.id,head==null?0:head.offset,head==null?0:head.data.bytes().length,head==null?0:DebugLog.millis(now-head.created),Arrays.toString(s.replies),s.blocks,s.bandwidth);
                DebugLog.log("SERVER reply_summary player={} epoch={} reasons={}",s.player.getUUID(),s.epoch,s.replyReasons);
                s.debugAt=now;s.debugPayload=s.payload;s.debugSent=s.sent;Arrays.fill(s.replies,0);s.blocks.clear();s.replyReasons.clear();
                DebugLog.connection("SERVER",s.player.connection.connection,s.player.getUUID());
            }
            DebugLog.timings("SERVER");
        }
    }
    private void complete(Runnable action){server.execute(()->{if(!stopping){action.run();schedule(DistantConfig.serverLimits());}});}
    private boolean foregroundPending(){for(Session session:players.values())if(!session.pending.isEmpty())return true;return false;}
    private boolean foregroundActive(){for(Work active:work.values())if(!active.demands.isEmpty())return true;return false;}
    private void scanBackground(){
        if(database==null||backgroundScanning||configChange!=null||exclusiveImport()||importStorageFailed
                ||!DistantConfig.SERVER_ENABLED.get()||ticks-backgroundScanTick<20)return;
        backgroundScanning=true;backgroundScanTick=ticks;
        byte[] refreshAfter=refreshCursor, missingAfter=missingCursor;
        workers.execute(()->{
            try{
                var refresh=database.pending(3,refreshAfter,128);
                var missing=database.pending(4,missingAfter,128);
                complete(()->{
                    refreshCursor=refresh.exhausted()?null:refresh.last();
                    missingCursor=missing.exhausted()?null:missing.last();
                    collectPending(refresh,refreshPending);
                    collectPending(missing,missingPending);
                    backgroundScanning=false;
                });
            }catch(RuntimeException ex){complete(()->{backgroundScanning=false;fail(ex);});}
        });
    }
    private void cancel(Session s,Protocol.Cancel cancel){
        long pos=ChunkPos.asLong(cancel.x(),cancel.z());var pending=s.pending.get(pos);
        if(pending!=null&&pending.requestId()==cancel.requestId())s.pending.remove(pos);
        Key key=new Key(s.dimension,cancel.x(),cancel.z());Work active=work.get(key);
        if(active!=null)active.demands.removeIf(d->{if(d.session!=s||d.want.requestId()!=cancel.requestId())return false;detach(active,d);return true;});
        if(active!=null&&active.demands.isEmpty()&&active.maintenance==null&&active.backgroundKind==0&&active.stage!=0&&active.stage!=2&&active.stage!=3){release(active);finish(active,-1);}
        var ready=s.ready.get(pos);if(ready!=null&&ready.member().requestId()==cancel.requestId()){s.ready.remove(pos);s.bytes-=ready.charge();}
        for(var it=s.send.iterator();it.hasNext();){Transfer transfer=it.next();
            if(transfer.offset==0&&transfer.members.isEmpty()&&transfer.key.equals(key)&&transfer.requestId==cancel.requestId()){
                it.remove();s.bytes-=transfer.data.bytes().length;
            }
        }
        for(var it=s.inflight.entrySet().iterator();it.hasNext();){var entry=it.next();var credit=entry.getValue();
            if(!credit.matches(key,cancel.requestId())||credit.aborting)continue;
            if(credit.members.stream().anyMatch(m->m.x()!=cancel.x()||m.z()!=cancel.z()||m.requestId()!=cancel.requestId()))continue;
            credit.aborting=true;
            for(var queued=s.send.iterator();queued.hasNext();){var transfer=queued.next();if(transfer.id==entry.getKey()){queued.remove();s.bytes-=transfer.data.bytes().length;break;}}
            Protocol.send(s.player,new Protocol.Abort(s.epoch,entry.getKey()));
        }
        s.latestRequestIds.remove(pos,cancel.requestId());
    }
    private void collectPending(LodDatabase.PendingPage page, LinkedHashMap<Key,LodDatabase.Pending> target){
        for(var entry:page.entries()){
            if(target.size()>=512)break;
            var dimension=ResourceKey.create(Registries.DIMENSION,new ResourceLocation(entry.dimension()));
            if(server.getLevel(dimension)==null)continue;
            Key key=new Key(dimension,ChunkPos.getX(entry.position()),ChunkPos.getZ(entry.position()));
            if(!work.containsKey(key))target.put(key,entry);
        }
    }
    private void scheduleBackground(DistantConfig.Limits limits){
        if(!DistantConfig.SERVER_ENABLED.get()||database==null||configChange!=null||exclusiveImport()||importStorageFailed
                ||foregroundPending()||foregroundActive()||(!reason.equals("工作中")&&!reason.equals("无远景订阅"))
                ||workers.getQueue().size()>DistantConfig.SERVER_QUEUE.get()||dirtyWrites.get()>8)return;
        long now=System.currentTimeMillis();
        while(backgroundTokens>=1 && tokens>=1 && backgroundActive<DistantConfig.BACKGROUND_CONCURRENCY.get()
                && generationActive<Math.max(1,limits.concurrency()-1)
                && work.size()<Math.max(1,DistantConfig.SERVER_QUEUE.get()-1)){
            LodDatabase.Pending next=null;Key key=null;
            for(var source:List.of(refreshPending,missingPending)){
                for(var entry:source.entrySet())if(entry.getValue().retryAt()<=now&&!work.containsKey(entry.getKey())){
                    key=entry.getKey();next=entry.getValue();break;
                }
                if(next!=null)break;
            }
            if(next==null)break;
            ServerLevel level=server.getLevel(key.dimension());
            long bytes=level.getSectionsCount()*ChunkSnapshot.RESERVED_BYTES;
            if(memory+bytes>DistantConfig.SERVER_MEMORY.get()*1048576L)break;
            Work w=new Work(key,bytes);w.backgroundKind=next.kind();w.backgroundVersion=next.version();
            w.generationSlot=true;w.stage=0;w.version=Math.max(next.version(),versions.getOrDefault(key,0L));
            work.put(key,w);generationActive++;backgroundActive++;memory+=bytes;backgroundTokens--;
            workers.execute(()->{
                try{
                    byte[] pendingValue=database.pendingValue(w.key.key(w.backgroundKind));
                    if(pendingValue==null || ByteBuffer.wrap(pendingValue).getLong()!=w.backgroundVersion){
                        complete(()->finish(w,-1));return;
                    }
                    byte[] invalid=database.get(w.key.key(2)),stored=database.get(w.key.key(1));
                    long revision=invalid==null?0:ByteBuffer.wrap(invalid).getLong();
                    LodColumn column=stored==null?null:ColumnCodec.decodeLevels(LodDatabase.unpack(stored),31);
                    complete(()->{
                        long latest=Math.max(revision,versions.getOrDefault(w.key,0L));
                        if(column!=null&&column.version()>=latest){
                            workers.execute(()->{
                                try{database.resolvePending(w.key.key(1),column.version());complete(()->{
                                    w.backgroundResolved=true;
                                    if(w.demands.isEmpty())finish(w,-1);else encodeAndQueue(w,column);
                                });}catch(RuntimeException ex){complete(()->{fail(ex);finish(w,1);});}
                            });
                        }else{w.stage=1;w.version=versions.computeIfAbsent(w.key,k->sequence.incrementAndGet());
                            w.version=Math.max(w.version,latest);w.generationStarted=0;}
                    });
                }catch(RuntimeException ex){complete(()->{fail(ex);finish(w,1);});}
            });
            if(next.kind()==3)refreshPending.remove(key);else missingPending.remove(key);
        }
    }
    private void retryBackground(Work w){
        if(w.backgroundKind==0)return;
        backgroundRetried++;
        long retryAt=System.currentTimeMillis()+10_000;
        if(!stopping)workers.execute(()->{try{database.retryPending(w.key.key(w.backgroundKind),w.backgroundVersion,retryAt);}
            catch(RuntimeException ex){complete(()->fail(ex));}});
        refreshCursor=missingCursor=null;
    }
    private void submitSavedChunk(Work w,ServerLevel level){
        if(w.backgroundReading)return;
        w.backgroundReading=true;
        workers.execute(()->{
            try{
                var position=new ChunkPos(w.key.x,w.key.z);
                Path directory=regionPath(server,level);
                Path file=directory.resolve("r."+position.getRegionX()+"."+position.getRegionZ()+".mca");
                List<ChunkSnapshot> snapshots=List.of();
                if(Files.isRegularFile(file))try(var region=new RegionFile(file,directory,false)){
                    try(var input=region.getChunkDataInputStream(position)){
                        if(input!=null)snapshots=RegionNbtImporter.read(level.registryAccess(),level.getMinSection(),level.getSectionsCount(),w.key.x,w.key.z,
                                RegionNbtImporter.readNbt(input,Math.max(32L<<20,w.bytes*2))).orElse(List.of());
                    }
                }
                var result=snapshots;
                complete(()->{
                    if(work.get(w.key)!=w)return;
                    if(!w.demands.isEmpty() && w.waitingForeground){
                        w.backgroundReading=false;w.waitingForeground=false;w.stage=1;w.snapshots.clear();return;
                    }
                    if(result.isEmpty()){finish(w,1);return;}
                    if(level.getChunkSource().getChunkNow(w.key.x,w.key.z)!=null
                            ||versions.getOrDefault(w.key,w.version)!=w.version){
                        w.backgroundReading=false;w.stage=1;return;
                    }
                    w.snapshots.addAll(result);w.stage=2;submitConversion(w);
                });
            }catch(IOException|RuntimeException ex){complete(()->{fail(new IllegalStateException("Saved chunk refresh failed",ex));finish(w,1);});}
        });
    }
    private void schedule(DistantConfig.Limits limits) {
        if(exclusiveImport()||importStorageFailed)return;
        if(configChange!=null || database==null || !DistantConfig.SERVER_ENABLED.get())return;
        if(dirtyWrites.get()>8 || workers.getQueue().size()>DistantConfig.SERVER_QUEUE.get())return;
        var sessions=new ArrayList<>(players.values()); if(sessions.isEmpty())return;
        int attempts=0,idle=0;
        while(attempts++<sessions.size()*limits.concurrency() && cacheTokens>=1 && cacheActive<limits.concurrency() && work.size()<DistantConfig.SERVER_QUEUE.get()) {
            if(idle++>=sessions.size())break;
            Session s=sessions.get(Math.floorMod(cursor++,sessions.size()));
            if(s.pending.isEmpty())continue;
            if(s.cacheActive>=DistantConfig.PLAYER_CONCURRENCY.get()){s.blocked("player_cache_concurrency");continue;}
            if(s.bytes+s.encodingBytes+s.cacheReadBytes>=DistantConfig.PLAYER_SEND_MIB.get()*1048576L){s.blocked("player_send_memory");continue;}
            if(totalQueuedBytes()>=DistantConfig.TOTAL_SEND_MIB.get()*1048576L){s.blocked("total_send_memory");continue;}
            if(s.reserved>=s.capacity){s.blocked("credit");continue;}
            var want=s.pending.values().iterator().next();
            // Aging turns eventually win; until then, movement reprioritizes near requests.
            if(ticks%20!=0){var p=s.player.chunkPosition();long best=Long.MAX_VALUE;for(var candidate:s.pending.values()){long dx=(long)candidate.x()-p.x,dz=(long)candidate.z()-p.z;long distance=dx*dx+dz*dz;if(distance<best){best=distance;want=candidate;}}}
            s.pending.remove(ChunkPos.asLong(want.x(),want.z()));
            if(!inRange(s,want.x(),want.z())) { reply(s,want,2,"out_of_range");s.latestRequestIds.remove(ChunkPos.asLong(want.x(),want.z()),want.requestId());idle=0;continue; }
            Key key=new Key(s.dimension,want.x(),want.z()); Work existing=work.get(key);
            if(existing!=null) {
                // Encoding has already captured its recipients; defer later arrivals to the cache.
                if(existing.stage==3){s.pending.put(key.pos(),want);continue;}
                var previous=existing.demands.stream().filter(d->d.session==s).findFirst().orElse(null);
                if(previous==null&&existing.generationSlot&&s.generating>=DistantConfig.PLAYER_CONCURRENCY.get()){reply(s,want,1,"generation_capacity");s.latestRequestIds.remove(key.pos(),want.requestId());idle=0;continue;}
                if(previous==null&&existing.cacheSlot&&s.bytes+s.encodingBytes+s.cacheReadBytes+existing.bytes>DistantConfig.PLAYER_SEND_MIB.get()*1048576L){s.pending.put(key.pos(),want);continue;}
                if(previous!=null){existing.demands.remove(previous);detach(existing,previous);}
                attach(existing,new Demand(s,want));
                if(existing.backgroundKind!=0&&existing.stage==5)existing.waitingForeground=true;
                idle=0;continue;
            }
            long bytes=s.player.level().getSectionsCount()*ChunkSnapshot.RESERVED_BYTES;
            // Cache decoding uses the existing send memory budget, never snapshot slots.
            if(totalQueuedBytes()+bytes>DistantConfig.TOTAL_SEND_MIB.get()*1048576L || s.bytes+s.encodingBytes+s.cacheReadBytes+bytes>DistantConfig.PLAYER_SEND_MIB.get()*1048576L){s.blocked("cache_read_memory");s.pending.put(key.pos(),want);continue;}
            Work w=new Work(key,bytes);w.cacheSlot=true;cacheActive++;cacheReadMemory+=bytes;
            idle=0;
            attach(w,new Demand(s,want));work.put(key,w);cacheTokens--;
            // Shared work can serve players at different distances, including arrivals during the read.
            int configuredMask=0;for(int level:DistanceBands.parse(DistantConfig.BANDS.get()).levels())configuredMask|=1<<level;
            final int cacheMask=configuredMask;
            long queued=DebugLog.start();
            workers.execute(() -> {
                DebugLog.end(SERVER_WORKER_QUEUE,queued);
                long timing=DebugLog.start();try {
                    byte[] invalid=database.get(key.key(2)), stored=database.get(key.key(1));
                    long readNanos=timing==0?0:System.nanoTime()-timing,decode=DebugLog.start();
                    LodColumn column=stored==null?null:ColumnCodec.decodeLevels(LodDatabase.unpack(stored),cacheMask);
                    long decodeNanos=DebugLog.end(SERVER_CACHE_DECODE,decode);
                    long revision=invalid==null?0:ByteBuffer.wrap(invalid).getLong();
                    DebugLog.end(SERVER_CACHE,timing);long ready=DebugLog.start();
                    complete(() -> {
                        DebugLog.end(SERVER_COMPLETION,ready);
                        if(work.get(key)!=w)return;
                        if(w.demands.isEmpty()&&w.maintenance==null&&w.backgroundKind==0){finish(w,-1);return;}
                        long latest=Math.max(revision,versions.getOrDefault(key,0L));
                        if(DebugLog.verbose())DebugLog.log("SERVER cache dimension={} x={} z={} request_ids={} result={} stored_bytes={} version={} latest={} read_ms={} decode_ms={} work_ms={}",key.dimension.location(),key.x,key.z,w.demands.stream().map(d->d.want.requestId()).toList(),column==null?"missing":column.version()<latest?"stale":"hit",stored==null?0:stored.length,column==null?0:column.version(),latest,DebugLog.millis(readNanos),DebugLog.millis(decodeNanos),DebugLog.millis(System.nanoTime()-w.started));
                        if(column!=null && column.version()>=latest) { hits++; encodeAndQueue(w,column); }
                        else {
                            misses++;releaseCache(w);
                            if(DebugLog.enabled()){if(column==null)debugMissing++;else debugStale++;}
                            // Bound missing-column work separately so it cannot fill the cache lane.
                            if(!generationCapacity(w,limits)){
                                long waiting=work.values().stream().filter(other->other.stage==4).count();
                                if(waiting>=Math.min(64,DistantConfig.SERVER_QUEUE.get()/4)){
                                    for(Demand d:w.demands)d.session.blocked("generation_capacity");waiting(w,"generation_capacity");finish(w,1);return;
                                }
                                w.stage=4;waiting(w,"generation_capacity");return;
                            }
                            startGeneration(w);
                        }
                    });
                } catch(RuntimeException e) { complete(() -> { fail(e); finish(w,1); }); }
            });
        }
    }
    private void attach(Work w,Demand d){
        w.demands.add(d);d.session.active++;
        if(w.cacheSlot){d.session.cacheActive++;d.session.cacheReadBytes+=w.bytes;}
        if(w.generationSlot)d.session.generating++;
    }
    private void detach(Work w,Demand d){
        d.session.active--;
        if(w.cacheSlot){d.session.cacheActive--;d.session.cacheReadBytes-=w.bytes;}
        if(w.generationSlot)d.session.generating--;
        d.session.latestRequestIds.remove(w.key.pos(),d.want.requestId());
    }
    private void releaseCache(Work w){
        if(!w.cacheSlot)return;
        w.cacheSlot=false;cacheActive--;cacheReadMemory-=w.bytes;
        for(Demand d:w.demands){d.session.cacheActive--;d.session.cacheReadBytes-=w.bytes;}
    }
    private boolean generationCapacity(Work w,DistantConfig.Limits limits){
        return generationActive<Math.min(limits.concurrency(),Math.max(1,DistantConfig.SERVER_QUEUE.get()-1))
                &&w.demands.stream().allMatch(d->d.session.generating<DistantConfig.PLAYER_CONCURRENCY.get())
                &&memory+w.bytes<=DistantConfig.SERVER_MEMORY.get()*1048576L;
    }
    private void startGeneration(Work w){
        w.generationSlot=true;generationActive++;memory+=w.bytes;
        for(Demand d:w.demands)d.session.generating++;
        w.stage=1;waiting(w,"generation_admission");
    }
    private void admitGeneration(DistantConfig.Limits limits){
        for(Work w:List.copyOf(work.values())){
            if(w.stage!=4)continue;
            w.demands.removeIf(d->{boolean stale=players.get(d.session.player.getUUID())!=d.session||!inRange(d.session,w.key.x,w.key.z);if(stale)detach(w,d);return stale;});
            if(w.demands.isEmpty()){finish(w,-1);continue;}
            if(generationCapacity(w,limits))startGeneration(w);
        }
    }
    private void waiting(Work w,String cause) {
        if(DebugLog.enabled())debugWaitTicks.merge(cause,1,Integer::sum);
        if(!cause.equals(w.waitReason)){
            long now=System.nanoTime();
            if(DebugLog.verbose())DebugLog.log("SERVER work_wait dimension={} x={} z={} from={} to={} previous_ms={} work_ms={} snapshot_sections={} ticket={}",w.key.dimension.location(),w.key.x,w.key.z,w.waitReason,cause,DebugLog.millis(now-w.waitSince),DebugLog.millis(now-w.started),w.snapshots.size(),w.ticket);
            w.waitReason=cause;w.waitSince=now;
        }
    }
    private void processWork(double snapshotMillis) {
        long deadline=System.nanoTime()+(long)(snapshotMillis*1e6);
        var ready=new ArrayList<>(work.values());ready.sort(Comparator.comparingInt(w->w.demands.isEmpty()?1:0));
        for(Work w:ready) {
            w.demands.removeIf(d -> {boolean stale=players.get(d.session.player.getUUID())!=d.session || !inRange(d.session,w.key.x,w.key.z);if(stale)detach(w,d);return stale;});
            if(w.stage!=1)continue;
            if(exclusiveImport()){release(w);finish(w,-1);continue;}
            var level=server.getLevel(w.key.dimension); var pos=new ChunkPos(w.key.x,w.key.z);
            if(w.maintenance != null && w.maintenance.cancelled) { release(w); finish(w,1); continue; }
            if(w.demands.isEmpty() && w.maintenance == null && w.backgroundKind==0) { release(w);finish(w,1);continue; }
            if(w.generationStarted!=0 && System.nanoTime()-w.generationStarted>TimeUnit.SECONDS.toNanos(DistantConfig.GENERATION_TIMEOUT.get())) {
                LOG.warn("LOD work timed out: dimension={} x={} z={} wait={} wait_ms={} ticket={} snapshot_sections={} chunk_status={}",w.key.dimension.location(),w.key.x,w.key.z,w.waitReason,DebugLog.millis(System.nanoTime()-w.waitSince),w.ticket,w.snapshots.size(),level.getChunkSource().getChunkDebugData(pos).replace('\n',' ').replaceAll("§.",""));
                release(w);finish(w,1);continue;
            }
            if(w.backgroundKind==0 && w.maintenance == null && w.demands.stream().allMatch(d->d.session.reserved>=d.session.capacity||d.session.bytes>=DistantConfig.PLAYER_SEND_MIB.get()*1048576L)){waiting(w,"send_backpressure");continue;}
            LevelChunk chunk=level.getChunkSource().getChunkNow(pos.x,pos.z);
            if(chunk==null && w.backgroundKind!=0 && w.demands.isEmpty()){
                release(w); w.stage=5; submitSavedChunk(w,level); continue;
            }
            if(w.generationStarted==0){
                if(chunk==null&&w.maintenance==null&&!DistantConfig.SERVER_GENERATE.get()){finish(w,2);continue;}
                if(!reason.equals("工作中") && !(w.maintenance!=null&&reason.equals("无远景订阅"))
                        && !(w.backgroundKind!=0&&reason.equals("无远景订阅"))){waiting(w,"schedule_gate:"+reason);continue;}
                if(tokens<1){waiting(w,"generation_rate");continue;}
                w.generationStarted=System.nanoTime();tokens--;
            }
            if(chunk==null && !w.ticket) {
                if(w.maintenance == null && !reason.equals("工作中")){waiting(w,"schedule_gate:"+reason);continue;}
                if((w.maintenance != null || w.backgroundKind!=0) && !reason.equals("工作中") && !reason.equals("无远景订阅")){waiting(w,"schedule_gate:"+reason);continue;}
                if(w.maintenance == null && !DistantConfig.SERVER_GENERATE.get()) { finish(w,2);continue; }
                PriorityState.add(level,pos);
                level.getChunkSource().addRegionTicket(TICKET,pos,0,pos); w.ticket=true;
            }
            if(chunk==null){waiting(w,"chunk_load_or_generate");continue;}
            if(!chunk.isLightCorrect()){waiting(w,"lighting");continue;}
            if(System.nanoTime()>=deadline){waiting(w,"snapshot_budget");continue;}
            waiting(w,"snapshot_copy");
            if(w.snapshots.isEmpty())w.version=versions.computeIfAbsent(w.key,k -> sequence.incrementAndGet());
            if(w.version!=versions.getOrDefault(w.key,0L)) { DebugLog.count(SERVER_SNAPSHOT_RESTART);w.snapshots.clear();w.version=versions.get(w.key); }
            while(w.snapshots.size()<chunk.getSectionsCount() && System.nanoTime()<deadline) {
                int index=w.snapshots.size(), y=chunk.getMinSection()+index; var section=chunk.getSections()[index];
                var light=level.getLightEngine(); var sectionPos=SectionPos.of(pos,y);
                var block=light.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
                var sky=light.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);
                var biomes=section.getBiomes().recreate();
                for(int by=0;by<4;by++)for(int bz=0;bz<4;bz++)for(int bx=0;bx<4;bx++)biomes.getAndSetUnchecked(bx,by,bz,section.getBiomes().get(bx,by,bz));
                w.snapshots.add(new ChunkSnapshot(pos.x,y,pos.z,section.getStates().copy(),biomes,block==null?null:block.copy(),sky==null?null:sky.copy()));
            }
            if(w.snapshots.size()==chunk.getSectionsCount()) { release(w);w.stage=2;submitConversion(w); }
        }
    }
    private void submitConversion(Work w) {
        long queued=DebugLog.start();
        workers.execute(() -> {
            DebugLog.end(SERVER_WORKER_QUEUE,queued);
            try {
                long start = System.nanoTime();
                long conversion=DebugLog.start();
                var column = ColumnConverter.convert(w.key.x, w.key.z, w.version, w.snapshots);
                DebugLog.end(SERVER_CONVERT,conversion);
                boolean stored=database.storeColumn(w.key.key(1), LodDatabase.pack(ColumnCodec.encode(column, 31)),column.version());
                double duty = DistantConfig.serverLimits().dutyCycle();
                if (duty < 1) {long pacing=DebugLog.start();LockSupport.parkNanos((long) ((System.nanoTime() - start) * (1 / duty - 1)));DebugLog.end(SERVER_PACE,pacing);}
                complete(() -> {
                    if(!stored){finish(w,1);return;}
                    if(w.backgroundKind!=0)w.backgroundResolved=true;
                    completed++;
                    if (w.maintenance != null) {
                        finishMaintenance(w, column);
                    } else encodeAndQueue(w, column);
                });
            } catch (RuntimeException ex) { complete(() -> { fail(ex); finish(w, 1); }); }
        });
    }
    private void encodeAndQueue(Work w,LodColumn column) {
        if(work.get(w.key)!=w)return;
        if(w.demands.isEmpty()&&w.maintenance==null&&w.backgroundKind==0){finish(w,-1);return;}
        if(exclusiveImport()){finish(w,-1);return;}
        if(column.version()<versions.getOrDefault(w.key,0L)) {finish(w,1);return;}
        w.stage=3;var bands=DistanceBands.parse(DistantConfig.BANDS.get());
        var recipients=new HashMap<Integer,List<Demand>>();
        for(Demand d:w.demands) {
            var p=d.session.player.chunkPosition();int level=bands.select(w.key.x,w.key.z,p.x,p.z);
            if((column.mask()&(1<<level))==0){reply(d.session,d.want,1,"missing_level");}
            else if(d.want.version()==column.version() && d.want.level()<=level) {
                reply(d.session,d.want,0);
            } else recipients.computeIfAbsent(level,k -> new ArrayList<>()).add(d);
        }
        if(recipients.isEmpty()){finish(w,-1);return;}
        int compressionLevel=DistantConfig.COMPRESSION_LEVEL.get();
        boolean batch=DistantConfig.MAX_BATCH_COLUMNS.get()>1;
        long queued=DebugLog.start();
        workers.execute(() -> {
            DebugLog.end(SERVER_WORKER_QUEUE,queued);
            long timing=DebugLog.start();try {
                var encodings=new HashMap<Integer,ColumnCodec.Encoded>();
                for(int level:recipients.keySet()){
                    if(batch){byte[] raw=ColumnCodec.encodeRaw(column,1<<level);encodings.put(level,new ColumnCodec.Encoded(false,raw.length,raw));}
                    else encodings.put(level,ColumnCodec.encode(column,1<<level,compressionLevel));
                }
                DebugLog.end(SERVER_ENCODE,timing);long ready=DebugLog.start();
                complete(() -> {
                    DebugLog.end(SERVER_COMPLETION,ready);
                    if(work.get(w.key)!=w)return;
                    if(column.version()<versions.getOrDefault(w.key,0L)){finish(w,1);return;}
                    releaseCache(w);
                    for(var entry:recipients.entrySet())for(Demand d:entry.getValue()) {
                        Session s=d.session;var data=encodings.get(entry.getKey());
                        if(players.get(s.player.getUUID())!=s || !w.demands.contains(d))continue;
                        var readyColumn=new Ready(new Protocol.Member(w.key.x,w.key.z,column.version(),entry.getKey(),d.want.requestId(),data.rawLength()),data.bytes(),ticks);
                        long charge=batch?readyColumn.charge():data.bytes().length;
                        if(s.bytes+s.encodingBytes+s.cacheReadBytes+charge>DistantConfig.PLAYER_SEND_MIB.get()*1048576L || totalQueuedBytes()+charge>DistantConfig.TOTAL_SEND_MIB.get()*1048576L) {reply(s,d.want,1,"send_memory");continue;}
                        if(batch){var previous=s.ready.put(w.key.pos(),readyColumn);if(previous!=null)s.bytes-=previous.charge();}
                        else s.send.add(new Transfer(++transferSequence,w.key,entry.getKey(),column.version(),s.epoch,d.want.requestId(),data));s.bytes+=charge;
                        if(DebugLog.verbose())DebugLog.log("SERVER queued player={} epoch={} transfer={} x={} z={} request_id={} level={} payload_bytes={} raw_bytes={} work_ms={}",s.player.getUUID(),s.epoch,transferSequence,w.key.x,w.key.z,d.want.requestId(),entry.getKey(),data.bytes().length,data.rawLength(),(System.nanoTime()-w.started)/1e6);
                    }
                    finish(w,-1);
                    for(var entry:recipients.values())for(Demand d:entry)flushBatch(d.session);
                    flushSends(System.nanoTime());
                });
            }catch(RuntimeException e){complete(() -> {fail(e);finish(w,1);});}
        });
    }
    private void finish(Work w,int status) {
        if(work.get(w.key)!=w)return;
        work.remove(w.key,w);releaseCache(w);
        if(w.generationSlot){memory-=w.bytes;generationActive--;}
        if(w.backgroundKind!=0){backgroundActive--;refreshPending.remove(w.key);missingPending.remove(w.key);
            if(w.backgroundResolved)backgroundCompleted++;else if(status>=0)retryBackground(w);}
        w.snapshots.clear();
        if (w.maintenance != null) {
            w.maintenance.active--;
            if(w.maintenanceResult==0)w.maintenance.completed();
            else if(w.maintenanceResult==2)w.maintenance.skipped();
            else w.maintenance.failed();
        }
        for(Demand d:w.demands){if(status>=0 && players.get(d.session.player.getUUID())==d.session && d.want.requestId()==d.session.latestRequestIds.getOrDefault(w.key.pos(),-1L))reply(d.session,d.want,status,w.waitReason);detach(w,d);}
    }
    private void release(Work w) {
        if(!w.ticket)return;
        var level=server.getLevel(w.key.dimension);var pos=new ChunkPos(w.key.x,w.key.z);
        level.getChunkSource().removeRegionTicket(TICKET,pos,0,pos);PriorityState.remove(level,pos);w.ticket=false;
    }
    private void flushDirty() {
        if((dirty.isEmpty()&&missingChecks.isEmpty())||importStorageFailed)return;
        // Ordered invalidations: an older asynchronous batch must never replace a newer revision.
        if(dirtyWrites.get()!=0)return;
        var batch=new HashMap<byte[],byte[]>();var flushed=new HashMap<Key,Long>();int count=0;
        for(var it=dirty.entrySet().iterator();it.hasNext() && count++<256;) {
            var e=it.next();it.remove();Key k=e.getKey();long version=e.getValue();
            batch.put(k.key(2),ByteBuffer.allocate(8).putLong(version).array());
            batch.put(k.key(3),LodDatabase.pendingValue(version,0));
            flushed.put(k,version);
            if(!exclusiveImport()&&!importStorageFailed)for(Session s:players.values())if(s.dimension==k.dimension && inRange(s,k.x,k.z))Protocol.send(s.player,new Protocol.Dirty(k.dimension.location().toString(),k.x,k.z,version,false));
        }
        var checks=new ArrayList<Key>();count=0;
        for(var it=missingChecks.iterator();it.hasNext()&&count++<256;){Key key=it.next();it.remove();checks.add(key);}
        checks.removeIf(flushed::containsKey);
        dirtyWrites.incrementAndGet();workers.execute(() -> {try{
            database.invalidationsAndMissing(batch,checks.stream().map(k->k.key(4)).toList());
            complete(()->{for(var e:flushed.entrySet())if(!work.containsKey(e.getKey())&&server.getLevel(e.getKey().dimension).getChunkSource().getChunkNow(e.getKey().x,e.getKey().z)==null)versions.remove(e.getKey(),e.getValue());});
        }catch(RuntimeException e){complete(() -> {
            for(var entry:flushed.entrySet())dirty.merge(entry.getKey(),entry.getValue(),Math::max);
            missingChecks.addAll(checks);
            if(exclusiveImport()){importStorageFailed=true;maintenance.failed();maintenance.error=e.toString();maintenance.cancelled=true;}
            fail(e);
        });}finally{dirtyWrites.decrementAndGet();}});
    }
    private long totalQueuedBytes(){long n=batchMemory+cacheReadMemory;for(Session s:players.values())n+=s.bytes;return n;}
    private void flushBatch(Session s) {
        if(exclusiveImport()||importStorageFailed)return;
        if(reopening)return;
        if(s.batching||s.ready.isEmpty())return;
        for(var it=s.ready.values().iterator();it.hasNext();){Ready r=it.next();var m=r.member();
            if(!inRange(s,m.x(),m.z())||m.version()<versions.getOrDefault(new Key(s.dimension,m.x(),m.z()),0L)){
                it.remove();s.bytes-=r.charge();reply(s,new Protocol.Want(m.x(),m.z(),m.version(),m.level(),m.requestId()),1,"batch_stale_or_out_of_range");
            }
        }
        if(s.ready.isEmpty())return;
        int max=DistantConfig.MAX_BATCH_COLUMNS.get(),sections=s.player.level().getSectionsCount(),raw=4;
        long credit=s.capacity-s.reserved-s.batchCredit,charge=0,reservation=0;
        var members=new ArrayList<Protocol.Member>();var columns=new ArrayList<byte[]>();
        var candidates=new ArrayList<>(s.ready.values());var center=s.player.chunkPosition();
        boolean age=ticks-s.lastReadyAgedTick>=20;
        candidates.sort(Comparator.comparingLong((Ready r)->distance(center,r.member().x(),r.member().z()))
                .thenComparingInt(Ready::tick));
        if(age){var oldest=s.ready.firstEntry().getValue();candidates.remove(oldest);candidates.addFirst(oldest);}
        for(Ready r:candidates){
            if(members.size()==max)break;
            int next=raw+4+r.raw().length;if(next>ColumnCodec.MAX_BYTES)continue;
            members.add(r.member());long needed=Protocol.batchReservation(next,next,members,sections);
            if(needed>credit){members.removeLast();continue;}
            raw=next;reservation=needed;columns.add(r.raw());charge+=r.charge();
        }
        if(columns.isEmpty()){s.blocked("batch_credit");return;}
        boolean full=columns.size()==max,limited=columns.size()<s.ready.size();
        boolean tail=s.pending.isEmpty()&&s.active==0;
        if(!full&&!limited&&!tail&&members.stream().allMatch(m->s.ready.get(ChunkPos.asLong(m.x(),m.z())).tick()==ticks))return;
        String cause=full?"full":limited?"budget":tail?"tail":"tick";
        batchFlushes[full?0:limited?1:tail?2:3]++;
        if(age&&members.contains(s.ready.firstEntry().getValue().member()))s.lastReadyAgedTick=ticks;
        for(var m:members)s.ready.remove(ChunkPos.asLong(m.x(),m.z()));
        int epoch=s.epoch,compression=DistantConfig.COMPRESSION_LEVEL.get();long held=charge,heldCredit=reservation;
        var metadata=List.copyOf(members);s.batching=true;s.batchCredit+=heldCredit;
        s.bytes-=held;s.encodingBytes+=held;batchMemory+=held;
        if(DebugLog.verbose())DebugLog.log("SERVER batch player={} epoch={} columns={} reason={} raw_bytes={} held_bytes={} credit={}",s.player.getUUID(),epoch,columns.size(),cause,raw,held,heldCredit);
        long queued=DebugLog.start();
        workers.execute(()->{
            DebugLog.end(SERVER_WORKER_QUEUE,queued);
            try{
                long encode=DebugLog.start();
                var data=BatchCodec.encode(columns,compression);
                long encoded=DebugLog.end(SERVER_BATCH_ENCODE,encode),ready=DebugLog.start();
                complete(()->{
                    DebugLog.end(SERVER_COMPLETION,ready);
                    s.batching=false;s.encodingBytes-=held;batchMemory-=held;
                    if(players.get(s.player.getUUID())!=s||s.epoch!=epoch){if(players.get(s.player.getUUID())==s)flushBatch(s);return;}
                    s.bytes+=data.bytes().length;s.batchCredit-=heldCredit;
                    var transfer=new Transfer(++transferSequence,s.dimension,epoch,metadata,data);
                    if(DebugLog.verbose())DebugLog.log("SERVER encoded player={} epoch={} transfer={} columns={} raw_bytes={} payload_bytes={} encode_ms={}",s.player.getUUID(),epoch,transfer.id,metadata.size(),data.rawLength(),data.bytes().length,DebugLog.millis(encoded));
                    s.batchCredit+=transfer.reservation(sections);s.send.add(transfer);
                    flushBatch(s);flushSends(System.nanoTime());
                });
            }catch(RuntimeException e){complete(()->{
                s.batching=false;s.encodingBytes-=held;batchMemory-=held;fail(e);
                if(players.get(s.player.getUUID())!=s||s.epoch!=epoch)return;
                s.batchCredit-=heldCredit;
                for(var m:metadata)reply(s,new Protocol.Want(m.x(),m.z(),m.version(),m.level(),m.requestId()),1,"batch_encode_failed");
            });}
        });
    }

    private void processMaintenance(DistantConfig.Limits limits) {
        MaintenanceTask task = maintenance;
        if(exclusiveImport()){processImport(task);return;}
        if (task == null || task.cancelled) return;
        if (!maintenancePaused && task.type == MaintenanceType.PREGEN) {
            while (task.pending.size() + task.active < Math.min(4096, Math.max(1, limits.concurrency() * 2))) {
                MaintenanceEntry next = task.nextPregen();
                if (next == null) { task.scanning = false; break; }
                task.pending.add(next); task.scanned++; task.queued++;
            }
        }
        while (!maintenancePaused && task.pending.size() + task.active < Math.min(4096, Math.max(1, limits.concurrency() * 2))) {
            MaintenanceEntry next = task.queue.poll();
            if (next == null) break;
            task.pending.add(next);
        }
        while (!maintenancePaused && (reason.equals("工作中") || reason.equals("无远景订阅"))
                && generationActive < Math.min(limits.concurrency(),Math.max(1,DistantConfig.SERVER_QUEUE.get()-1)) && work.size() < DistantConfig.SERVER_QUEUE.get()) {
            MaintenanceEntry entry = task.pending.poll();
            if (entry == null) break;
            if (work.containsKey(entry.key)) {
                task.pending.addLast(entry);
                break;
            }
            ServerLevel level = server.getLevel(entry.key.dimension);
            if (level == null) { task.skipped(); continue; }
            long bytes = level.getSectionsCount() * ChunkSnapshot.RESERVED_BYTES;
            long budget=DistantConfig.SERVER_MEMORY.get()*1048576L;
            if(bytes>budget){task.failed();LOG.error("Maintenance snapshot budget cannot fit a column in {}",entry.key.dimension.location());continue;}
            if (memory + bytes > budget || tokens < 1) { task.pending.addFirst(entry); break; }
            Work w = new Work(entry.key, bytes); w.maintenance = task;w.generationSlot=true;w.generationStarted=System.nanoTime();generationActive++;
            work.put(entry.key, w); memory += bytes; task.active++; tokens--;
            workers.execute(() -> {
                try {
                    byte[] invalid = database.get(entry.key.key(2));
                    byte[] stored = database.get(entry.key.key(1));
                    int mask = 31;
                    LodColumn column = stored == null ? null : ColumnCodec.decodeLevels(LodDatabase.unpack(stored), mask);
                    long revision = invalid == null ? 0 : ByteBuffer.wrap(invalid).getLong();
                    complete(() -> {
                        long latest = Math.max(revision, versions.getOrDefault(entry.key, 0L));
                        if (column != null && column.version() >= latest) { hits++; finishMaintenance(w, column); }
                        else { misses++; w.stage = 1; }
                    });
                } catch (RuntimeException ex) { complete(() -> { fail(ex); finish(w, 1); }); }
            });
        }
        if (task.done()) completeMaintenance(task);
    }

    private void completeMaintenance(MaintenanceTask task) {
        if (maintenance != task) return;
        lastMaintenance = task;
        maintenance = null;
        task.ended=System.nanoTime();task.phase="已结束";
        if(task.type==MaintenanceType.IMPORT){
            task.queue.clear();
            completed+=task.completed;
            for(Session s:players.values())Protocol.send(s.player,new Protocol.Maintenance(importStorageFailed||configChange!=null));
            LOG.info("IMPORT timing read_ms={} convert_ms={} encode_ms={} write_ms={} elapsed_ms={}",task.readNanos.sum()/1e6,task.convertNanos.sum()/1e6,task.encodeNanos.sum()/1e6,task.writeNanos.sum()/1e6,(task.ended-task.started)/1e6);
            LOG.info("IMPORT lifecycle prepare_ms={} drained_ms={} synced_ms={} restored_ms={}",(task.importingAt-task.started)/1e6,(task.drainedAt-task.started)/1e6,(task.syncedAt-task.started)/1e6,(task.ended-task.started)/1e6);
            if(task.unreadable!=0)LOG.warn("IMPORT skipped {} unreadable chunks",task.unreadable);
        }
        if (task.source != null && !stopping) {
            String result=task.type.name().toLowerCase(Locale.ROOT)+" 维护任务已结束：完成 "+task.completed+"，跳过 "+task.skipped+"，失败 "+task.failed;
            if (task.failed != 0) task.source.sendFailure(Component.literal(result+" "+task.error));
            else task.source.sendSuccess(() -> Component.literal(result), false);
        }
        task.source=null;
    }

    private void finishMaintenance(Work w, LodColumn column) {
        w.maintenanceResult=0;
        if (w.demands.isEmpty()) finish(w, -1);
        else encodeAndQueue(w, column);
    }
    private int creditLimit(Session s){return Math.min(s.advertisedCapacity,(int)Math.min(Integer.MAX_VALUE,DistantConfig.PLAYER_SEND_MIB.get()*1048576L*4));}
    private static long distance(ChunkPos center,int x,int z){long dx=(long)x-center.x,dz=(long)z-center.z;return dx*dx+dz*dz;}
    private void selectTransfer(Session s){
        Transfer head=s.send.peek();if(head==null||head.offset!=0)return;
        Transfer chosen=head;boolean age=ticks-s.lastSendAgedTick>=20;long best=Long.MAX_VALUE;var center=s.player.chunkPosition();
        for(var candidate:s.send){
            if(age){if(candidate.created<chosen.created)chosen=candidate;}
            else {long nearest=Long.MAX_VALUE;if(candidate.members.isEmpty())nearest=distance(center,candidate.key.x,candidate.key.z);
                else for(var m:candidate.members)nearest=Math.min(nearest,distance(center,m.x(),m.z()));
                if(nearest<best){best=nearest;chosen=candidate;}}
        }
        if(age)s.lastSendAgedTick=ticks;
        if(chosen!=head){s.send.remove(chosen);s.send.addFirst(chosen);}
    }
    private void flushSends(long now) {
        if(exclusiveImport()||importStorageFailed)return;
        totalBudget.update(now,DistantConfig.TOTAL_MBPS.get()*1_000_000/8);
        var sessions=new ArrayList<>(players.values());int attempts=0,idle=0;
        for(Session s:sessions) {
            s.capacity=creditLimit(s);
            flushBatch(s);
            if(ticks%40==0)s.vanilla.removeIf(p->!ChunkMap.isChunkInRange(ChunkPos.getX(p),ChunkPos.getZ(p),s.player.chunkPosition().x,s.player.chunkPosition().z,server.getPlayerList().getViewDistance()));
            double cap=DistantConfig.TOTAL_MBPS.get()*1_000_000/8;
            if(s.bandwidth>0)cap=Math.min(cap,s.bandwidth*1024.0);
            if(DistantConfig.PLAYER_KBPS.get()>0)cap=Math.min(cap,DistantConfig.PLAYER_KBPS.get()*1024.0);
            s.budget.update(now,cap);
        }
        while(!sessions.isEmpty() && totalBudget.available()>128 && attempts<sessions.size()*256 && idle<sessions.size()) {
            Session s=sessions.get(Math.floorMod(sendCursor++,sessions.size()));attempts++;idle++;
            selectTransfer(s);Transfer t=s.send.peek();if(t==null)continue;
            boolean stale=t.members.isEmpty()?(!inRange(s,t.key.x,t.key.z)||t.version<versions.getOrDefault(t.key,0L)):t.members.stream().anyMatch(m->!inRange(s,m.x(),m.z())||m.version()<versions.getOrDefault(new Key(s.dimension,m.x(),m.z()),0L));
            if(stale||t.epoch!=s.epoch){if(DebugLog.verbose())DebugLog.log("SERVER discard player={} epoch={} transfer={} offset={} reason={}",s.player.getUUID(),t.epoch,t.id,t.offset,stale?"stale":"epoch");s.send.remove();s.bytes-=t.data.bytes().length;if(t.offset>0){var credit=s.inflight.get(t.id);if(credit!=null)credit.aborting=true;Protocol.send(s.player,new Protocol.Abort(t.epoch,t.id));}else if(!t.members.isEmpty()){s.batchCredit-=t.reservation(s.player.level().getSectionsCount());for(var m:t.members)reply(s,new Protocol.Want(m.x(),m.z(),m.version(),m.level(),m.requestId()),1,"transfer_stale");}idle=0;continue;}
            long reservation=t.reservation(s.player.level().getSectionsCount());
            // A batch already reserved before a lower limit was applied may drain on its own.
            // New single-column transfers always obey the current effective credit.
            long sendCapacity=t.members.isEmpty()?s.capacity:Math.max(s.capacity,Math.min(s.advertisedCapacity,reservation));
            if(t.offset==0 && s.reserved+reservation>sendCapacity){DebugLog.count(SERVER_CREDIT_BLOCK);s.blocked("send_credit");continue;}
            int overhead=128+(t.offset==0?t.members.size()*29:0);
            if(s.deficit<=overhead)s.deficit+=Protocol.FRAGMENT_BYTES;
            int size=Math.min(Protocol.FRAGMENT_BYTES,Math.min(s.deficit-overhead,Math.min(totalBudget.available(),s.budget.available())-overhead));
            size=Math.min(size,t.data.bytes().length-t.offset);if(size<=0){if(s.budget.available()<=overhead){DebugLog.count(SERVER_PLAYER_BANDWIDTH_BLOCK);s.blocked("player_bandwidth");}if(totalBudget.available()<=overhead)s.blocked("total_bandwidth");continue;}
            if(t.offset==0){
                t.firstSent=System.nanoTime();s.reserved+=reservation;if(!t.members.isEmpty())s.batchCredit-=reservation;s.inflight.put(t.id,new Credit(reservation,t.firstSent,t));
                if(DebugLog.verbose())DebugLog.log("SERVER send_begin player={} world={} dimension={} epoch={} transfer={} columns={} payload_bytes={} raw_bytes={} queue_ms={} reservation={}",s.player.getUUID(),world,s.dimension.location(),t.epoch,t.id,t.members.isEmpty()?1:t.members.size(),t.data.bytes().length,t.data.rawLength(),DebugLog.millis(t.firstSent-t.created),reservation);
            }
            byte[] bytes=Arrays.copyOfRange(t.data.bytes(),t.offset,t.offset+size);
            if(t.members.isEmpty())Protocol.send(s.player,new Protocol.Fragment(t.epoch,t.id,t.key.x,t.key.z,t.version,t.level,t.requestId,t.data.compressed(),t.data.rawLength(),t.data.bytes().length,t.offset,bytes));
            else Protocol.send(s.player,new Protocol.BatchFragment(t.epoch,t.id,t.data.compressed(),t.data.rawLength(),t.data.bytes().length,t.offset,t.offset==0?t.members:List.of(),bytes));
            idle=0;
            t.offset+=size;t.fragments++;s.payload+=size;s.deficit-=size+overhead;totalBudget.spend(size+overhead);s.budget.spend(size+overhead);sentBytes+=size+overhead;s.sent+=size+overhead;
            if(t.members.isEmpty())sentLevels[t.level]+=size+overhead;
            if(t.offset==t.data.bytes().length){
                var credit=s.inflight.get(t.id);if(credit!=null)credit.submitted=System.nanoTime();
                if(DebugLog.verbose())DebugLog.log("SERVER submitted player={} epoch={} transfer={} fragments={} payload_bytes={} submit_ms={}",s.player.getUUID(),t.epoch,t.id,t.fragments,t.offset,DebugLog.millis(System.nanoTime()-t.firstSent));
                s.send.remove();s.bytes-=t.data.bytes().length;
            }
            if(s.deficit>128&&!s.send.isEmpty())sendCursor--;
        }
        if(DebugLog.enabled()&&totalBudget.available()<=128&&sessions.stream().anyMatch(s->!s.send.isEmpty()))DebugLog.count(SERVER_TOTAL_BANDWIDTH_BLOCK);
    }
    private void fail(RuntimeException e){failure=e.toString();LOG.error("Voxy Distant server task failed",e);}
    public static String status(){var s=instance;return s==null?"服务端未启动":String.format(Locale.ROOT,"TPS %.1f / MSPT %.1f · 原版 %d · 读缓存 %d · 生成 %d · 完成 %d · 缓存 %d/%d · 内存 %.1f MiB · 发送 %.1f MiB · 生成调度：%s%s",s.governor.tps(),s.server.getAverageTickTime(),PriorityState.foregroundPending(),s.cacheActive,s.generationActive,s.completed,s.hits,s.hits+s.misses,s.memory/1048576.0,s.sentBytes/1048576.0,s.reason,s.failure.isEmpty()?"":" · "+s.failure);}
    private static String details(){var s=instance;if(s==null)return status();var out=new StringBuilder(status());out.append(String.format(Locale.ROOT,"\n平均生成 %.2f 列/秒",s.completed/Math.max(1,(System.nanoTime()-s.started)/1e9)));out.append("\n单列各层发送字节（合批不拆分归属） ").append(Arrays.toString(s.sentLevels)).append(" · 排队及压缩预留 ").append(s.totalQueuedBytes()).append(" B · 待保存 ").append(s.dirtyWrites.get());out.append("\n合批触发 满批/预算/尾批/tick ").append(Arrays.toString(s.batchFlushes));
        var task=s.maintenance!=null?s.maintenance:s.lastMaintenance;if(task!=null){long processed=task.completed+task.skipped+task.failed-task.scanFailures;out.append("\n维护任务 ").append(task.type==MaintenanceType.PREGEN?"pregen":"import").append(" · 扫描槽位 ").append(task.scanned).append(" · 候选入队 ").append(task.queued).append(" · 完成 ").append(task.completed).append(" · 跳过 ").append(task.skipped).append(" · 失败 ").append(task.failed).append(" · 已处理 ").append(processed).append('/').append(task.total).append(task.scanning?" · 扫描中":"").append(s.maintenance==null?" · 已结束":s.maintenancePaused?" · 暂停":"").append(" · 活动 ").append(task.active);if(task.type==MaintenanceType.IMPORT){double seconds=((task.ended==0?System.nanoTime():task.ended)-task.started)/1e9;out.append(String.format(Locale.ROOT," · %s · 线程 %d · 导入内存 %.1f/%.1f MiB · %.1f 秒 · %.1f 列/秒",task.phase,task.importThreads,task.importMemory/1048576.0,task.importBudget/1048576.0,seconds,task.completed/Math.max(0.001,seconds))).append(" · 读取异常 ").append(task.unreadable);}}
        for(var p:s.players.values())out.append("\n").append(p.player.getGameProfile().getName()).append(": 生成 ").append(p.active).append("，待请求 ").append(p.pending.size()).append("，排队 ").append(p.bytes+p.encodingBytes).append(" B，接收预留 ").append(p.reserved).append(" B，累计发送 ").append(p.sent).append(" B");return out.toString();}
    public static void configuration(ServerPlayer player, Protocol.ConfigRequest request) {
        var self=instance;
        if(self==null || self.stopping)return;
        java.util.function.Consumer<String> reply=error -> {
            if(player.connection.isAcceptingMessages())Protocol.send(player,new Protocol.ConfigResult(request.id(),self.configRevision,error.isEmpty(),error,
                    player.hasPermissions(2)?ServerSettings.current().values():List.of()));
        };
        if(!player.hasPermissions(2)){reply.accept("权限不足：需要 OP 等级 2");return;}
        if(!request.save()){reply.accept("");return;}
        if(request.revision()!=self.configRevision){reply.accept("配置已被其他管理员修改，请重新读取后保存");return;}
        self.applyConfiguration(new ServerSettings.Snapshot(request.values()),()->player.hasPermissions(2)&&player.connection.isAcceptingMessages(),reply);
    }
    public static void cacheStats(ServerPlayer player) {
        var self=instance;
        if(self==null||self.stopping||self.database==null)return;
        self.workers.execute(()->{
            try {
                var db=self.database;
                var stats=db.cacheStats();
                self.server.execute(()->{if(!self.stopping&&player.connection.isAcceptingMessages())Protocol.send(player,new Protocol.CacheStats(stats.bytes(),stats.columns()));});
            }catch(RuntimeException ex){self.server.execute(()->self.fail(ex));}
        });
    }
    private void applyConfiguration(ServerSettings.Snapshot next,java.util.function.BooleanSupplier allowed,java.util.function.Consumer<String> done) {
        if(configChange!=null){done.accept("另一项配置正在应用，请稍后重新读取");return;}
        try{
            next.validate();
            validateSendMemory(server,(int)next.number(DistantConfig.PLAYER_SEND_MIB),(int)next.number(DistantConfig.TOTAL_SEND_MIB));
        }catch(IllegalArgumentException ex){done.accept(ex.getMessage());return;}
        var before=ServerSettings.current();
        configChange=new ConfigChange(before,next,allowed,done);
        maintenancePaused = true;
        if(maintenance==null&&!importStorageFailed&&next.get(DistantConfig.SERVER_CACHE_MIB).equals(before.get(DistantConfig.SERVER_CACHE_MIB)))finishConfigChange(null);
    }
    private void advanceConfigChange() {
        if(exclusiveImport() && maintenance.importWorkers!=null && !maintenance.importWorkers.isTerminated())return;
        if(!work.isEmpty()||dirtyWrites.get()!=0||workers.getActiveCount()!=0||!workers.getQueue().isEmpty()||batchMemory!=0)return;
        var change=configChange;
        if(!change.allowed.getAsBoolean()){finishConfigChange("权限已撤销或连接已断开");return;}
        if(!importStorageFailed&&change.after.get(DistantConfig.SERVER_CACHE_MIB).equals(change.before.get(DistantConfig.SERVER_CACHE_MIB))){finishConfigChange(null);return;}
        reopening=true;
        workers.execute(()->{
            String error=null;
            try{reopenDatabase((int)change.after.number(DistantConfig.SERVER_CACHE_MIB));}
            catch(RuntimeException ex){LOG.error("Reopening LOD cache failed",ex);error="缓存重开失败："+ex.getMessage();
                try{reopenDatabase((int)change.before.number(DistantConfig.SERVER_CACHE_MIB));}
                catch(RuntimeException restore){LOG.error("Restoring LOD cache failed",restore);error+="；恢复失败，远景服务已停止："+restore.getMessage();}
            }
            String result=error;server.execute(()->{if(!stopping)finishConfigChange(result);});
        });
    }
    private void reopenDatabase(int mib) {
        if(database!=null){
            try{database.close();}
            catch(RuntimeException ex){if(!importStorageFailed)throw ex;LOG.error("Closing failed LOD storage before recovery",ex);}
            finally{database=null;}
        }
        database=new LodDatabase(server.getWorldPath(LevelResource.ROOT).resolve("data/voxy-distant"),mib*1048576L);
        database.countCachedColumns();
        database.sync();importStorageFailed=false;failure="";
    }
    private void finishConfigChange(String error) {
        var change=configChange;
        if(error==null) {
            try{
                if(!change.allowed.getAsBoolean())throw new IllegalArgumentException("权限已撤销或连接已断开");
                DistantConfig.saveServer(change.after);
            }catch(RuntimeException ex){LOG.error("Applying server settings failed",ex);error="配置未应用："+ex.getMessage();
                if(reopening){
                    String original=error;
                    workers.execute(()->{
                        String restored=original;
                        try{reopenDatabase((int)change.before.number(DistantConfig.SERVER_CACHE_MIB));}
                        catch(RuntimeException restore){LOG.error("Restoring LOD cache failed",restore);restored+="；缓存恢复失败，远景服务已停止";}
                        String result=restored;server.execute(()->{if(!stopping)finishConfigChange(result);});
                    });return;
                }
            }
        }
        configChange=null;reopening=false;maintenancePaused=false;
        if(error==null){
            configRevision++;
            // Equal greetings are ignored by clients, so transport-only edits preserve the session.
            for(Session s:players.values()){hello(s.player);Protocol.send(s.player,new Protocol.Maintenance(exclusiveImport()||importStorageFailed));}
            change.done.accept("");
        }else{if(database==null){failure=error;importStorageFailed=true;if(exclusiveImport()){maintenance.failed();maintenance.error=error;maintenance.cancelled=true;}}change.done.accept(error);}
    }
    private static Path regionPath(MinecraftServer server, ServerLevel level) {
        return DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("region");
    }
    private void startPregen(CommandSourceStack source, int radius, int blockX, int blockZ) {
        if (maintenance != null) { source.sendFailure(Component.literal("已有维护任务正在运行")); return; }
        if(importStorageFailed){source.sendFailure(Component.literal("LOD 数据库不可用，请先重开缓存"));return;}
        ServerLevel level = source.getLevel() == null ? server.overworld() : source.getLevel();
        var task = new MaintenanceTask(MaintenanceType.PREGEN); task.radius = radius; task.centerX = Math.floorDiv(blockX, 16); task.centerZ = Math.floorDiv(blockZ, 16); task.dimension = level.dimension(); task.coordinates = MaintenanceSupport.circularCoordinates(radius).iterator(); task.total = MaintenanceSupport.circularCoordinateCount(radius); task.scanning=true; task.source = source;
        maintenance = task; source.sendSuccess(() -> Component.literal("已开始预生成：中心区块 " + task.centerX + "," + task.centerZ + "，半径 " + radius), false);
    }
    private void startImport(CommandSourceStack source) {
        if (maintenance != null) { source.sendFailure(Component.literal("已有维护任务正在运行")); return; }
        if(configChange!=null||database==null){source.sendFailure(Component.literal("正在初始化或应用配置，请稍后导入"));return;}
        if(importStorageFailed){source.sendFailure(Component.literal("LOD 数据库不可用，请先重开缓存"));return;}
        var task = new MaintenanceTask(MaintenanceType.IMPORT); task.source = source; maintenance = task;
        for(Session s:players.values()){
            Protocol.send(s.player,new Protocol.Maintenance(true));
            s.pending.clear();s.latestRequestIds.clear();
        }
        for(Work w:work.values()){for(Demand d:w.demands)detach(w,d);w.demands.clear();}
        source.sendSuccess(() -> Component.literal("已进入独占导入：远景服务暂停，正在排空后台工作"), false);
    }
    private void processImport(MaintenanceTask task) {
        if(task.phase.equals("收尾"))return;
        if(maintenancePaused)return;
        if(task.cancelled&&task.phase.equals("准备")){completeMaintenance(task);return;}
        if(task.phase.equals("准备")){
            if(!work.isEmpty()||workers.getActiveCount()!=0||!workers.getQueue().isEmpty()||batchMemory!=0)return;
            for(Session s:players.values()){
                s.pending.clear();s.latestRequestIds.clear();s.ready.clear();s.send.clear();s.inflight.clear();
                s.bytes=s.reserved=s.batchCredit=s.encodingBytes=0;s.active=0;
            }
            task.version=sequence.incrementAndGet();
            lastChangeTick.clear();
            try{server.saveEverything(false,true,true);}
            catch(RuntimeException ex){task.failed();task.error=ex.toString();task.cancelled=true;fail(ex);completeMaintenance(task);return;}
            var dimensions=new HashMap<ResourceKey<Level>,ImportDimension>();
            for(var level:server.getAllLevels())dimensions.put(level.dimension(),new ImportDimension(new RegionNbtImporter(level.registryAccess()),level.getMinSection(),level.getSectionsCount()));
            task.dimensions=Map.copyOf(dimensions);task.phase="导入";task.scanning=true;task.importingAt=System.nanoTime();
            startImportScanner(task);
        }
        if(task.importWorkers!=null){
            if(!task.importWorkers.isTerminated())return;
            task.importWorkers=null;
        }
        if(task.cancelled || !task.scanning&&task.queue.isEmpty()){
            if(task.scanning)return;
            if(task.drainedAt==0)task.drainedAt=System.nanoTime();
            if(!importStorageFailed){flushDirty();if(dirtyWrites.get()!=0||!dirty.isEmpty())return;}
            task.phase="收尾";
            if(database==null){completeMaintenance(task);return;}
            workers.execute(()->{
                try{database.sync();task.syncedAt=System.nanoTime();complete(()->completeMaintenance(task));}
                catch(RuntimeException ex){complete(()->{importStorageFailed=true;task.failed();task.error+=(task.error.isEmpty()?"":"; ")+ex;fail(ex);completeMaintenance(task);});}
            });
            return;
        }
        task.importBudget=DistantConfig.importMemory();
        long largest=task.dimensions.values().stream().mapToLong(d->2L*d.sections*ChunkSnapshot.RESERVED_BYTES).max().orElse(1);
        task.importThreads=(int)Math.min(DistantConfig.importThreads(),task.importBudget/largest);
        if(task.importThreads==0){task.failed();task.error="导入内存预算不足以容纳一个完整区块";task.cancelled=true;return;}
        task.importWorkers=Executors.newFixedThreadPool(task.importThreads,r->{var t=new Thread(r,"Voxy Distant import");t.setDaemon(true);return t;});
        for(int i=0;i<task.importThreads;i++)task.importWorkers.execute(()->importLoop(task));
        task.importWorkers.shutdown();
    }
    private void importLoop(MaintenanceTask task) {
        RegionFile region=null;Path opened=null;
        try{
            while(true){
                MaintenanceEntry entry;ImportDimension dimension;long bytes;
                synchronized(task){
                    while(true){
                        if(task.cancelled||maintenancePaused)return;
                        entry=task.queue.peek();
                        if(entry==null){if(!task.scanning)return;task.wait(10);continue;}
                        dimension=task.dimensions.get(entry.key.dimension);
                        bytes=2L*dimension.sections*ChunkSnapshot.RESERVED_BYTES;
                        if(task.importMemory+bytes>task.importBudget){task.wait(10);continue;}
                        task.queue.remove();task.importMemory+=bytes;task.active++;break;
                    }
                }
                try{
                    List<ChunkSnapshot> snapshots;
                    long start=System.nanoTime();
                    try{
                        if(!entry.regionFile.equals(opened)){
                            if(region!=null){region.close();region=null;}
                            opened=null;
                            if(!Files.isRegularFile(entry.regionFile)){task.skipped();continue;}
                            region=new RegionFile(entry.regionFile,entry.regionFile.getParent(),false);opened=entry.regionFile;
                        }
                        try(var input=region.getChunkDataInputStream(entry.position)){
                            snapshots=input==null?List.of():dimension.parser.read(dimension.minSection,dimension.sections,entry.key.x,entry.key.z,
                                    RegionNbtImporter.readNbt(input,bytes/2)).orElse(List.of());
                        }
                    }catch(IOException|RuntimeException ex){task.unreadable(entry,ex);continue;}
                    finally{task.readNanos.add(System.nanoTime()-start);}
                    if(snapshots.isEmpty()){task.skipped();continue;}
                    start=System.nanoTime();
                    var column=ColumnConverter.convert(entry.key.x,entry.key.z,task.version,snapshots);
                    task.convertNanos.add(System.nanoTime()-start);start=System.nanoTime();
                    byte[] encoded=LodDatabase.pack(ColumnCodec.encode(column,31));
                    task.encodeNanos.add(System.nanoTime()-start);start=System.nanoTime();
                    try{database.importColumn(entry.key.key(1),encoded);}
                    catch(RuntimeException ex){importStorageFailed=true;throw ex;}
                    versions.computeIfPresent(entry.key,(key,revision)->Math.max(revision,task.version));
                    task.writeNanos.add(System.nanoTime()-start);task.completed();
                }finally{synchronized(task){task.importMemory-=bytes;task.active--;task.notifyAll();}}
            }
        }catch(InterruptedException ex){Thread.currentThread().interrupt();if(!task.cancelled){task.failed();task.error=ex.toString();task.cancelled=true;}}
        catch(RuntimeException ex){task.failed();task.error=ex.toString();task.cancelled=true;LOG.error("Exclusive LOD import failed",ex);}
        finally{
            if(region!=null)try{region.close();}catch(IOException ex){task.failed();task.error=ex.toString();task.cancelled=true;LOG.error("Closing import region failed",ex);}
        }
    }
    private void startImportScanner(MaintenanceTask task) {
        var regions=new LinkedHashMap<ResourceKey<Level>,Path>();
        for(var level:server.getAllLevels())regions.put(level.dimension(),regionPath(server,level));
        var scanner = new Thread(() -> scanImport(task, regions), "Voxy Distant import scanner");
        scanner.setDaemon(true);
        scanner.setPriority(Thread.MIN_PRIORITY);
        maintenanceScanner = scanner;
        scanner.start();
    }
    private void scanImport(MaintenanceTask task, Map<ResourceKey<Level>,Path> regions) {
        try {
            for (var dimension : regions.entrySet()) {
                if (task.cancelled) return;
                Path region = dimension.getValue();
                if (!java.nio.file.Files.isDirectory(region)) continue;
                List<Path> files;
                try (var stream = java.nio.file.Files.list(region)) {
                    files = stream.filter(path -> MaintenanceSupport.parseRegionFileName(path.getFileName().toString()).isPresent())
                            .sorted().toList();
                } catch (IOException ex) {
                    task.scanFailed();LOG.warn("Cannot scan import directory {}",region,ex);
                    continue;
                }
                for (Path file : files) {
                        if (task.cancelled) return;
                        var parsed = MaintenanceSupport.parseRegionFileName(file.getFileName().toString());
                        if (parsed.isEmpty()) continue;
                        try (var regionFile = new RegionFile(file, region, false)) {
                            for (int localZ = 0; localZ < 32; localZ++) for (int localX = 0; localX < 32; localX++) {
                                while (maintenancePaused && !task.cancelled) Thread.sleep(25);
                                if (task.cancelled) return;
                                task.scannedSlot();
                                var pos = new ChunkPos(parsed.get().x() * 32 + localX, parsed.get().z() * 32 + localZ);
                                if (!regionFile.doesChunkExist(pos)) { task.skipped(); continue; }
                                var entry=new MaintenanceEntry(new Key(dimension.getKey(), pos.x, pos.z), file, pos);
                                while(!task.cancelled){
                                    if(!maintenancePaused && task.queue.offer(entry)){task.queuedCandidate();break;}
                                    Thread.sleep(25);
                                }
                            }
                        } catch (IOException ex) { task.scanFailed();LOG.warn("Cannot scan import region {}",file,ex); }
                    }
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt();if(!task.cancelled){task.scanFailed();LOG.error("LOD import scan interrupted",ex);} }
        catch (RuntimeException ex) {task.scanFailed();LOG.error("LOD import scan failed",ex);}
        finally {
            task.scanning = false;
            if (Thread.currentThread() == maintenanceScanner) maintenanceScanner = null;
        }
    }
    private static void commands(RegisterCommandsEvent e){e.getDispatcher().register(Commands.literal("voxydistant").requires(s -> s.hasPermission(2))
            .then(Commands.literal("stats").executes(c -> {c.getSource().sendSuccess(() -> Component.literal(details()),false);return 1;}))
            .then(Commands.literal("import").executes(c -> { if(instance==null){c.getSource().sendFailure(Component.literal("服务端未启动"));return 0;} instance.startImport(c.getSource());return 1;}))
            .then(Commands.literal("pregen").then(Commands.argument("radius", IntegerArgumentType.integer(1,2048)).then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer()).executes(c -> { if(instance==null){c.getSource().sendFailure(Component.literal("服务端未启动"));return 0;} instance.startPregen(c.getSource(), IntegerArgumentType.getInteger(c,"radius"), IntegerArgumentType.getInteger(c,"x"), IntegerArgumentType.getInteger(c,"z"));return 1;})))))
            .then(Commands.literal("reload").executes(c -> {
                try{instance.applyConfiguration(DistantConfig.readServerFile(),()->true,error->{
                    if(error.isEmpty()){
                        try{DistantConfig.reloadDebug();c.getSource().sendSuccess(()->Component.literal("配置已重新读取并应用"),false);}
                        catch(RuntimeException ex){LOG.error("Reloading diagnostic settings failed",ex);c.getSource().sendFailure(Component.literal("服务端配置已应用；诊断配置读取失败："+ex.getMessage()));}
                    }
                    else c.getSource().sendFailure(Component.literal(error));
                });}catch(RuntimeException ex){LOG.error("Reloading server settings failed",ex);c.getSource().sendFailure(Component.literal("重读失败："+ex.getMessage()));return 0;}
                return 1;
            })));}
    private static void stop(ServerStoppingEvent e){
        var s=instance;if(s==null)return;s.stopping=true;if(s.maintenance!=null)synchronized(s.maintenance){s.maintenance.cancelled=true;s.maintenance.queue.clear();s.maintenance.pending.clear();s.maintenance.notifyAll();}
        // Cooperative cancellation: interrupting RegionFile I/O closes its channel before close() can flush it.
        var scanner=s.maintenanceScanner;
        var importer=s.maintenance==null?null:s.maintenance.importWorkers;
        if(importer!=null)try{while(!importer.awaitTermination(1,TimeUnit.SECONDS))LOG.debug("Waiting for import columns");}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
        for(Work w:s.work.values())s.release(w);
        var finalDirty=new HashMap<byte[],byte[]>();for(var entry:s.dirty.entrySet()){
            finalDirty.put(entry.getKey().key(2),ByteBuffer.allocate(8).putLong(entry.getValue()).array());
            finalDirty.put(entry.getKey().key(3),LodDatabase.pendingValue(entry.getValue(),0));
        }s.dirty.clear();
        var finalChecks=s.missingChecks.stream().map(k->k.key(4)).toList();s.missingChecks.clear();
        s.workers.shutdown();
        try{while(!s.workers.awaitTermination(1,TimeUnit.SECONDS))LOG.debug("Waiting for server LOD writes");}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
        if(scanner!=null)try{scanner.join();}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
        if(s.maintenance!=null){s.maintenance.queue.clear();s.maintenance.pending.clear();s.maintenance.active=0;}
        for(var w:s.work.values())w.snapshots.clear();s.work.clear();s.memory=0;
        try{if(s.database!=null)try{if(!finalDirty.isEmpty()||!finalChecks.isEmpty())s.database.invalidationsAndMissing(finalDirty,finalChecks);}finally{s.database.close();}}
        finally{PriorityState.clear();instance=null;}
    }
}
