package dev.voxydistant.client;

import com.mojang.logging.LogUtils;
import dev.voxydistant.DebugLog;
import static dev.voxydistant.DebugLog.Metric.*;
import dev.voxydistant.compat.*;
import dev.voxydistant.config.*;
import dev.voxydistant.data.*;
import dev.voxydistant.generation.*;
import dev.voxydistant.network.Protocol;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

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
    public static void maintenance(Protocol.Maintenance message) {
        if(maintenancePaused==message.paused())return;
        maintenancePaused=message.paused();
        Session s=session;if(s==null)return;
        s.epoch=++sequence;s.generation++;s.pending.clear();s.checked.clear();s.invalid.clear();s.retries.clear();s.fullRetry.clear();
        synchronized(s.assemblies){
            for(var a:s.assemblies.values())s.memory.addAndGet(-a.reservation);s.assemblies.clear();
            for(var a:s.batchAssemblies.values())s.memory.addAndGet(-a.reservation);s.batchAssemblies.clear();
        }
        s.x=Integer.MIN_VALUE;s.scan=null;s.scanHeld=null;
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
    private static final class Pending {
        private final long started,id;private final int desired,generation;
        private CoverageStore.Stamp before=new CoverageStore.Stamp(0,5);
        private long dirtyBefore;
        private volatile boolean receiving;
        Pending(long started,int desired,int generation,long id){this.started=started;this.desired=desired;this.generation=generation;this.id=id;}
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
        });
        final AtomicLong memory=new AtomicLong();final ConcurrentHashMap<Long,Long> versions=new ConcurrentHashMap<>();
        // Vanilla snapshots must not consume credit already advertised to the server.
        final long receiveLimit=DistantConfig.RECEIVE_MIB.get()*1048576L;
        final long snapshotLimit=Math.min(32L<<20,receiveLimit/4);
        final int networkCapacity=(int)(receiveLimit-snapshotLimit);
        final AtomicLong snapshotMemory=new AtomicLong();
        final Map<Long,Assembly> assemblies=new HashMap<>();
        final Map<Long,BatchAssembly> batchAssemblies=new HashMap<>(); // guarded by assemblies
        final Map<Long,Pending> pending=new ConcurrentHashMap<>();
        final Set<Long> loadedFull=ConcurrentHashMap.newKeySet();
        final Map<Long,Integer> checked=new HashMap<>();
        final Set<Long> invalid=new LinkedHashSet<>();
        final RetryQueue retries=new RetryQueue();
        final Set<Long> fullRetry=new LinkedHashSet<>();
        final DistanceBands bands;volatile int epoch=++sequence;volatile boolean closed;
        NearbyChunks scan;Long scanHeld;int x=Integer.MIN_VALUE,z,radius,view,ticks,generation,scanBudget,requestBudget,preempted;volatile long applied,received;long requestSequence;
        boolean refillQueued;
        long debugAt,requestIdleAt;String requestState="initializing";
        long rateAt,rateBytes,rateColumns;double receiveKiBPerSecond,receiveColumnsPerSecond;
        long debugReceived,debugApplied,debugTicks;
        final Map<String,Integer> requestStates=new LinkedHashMap<>();
        Session(WorldEngine engine,net.minecraft.client.multiplayer.ClientLevel level,Protocol.Hello hello){this.engine=engine;this.level=level;this.hello=hello;bands=DistanceBands.parse(hello.bands());VoxyBridge.coverage(engine).remote(hello.minY(),hello.maxY(),DistantConfig.INDEX_MIB.get());}
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
        if(session==null){WorldEngine engine=VoxyBridge.acquire(mc.level);if(engine==null)return;session=new Session(engine,mc.level,greeting);session.versions.putAll(initialVersions);session.fullRetry.addAll(initialVersions.keySet());session.fullRetry.addAll(lightReady);initialVersions.clear();}
        Session s=session;s.ticks++;
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
            DebugLog.log("CLIENT state epoch={} center={},{} radius={} bands={} applied={} received_payload_bytes={} reserved_bytes={} receive_limit={} snapshot_bytes={} snapshot_limit={} advertised_credit={} pending={}/{} invalid={} full_retry={} worker_queue={} worker_active={} main_tasks={} scanning={} request_state={}",s.epoch,s.x,s.z,s.radius,s.hello.bands(),s.applied,s.received,s.memory.get(),s.receiveLimit,s.snapshotMemory.get(),s.snapshotLimit,s.networkCapacity,s.pending.size(),DistantConfig.REQUEST_WINDOW.get(),s.invalid.size(),s.fullRetry.size(),s.worker.getQueue().size(),s.worker.getActiveCount(),mc.getPendingTasksCount(),s.scan!=null,s.requestState);
            DebugLog.timings("CLIENT");
            long oldest=0;for(var requested:s.pending.values())oldest=Math.max(oldest,now-requested.started());
            synchronized(s.assemblies){DebugLog.log("CLIENT pipeline world={} dimension={} epoch={} partial_columns={} partial_batches={} oldest_request_ms={} ingest_jobs={} download_kib={} duty={} retry_cooldown={}",s.hello.world(),s.hello.dimension(),s.epoch,s.assemblies.size(),s.batchAssemblies.size(),DebugLog.millis(oldest),s.engine.instanceIn.getIngestService().getTaskCount(),DistantConfig.DOWNLOAD_KBPS.get(),DistantConfig.RECEIVE_DUTY.get(),s.retries.size());}
            for(var retry:s.retries.oldest(4))DebugLog.log("CLIENT retry_wait epoch={} x={} z={} desired={} attempts={} urgent={} remaining_ms={}",s.epoch,ChunkPos.getX(retry.position()),ChunkPos.getZ(retry.position()),retry.desired(),retry.attempts(),retry.urgent(),DebugLog.millis(Math.max(0,retry.due()-now)));
            DebugLog.connection("CLIENT",mc.getConnection().getConnection(),mc.player.getUUID());
        }
        if(s.ticks%20==0)s.worker.execute(()->{long timing=DebugLog.start();try{VoxyBridge.coverage(s.engine).checkpointIfDue(s.engine.storage::flush);}catch(RuntimeException e){failed(s,e);}finally{DebugLog.end(CLIENT_CHECKPOINT,timing);}});
        if(maintenancePaused)return;
        if(!s.fullRetry.isEmpty()){
            var it=s.fullRetry.iterator();
            for(int budget=8;it.hasNext()&&budget>0&&s.worker.getQueue().size()<256;){
                long p=it.next();var chunk=mc.level.getChunkSource().getChunk(ChunkPos.getX(p),ChunkPos.getZ(p),ChunkStatus.FULL,false);
                if(chunk!=null&&!lightReady.contains(p))continue;
                if(chunk!=null&&s.snapshotMemory.get()+chunk.getSectionsCount()*ChunkSnapshot.RESERVED_BYTES>s.snapshotLimit)break;
                it.remove();if(chunk!=null)full(s.engine,chunk);else s.invalid.add(p);
                budget--;
            }
        }
        int radius=Math.min(s.hello.radius(),VoxyBridge.radiusChunks());if(DistantConfig.RECEIVE_RADIUS.get()>0)radius=Math.min(radius,DistantConfig.RECEIVE_RADIUS.get());
        int x=mc.player.chunkPosition().x,z=mc.player.chunkPosition().z,view=mc.options.renderDistance().get();
        if(s.x!=x||s.z!=z||s.radius!=radius||s.view!=view) {
            if(DebugLog.enabled())DebugLog.log("CLIENT move epoch={} from_x={} from_z={} x={} z={} generation={} radius={} view={}",s.epoch,s.x,s.z,x,z,s.generation+1,radius,view);
            boolean teleport=s.x!=Integer.MIN_VALUE&&(Math.abs((long)x-s.x)>32||Math.abs((long)z-s.z)>32);
            s.x=x;s.z=z;s.radius=radius;s.view=view;s.generation++;s.scan=new NearbyChunks(radius,-1);s.scanHeld=null;s.preempted=0;
            if(teleport){s.epoch=++sequence;s.pending.clear();s.checked.clear();s.invalid.clear();s.retries.clear();synchronized(s.assemblies){for(var a:s.assemblies.values())s.memory.addAndGet(-a.reservation);s.assemblies.clear();for(var a:s.batchAssemblies.values())s.memory.addAndGet(-a.reservation);s.batchAssemblies.clear();}}
            s.checked.keySet().removeIf(p->!inRange(s,ChunkPos.getX(p),ChunkPos.getZ(p)));
            s.versions.keySet().removeIf(p->!inRange(s,ChunkPos.getX(p),ChunkPos.getZ(p)));
            s.invalid.removeIf(p->!inRange(s,ChunkPos.getX(p),ChunkPos.getZ(p)));
            s.retries.moved(p->inRange(s,ChunkPos.getX(p),ChunkPos.getZ(p)),p->desired(s,p),p->urgent(s,p),System.nanoTime());
            Minecraft.getInstance().execute(()->send(s,List.of()));
            final int range=radius;s.worker.execute(()->VoxyBridge.coverage(s.engine).active(x,z,range));
        }
        s.pending.entrySet().removeIf(e->{if(System.nanoTime()-e.getValue().started()>TimeUnit.SECONDS.toNanos(30)){if(DebugLog.enabled())DebugLog.log("CLIENT request_timeout epoch={} x={} z={} request_id={}",s.epoch,ChunkPos.getX(e.getKey()),ChunkPos.getZ(e.getKey()),e.getValue().id());retry(s,e.getKey(),"timeout");return true;}return false;});
        s.scanBudget=256;
        s.requestBudget=256;
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
        return cacheColumns<0?"服务器缓存：查询中…":String.format(Locale.ROOT,"服务器缓存 %.1f MiB · %,d 列",cacheBytes/1048576.0,cacheColumns);
    }
    public static String receiveSpeed() {
        Session s=session;
        return s==null?"接收速度：无会话":String.format(Locale.ROOT,"接收速度 %.1f KiB/s · %.1f 列/秒",s.receiveKiBPerSecond,s.receiveColumnsPerSecond);
    }
    private static void requestMore(Session s) {
        if(session!=s||s.closed||maintenancePaused)return;
        var mc=Minecraft.getInstance();
        int window=DistantConfig.REQUEST_WINDOW.get();
        s.requestState=mc.isPaused()?"paused":s.pending.size()>=window&&(s.scan==null||s.preempted>=16)?"request_window":s.requestBudget==0?"request_rate":s.memory.get()>DistantConfig.RECEIVE_MIB.get()*1048576L*3/4?"receive_memory":VoxyBridge.backedUp(s.engine)?"voxy_backlog":"scanning";
        if(!s.requestState.equals("scanning")){switch(s.requestState){case "request_window"->DebugLog.count(CLIENT_REQUEST_WINDOW);case "receive_memory"->DebugLog.count(CLIENT_REQUEST_MEMORY);case "voxy_backlog"->DebugLog.count(CLIENT_REQUEST_BACKLOG);}return;}
        var positions=new LinkedHashSet<Long>();
        int batch=Math.min(64,Math.min(s.requestBudget,Math.max(0,window-s.pending.size())+(s.scan==null?0:Math.max(0,16-s.preempted))));
        if(batch==0){s.requestState="request_window";return;}
        var cancels=new ArrayList<Protocol.Cancel>();
        // Reserve half a request batch for scanning past temporarily missing columns.
        int retryLimit=s.pending.size()>=window?0:s.scan==null?batch:Math.min(batch/2,window-s.pending.size());
        int refillBudget=256;
        for(var it=s.invalid.iterator();it.hasNext()&&positions.size()<retryLimit&&refillBudget-->0;){long p=it.next();it.remove();if(inRange(s,ChunkPos.getX(p),ChunkPos.getZ(p))&&!s.pending.containsKey(p)&&!s.retries.scheduled(p))positions.add(p);}
        long retryNow=System.nanoTime();
        for(int i=0;i<batch&&positions.size()<retryLimit;i++){
            Long p=s.retries.poll(retryNow);if(p==null)break;
            if(!inRange(s,ChunkPos.getX(p),ChunkPos.getZ(p))){s.retries.remove(p);continue;}
            if(!s.pending.containsKey(p))positions.add(p);
        }
        while(s.scan!=null&&positions.size()<batch&&s.scanBudget>0) {
            s.scanBudget--;
            Long held=s.scanHeld;s.scanHeld=null;
            var next=held==null?s.scan.next():null;if(held==null&&next==null){s.scan=null;if(DebugLog.enabled())DebugLog.log("CLIENT scan_complete epoch={} generation={} pending={} refine={} retries={}",s.epoch,s.generation,s.pending.size(),s.invalid.size(),s.retries.size());break;}
            int cx=held==null?s.x+next.x():ChunkPos.getX(held),cz=held==null?s.z+next.z():ChunkPos.getZ(held);long pos=ChunkPos.asLong(cx,cz);int desired=s.bands.select(cx,cz,s.x,s.z);
            // Skip only columns the client actually has; its requested view may exceed the server view.
            if(mc.level.getChunkSource().getChunk(cx,cz,ChunkStatus.FULL,false)!=null){s.loadedFull.add(pos);continue;}
            if(s.pending.containsKey(pos)||s.retries.scheduled(pos)||s.checked.getOrDefault(pos,5)<=desired)continue;
            if(s.pending.size()+positions.size()>=window){
                var cancel=preempt(s,pos,positions);
                if(cancel==null){s.scanHeld=pos;break;}
                cancels.add(cancel);
            }
            positions.add(pos);
        }
        positions.removeIf(p->{if(mc.level.getChunkSource().getChunk(ChunkPos.getX(p),ChunkPos.getZ(p),ChunkStatus.FULL,false)==null)return false;s.loadedFull.add(p);s.fullRetry.add(p);s.retries.remove(p);return true;});
        if(positions.isEmpty()){if(!cancels.isEmpty())send(s,List.of(),cancels);s.requestState=s.scan==null?"scan_complete":s.scanHeld!=null?"request_window":"scan_budget";return;}
        s.requestBudget-=positions.size();
        DebugLog.end(CLIENT_REQUEST_IDLE,s.requestIdleAt);s.requestIdleAt=0;
        s.requestState="index_lookup";
        DebugLog.count(CLIENT_REQUEST_BATCH);
        long now=System.nanoTime();var issued=new LinkedHashMap<Long,Long>();positions.forEach(p->{long id=++s.requestSequence;s.pending.put(p,new Pending(now,desired(s,p),s.generation,id));issued.put(p,id);});int epoch=s.epoch;
        s.worker.execute(()->{
            long timing=DebugLog.start();try {
                var wants=new ArrayList<Protocol.Want>();var index=VoxyBridge.coverage(s.engine);
                for(long p:positions){int cx=ChunkPos.getX(p),cz=ChunkPos.getZ(p);var stamp=index.column(cx,cz,s.hello.minY(),s.hello.maxY());wants.add(new Protocol.Want(cx,cz,stamp.version(),stamp.level(),issued.get(p)));}
                long ready=DebugLog.start();Minecraft.getInstance().execute(()->{DebugLog.end(CLIENT_REQUEST_CONTROL,ready);if(session==s&&s.epoch==epoch){
                    wants.removeIf(w->!currentRequest(s,ChunkPos.asLong(w.x(),w.z()),w.requestId()));
                    for(var w:wants){long p=ChunkPos.asLong(w.x(),w.z());var request=s.pending.get(p);request.before=new CoverageStore.Stamp(w.version(),w.level());request.dirtyBefore=s.versions.getOrDefault(p,0L);if(w.level()<5&&w.version()>=request.dirtyBefore)s.checked.put(p,w.level());}
                    if(DebugLog.verbose())for(var w:wants)DebugLog.log("CLIENT want epoch={} generation={} x={} z={} request_id={} version={} level={} desired={}",s.epoch,s.generation,w.x(),w.z(),w.requestId(),w.version(),w.level(),desired(s,ChunkPos.asLong(w.x(),w.z())));
                    send(s,wants,cancels);refill(s);
                }});
            }catch(RuntimeException e){failed(s,e);}finally{DebugLog.end(CLIENT_INDEX,timing);}
        });
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
    private static boolean inRange(Session s,int x,int z){long dx=(long)x-s.x,dz=(long)z-s.z;return dx*dx+dz*dz<=(long)s.radius*s.radius;}
    private static int desired(Session s,long p){return s.bands.select(ChunkPos.getX(p),ChunkPos.getZ(p),s.x,s.z);}
    private static boolean urgent(Session s,long p){
        int target=desired(s,p),known=s.checked.getOrDefault(p,5),x=ChunkPos.getX(p),z=ChunkPos.getZ(p),size=1<<(s.bands.levels()[0]+2);
        int bx=Math.floorDiv(x,size)*size,bz=Math.floorDiv(z,size)*size;
        long dx=Math.max(Math.max(bx-s.x,s.x-(bx+size-1)),0),dz=Math.max(Math.max(bz-s.z,s.z-(bz+size-1)),0);
        return dx*dx+dz*dz<=(long)s.bands.radii()[0]*s.bands.radii()[0]||known<5&&known>target;
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
            var player=Minecraft.getInstance().player.chunkPosition();
            int target=s.bands.select(ChunkPos.getX(p),ChunkPos.getZ(p),player.x,player.z);
            long dirtyVersion=s.versions.getOrDefault(p,0L),latest=Math.max(responseVersion,dirtyVersion);
            boolean current=stamp.version()>=latest;
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
    private static void send(Session s,List<Protocol.Want> wants){send(s,wants,List.of());}
    private static void send(Session s,List<Protocol.Want> wants,List<Protocol.Cancel> cancels){if(session==s&&!s.closed&&!maintenancePaused){
        if(DebugLog.verbose())DebugLog.log("CLIENT request world={} dimension={} epoch={} wants={} pending={} credit={} bandwidth_kib={}",s.hello.world(),s.hello.dimension(),s.epoch,wants.size(),s.pending.size(),s.networkCapacity,DistantConfig.DOWNLOAD_KBPS.get());
        Protocol.CHANNEL.sendToServer(new Protocol.Requests(s.epoch,s.radius,s.view,DistantConfig.DOWNLOAD_KBPS.get(),s.networkCapacity,wants,cancels));
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
                if(a==null){if(f.offset()!=0)return;long reservation=reserve(s,f);if(s.memory.addAndGet(reservation)>s.receiveLimit){s.memory.addAndGet(-reservation);throw new IllegalArgumentException("Receive credit exceeded");}a=new Assembly(f,reservation);s.assemblies.put(f.transfer(),a);var pending=s.pending.get(ChunkPos.asLong(f.x(),f.z()));if(pending!=null&&pending.id()==f.requestId())synchronized(pending){pending.receiving=true;}}
                var h=a.header;if(a.offset!=f.offset()||h.x()!=f.x()||h.z()!=f.z()||h.level()!=f.level()||h.version()!=f.version()||h.totalLength()!=f.totalLength()||h.rawLength()!=f.rawLength()||h.compressed()!=f.compressed())throw new IllegalArgumentException("Inconsistent LOD fragments");
                System.arraycopy(f.bytes(),0,a.bytes,a.offset,f.bytes().length);a.offset+=f.bytes().length;s.received+=f.bytes().length;
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
                    if(session==s&&currentRequest(s,position,f.requestId())&&!s.loadedFull.contains(position)&&column.version()>=latest){long apply=DebugLog.start();var coverage=VoxyBridge.coverage(s.engine);int conflicts=coverage.conflicts(column.x(),column.z(),s.hello.minY(),s.hello.maxY(),column.version());if(conflicts>0){DebugLog.count(CLIENT_OLD_VERSION_REPLACED);if(DebugLog.verbose())DebugLog.log("CLIENT authoritative_replace x={} z={} reply_version={} newer_sections={}",column.x(),column.z(),column.version(),conflicts);}CoarseLodReceiver.receive(s.engine,column,s.level.registryAccess(),true);complete.trace.apply+=DebugLog.end(CLIENT_APPLY,apply);s.applied++;complete.trace.applied++;}
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
        if(r.status()==0){s.worker.execute(()->{try{if(!s.closed&&s.epoch==r.epoch())completed(s,r.epoch(),p,r.requestId(),r.version(),"unchanged");}catch(RuntimeException e){failed(s,e);}});return;}
        s.pending.remove(p);
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
                BatchAssembly a=s.batchAssemblies.get(f.transfer());
                if(a==null){
                    if(f.offset()!=0)return;
                    ColumnCodec.bounded(f.members().size(),1,BatchCodec.MAX_COLUMNS);long raw=4;var positions=new HashSet<Long>();
                    for(var m:f.members()){
                        ColumnCodec.bounded(m.level(),0,4);ColumnCodec.bounded(m.rawLength(),1,ColumnCodec.MAX_BYTES);raw+=4L+m.rawLength();
                        if(!positions.add(ChunkPos.asLong(m.x(),m.z())))throw new IllegalArgumentException("Duplicate batch column");
                    }
                    if(raw!=f.rawLength())throw new IllegalArgumentException("Batch raw length mismatch");
                    long reservation=Protocol.batchReservation(f.totalLength(),f.rawLength(),f.members(),s.hello.maxY()-s.hello.minY());
                    if(s.memory.addAndGet(reservation)>s.receiveLimit){s.memory.addAndGet(-reservation);throw new IllegalArgumentException("Receive batch credit exceeded");}
                    a=new BatchAssembly(f,reservation);s.batchAssemblies.put(f.transfer(),a);
                    for(var m:f.members()){var pending=s.pending.get(ChunkPos.asLong(m.x(),m.z()));if(pending!=null&&pending.id()==m.requestId())synchronized(pending){pending.receiving=true;}}
                }
                var h=a.header;if(a.offset!=f.offset()||h.totalLength()!=f.totalLength()||h.rawLength()!=f.rawLength()||h.compressed()!=f.compressed())throw new IllegalArgumentException("Inconsistent batch fragments");
                System.arraycopy(f.bytes(),0,a.bytes,a.offset,f.bytes().length);a.offset+=f.bytes().length;s.received+=f.bytes().length;
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
                    for(var m:members){
                        if(s.closed||s.epoch!=f.epoch())return;
                        int length=buffer.getInt();byte[] bytes=new byte[length];buffer.get(bytes);
                        decode=DebugLog.start();var column=ColumnCodec.decodeRaw(bytes,31);complete.trace.decode+=DebugLog.end(CLIENT_DECODE,decode);
                        if(column.x()!=m.x()||column.z()!=m.z()||column.version()!=m.version()||column.minimumLevel()!=m.level()||column.minY()!=s.hello.minY()||column.sections().length!=s.hello.maxY()-s.hello.minY())throw new IllegalArgumentException("Batch column metadata mismatch");
                        long p=ChunkPos.asLong(m.x(),m.z());
                        long latest=s.versions.getOrDefault(p,0L);
                        if(session==s&&currentRequest(s,p,m.requestId())&&!s.loadedFull.contains(p)&&column.version()>=latest){long apply=DebugLog.start();var coverage=VoxyBridge.coverage(s.engine);int conflicts=coverage.conflicts(column.x(),column.z(),s.hello.minY(),s.hello.maxY(),column.version());if(conflicts>0){DebugLog.count(CLIENT_OLD_VERSION_REPLACED);if(DebugLog.verbose())DebugLog.log("CLIENT authoritative_replace x={} z={} reply_version={} newer_sections={}",column.x(),column.z(),column.version(),conflicts);}CoarseLodReceiver.receive(s.engine,column,s.level.registryAccess(),true);complete.trace.apply+=DebugLog.end(CLIENT_APPLY,apply);s.applied++;complete.trace.applied++;}
                        if(DebugLog.verbose())DebugLog.log("CLIENT column epoch={} transfer={} x={} z={} request_id={} level={} version={} latest={} raw_bytes={} applied={}",f.epoch(),f.transfer(),m.x(),m.z(),m.requestId(),m.level(),m.version(),latest,m.rawLength(),currentRequest(s,p,m.requestId())&&column.version()>=latest);
                        completed(s,f.epoch(),p,m.requestId(),m.version(),"batch");
                    }
                    result="ok";
                }catch(RuntimeException e){result="failed";if(DebugLog.enabled())DebugLog.log("CLIENT failed epoch={} transfer={} error={}",f.epoch(),f.transfer(),e.toString());failed(s,e);}
                finally{long paced=System.nanoTime();pace(started);complete.trace.applied(started,paced,result);s.memory.addAndGet(-complete.reservation);long receiptQueued=DebugLog.start();Minecraft.getInstance().execute(()->{DebugLog.end(CLIENT_RECEIPT_CONTROL,receiptQueued);if(session==s&&s.epoch==f.epoch()){complete.trace.receipt();Protocol.CHANNEL.sendToServer(new Protocol.Receipt(f.epoch(),f.transfer()));}});}
            });
        }catch(RuntimeException e){failed(s,e);}
    }
    public static void abort(Protocol.Abort a){Session s=session;if(s==null||s.epoch!=a.epoch())return;synchronized(s.assemblies){Assembly removed=s.assemblies.remove(a.transfer());if(removed!=null){s.memory.addAndGet(-removed.reservation);long p=ChunkPos.asLong(removed.header.x(),removed.header.z());if(currentRequest(s,p,removed.header.requestId())){s.pending.remove(p);s.invalid.add(p);}}BatchAssembly batch=s.batchAssemblies.remove(a.transfer());if(batch!=null){s.memory.addAndGet(-batch.reservation);for(var m:batch.header.members()){long p=ChunkPos.asLong(m.x(),m.z());if(currentRequest(s,p,m.requestId())){s.pending.remove(p);s.invalid.add(p);}}}}}
    public static void dirty(Protocol.Dirty d) {
        if(greeting==null||!greeting.dimension().equals(d.dimension()))return;
        Session s=session;long pos=ChunkPos.asLong(d.x(),d.z());if(s==null){initialVersions.merge(pos,d.version(),Math::max);return;}
        s.versions.merge(pos,d.version(),Math::max);s.checked.remove(pos);s.retries.remove(pos);if(!d.vanilla())s.invalid.add(pos);
        // A revision applies to the whole column, even when vanilla changed only one section.
        // Recapture loaded columns after the following vanilla packet has run on the main thread.
        s.fullRetry.add(pos);
        // Keep the existing render hierarchy while requiring a fresh revision for cache hits.
        s.worker.execute(()->{try{VoxyBridge.coverage(s.engine).restrict(d.x(),d.z(),s.hello.minY(),s.hello.maxY(),d.version());}catch(RuntimeException e){failed(s,e);}});
    }
    public static boolean handles(WorldEngine world){Session s=session;return s!=null&&!s.closed&&s.engine==world;}
    public static void lightPending(net.minecraft.client.multiplayer.ClientLevel level,int x,int z){
        if(lightLevel!=level){lightLevel=level;lightReady.clear();}
        lightReady.remove(ChunkPos.asLong(x,z));
    }
    public static void lightApplied(net.minecraft.client.multiplayer.ClientLevel level,int x,int z){
        if(lightLevel!=level){lightLevel=level;lightReady.clear();}
        if(level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false)==null)return;
        long p=ChunkPos.asLong(x,z);lightReady.add(p);
        Session s=session;if(s!=null&&s.level==level&&!s.closed)s.fullRetry.add(p);
    }
    public static void unload(LevelChunk chunk){
        long p=chunk.getPos().toLong();lightReady.remove(p);
        Session s=session;if(s==null||s.closed||chunk.getLevel()!=s.level)return;
        s.loadedFull.remove(p);s.checked.remove(p);s.retries.remove(p);s.fullRetry.remove(p);
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
        if(lightLevel!=s.level||!lightReady.contains(p)){s.fullRetry.add(p);return;}
        long version=s.versions.getOrDefault(p,0L);if(version==0)return;
        long bytes=ChunkSnapshot.RESERVED_BYTES;if(s.snapshotMemory.addAndGet(bytes)>s.snapshotLimit){s.snapshotMemory.addAndGet(-bytes);Minecraft.getInstance().execute(()->{if(session==s)s.fullRetry.add(ChunkPos.asLong(x,z));});return;}s.memory.addAndGet(bytes);
        var biomes=section.getBiomes().recreate();for(int by=0;by<4;by++)for(int bz=0;bz<4;bz++)for(int bx=0;bx<4;bx++)biomes.getAndSetUnchecked(bx,by,bz,section.getBiomes().get(bx,by,bz));
        var snapshot=new ChunkSnapshot(x,y,z,section.getStates().copy(),biomes,block==null?null:block.copy(),sky==null?null:sky.copy());int epoch=s.epoch;
        s.worker.execute(()->{
            long started=System.nanoTime();try{if(!s.closed&&s.epoch==epoch&&version>=s.versions.getOrDefault(ChunkPos.asLong(x,z),0L))VoxyBridge.ingestRemote(world,snapshot,version);}
            catch(RuntimeException e){failed(s,e);}finally{pace(started);s.memory.addAndGet(-bytes);s.snapshotMemory.addAndGet(-bytes);}
        });
    }
    private static void pace(long started){double duty=DistantConfig.RECEIVE_DUTY.get();if(duty<1){long timing=DebugLog.start();java.util.concurrent.locks.LockSupport.parkNanos((long)((System.nanoTime()-started)*(1/duty-1)));DebugLog.end(CLIENT_PACE,timing);}}
    private static void failed(Session s,RuntimeException e){failure=e.toString();LOG.error("Voxy Distant receive failed",e);Minecraft.getInstance().execute(()->{if(session==s){s.radius=0;send(s,List.of());close();greeting=null;}});}
    public static String status(){if(maintenancePaused)return "服务端正在导入远景";Session s=session;return s==null?(failure.isEmpty()?"无服务端远景会话":failure):String.format(Locale.ROOT,"接收 %d 列 · %.1f MiB · 缓冲 %.1f MiB · 请求 %d",s.applied,s.received/1048576.0,s.memory.get()/1048576.0,s.pending.size());}
    public static void close(){Session s=session;session=null;if(s==null)return;if(DebugLog.enabled())DebugLog.log("CLIENT close epoch={} applied={} payload_bytes={} pending={} worker_queue={}",s.epoch,s.applied,s.received,s.pending.size(),s.worker.getQueue().size());synchronized(s.assemblies){s.closed=true;for(var a:s.assemblies.values())s.memory.addAndGet(-a.reservation);s.assemblies.clear();for(var a:s.batchAssemblies.values())s.memory.addAndGet(-a.reservation);s.batchAssemblies.clear();}s.worker.execute(s.engine::releaseRef);s.worker.shutdown();retired.add(s);}
    public static void shutdown(){close();try{for(Session s:retired)while(!s.worker.awaitTermination(1,TimeUnit.SECONDS)){}retired.clear();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    private RemoteClient(){}
}
