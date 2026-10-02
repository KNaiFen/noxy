package dev.voxydistant.client;

import com.mojang.logging.LogUtils;
import dev.voxydistant.DebugLog;
import static dev.voxydistant.DebugLog.Metric.*;
import dev.voxydistant.compat.*;
import dev.voxydistant.config.*;
import dev.voxydistant.data.*;
import dev.voxydistant.generation.*;
import dev.voxydistant.network.Protocol;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.file.Files;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Single ordered apply lane, bounded by reserved decoded memory. Main thread owns requests only. */
public final class RemoteClient {
    private static final org.slf4j.Logger LOG=LogUtils.getLogger();
    private static volatile Session session;
    private static Protocol.Hello greeting;
    private static int sequence;
    private static final List<Session> retired=new ArrayList<>();
    private static volatile String failure="";
    private static volatile boolean maintenancePaused;
    private static Connection cacheConnection;
    private static net.minecraft.client.multiplayer.ClientLevel lightLevel;
    private static final Set<Long> lightReady=new HashSet<>();
    private static long cacheBytes=-1,cacheColumns=-1,lastCacheRequest;
    private static volatile String localCacheStatus="本机缓存：查询中…";
    private static long lastLocalCacheRequest;
    private static String localCacheWorld;
    private static CompletableFuture<?> localCacheTask;
    public static void maintenance(Protocol.Maintenance message) {
        if(maintenancePaused==message.paused())return;
        maintenancePaused=message.paused();
        Session s=session;if(s==null)return;
        s.epoch=++sequence;s.generation++;s.pending.clear();s.checked.clear();s.invalid.clear();s.retries.clear();s.fullRetry.clear();s.deferredDirty.clear();s.deferredReplies.clear();s.deferredSnapshots.clear();
        synchronized(s.assemblies){
            for(var a:s.assemblies.values())s.memory.addAndGet(-a.reservation);s.assemblies.clear();
            for(var a:s.batchAssemblies.values())s.memory.addAndGet(-a.reservation);s.batchAssemblies.clear();
            s.discardedBatches.clear();
        }
        s.x=Integer.MIN_VALUE;s.discovery.reset();s.regionQueries.clear();
        if(!maintenancePaused)s.fullRetry.addAll(s.versions.keySet());
    }
    private static final Map<Long,Long> initialVersions=new HashMap<>();
    private static final class Assembly {
        final Protocol.Fragment header;final byte[] bytes;final long reservation;final DebugLog.ReceiveTrace trace;int offset;
        Assembly(Protocol.Fragment f,long reservation){header=f;bytes=new byte[f.totalLength()];this.reservation=reservation;trace=new DebugLog.ReceiveTrace(f.epoch(),f.transfer());}
    }
    private static final class BatchAssembly {
        final Protocol.BatchFragment header;final byte[] bytes;final long reservation;final DebugLog.ReceiveTrace trace;int offset;
        BatchAssembly(Protocol.BatchFragment f,long reservation){header=f;bytes=new byte[f.totalLength()];this.reservation=reservation;trace=new DebugLog.ReceiveTrace(f.epoch(),f.transfer());}
    }
    private static final class DiscardedBatch {
        final int totalLength;int offset;
        DiscardedBatch(int totalLength,int offset){this.totalLength=totalLength;this.offset=offset;}
    }
    private static final class Pending {
        private final long started,id;private final int desired,generation;
        private CoverageStore.Stamp before=new CoverageStore.Stamp(0,5);
        private long dirtyBefore;
        private volatile boolean sent,receiving,applying;
        private volatile long lastProgress,transfer;
        Pending(long started,int desired,int generation,long id){this.started=started;this.lastProgress=started;this.desired=desired;this.generation=generation;this.id=id;}
        long started(){return started;}int desired(){return desired;}int generation(){return generation;}long id(){return id;}
    }
    private static final class Session {
        final WorldEngine engine;final net.minecraft.client.multiplayer.ClientLevel level;final Protocol.Hello hello;
        final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(512),r->{var t=new Thread(r,"Voxy Distant receive");t.setDaemon(true);return t;},(task,executor)->{
            if(executor.isShutdown())throw new RejectedExecutionException("LOD receive worker is shut down");
            // Preserve the single apply lane and its ordering when a burst fills the bounded queue.
            try{while(!executor.getQueue().offer(task,100,TimeUnit.MILLISECONDS)){
                if(executor.isShutdown())throw new RejectedExecutionException("LOD receive worker shut down while waiting for capacity");
            }}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RejectedExecutionException("Interrupted while waiting for LOD receive capacity",e);}
        }){
            @Override protected void terminated(){if(releaseOnTermination)engine.releaseRef();}
        };
        volatile boolean releaseOnTermination;
        final AtomicLong memory=new AtomicLong();final ConcurrentHashMap<Long,Long> versions=new ConcurrentHashMap<>();
        // Vanilla snapshots must not consume credit already advertised to the server.
        final long receiveLimit=DistantConfig.RECEIVE_MIB.get()*1048576L;
        final long snapshotLimit=Math.min(32L<<20,receiveLimit/4);
        final int networkCapacity=(int)(receiveLimit-snapshotLimit);
        final AtomicLong snapshotMemory=new AtomicLong();
        final Map<Long,Assembly> assemblies=new HashMap<>();
        final Map<Long,BatchAssembly> batchAssemblies=new HashMap<>(); // guarded by assemblies
        final Map<Long,DiscardedBatch> discardedBatches=new HashMap<>(); // guarded by assemblies
        final Map<Long,Pending> pending=new ConcurrentHashMap<>();
        final Set<Long> loadedFull=ConcurrentHashMap.newKeySet();
        final Map<Long,Integer> checked=new HashMap<>();
        final Set<Long> invalid=new LinkedHashSet<>();
        final Map<Long,Integer> budgetInsufficient=new HashMap<>();
        final RetryQueue retries=new RetryQueue();
        final Set<Long> fullRetry=new LinkedHashSet<>();
        final Map<Long,Long> deferredDirty=new LinkedHashMap<>();
        final ArrayDeque<Protocol.Reply> deferredReplies=new ArrayDeque<>();
        final Set<Long> deferredSnapshots=ConcurrentHashMap.newKeySet();
        final ConcurrentHashMap<Long,ChunkSnapshot> latestSnapshots=new ConcurrentHashMap<>();
        boolean checkpointPending;
        boolean activePending;int activeX,activeZ,activeRadius;
        final DistanceBands bands;volatile int epoch=++sequence;volatile boolean closed;
        final RegionDiscovery discovery;
        dev.voxydistant.movement.RequestShape shape;
        long movementRevision=-1;
        final Map<Long,RegionRequest> regionQueries=new LinkedHashMap<>();
        boolean preparingDirectory;int directoryTick=-1;
        int x=Integer.MIN_VALUE,z,radius,requestedRadius,view,ticks,generation,requestBudget,preempted,indexMiB=DistantConfig.INDEX_MIB.get(),lastPressureTick=-200;volatile long applied,received;long requestSequence,regionSequence;
        boolean refillQueued;
        long debugAt,requestIdleAt;String requestState="initializing";
        long rateAt,rateBytes,rateColumns;double receiveKiBPerSecond,receiveColumnsPerSecond;
        long debugReceived,debugApplied,debugTicks;
        final Map<String,Integer> requestStates=new LinkedHashMap<>();
        Session(WorldEngine engine,net.minecraft.client.multiplayer.ClientLevel level,Protocol.Hello hello){this.engine=engine;this.level=level;this.hello=hello;bands=DistanceBands.parse(hello.bands());discovery=new RegionDiscovery(bands);VoxyBridge.coverage(engine).remote(hello.minY(),hello.maxY(),DistantConfig.INDEX_MIB.get());}
        // Start the single ordered lane before offering, without waiting for queue space.
        boolean offer(Runnable task){worker.prestartCoreThread();return !worker.isShutdown()&&worker.getQueue().offer(task);}
    }
    public static UUID worldId(){return greeting==null?null:greeting.world();}
    public static Protocol.Hello greeting(){return greeting;}
    public static void hello(Protocol.Hello hello) {
        ColumnCodec.bounded(hello.radius(),1,2048);ColumnCodec.bounded(hello.minY(),-256,255);ColumnCodec.bounded(hello.maxY(),hello.minY()+1,256);
        if(greeting!=null&&greeting.equals(hello)&&session!=null)return;
        boolean changed=greeting==null||!greeting.world().equals(hello.world());
        if(greeting!=null&&(changed||!greeting.dimension().equals(hello.dimension())))initialVersions.clear();
        close();greeting=hello;
        if(DebugLog.enabled())DebugLog.log("CLIENT hello world={} dimension={} radius={} bands={}",hello.world(),hello.dimension(),hello.radius(),hello.bands());
        if(changed&&Minecraft.getInstance().level!=null)VoxyBridge.resetRenderer();
    }
    public static void tick() {
        retired.removeIf(s->s.worker.isTerminated());
        var mc=Minecraft.getInstance();
        var connection=mc.getConnection()==null?null:mc.getConnection().getConnection();
        if(connection!=cacheConnection){cacheConnection=connection;cacheBytes=-1;cacheColumns=-1;lastCacheRequest=0;}
        if(lightLevel!=mc.level){lightLevel=mc.level;lightReady.clear();}
        if(mc.level==null||mc.player==null){close();greeting=null;initialVersions.clear();maintenancePaused=false;return;}
        if(session!=null&&session.level!=mc.level)close();
        if(greeting==null)return;
        if(maintenancePaused&&mc.player.tickCount%20==0)mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal("服务端正在导入远景"),false);
        if(!mc.level.dimension().location().toString().equals(greeting.dimension()))return;
        if(!DistantConfig.RECEIVE.get()||!VoxyBridge.enabled()){if(session!=null){session.radius=0;send(session,List.of());close();}return;}
        if(session==null){
            try{DistantConfig.validateReceiveMemory(mc.level.getSectionsCount(),DistantConfig.RECEIVE_MIB.get());}
            catch(IllegalArgumentException ex){failure=ex.getMessage();if(mc.player.tickCount%100==0)mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(failure),false);return;}
            WorldEngine engine=VoxyBridge.acquire(mc.level);if(engine==null)return;session=new Session(engine,mc.level,greeting);session.versions.putAll(initialVersions);session.fullRetry.addAll(initialVersions.keySet());session.fullRetry.addAll(lightReady);initialVersions.clear();
        }
        Session s=session;s.ticks++;
        if(!s.budgetInsufficient.isEmpty()&&s.ticks%100==0)mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal("远景列超出接收信用；请增大接收缓冲后重连，或提高服务端单人发送内存"),false);
        drainMainWork(s);
        long rateNow=System.nanoTime();
        if(s.rateAt==0){s.rateAt=rateNow;s.rateBytes=s.received;s.rateColumns=s.applied;}
        else if(rateNow-s.rateAt>=TimeUnit.SECONDS.toNanos(1)){
            double seconds=(rateNow-s.rateAt)/1e9;
            s.receiveKiBPerSecond=(s.received-s.rateBytes)/1024.0/seconds;
            s.receiveColumnsPerSecond=(s.applied-s.rateColumns)/seconds;
            s.rateAt=rateNow;s.rateBytes=s.received;s.rateColumns=s.applied;
        }
        if(DebugLog.enabled())s.requestStates.merge(s.requestState,1,Integer::sum);
        if(DebugLog.enabled()&&System.nanoTime()-s.debugAt>=TimeUnit.SECONDS.toNanos(DistantConfig.DEBUG_INTERVAL.get())){
            long now=System.nanoTime();
            if(s.debugAt!=0)DebugLog.log("CLIENT interval world={} dimension={} epoch={} interval_ms={} payload_bytes={} payload_mbps={} applied_columns={} client_ticks={} request_state_ticks={}",s.hello.world(),s.hello.dimension(),s.epoch,DebugLog.millis(now-s.debugAt),s.received-s.debugReceived,DebugLog.mbps(s.received-s.debugReceived,now-s.debugAt),s.applied-s.debugApplied,s.ticks-s.debugTicks,s.requestStates);
            s.debugAt=now;s.debugReceived=s.received;s.debugApplied=s.applied;s.debugTicks=s.ticks;s.requestStates.clear();
            DebugLog.log("CLIENT state epoch={} center={},{} radius={} bands={} applied={} received_payload_bytes={} reserved_bytes={} receive_limit={} snapshot_bytes={} snapshot_limit={} advertised_credit={} pending={}/{} invalid={} full_retry={} worker_queue={} worker_active={} main_tasks={} discovering={} request_state={}",s.epoch,s.x,s.z,s.radius,s.hello.bands(),s.applied,s.received,s.memory.get(),s.receiveLimit,s.snapshotMemory.get(),s.snapshotLimit,s.networkCapacity,s.pending.size(),DistantConfig.REQUEST_WINDOW.get(),s.invalid.size(),s.fullRetry.size(),s.worker.getQueue().size(),s.worker.getActiveCount(),mc.getPendingTasksCount(),s.discovery.busy(),s.requestState);
            var directories=coverageUsage(s);DebugLog.log("CLIENT directory prepared={} boundary_checks={} column_checks={} reads={} migrations={} queries={}",s.discovery.prepared,s.discovery.boundaryChecks,s.discovery.checks,directories.reads(),directories.migrations(),s.regionQueries.size());
            DebugLog.timings("CLIENT");
            long oldest=0;for(var requested:s.pending.values())oldest=Math.max(oldest,now-requested.started());
            synchronized(s.assemblies){DebugLog.log("CLIENT pipeline world={} dimension={} epoch={} partial_columns={} partial_batches={} oldest_request_ms={} ingest_jobs={} download_kib={} duty={} retry_cooldown={}",s.hello.world(),s.hello.dimension(),s.epoch,s.assemblies.size(),s.batchAssemblies.size(),DebugLog.millis(oldest),s.engine.instanceIn.getIngestService().getTaskCount(),DistantConfig.DOWNLOAD_KBPS.get(),DistantConfig.RECEIVE_DUTY.get(),s.retries.size());}
            for(var retry:s.retries.oldest(4))DebugLog.log("CLIENT retry_wait epoch={} x={} z={} desired={} attempts={} urgent={} remaining_ms={}",s.epoch,ChunkPos.getX(retry.position()),ChunkPos.getZ(retry.position()),retry.desired(),retry.attempts(),retry.urgent(),DebugLog.millis(Math.max(0,retry.due()-now)));
            DebugLog.connection("CLIENT",mc.getConnection().getConnection(),mc.player.getUUID());
        }
        if(s.ticks%20==0){s.checkpointPending=true;drainMainWork(s);}
        if(maintenancePaused)return;
        if(!s.deferredSnapshots.isEmpty()&&s.worker.getQueue().size()<256){for(long p:s.deferredSnapshots)if(s.deferredSnapshots.remove(p))s.fullRetry.add(p);}
        if(s.deferredDirty.isEmpty()&&!s.fullRetry.isEmpty()){
            var it=s.fullRetry.iterator();
            for(int budget=8;it.hasNext()&&budget>0&&s.worker.getQueue().size()<256;){
                long p=it.next();var chunk=mc.level.getChunkSource().getChunk(ChunkPos.getX(p),ChunkPos.getZ(p),ChunkStatus.FULL,false);
                if(chunk!=null&&!lightReady.contains(p))continue;
                if(chunk!=null&&s.snapshotMemory.get()+chunk.getSectionsCount()*ChunkSnapshot.RESERVED_BYTES>s.snapshotLimit)break;
                it.remove();if(chunk!=null)full(s.engine,chunk);else s.invalid.add(p);
                budget--;
            }
        }
        if(!s.deferredDirty.isEmpty())return;
        int x=mc.player.chunkPosition().x,z=mc.player.chunkPosition().z,view=mc.options.renderDistance().get();
        int receiveRadius=DistantConfig.RECEIVE_RADIUS.get();
        int requested=Math.min(s.hello.radius(),receiveRadius>0?receiveRadius:VoxyBridge.radiusChunks());
        int indexMiB=DistantConfig.INDEX_MIB.get();
        boolean indexChanged=s.indexMiB!=indexMiB;
        if(indexChanged){VoxyBridge.coverage(s.engine).remote(s.hello.minY(),s.hello.maxY(),indexMiB);s.indexMiB=indexMiB;}
        var coverage=VoxyBridge.coverage(s.engine);
        var motion=dev.voxydistant.movement.MovementPrediction.CLIENT.snapshot();
        boolean pressure=coverage.pressure();
        if(pressure)s.lastPressureTick=s.ticks;
        int radius=pressure?coverage.stepDown(requested,x,z)
                :s.x!=x||s.z!=z||s.requestedRadius!=requested||indexChanged||s.radius<requested&&s.ticks-s.lastPressureTick>=200&&s.ticks%20==0
                ?coverage.limitRadius(requested,x,z):s.radius;
        s.requestedRadius=requested;
        if(radius<requested&&s.ticks%100==0)mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal("索引预算不足：实际远景 "+radius+" / "+requested+" 区块；详情见 VD 设置"),false);
        if(s.movementRevision!=motion.revision()||radius!=s.radius||indexChanged)radius=Math.min(radius,coverage.limitShape(motion.shape(radius,s.hello.radius()),Math.min(radius,VoxyBridge.radiusChunks())));
        boolean teleportMove=s.x!=Integer.MIN_VALUE&&(Math.abs((long)x-s.x)>32||Math.abs((long)z-s.z)>32);
        if(s.shape==null||teleportMove||s.radius!=radius||s.view!=view||s.movementRevision!=motion.revision()) {
            if(DebugLog.enabled())DebugLog.log("CLIENT move epoch={} from_x={} from_z={} x={} z={} generation={} radius={} view={}",s.epoch,s.x,s.z,x,z,s.generation+1,radius,view);
            boolean teleport=s.x!=Integer.MIN_VALUE&&(Math.abs((long)x-s.x)>32||Math.abs((long)z-s.z)>32);
            s.x=x;s.z=z;s.radius=radius;s.view=view;s.generation++;s.preempted=0;
            if(teleport){s.epoch=++sequence;s.pending.clear();s.checked.clear();s.invalid.clear();s.retries.clear();synchronized(s.assemblies){for(var a:s.assemblies.values())s.memory.addAndGet(-a.reservation);s.assemblies.clear();for(var a:s.batchAssemblies.values())s.memory.addAndGet(-a.reservation);s.batchAssemblies.clear();s.discardedBatches.clear();}}
            if(teleport){s.discovery.reset();s.regionQueries.clear();}
            s.movementRevision=motion.revision();s.shape=motion.shape(radius,s.hello.radius()).precision(requested==0?1:(double)radius/requested);s.discovery.move(s.shape);
            var regionCancels=new ArrayList<Long>();for(var it=s.regionQueries.entrySet().iterator();it.hasNext();){var entry=it.next();var r=entry.getValue().region();if(s.discovery.regions.get(RegionDiscovery.key(r.x,r.z))!=r){regionCancels.add(entry.getKey());it.remove();}}
            if(!regionCancels.isEmpty())Protocol.CHANNEL.sendToServer(new Protocol.RegionCancel(s.epoch,List.copyOf(regionCancels)));
            if(DebugLog.verbose()&&!regionCancels.isEmpty())DebugLog.log("CLIENT region_cancel count={}",regionCancels.size());
            var cancels=new ArrayList<Protocol.Cancel>();for(var entry:s.pending.entrySet()){var request=entry.getValue();long p=entry.getKey();if(!inRange(s,ChunkPos.getX(p),ChunkPos.getZ(p)))synchronized(request){if(!request.receiving&&!request.applying&&s.pending.remove(p,request))cancels.add(new Protocol.Cancel(ChunkPos.getX(p),ChunkPos.getZ(p),request.id()));}}
            for(int i=0;i<cancels.size();i+=16)send(s,List.of(),cancels.subList(i,Math.min(i+16,cancels.size())));
            s.checked.keySet().removeIf(p->!inRange(s,ChunkPos.getX(p),ChunkPos.getZ(p)));
            s.versions.keySet().removeIf(p->!inRange(s,ChunkPos.getX(p),ChunkPos.getZ(p))&&!s.pending.containsKey(p));
            s.invalid.removeIf(p->!inRange(s,ChunkPos.getX(p),ChunkPos.getZ(p)));
            s.retries.moved(p->inRange(s,ChunkPos.getX(p),ChunkPos.getZ(p)),p->desired(s,p),p->urgent(s,p),System.nanoTime());
            Minecraft.getInstance().execute(()->send(s,List.of()));
            s.activeX=x;s.activeZ=z;s.activeRadius=radius;s.activePending=true;
            drainMainWork(s);
        }
        var expired=expireRequests(s,System.nanoTime());
        for(int i=0;i<expired.size();i+=16)send(s,List.of(),expired.subList(i,Math.min(i+16,expired.size())));
        s.requestBudget=DistantConfig.REQUEST_COLUMNS_PER_TICK.get();
        discoverRegions(s);
        requestMore(s);
    }
    public static void requestCacheStats() {
        if(cacheConnection==null||!Protocol.CHANNEL.isRemotePresent(cacheConnection))return;
        long now=System.nanoTime();
        if(lastCacheRequest!=0&&now-lastCacheRequest<TimeUnit.SECONDS.toNanos(5))return;
        lastCacheRequest=now;
        Protocol.CHANNEL.sendToServer(new Protocol.CacheStatsRequest());
    }
    public static void cacheStats(Connection source,Protocol.CacheStats stats) {
        if(source!=cacheConnection)return;
        cacheBytes=stats.bytes();cacheColumns=stats.columns();
    }
    public static String cacheStatus() {
        if(cacheConnection==null)return "服务器缓存：未连接";
        if(!Protocol.CHANNEL.isRemotePresent(cacheConnection))return "服务器缓存：未安装兼容扩展";
        return cacheColumns<0?(cacheBytes<0?"服务器缓存：查询中…":String.format(Locale.ROOT,"服务器缓存 %.1f MiB · 列数统计中…",cacheBytes/1048576.0)):String.format(Locale.ROOT,"服务器缓存 %.1f MiB · %,d 列",cacheBytes/1048576.0,cacheColumns);
    }
    public static String receiveSpeed() {
        Session s=session;
        return s==null?"接收速度：无会话":String.format(Locale.ROOT,"接收速度 %.1f KiB/s · %.1f 列/秒 · 目录 %d 区域 · 核验中 %d · 待确认 %d",s.receiveKiBPerSecond,s.receiveColumnsPerSecond,s.discovery.regions.size(),s.regionQueries.size(),s.pending.size());
    }
    public static String indexStatus(){
        Session s=session;
        if(s==null)return "覆盖索引：无远景会话";
        var usage=VoxyBridge.coverage(s.engine).usage();
        return String.format(Locale.ROOT,"覆盖索引 %d/%d 页 · %.0f%% · %.1f/%d MiB",
                usage.pages(),usage.limit(),100.0*usage.pages()/usage.limit(),usage.pages()*400000.0/1048576,DistantConfig.INDEX_MIB.get());
    }
    public static String radiusStatus(){
        Session s=session;
        if(s==null)return "远景范围：无远景会话";
        return "远景 接收 "+s.radius+" / 请求 "+s.requestedRadius+" · 显示 "+Math.min(s.radius,VoxyBridge.radiusChunks())+" 区块"
                +(s.radius<s.requestedRadius?" · 索引预算不足":"");
    }
    public static void requestLocalCacheStats(){
        Session s=session;
        if(s==null){localCacheWorld=null;localCacheStatus="本机缓存：无远景会话";return;}
        var base=((VoxyClientInstance)s.engine.instanceIn).getStorageBasePath().resolve(WorldIdentifier.of(s.level).getWorldId());
        String world=base.toString();
        if(!world.equals(localCacheWorld)){localCacheWorld=world;lastLocalCacheRequest=0;localCacheStatus="本机缓存：查询中…";}
        long now=System.nanoTime();
        if(localCacheTask!=null&&!localCacheTask.isDone()||lastLocalCacheRequest!=0&&now-lastLocalCacheRequest<TimeUnit.SECONDS.toNanos(60))return;
        lastLocalCacheRequest=now;
        s.engine.acquireRef();
        localCacheTask=CompletableFuture.runAsync(()->{
            try{
                long bytes;
                try(var files=Files.walk(base.resolve("storage"))){bytes=files.filter(Files::isRegularFile).mapToLong(path->{
                    try{return Files.size(path);}catch(IOException e){throw new UncheckedIOException(e);}
                }).sum();}
                var columns=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                s.engine.storage.iteratePositions(-1,key->columns.add(((long)WorldEngine.getLevel(key)<<48)
                        |((long)(WorldEngine.getX(key)&0xffffff)<<24)|(WorldEngine.getZ(key)&0xffffff)));
                if(session==s)localCacheStatus=String.format(Locale.ROOT,"本机 Voxy 缓存 %.1f MiB · %,d LOD 列（含各层）",bytes/1048576.0,columns.size());
            }catch(IOException|RuntimeException e){
                LOG.error("Voxy Distant local cache statistics failed",e);
                if(session==s)localCacheStatus="本机缓存：统计失败（见 latest.log）";
            }finally{s.engine.releaseRef();}
        });
    }
    public static String localCacheStatus(){return localCacheStatus;}
    private record RegionRequest(RegionDiscovery.Region region,long sent){}
    private static CoverageStore.DirectoryUsage coverageUsage(Session s){return VoxyBridge.coverage(s.engine).directoryUsage();}
    private static void discoverRegions(Session s){
        long now=System.nanoTime();
        for(var it=s.regionQueries.entrySet().iterator();it.hasNext();){var q=it.next();if(now-q.getValue().sent()>TimeUnit.SECONDS.toNanos(30)){var region=q.getValue().region();region.queried=false;region.query=true;it.remove();}}
        while(s.regionQueries.size()<DistantConfig.REGION_QUERY_WINDOW.get()){var region=s.discovery.nextQuery();if(region==null)break;long id=++s.regionSequence;s.regionQueries.put(id,new RegionRequest(region,now));Protocol.CHANNEL.sendToServer(new Protocol.RegionQuery(s.epoch,id,region.x,region.z));}
        if(s.preparingDirectory||s.directoryTick==s.ticks||s.worker.getQueue().size()>=256)return;
        var tasks=new ArrayList<RegionDiscovery.Region>();
        for(var r:s.discovery.regions.values())if(r.maskPending||r.prepare&&!r.migrate)tasks.add(r);
        if(tasks.isEmpty())for(var r:s.discovery.regions.values())if(r.prepare)tasks.add(r);
        if(tasks.isEmpty())return;
        tasks.sort(Comparator.comparingLong((RegionDiscovery.Region r)->r.preparedAt).thenComparingDouble(r->s.shape.minimumScore(r.x*32,r.z*32,r.x*32+31,r.z*32+31)));
        if(tasks.size()>8)tasks.subList(8,tasks.size()).clear();
        s.preparingDirectory=true;s.directoryTick=s.ticks;int epoch=s.epoch;var footprint=s.shape;
        if(!s.offer(()->{
            try{
                long started=DebugLog.start(),deadline=System.nanoTime()+2_000_000L;var results=new LinkedHashMap<RegionDiscovery.Region,CoverageStore.Directory>();var masks=new LinkedHashMap<RegionDiscovery.Region,RegionDiscovery.Masks>();
                for(var region:tasks){if(s.closed||s.epoch!=epoch)break;if(region.maskPending)masks.put(region,s.discovery.buildMasks(region,footprint));if(region.prepare)results.put(region,VoxyBridge.coverage(s.engine).directory(region.x,region.z,region.migrate));if(System.nanoTime()>=deadline)break;}
                DebugLog.end(CLIENT_INDEX,started);
                Minecraft.getInstance().execute(()->{s.preparingDirectory=false;if(session!=s||s.epoch!=epoch)return;masks.forEach((region,value)->s.discovery.applyMasks(region,footprint,value));results.forEach((region,directory)->{
                    if(directory!=null)for(int i=0;i<1024;i++){long p=ChunkPos.asLong(region.x*32+(i&31),region.z*32+(i>>5));if(s.versions.getOrDefault(p,0L)>directory.versions[i])directory.levels[i]=5;}
                    s.discovery.local(region,directory);
                });});
            }catch(RuntimeException e){failed(s,e);}
        }))s.preparingDirectory=false;
    }
    public static void regionSummary(Protocol.RegionSummary summary){
        Session s=session;if(s==null||s.closed||s.epoch!=summary.epoch())return;
        var query=s.regionQueries.remove(summary.id());if(query==null)return;var region=query.region();
        if(s.discovery.regions.get(RegionDiscovery.key(region.x,region.z))!=region)return;
        if(region.x!=summary.x()||region.z!=summary.z())throw new IllegalArgumentException("Mismatched region summary");
        for(int i=0;i<1024;i++){long p=ChunkPos.asLong(region.x*32+(i&31),region.z*32+(i>>5));if(!inRange(s,ChunkPos.getX(p),ChunkPos.getZ(p)))continue;if(s.versions.getOrDefault(p,0L)>summary.versions()[i])summary.masks()[i]=0;
            int target=desired(s,p);
            if((summary.masks()[i]&(1<<target))!=0&&region.local!=null&&region.local.versions[i]==summary.versions()[i]&&region.local.levels[i]<=target)s.checked.put(p,(int)region.local.levels[i]);else s.checked.remove(p);}
        s.discovery.summary(region,summary.versions(),summary.masks());refill(s);
    }
    private static void requestMore(Session s) {
        if(session!=s||s.closed||maintenancePaused||s.radius==0||VoxyBridge.coverage(s.engine).overBudget())return;
        var mc=Minecraft.getInstance();
        int window=DistantConfig.REQUEST_WINDOW.get();
        s.requestState=mc.isPaused()?"paused":!s.deferredDirty.isEmpty()||!s.deferredReplies.isEmpty()||s.worker.getQueue().remainingCapacity()==0?"worker_queue":s.pending.size()>=window&&(!s.discovery.busy()||s.preempted>=16)?"request_window":s.requestBudget==0?"request_rate":s.memory.get()>DistantConfig.RECEIVE_MIB.get()*1048576L*3/4?"receive_memory":VoxyBridge.backedUp(s.engine)?"voxy_backlog":"scanning";
        if(!s.requestState.equals("scanning")){switch(s.requestState){case "request_window"->DebugLog.count(CLIENT_REQUEST_WINDOW);case "receive_memory"->DebugLog.count(CLIENT_REQUEST_MEMORY);case "voxy_backlog"->DebugLog.count(CLIENT_REQUEST_BACKLOG);}return;}
        var positions=new LinkedHashSet<Long>();
        int batch=Math.min(64,Math.min(s.requestBudget,Math.max(0,window-s.pending.size())+(!s.discovery.busy()?0:Math.max(0,16-s.preempted))));
        if(batch==0){s.requestState="request_window";return;}
        var cancels=new ArrayList<Protocol.Cancel>();
        // Repairs leave room for newly discovered gaps.
        int retryLimit=s.pending.size()>=window?0:!s.discovery.busy()?batch:Math.min(batch/2,window-s.pending.size());
        int refillBudget=256;
        for(var it=s.invalid.iterator();it.hasNext()&&positions.size()<retryLimit&&refillBudget-->0;){long p=it.next();it.remove();Integer blocked=s.budgetInsufficient.get(p);if(blocked!=null&&desired(s,p)<=blocked)continue;s.budgetInsufficient.remove(p);if(inRange(s,ChunkPos.getX(p),ChunkPos.getZ(p))&&!s.pending.containsKey(p)&&!s.retries.scheduled(p))positions.add(p);}
        long retryNow=System.nanoTime();
        for(int i=0;i<batch&&positions.size()<retryLimit;i++){
            Long p=s.retries.poll(retryNow);if(p==null)break;
            if(!inRange(s,ChunkPos.getX(p),ChunkPos.getZ(p))){s.retries.remove(p);continue;}
            if(!s.pending.containsKey(p))positions.add(p);
        }
        int candidateBudget=256;
        while(positions.size()<batch&&candidateBudget-->0){
            Long p=s.discovery.poll();if(p==null)break;
            if(s.pending.containsKey(p)||s.retries.scheduled(p)||s.checked.getOrDefault(p,5)<=desired(s,p))continue;
            Integer blocked=s.budgetInsufficient.get(p);if(blocked!=null&&desired(s,p)<=blocked)continue;
            if(mc.level.getChunkSource().getChunk(ChunkPos.getX(p),ChunkPos.getZ(p),ChunkStatus.FULL,false)!=null){s.loadedFull.add(p);continue;}
            if(s.pending.size()+positions.size()>=window){var cancel=preempt(s,p,positions);if(cancel==null){s.invalid.add(p);break;}cancels.add(cancel);}
            positions.add(p);
        }
        positions.removeIf(p->{if(mc.level.getChunkSource().getChunk(ChunkPos.getX(p),ChunkPos.getZ(p),ChunkStatus.FULL,false)==null)return false;s.loadedFull.add(p);s.fullRetry.add(p);s.retries.remove(p);return true;});
        if(positions.isEmpty()){if(!cancels.isEmpty())send(s,List.of(),cancels);s.requestState=!s.discovery.busy()?"directory_complete":"directory_pending";return;}
        s.requestBudget-=positions.size();
        DebugLog.end(CLIENT_REQUEST_IDLE,s.requestIdleAt);s.requestIdleAt=0;
        s.requestState="index_lookup";
        DebugLog.count(CLIENT_REQUEST_BATCH);
        long now=System.nanoTime();var issued=new LinkedHashMap<Long,Long>();positions.forEach(p->{long id=++s.requestSequence;s.pending.put(p,new Pending(now,desired(s,p),s.generation,id));issued.put(p,id);});int epoch=s.epoch;
        if(!s.offer(()->{
            long timing=DebugLog.start();try {
                var wants=new ArrayList<Protocol.Want>();var index=VoxyBridge.coverage(s.engine);
                for(long p:positions){int cx=ChunkPos.getX(p),cz=ChunkPos.getZ(p);var stamp=index.directoryColumn(cx,cz);var pending=s.pending.get(p);if(pending!=null)wants.add(new Protocol.Want(cx,cz,stamp.version(),stamp.level(),issued.get(p),pending.desired()));}
                long ready=DebugLog.start();Minecraft.getInstance().execute(()->{DebugLog.end(CLIENT_REQUEST_CONTROL,ready);if(session==s&&s.epoch==epoch){
                    wants.removeIf(w->!currentRequest(s,ChunkPos.asLong(w.x(),w.z()),w.requestId())||!inRange(s,w.x(),w.z()));
                    for(var w:wants){long p=ChunkPos.asLong(w.x(),w.z());var request=s.pending.get(p);request.before=new CoverageStore.Stamp(w.version(),w.level());request.dirtyBefore=s.versions.getOrDefault(p,0L);request.lastProgress=System.nanoTime();request.sent=true;if(w.level()<5&&w.version()>=request.dirtyBefore)s.checked.put(p,w.level());}
                    if(DebugLog.verbose())for(var w:wants)DebugLog.log("CLIENT want epoch={} generation={} x={} z={} request_id={} version={} level={} desired={}",s.epoch,s.generation,w.x(),w.z(),w.requestId(),w.version(),w.level(),desired(s,ChunkPos.asLong(w.x(),w.z())));
                    send(s,wants,cancels);refill(s);
                }});
            }catch(RuntimeException e){failed(s,e);}finally{DebugLog.end(CLIENT_INDEX,timing);}
        })){
            for(long p:positions){s.pending.remove(p);s.invalid.add(p);}
            if(!cancels.isEmpty())send(s,List.of(),cancels);
            s.requestState="worker_queue";
        }
    }
    private static void refill(Session s){
        if(s.refillQueued)return;
        s.refillQueued=true;
        Minecraft.getInstance().tell(()->{s.refillQueued=false;requestMore(s);});
    }
    private static Protocol.Cancel preempt(Session s,long candidate,Set<Long> positions){
        if(s.preempted>=16)return null;
        int cx=ChunkPos.getX(candidate),cz=ChunkPos.getZ(candidate);
        long distance=(long)(cx-s.x)*(cx-s.x)+(long)(cz-s.z)*(cz-s.z),farthest=distance;Long victim=null;
        for(var entry:s.pending.entrySet()){
            long old=entry.getKey();if(entry.getValue().receiving||positions.contains(old))continue;
            long dx=(long)ChunkPos.getX(old)-s.x,dz=(long)ChunkPos.getZ(old)-s.z,d=dx*dx+dz*dz;
            if(d>farthest){farthest=d;victim=old;}
        }
        if(victim==null||Math.sqrt(farthest)-Math.sqrt(distance)<8)return null;
        var removed=s.pending.get(victim);
        synchronized(removed){if(removed.receiving)return null;s.pending.remove(victim,removed);}
        retry(s,victim,"preempted");s.preempted++;
        return new Protocol.Cancel(ChunkPos.getX(victim),ChunkPos.getZ(victim),removed.id());
    }
    private static boolean inRange(Session s,int x,int z){var player=Minecraft.getInstance().player.chunkPosition();long dx=(long)x-player.x,dz=(long)z-player.z;return s.shape!=null&&s.shape.contains(x,z)&&dx*dx+dz*dz<=(long)s.hello.radius()*s.hello.radius();}
    private static int desired(Session s,long p){var player=Minecraft.getInstance().player.chunkPosition();int x=ChunkPos.getX(p),z=ChunkPos.getZ(p),allowed=s.bands.select(x,z,player.x,player.z);return s.shape==null?allowed:Math.max(s.shape.desired(s.bands,x,z),allowed);}
    private static boolean urgent(Session s,long p){
        int target=desired(s,p),known=s.checked.getOrDefault(p,5);return target==s.bands.levels()[0]||known<5&&known>target;
    }
    private static void retry(Session s,long p,String reason){
        if(!inRange(s,ChunkPos.getX(p),ChunkPos.getZ(p))){s.retries.remove(p);return;}
        var retry=s.retries.failed(p,desired(s,p),urgent(s,p),System.nanoTime());
        if(DebugLog.verbose())DebugLog.log("CLIENT retry epoch={} x={} z={} reason={} desired={} attempts={} urgent={} delay_ms={} due_epoch_ms={}",s.epoch,ChunkPos.getX(p),ChunkPos.getZ(p),reason,retry.desired(),retry.attempts(),retry.urgent(),DebugLog.millis(retry.due()-System.nanoTime()),System.currentTimeMillis()+TimeUnit.NANOSECONDS.toMillis(Math.max(0,retry.due()-System.nanoTime())));
    }
    /** Called on the apply lane so the stamp reflects accepted voxels, not just packet metadata. */
    private static void completed(Session s,int epoch,long p,long requestId,long responseVersion,String source){
        var stamp=VoxyBridge.coverage(s.engine).column(ChunkPos.getX(p),ChunkPos.getZ(p),s.hello.minY(),s.hello.maxY());
        Minecraft.getInstance().execute(()->{
            if(session!=s||s.epoch!=epoch||!currentRequest(s,p,requestId))return;
            var requested=s.pending.remove(p);
            int target=desired(s,p);
            long dirtyVersion=s.versions.getOrDefault(p,0L),latest=Math.max(responseVersion,dirtyVersion);
            boolean current=stamp.version()>=latest;
            s.discovery.updated(p,current?stamp:new CoverageStore.Stamp(0,5));
            if(current&&stamp.level()<5)s.checked.put(p,stamp.level());else s.checked.remove(p);
            boolean refine=inRange(s,ChunkPos.getX(p),ChunkPos.getZ(p))&&(!current||stamp.level()>target);
            boolean progressed=stamp.version()!=requested.before.version()||stamp.level()<requested.before.level();
            boolean wake=progressed||dirtyVersion>requested.dirtyBefore||target<requested.desired();
            if(refine){DebugLog.count(CLIENT_REFINE);if(wake){s.retries.remove(p);s.invalid.add(p);}
                else{s.invalid.remove(p);s.retries.stalled(p,requested.desired(),urgent(s,p));DebugLog.count(CLIENT_NO_PROGRESS);}}
            else{s.invalid.remove(p);s.retries.remove(p);}
            if(DebugLog.verbose())DebugLog.log("CLIENT complete epoch={} generation={} request_generation={} request_desired={} x={} z={} request_id={} source={} version={} latest={} level={} desired={} refine={} reason={}",epoch,s.generation,requested.generation(),requested.desired(),ChunkPos.getX(p),ChunkPos.getZ(p),requestId,source,stamp.version(),latest,stamp.level(),target,refine,!current?"newer_version":stamp.level()>target?wake?"precision":"no_progress":"satisfied");
            refill(s);
        });
    }
    private static boolean currentRequest(Session s,long p,long requestId){var pending=s.pending.get(p);return pending!=null&&pending.id()==requestId;}
    private static List<Protocol.Cancel> expireRequests(Session s,long now){
        var cancels=new ArrayList<Protocol.Cancel>();
        synchronized(s.assemblies){
            var cancelledTransfers=new HashSet<Long>();
            for(var entry:s.pending.entrySet()){
                long p=entry.getKey();var request=entry.getValue();
                if(!request.sent||request.applying||now-request.lastProgress<=TimeUnit.SECONDS.toNanos(request.receiving?120:30))continue;
                synchronized(request){if(request.applying||now-request.lastProgress<=TimeUnit.SECONDS.toNanos(request.receiving?120:30)||!s.pending.remove(p,request))continue;}
                if(request.receiving&&(s.assemblies.containsKey(request.transfer)||s.batchAssemblies.containsKey(request.transfer)))cancelledTransfers.add(request.transfer);
                if(DebugLog.enabled())DebugLog.log("CLIENT request_timeout epoch={} x={} z={} request_id={} phase={}",s.epoch,ChunkPos.getX(p),ChunkPos.getZ(p),request.id(),request.receiving?"transfer_idle":"waiting_reply");
                retry(s,p,request.receiving?"transfer_idle":"waiting_reply");
                cancels.add(new Protocol.Cancel(ChunkPos.getX(p),ChunkPos.getZ(p),request.id()));
            }
            for(long transfer:cancelledTransfers){
                for(var other:s.pending.entrySet())if(other.getValue().receiving&&other.getValue().transfer==transfer&&!other.getValue().applying&&s.pending.remove(other.getKey(),other.getValue())){
                    retry(s,other.getKey(),"transfer_idle");
                    cancels.add(new Protocol.Cancel(ChunkPos.getX(other.getKey()),ChunkPos.getZ(other.getKey()),other.getValue().id()));
                }
                var single=s.assemblies.remove(transfer);if(single!=null)s.memory.addAndGet(-single.reservation);
                var batch=s.batchAssemblies.remove(transfer);if(batch!=null){s.memory.addAndGet(-batch.reservation);s.discardedBatches.put(transfer,new DiscardedBatch(batch.bytes.length,batch.offset));}
            }
        }
        return cancels;
    }
    private static void send(Session s,List<Protocol.Want> wants){send(s,wants,List.of());}
    private static void send(Session s,List<Protocol.Want> wants,List<Protocol.Cancel> cancels){if(session==s&&!s.closed&&!maintenancePaused){
        if(DebugLog.verbose())DebugLog.log("CLIENT request world={} dimension={} epoch={} wants={} cancels={} pending={} credit={} bandwidth_kib={}",s.hello.world(),s.hello.dimension(),s.epoch,wants.size(),cancels.size(),s.pending.size(),s.networkCapacity,DistantConfig.DOWNLOAD_KBPS.get());
        var player=Minecraft.getInstance().player.chunkPosition();
        int envelope=s.shape==null||s.radius==0?s.radius:Math.min(s.hello.radius(),(int)Math.ceil(Math.hypot(s.shape.centerX()-player.x,s.shape.centerZ()-player.z)+s.radius*(1+.5*s.shape.amount())));
        if(s.radius>0)for(var entry:s.pending.entrySet())if(entry.getValue().receiving){long p=entry.getKey();envelope=Math.min(s.hello.radius(),Math.max(envelope,(int)Math.ceil(Math.sqrt((double)(ChunkPos.getX(p)-player.x)*(ChunkPos.getX(p)-player.x)+(double)(ChunkPos.getZ(p)-player.z)*(ChunkPos.getZ(p)-player.z)))));}
        Protocol.CHANNEL.sendToServer(new Protocol.Requests(s.epoch,envelope,s.view,DistantConfig.DOWNLOAD_KBPS.get(),s.networkCapacity,wants,cancels,s.shape));
    }}
    private static long reserve(Session s,Protocol.Fragment f){return Protocol.reservation(f.totalLength(),f.rawLength(),f.level(),s.hello.maxY()-s.hello.minY());}
    public static void fragment(Protocol.Fragment f) {
        Session s=session;if(s==null||s.closed||f.epoch()!=s.epoch){if(DebugLog.verbose())DebugLog.log("CLIENT discard epoch={} transfer={} reason=inactive_epoch",f.epoch(),f.transfer());return;}
        try {
            ColumnCodec.bounded(f.level(),0,4);ColumnCodec.bounded(f.totalLength(),1,ColumnCodec.MAX_BYTES);ColumnCodec.bounded(f.rawLength(),1,ColumnCodec.MAX_BYTES);
            ColumnCodec.bounded(f.offset(),0,f.totalLength());if(f.bytes().length==0||f.bytes().length>f.totalLength()-f.offset())throw new IllegalArgumentException("Invalid fragment range");
            Assembly complete;
            synchronized(s.assemblies) {
                Assembly a=s.assemblies.get(f.transfer());
                if(a==null){if(f.offset()!=0||!currentRequest(s,ChunkPos.asLong(f.x(),f.z()),f.requestId()))return;long reservation=reserve(s,f);if(s.memory.addAndGet(reservation)>s.receiveLimit){s.memory.addAndGet(-reservation);throw new IllegalArgumentException("Receive credit exceeded");}a=new Assembly(f,reservation);s.assemblies.put(f.transfer(),a);}
                var h=a.header;if(a.offset!=f.offset()||h.x()!=f.x()||h.z()!=f.z()||h.level()!=f.level()||h.version()!=f.version()||h.totalLength()!=f.totalLength()||h.rawLength()!=f.rawLength()||h.compressed()!=f.compressed())throw new IllegalArgumentException("Inconsistent LOD fragments");
                System.arraycopy(f.bytes(),0,a.bytes,a.offset,f.bytes().length);a.offset+=f.bytes().length;s.received+=f.bytes().length;
                var pending=s.pending.get(ChunkPos.asLong(h.x(),h.z()));if(pending!=null&&pending.id()==h.requestId())synchronized(pending){pending.receiving=true;pending.transfer=f.transfer();pending.lastProgress=System.nanoTime();if(a.offset==a.bytes.length)pending.applying=true;}
                a.trace.fragment(f.offset(),f.bytes().length,f.totalLength(),f.rawLength(),1);
                if(a.offset!=a.bytes.length)return;s.assemblies.remove(f.transfer());complete=a;
            }
            long queued=DebugLog.start();
            s.worker.execute(()->{
                DebugLog.end(CLIENT_QUEUE,queued);
                long started=System.nanoTime();String result="discarded";try {
                    if(s.closed||s.epoch!=f.epoch())return;
                    long decode=DebugLog.start();var column=ColumnCodec.decode(new ColumnCodec.Encoded(f.compressed(),f.rawLength(),complete.bytes));complete.trace.decode+=DebugLog.end(CLIENT_DECODE,decode);
                    if(column.x()!=f.x()||column.z()!=f.z()||column.version()!=f.version()||column.minimumLevel()!=f.level()||column.minY()!=s.hello.minY()||column.sections().length!=s.hello.maxY()-s.hello.minY())throw new IllegalArgumentException("LOD column metadata mismatch");
                    long latest=s.versions.getOrDefault(ChunkPos.asLong(f.x(),f.z()),0L);
                    long position=ChunkPos.asLong(f.x(),f.z());
                    var coverage=VoxyBridge.coverage(s.engine);
                    long lock=DebugLog.start();
                    synchronized(coverage){
                        DebugLog.end(CLIENT_COVERAGE_LOCK,lock);
                        if(session==s&&s.epoch==f.epoch()&&currentRequest(s,position,f.requestId())&&!s.loadedFull.contains(position)&&column.version()>=latest){long apply=DebugLog.start();int conflicts=coverage.conflicts(column.x(),column.z(),s.hello.minY(),s.hello.maxY(),column.version());if(conflicts>0){DebugLog.count(CLIENT_OLD_VERSION_REPLACED);if(DebugLog.verbose())DebugLog.log("CLIENT authoritative_replace x={} z={} reply_version={} newer_sections={}",column.x(),column.z(),column.version(),conflicts);}CoarseLodReceiver.receive(s.engine,column,s.level.registryAccess(),true);complete.trace.apply+=DebugLog.end(CLIENT_APPLY,apply);s.applied++;complete.trace.applied++;}
                    }
                    result="ok";
                    if(DebugLog.verbose())DebugLog.log("CLIENT column epoch={} transfer={} x={} z={} request_id={} level={} version={} latest={} payload_bytes={} raw_bytes={} reservation={} applied={}",s.epoch,f.transfer(),f.x(),f.z(),f.requestId(),f.level(),f.version(),latest,complete.bytes.length,f.rawLength(),complete.reservation,currentRequest(s,ChunkPos.asLong(f.x(),f.z()),f.requestId())&&column.version()>=latest);
                    completed(s,f.epoch(),ChunkPos.asLong(f.x(),f.z()),f.requestId(),f.version(),"column");
                }catch(RuntimeException e){result="failed";if(DebugLog.enabled())DebugLog.log("CLIENT failed epoch={} transfer={} error={}",f.epoch(),f.transfer(),e.toString());failed(s,e);}
                finally{long paced=System.nanoTime();pace(started);complete.trace.applied(started,paced,result);s.memory.addAndGet(-complete.reservation);long receiptQueued=DebugLog.start();Minecraft.getInstance().execute(()->{DebugLog.end(CLIENT_RECEIPT_CONTROL,receiptQueued);if(session==s&&s.epoch==f.epoch()){complete.trace.receipt();Protocol.CHANNEL.sendToServer(new Protocol.Receipt(f.epoch(),f.transfer()));}});}
            });
        }catch(RuntimeException e){failed(s,e);}
    }
    public static void reply(Protocol.Reply r){
        Session s=session;if(s==null||r.epoch()!=s.epoch)return;
        if(DebugLog.verbose())DebugLog.log("CLIENT reply epoch={} x={} z={} request_id={} version={} level={} status={}",r.epoch(),r.x(),r.z(),r.requestId(),r.version(),r.level(),r.status());
        long p=ChunkPos.asLong(r.x(),r.z());
        if(!currentRequest(s,p,r.requestId()))return;
        if(r.status()==0){var request=s.pending.get(p);synchronized(request){request.applying=true;}s.deferredReplies.add(r);drainMainWork(s);return;}
        s.pending.remove(p);
        if(r.status()==3){
            if(r.version()<s.versions.getOrDefault(p,0L)){s.invalid.add(p);s.retries.remove(p);refill(s);return;}
            if(desired(s,p)>r.level()){s.invalid.add(p);s.retries.remove(p);refill(s);return;}
            s.budgetInsufficient.put(p,r.level());s.invalid.remove(p);s.retries.remove(p);
            refill(s);return;
        }
        if(r.status()==2&&s.level.getChunkSource().getChunk(r.x(),r.z(),ChunkStatus.FULL,false)!=null){s.fullRetry.add(p);s.retries.remove(p);}
        else retry(s,p,r.status()==1?"server_retry":"unavailable");
        refill(s);
    }
    public static void batchFragment(Protocol.BatchFragment f) {
        Session s=session;if(s==null||s.closed||f.epoch()!=s.epoch){if(DebugLog.verbose())DebugLog.log("CLIENT discard epoch={} transfer={} reason=inactive_epoch",f.epoch(),f.transfer());return;}
        try {
            ColumnCodec.bounded(f.totalLength(),1,ColumnCodec.MAX_BYTES);ColumnCodec.bounded(f.rawLength(),1,ColumnCodec.MAX_BYTES);
            ColumnCodec.bounded(f.offset(),0,f.totalLength());if(f.bytes().length==0||f.bytes().length>f.totalLength()-f.offset())throw new IllegalArgumentException("Invalid batch fragment range");
            BatchAssembly complete;
            synchronized(s.assemblies){
                if(s.closed||s.epoch!=f.epoch())return;
                DiscardedBatch discarded=s.discardedBatches.get(f.transfer());
                if(discarded!=null){
                    if(discarded.totalLength!=f.totalLength()||discarded.offset!=f.offset())throw new IllegalArgumentException("Inconsistent discarded batch fragments");
                    discarded.offset+=f.bytes().length;
                    if(discarded.offset==discarded.totalLength){s.discardedBatches.remove(f.transfer());Protocol.CHANNEL.sendToServer(new Protocol.Receipt(f.epoch(),f.transfer()));}
                    return;
                }
                BatchAssembly a=s.batchAssemblies.get(f.transfer());
                if(a==null){
                    if(f.offset()!=0)return;
                    if(f.members().stream().noneMatch(m->currentRequest(s,ChunkPos.asLong(m.x(),m.z()),m.requestId()))){
                        int offset=f.bytes().length;
                        if(offset==f.totalLength())Protocol.CHANNEL.sendToServer(new Protocol.Receipt(f.epoch(),f.transfer()));
                        else s.discardedBatches.put(f.transfer(),new DiscardedBatch(f.totalLength(),offset));
                        return;
                    }
                    ColumnCodec.bounded(f.members().size(),1,BatchCodec.MAX_COLUMNS);long raw=4;var positions=new HashSet<Long>();
                    for(var m:f.members()){
                        ColumnCodec.bounded(m.level(),0,4);ColumnCodec.bounded(m.rawLength(),1,ColumnCodec.MAX_BYTES);raw+=4L+m.rawLength();
                        if(!positions.add(ChunkPos.asLong(m.x(),m.z())))throw new IllegalArgumentException("Duplicate batch column");
                    }
                    if(raw!=f.rawLength())throw new IllegalArgumentException("Batch raw length mismatch");
                    long reservation=Protocol.batchReservation(f.totalLength(),f.rawLength(),f.members(),s.hello.maxY()-s.hello.minY());
                    if(s.memory.addAndGet(reservation)>s.receiveLimit){s.memory.addAndGet(-reservation);throw new IllegalArgumentException("Receive batch credit exceeded");}
                    a=new BatchAssembly(f,reservation);s.batchAssemblies.put(f.transfer(),a);
                }
                var h=a.header;if(a.offset!=f.offset()||h.totalLength()!=f.totalLength()||h.rawLength()!=f.rawLength()||h.compressed()!=f.compressed())throw new IllegalArgumentException("Inconsistent batch fragments");
                System.arraycopy(f.bytes(),0,a.bytes,a.offset,f.bytes().length);a.offset+=f.bytes().length;s.received+=f.bytes().length;
                long progress=System.nanoTime();for(var m:h.members()){var pending=s.pending.get(ChunkPos.asLong(m.x(),m.z()));if(pending!=null&&pending.id()==m.requestId())synchronized(pending){pending.receiving=true;pending.transfer=f.transfer();pending.lastProgress=progress;if(a.offset==a.bytes.length)pending.applying=true;}}
                a.trace.fragment(f.offset(),f.bytes().length,f.totalLength(),f.rawLength(),a.header.members().size());
                if(a.offset!=a.bytes.length)return;s.batchAssemblies.remove(f.transfer());complete=a;
            }
            long queued=DebugLog.start();
            s.worker.execute(()->{
                DebugLog.end(CLIENT_QUEUE,queued);long started=System.nanoTime();String result="discarded";
                try{
                    if(s.closed||s.epoch!=f.epoch())return;
                    long decode=DebugLog.start();byte[] raw=ColumnCodec.uncompress(new ColumnCodec.Encoded(f.compressed(),f.rawLength(),complete.bytes));
                    var buffer=java.nio.ByteBuffer.wrap(raw);var members=complete.header.members();
                    if(buffer.getInt()!=members.size())throw new IllegalArgumentException("Batch column count mismatch");
                    // Validate the frame before applying any column, then expand only one column at a time.
                    for(var m:members){if(buffer.remaining()<4||buffer.getInt()!=m.rawLength()||buffer.remaining()<m.rawLength())throw new IllegalArgumentException("Invalid batch column length");buffer.position(buffer.position()+m.rawLength());}
                    if(buffer.hasRemaining())throw new IllegalArgumentException("Trailing batch bytes");
                    buffer.position(4);complete.trace.decode+=DebugLog.end(CLIENT_DECODE,decode);
                    var batch=new CoarseLodReceiver.Batch(s.engine);
                    try{for(var m:members){
                        if(s.closed||s.epoch!=f.epoch())return;
                        int length=buffer.getInt();byte[] bytes=new byte[length];buffer.get(bytes);
                        decode=DebugLog.start();var column=ColumnCodec.decodeRaw(bytes,31);complete.trace.decode+=DebugLog.end(CLIENT_DECODE,decode);
                        if(column.x()!=m.x()||column.z()!=m.z()||column.version()!=m.version()||column.minimumLevel()!=m.level()||column.minY()!=s.hello.minY()||column.sections().length!=s.hello.maxY()-s.hello.minY())throw new IllegalArgumentException("Batch column metadata mismatch");
                        long p=ChunkPos.asLong(m.x(),m.z());
                        long latest=s.versions.getOrDefault(p,0L);
                        var coverage=VoxyBridge.coverage(s.engine);
                        long lock=DebugLog.start();
                        synchronized(coverage){
                            DebugLog.end(CLIENT_COVERAGE_LOCK,lock);
                            if(session==s&&s.epoch==f.epoch()&&currentRequest(s,p,m.requestId())&&!s.loadedFull.contains(p)&&column.version()>=latest){long apply=DebugLog.start();int conflicts=coverage.conflicts(column.x(),column.z(),s.hello.minY(),s.hello.maxY(),column.version());if(conflicts>0){DebugLog.count(CLIENT_OLD_VERSION_REPLACED);if(DebugLog.verbose())DebugLog.log("CLIENT authoritative_replace x={} z={} reply_version={} newer_sections={}",column.x(),column.z(),column.version(),conflicts);}CoarseLodReceiver.receive(s.engine,column,s.level.registryAccess(),true,batch);complete.trace.apply+=DebugLog.end(CLIENT_APPLY,apply);s.applied++;complete.trace.applied++;}
                        }
                        if(DebugLog.verbose())DebugLog.log("CLIENT column epoch={} transfer={} x={} z={} request_id={} level={} version={} latest={} raw_bytes={} applied={}",f.epoch(),f.transfer(),m.x(),m.z(),m.requestId(),m.level(),m.version(),latest,m.rawLength(),currentRequest(s,p,m.requestId())&&column.version()>=latest);
                        completed(s,f.epoch(),p,m.requestId(),m.version(),"batch");
                    }}finally{batch.finish();}
                    result="ok";
                }catch(RuntimeException e){result="failed";if(DebugLog.enabled())DebugLog.log("CLIENT failed epoch={} transfer={} error={}",f.epoch(),f.transfer(),e.toString());failed(s,e);}
                finally{long paced=System.nanoTime();pace(started);complete.trace.applied(started,paced,result);s.memory.addAndGet(-complete.reservation);long receiptQueued=DebugLog.start();Minecraft.getInstance().execute(()->{DebugLog.end(CLIENT_RECEIPT_CONTROL,receiptQueued);if(session==s&&s.epoch==f.epoch()){complete.trace.receipt();Protocol.CHANNEL.sendToServer(new Protocol.Receipt(f.epoch(),f.transfer()));}});}
            });
        }catch(RuntimeException e){failed(s,e);}
    }
    public static void abort(Protocol.Abort a){Session s=session;if(s==null||s.epoch!=a.epoch())return;synchronized(s.assemblies){s.discardedBatches.remove(a.transfer());Assembly removed=s.assemblies.remove(a.transfer());if(removed!=null){s.memory.addAndGet(-removed.reservation);long p=ChunkPos.asLong(removed.header.x(),removed.header.z());if(currentRequest(s,p,removed.header.requestId())){s.pending.remove(p);s.invalid.add(p);}}BatchAssembly batch=s.batchAssemblies.remove(a.transfer());if(batch!=null){s.memory.addAndGet(-batch.reservation);for(var m:batch.header.members()){long p=ChunkPos.asLong(m.x(),m.z());if(currentRequest(s,p,m.requestId())){s.pending.remove(p);s.invalid.add(p);}}}}Protocol.CHANNEL.sendToServer(new Protocol.Receipt(a.epoch(),a.transfer()));}
    public static void dirty(Protocol.Dirty d) {
        if(greeting==null||!greeting.dimension().equals(d.dimension()))return;
        Session s=session;long pos=ChunkPos.asLong(d.x(),d.z());if(s==null){initialVersions.merge(pos,d.version(),Math::max);return;}
        long previous=s.versions.getOrDefault(pos,0L);s.versions.merge(pos,d.version(),Math::max);s.checked.remove(pos);s.retries.remove(pos);if(d.version()>previous)s.budgetInsufficient.remove(pos);if(!d.vanilla())s.invalid.add(pos);
        s.discovery.dirty(pos);
        invalidateSnapshots(s,d.x(),d.z());
        // A revision applies to the whole column, even when vanilla changed only one section.
        // Recapture loaded columns after the following vanilla packet has run on the main thread.
        s.fullRetry.add(pos);
        // Keep the existing render hierarchy while requiring a fresh revision for cache hits.
        s.deferredDirty.merge(pos,d.version(),Math::max);drainMainWork(s);
    }
    private static void drainMainWork(Session s){
        while(!s.deferredDirty.isEmpty()){
            var entry=s.deferredDirty.entrySet().iterator().next();long p=entry.getKey(),version=entry.getValue();int x=ChunkPos.getX(p),z=ChunkPos.getZ(p);
            if(!s.offer(()->{try{var coverage=VoxyBridge.coverage(s.engine);synchronized(coverage){if(!s.closed)coverage.restrict(x,z,s.hello.minY(),s.hello.maxY(),version);}}catch(RuntimeException e){failed(s,e);}}))return;
            s.deferredDirty.remove(p);
        }
        while(!s.deferredReplies.isEmpty()){
            var reply=s.deferredReplies.peek();long p=ChunkPos.asLong(reply.x(),reply.z());
            if(!s.offer(()->{try{if(!s.closed&&s.epoch==reply.epoch())completed(s,reply.epoch(),p,reply.requestId(),reply.version(),"unchanged");}catch(RuntimeException e){failed(s,e);}}))return;
            s.deferredReplies.remove();
        }
        if(s.activePending){int x=s.activeX,z=s.activeZ,radius=s.activeRadius;if(s.offer(()->VoxyBridge.coverage(s.engine).active(x,z,radius)))s.activePending=false;}
        if(s.checkpointPending&&s.offer(()->{long timing=DebugLog.start();try{VoxyBridge.coverage(s.engine).checkpointIfDue(s.engine.storage::flush);}catch(RuntimeException e){failed(s,e);}finally{DebugLog.end(CLIENT_CHECKPOINT,timing);}}))s.checkpointPending=false;
    }
    public static boolean handles(WorldEngine world){Session s=session;return s!=null&&!s.closed&&s.engine==world;}
    public static float renderRadiusSquared(){Session s=session;if(s==null||s.closed)return -1;float blocks=Math.min(s.radius,VoxyBridge.radiusChunks())*16f;return blocks*blocks;}
    public static void lightPending(net.minecraft.client.multiplayer.ClientLevel level,int x,int z){
        if(lightLevel!=level){lightLevel=level;lightReady.clear();}
        lightReady.remove(ChunkPos.asLong(x,z));
        Session s=session;if(s!=null&&s.level==level)invalidateSnapshots(s,x,z);
    }
    public static void lightApplied(net.minecraft.client.multiplayer.ClientLevel level,int x,int z){
        if(lightLevel!=level){lightLevel=level;lightReady.clear();}
        if(level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false)==null)return;
        long p=ChunkPos.asLong(x,z);lightReady.add(p);
        Session s=session;if(s!=null&&s.level==level&&!s.closed){invalidateSnapshots(s,x,z);s.fullRetry.add(p);}
    }
    public static void unload(LevelChunk chunk){
        long p=chunk.getPos().toLong();lightReady.remove(p);
        Session s=session;if(s==null||s.closed||chunk.getLevel()!=s.level)return;
        s.loadedFull.remove(p);s.checked.remove(p);s.retries.remove(p);s.fullRetry.remove(p);
        invalidateSnapshots(s,chunk.getPos().x,chunk.getPos().z);
        if(inRange(s,chunk.getPos().x,chunk.getPos().z))s.invalid.add(p);
    }
    public static boolean full(WorldEngine world,LevelChunk chunk) {
        Session s=session;if(s==null||s.engine!=world||s.closed)return false;
        if(maintenancePaused)return true;
        long p=chunk.getPos().toLong();s.loadedFull.add(p);
        if(lightLevel!=s.level||!lightReady.contains(p)){s.fullRetry.add(p);return true;}
        for(int i=0;i<chunk.getSectionsCount();i++) {
            int y=chunk.getMinSection()+i;var pos=SectionPos.of(chunk.getPos(),y);var light=chunk.getLevel().getLightEngine();
            snapshot(world,chunk.getSections()[i],chunk.getPos().x,y,chunk.getPos().z,light.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos),light.getLayerListener(LightLayer.SKY).getDataLayerData(pos));
        }return true;
    }
    public static void snapshot(WorldEngine world,LevelChunkSection section,int x,int y,int z,DataLayer block,DataLayer sky) {
        if(maintenancePaused)return;
        Session s=session;if(s==null||s.engine!=world||s.closed)return;
        long p=ChunkPos.asLong(x,z);
        long key=SectionPos.asLong(x,y,z);
        if(lightLevel!=s.level||!lightReady.contains(p)){s.latestSnapshots.remove(key);s.fullRetry.add(p);return;}
        long version=s.versions.getOrDefault(p,0L);if(version==0)return;
        if(s.worker.getQueue().size()>=256){s.latestSnapshots.remove(key);s.deferredSnapshots.add(p);return;}
        long bytes=ChunkSnapshot.RESERVED_BYTES;if(s.snapshotMemory.addAndGet(bytes)>s.snapshotLimit){s.snapshotMemory.addAndGet(-bytes);s.latestSnapshots.remove(key);Minecraft.getInstance().execute(()->{if(session==s)s.fullRetry.add(ChunkPos.asLong(x,z));});return;}s.memory.addAndGet(bytes);
        var biomes=section.getBiomes().recreate();for(int by=0;by<4;by++)for(int bz=0;bz<4;bz++)for(int bx=0;bx<4;bx++)biomes.getAndSetUnchecked(bx,by,bz,section.getBiomes().get(bx,by,bz));
        var snapshot=new ChunkSnapshot(x,y,z,section.getStates().copy(),biomes,block==null?null:block.copy(),sky==null?null:sky.copy());int epoch=s.epoch;
        // Retire only a queued predecessor after the new immutable capture exists.
        DebugLog.count(CLIENT_SNAPSHOT_CAPTURE);s.latestSnapshots.put(key,snapshot);
        if(!s.offer(()->{
            long started=System.nanoTime();try{synchronized(VoxyBridge.coverage(world)){if(!s.closed&&s.epoch==epoch&&version>=s.versions.getOrDefault(ChunkPos.asLong(x,z),0L)){if(s.latestSnapshots.get(key)==snapshot){VoxyBridge.ingestRemote(world,snapshot,version);var stamp=VoxyBridge.coverage(world).directoryColumn(x,z);Minecraft.getInstance().execute(()->{if(session==s&&s.epoch==epoch)s.discovery.updated(p,stamp.version()>=s.versions.getOrDefault(p,0L)?stamp:new CoverageStore.Stamp(0,5));});}else DebugLog.count(CLIENT_SNAPSHOT_SUPERSEDED);}}}
            catch(RuntimeException e){failed(s,e);}finally{s.latestSnapshots.remove(key,snapshot);pace(started);s.memory.addAndGet(-bytes);s.snapshotMemory.addAndGet(-bytes);}
        })){
            s.latestSnapshots.remove(key,snapshot);
            s.memory.addAndGet(-bytes);s.snapshotMemory.addAndGet(-bytes);s.deferredSnapshots.add(p);
        }
    }
    private static void invalidateSnapshots(Session s,int x,int z){
        for(int y=s.level.getMinSection();y<s.level.getMaxSection();y++)s.latestSnapshots.remove(SectionPos.asLong(x,y,z));
    }
    private static void pace(long started){double duty=DistantConfig.RECEIVE_DUTY.get();if(duty<1){long timing=DebugLog.start();java.util.concurrent.locks.LockSupport.parkNanos((long)((System.nanoTime()-started)*(1/duty-1)));DebugLog.end(CLIENT_PACE,timing);}}
    private static void failed(Session s,RuntimeException e){failure=e.toString();LOG.error("Voxy Distant receive failed",e);Minecraft.getInstance().execute(()->{if(session==s){s.radius=0;send(s,List.of());close();greeting=null;}});}
    public static String status(){if(maintenancePaused)return "服务端正在导入远景";Session s=session;return s==null?(failure.isEmpty()?"无服务端远景会话":failure):String.format(Locale.ROOT,"接收 %d 列 · %.1f MiB · 缓冲 %.1f MiB · 请求 %d%s",s.applied,s.received/1048576.0,s.memory.get()/1048576.0,s.pending.size(),s.budgetInsufficient.isEmpty()?"":" · 信用不足 "+s.budgetInsufficient.size()+" 列");}
    public static void close(){
        Session s=session;if(s==null)return;
        // Finish the current write before handing ingest back; queued work rechecks closed under this lock.
        synchronized(VoxyBridge.coverage(s.engine)){s.closed=true;session=null;}
        if(DebugLog.enabled())DebugLog.log("CLIENT close epoch={} applied={} payload_bytes={} pending={} worker_queue={}",s.epoch,s.applied,s.received,s.pending.size(),s.worker.getQueue().size());
        synchronized(s.assemblies){for(var a:s.assemblies.values())s.memory.addAndGet(-a.reservation);s.assemblies.clear();for(var a:s.batchAssemblies.values())s.memory.addAndGet(-a.reservation);s.batchAssemblies.clear();s.discardedBatches.clear();}
        s.releaseOnTermination=true;s.worker.shutdown();retired.add(s);
    }
    public static void shutdown(){close();try{for(Session s:retired)while(!s.worker.awaitTermination(1,TimeUnit.SECONDS)){}retired.clear();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    private RemoteClient(){}
}
