package dev.voxydistant.compat;

import dev.voxydistant.data.LodDatabase;
import dev.voxydistant.DebugLog;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicIntegerArray;

/** 32³-section pages. Render reads precomputed counters; disk access belongs to workers. */
public final class CoverageStore {
    // Low three bits describe stored geometry; pending refresh is separate from renderability.
    private static final int STALE_MISSING=5|8;
    private static final int REFRESH_PENDING=16;
    private final Path path;
    private final CompletableFuture<LodDatabase> database;
    private volatile it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Page> pages = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private final LinkedHashMap<Long, Page> lru = new LinkedHashMap<>(16,.75f,true);
    private final Set<Long> unsafePages = new HashSet<>();
    private final Set<Long> changedPages = new HashSet<>();
    private final Map<Long,Set<Long>> pendingSaves = new HashMap<>();
    private long meshSequence;
    private long lastCheckpoint;
    private int writeDepth;
    private final Map<me.cortex.voxy.common.world.WorldSection,Integer> deferredUpdates=new LinkedHashMap<>();
    private volatile long revision;
    private volatile boolean remote;
    private int minY=-4,maxY=20,centerX,centerZ,radius,maxPages=192;
    private static final class Page {
        final byte[] minimum=new byte[32768];
        final long[] versions=new long[32768];
        final AtomicIntegerArray[] counts=new AtomicIntegerArray[4];
        final AtomicIntegerArray[] blockers=new AtomicIntegerArray[4];
        final AtomicIntegerArray[] coarse=new AtomicIntegerArray[4];
        volatile boolean restricted;
        volatile boolean unsafe;
        volatile long explorationRevision;
        final java.util.concurrent.atomic.AtomicLongArray meshes=new java.util.concurrent.atomic.AtomicLongArray(4681);
        final ConcurrentHashMap<Integer,RenderMask> renderMasks=new ConcurrentHashMap<>();
        Page(){Arrays.fill(minimum,(byte)5);for(int l=1;l<=4;l++){counts[l-1]=new AtomicIntegerArray(32768>>(3*(l+1)));blockers[l-1]=new AtomicIntegerArray(32768>>(3*(l+1)));coarse[l-1]=new AtomicIntegerArray(32768>>(3*(l+1)));}}
    }
    private record RenderMask(long generation,byte children){}
    public CoverageStore(Path path) {
        this.path=path;
        database=CompletableFuture.supplyAsync(()->{var db=new LodDatabase(path.resolveSibling(path.getFileName()+".rocksdb"),8L<<20);migrate(db);return db;});
    }
    public synchronized void remote(int min,int max,int memoryMiB){minY=min;maxY=max;maxPages=Math.max(8,(int)(memoryMiB*1048576L/350000));remote=true;}
    public synchronized void active(int x,int z,int r){centerX=x;centerZ=z;radius=r;}
    public boolean remote(){return remote;}
    public long revision(){return revision;}
    private static int meshIndex(long node){int l=(int)(node>>>60),w=16>>l,offset=(32768-(32768>>(3*l)))/7;return offset+((ny(node)&(w-1))*w+(nz(node)&(w-1)))*w+(nx(node)&(w-1));}
    public long meshGeneration(long node){int l=(int)(node>>>60);Page p=pages.get(pageKey(nx(node)<<(l+1),ny(node)<<(l+1),nz(node)<<(l+1)));return p==null?0:p.meshes.get(meshIndex(node));}
    /** Mesh workers must restore persisted split restrictions before publishing their first mesh. */
    public void prepareMesh(long node){
        int l=(int)(node>>>60);if(l==0)return;
        int x=nx(node)<<(l+1),y=ny(node)<<(l+1),z=nz(node)<<(l+1);
        if(pages.containsKey(pageKey(x,y,z)))return;
        long started=DebugLog.start();
        try{synchronized(this){page(x,y,z);}}finally{DebugLog.end(DebugLog.Metric.CLIENT_COVERAGE_MESH_LOAD,started);}
        if(DebugLog.mesh())DebugLog.log("CLIENT mesh_coverage_load node={} level={} page={} can_split={} elapsed_ms={}",node,l,pageKey(x,y,z),canSplit(node),started==0?0:DebugLog.millis(System.nanoTime()-started));
    }
    public void meshBegin(int x,int z,int min,int max){if(writeDepth++==0)meshToggle(x,z,min,max);}
    public void meshEnd(int x,int z,int min,int max){if(--writeDepth==0)meshToggle(x,z,min,max);}
    public synchronized boolean deferUpdate(me.cortex.voxy.common.world.WorldSection section,int flags,int neighbors){
        if(writeDepth==0)return false;
        if(!deferredUpdates.containsKey(section))section.acquire();
        deferredUpdates.merge(section,flags|(neighbors<<8),(a,b)->a|b);return true;
    }
    public void meshEnd(me.cortex.voxy.common.world.WorldEngine world,int x,int z,int min,int max){
        meshEnd(x,z,min,max);
        if(writeDepth!=0)return;
        try{for(var entry:deferredUpdates.entrySet())world.markDirty(entry.getKey(),entry.getValue()&255,entry.getValue()>>>8);}
        finally{for(var section:deferredUpdates.keySet())section.release();deferredUpdates.clear();}
    }
    private void meshToggle(int x,int z,int min,int max){for(int l=0;l<=4;l++)for(int y=min>>(l+1);y<=((max-1)>>(l+1));y++){long k=node(l,x>>(l+1),y,z>>(l+1));Page p=page(x,y<<(l+1),z);int i=meshIndex(k);p.meshes.set(i,(++meshSequence<<1)|((p.meshes.get(i)&1)^1));}}
    public static long node(int l,int x,int y,int z){return ((long)l<<60)|((long)(y&255)<<52)|((long)(z&0xffffff)<<28)|((long)(x&0xffffff)<<4);}
    public static long group(int x,int y,int z){return node(1,x>>2,y>>2,z>>2);}
    public static long bit(int x,int y,int z){return 1L<<((x&3)|((z&3)<<2)|((y&3)<<4));}
    private static long pageKey(int x,int y,int z){return node(4,x>>5,y>>5,z>>5);}
    private static int index(int x,int y,int z){return (x&31)|((z&31)<<5)|((y&31)<<10);}
    private static int nx(long k){return (int)((k<<36)>>40);}
    private static int ny(long k){return (int)((k<<4)>>56);}
    private static int nz(long k){return (int)((k<<12)>>40);}
    private static byte[] key(long id){return ByteBuffer.allocate(9).put((byte)1).putLong(id).array();}
    private static byte[] guard(long id){return ByteBuffer.allocate(9).put((byte)3).putLong(id).array();}
    private Page page(int x,int y,int z) {
        long id=pageKey(x,y,z);Page p=lru.get(id);if(p!=null)return p;
        while(lru.size()>=maxPages) {
            var it=lru.entrySet().iterator();boolean removed=false;
            while(it.hasNext()) {
                var e=it.next();int px=nx(e.getKey())*32,pz=nz(e.getKey())*32;
                long dx=Math.max(Math.max(px-centerX,centerX-(px+31)),0),dz=Math.max(Math.max(pz-centerZ,centerZ-(pz+31)),0);
                if(remote&&dx*dx+dz*dz<=(long)radius*radius)continue;
                if(changedPages.contains(e.getKey()))continue;
                var next=new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(pages);next.remove(e.getKey().longValue());pages=next;it.remove();removed=true;break;
            }
            if(!removed)throw new IllegalStateException("活动覆盖索引超过预算，请减小范围或增加 indexMemoryMiB");
        }
        p=new Page();byte[] bytes=database.join().get(key(id));
        if(bytes!=null){var b=ByteBuffer.wrap(bytes);p.restricted=b.get()!=0;for(int i=0;i<32768;i++){p.minimum[i]=b.get();p.versions[i]=b.getLong();if(p.minimum[i]==5&&p.versions[i]!=0)p.minimum[i]=STALE_MISSING;}}
        byte[] unsafe=database.join().get(guard(id));if(unsafe!=null&&unsafe[0]!=0){Arrays.fill(p.minimum,(byte)STALE_MISSING);p.restricted=true;p.unsafe=true;}
        if(remote)for(int cy=0;cy<32;cy++)if((y&~31)+cy<minY||(y&~31)+cy>=maxY)Arrays.fill(p.minimum,cy*1024,(cy+1)*1024,(byte)32);
        for(int cy=0;cy<32;cy++)for(int cz=0;cz<32;cz++)for(int cx=0;cx<32;cx++) {
            int i=index(cx,cy,cz),value=p.minimum[i]&255,m=value&7;
            for(int l=1;l<=4;l++){int c=counter(cx,cy,cz,l);if(m<l)p.counts[l-1].incrementAndGet(c);if(blocksSplit(value,l))p.blockers[l-1].incrementAndGet(c);if(coarse(value,l))p.coarse[l-1].incrementAndGet(c);}
        }
        lru.put(id,p);var next=new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(pages);next.put(id,p);pages=next;
        DebugLog.count(bytes==null?DebugLog.Metric.CLIENT_COVERAGE_NEW_PAGE:DebugLog.Metric.CLIENT_COVERAGE_DISK_PAGE);
        if(DebugLog.mesh())DebugLog.log("CLIENT coverage_page page={} x={} y={} z={} source={} unsafe={} restricted={}",id,x&~31,y&~31,z&~31,bytes==null?"missing":"disk",unsafe!=null&&unsafe[0]!=0,p.restricted);
        return p;
    }
    private static int counter(int x,int y,int z,int l){int w=32>>(l+1);return (((y&31)>>(l+1))*w+((z&31)>>(l+1)))*w+((x&31)>>(l+1));}
    // Untouched space has no previous terrain to lose; coarse or invalidated data does.
    private static boolean blocksSplit(int value,int level){return (value&7)>=level&&value!=5;}
    private static boolean coarse(int value,int level){return (value&7)>=level&&(value&7)<=4;}
    private static void set(Page p,int x,int y,int z,int value,long version) {
        int i=index(x,y,z),before=p.minimum[i]&255,old=before&7,m=value&7;p.minimum[i]=(byte)value;p.versions[i]=version;
        for(int l=1;l<=4;l++){
            int c=counter(x,y,z,l);if((old<l)!=(m<l))p.counts[l-1].addAndGet(c,m<l?1:-1);
            boolean blocked=blocksSplit(value,l);if(blocksSplit(before,l)!=blocked)p.blockers[l-1].addAndGet(c,blocked?1:-1);
            if(coarse(before,l)!=coarse(value,l))p.coarse[l-1].addAndGet(c,coarse(value,l)?1:-1);
        }
    }
    public boolean canSplit(long k) {
        int l=(int)(k>>>60);if(l==0)return false;
        int x=nx(k)<<(l+1),y=ny(k)<<(l+1),z=nz(k)<<(l+1);Page p=pages.get(pageKey(x,y,z));
        if(p==null)return !remote;if(!remote&&!p.restricted)return true;
        return p.blockers[l-1].get(counter(x,y,z,l))==0;
    }
    /** A stale Voxy snapshot blocks only until its cached child sections are proven to exist. */
    public boolean blockedOnlyByStale(long k){
        int l=(int)(k>>>60);if(l==0)return false;
        int x=nx(k)<<(l+1),y=ny(k)<<(l+1),z=nz(k)<<(l+1);Page p=pages.get(pageKey(x,y,z));
        if(p==null||p.unsafe)return false;
        int c=counter(x,y,z,l),blocked=p.blockers[l-1].get(c);
        return blocked>0&&p.coarse[l-1].get(c)==0;
    }
    public int missingBlockers(long k){
        int l=(int)(k>>>60);if(l==0)return 0;
        int x=nx(k)<<(l+1),y=ny(k)<<(l+1),z=nz(k)<<(l+1);Page p=pages.get(pageKey(x,y,z));
        return p==null?0:p.blockers[l-1].get(counter(x,y,z,l));
    }
    public void acceptedMeshChildren(long node,long generation,byte children){
        int l=(int)(node>>>60),x=nx(node)<<(l+1),y=ny(node)<<(l+1),z=nz(node)<<(l+1);
        Page p=pages.get(pageKey(x,y,z));
        if(p!=null&&p.meshes.get(meshIndex(node))==generation&&(generation&1)==0)p.renderMasks.put(meshIndex(node),new RenderMask(generation,children));
    }
    public int cachedMeshChildren(long node){
        int l=(int)(node>>>60),x=nx(node)<<(l+1),y=ny(node)<<(l+1),z=nz(node)<<(l+1);
        Page p=pages.get(pageKey(x,y,z));if(p==null)return -1;
        var mask=p.renderMasks.get(meshIndex(node));
        return mask!=null&&mask.generation==p.meshes.get(meshIndex(node))?mask.children&255:-1;
    }
    public synchronized boolean hasFull(int x,int y,int z){return (page(x,y,z).minimum[index(x,y,z)]&(7|REFRESH_PENDING))==0;}
    public boolean hasColumn(int x,int z,int min,int max) {
        for(int y=min;y<max;y++){Page p=pages.get(pageKey(x,y,z));if(p==null||(p.minimum[index(x,y,z)]&(7|REFRESH_PENDING))!=0)return false;}return true;
    }
    public synchronized boolean changedSince(int x,int y,int z,long before){Page p=pages.get(pageKey(x,y,z));return p!=null&&p.explorationRevision>before;}
    public synchronized void markFull(int x,int y,int z,boolean background){Page p=page(x,y,z);set(p,x,y,z,0,0);if(!background)p.explorationRevision=++revision;}
    public synchronized boolean accepts(int x,int y,int z,long version,int level) {
        Page p=page(x,y,z);int i=index(x,y,z);return version>p.versions[i]||version==p.versions[i]&&((p.minimum[i]&REFRESH_PENDING)!=0||level<=(p.minimum[i]&7));
    }
    /** Inspect actual sections; a whole-column Stamp loses mixed-version detail. */
    public synchronized int conflicts(int x,int z,int min,int max,long version){int count=0;for(int y=min;y<max;y++){Page p=page(x,y,z);if(p.versions[index(x,y,z)]>version)count++;}return count;}
    /** Persist restrictions before voxel mutation. Completion waits for confirmed Voxy shutdown saves. */
    public synchronized void restrict(int x,int z,int min,int max,long version) {
        restrict(x,z,min,max,version,false);
    }
    /** A matched server data reply may replace an older-world column even when its revision is lower. */
    public synchronized void restrict(int x,int z,int min,int max,long version,boolean authoritative) {
        beginWrite(x,z,min,max);
        meshBegin(x,z,min,max);
        try{
        for(int y=min;y<max;y++){Page p=page(x,y,z);int i=index(x,y,z);if(version==p.versions[i]||!authoritative&&version<p.versions[i])continue;int previous=p.minimum[i]&255;set(p,x,y,z,(previous&7)<5?previous|REFRESH_PENDING:previous,version);p.restricted=true;}
        }finally{meshEnd(x,z,min,max);}
    }
    public synchronized void beginWrite(int x,int z,int min,int max){
        var batch=new HashMap<byte[],byte[]>();
        for(int y=min;y<max;y++){page(x,y,z);long id=pageKey(x,y,z);changedPages.add(id);if(unsafePages.add(id))batch.put(guard(id),new byte[]{1});}
        if(!batch.isEmpty()){database.join().batch(batch);database.join().sync();}
    }
    public synchronized void received(int x,int y,int z,long version,int level,boolean air){Page p=page(x,y,z);set(p,x,y,z,level|(air?32:0),version);p.restricted=true;}
    public synchronized void awaitingSave(long node){int l=(int)(node>>>60);long id=pageKey(nx(node)<<(l+1),ny(node)<<(l+1),nz(node)<<(l+1));pendingSaves.computeIfAbsent(id,k->new HashSet<>()).add(node);}
    public synchronized boolean saved(long node,long generation){long current=meshGeneration(node);if(generation!=current){if(DebugLog.verbose())DebugLog.log("CLIENT save_stale node={} saved_generation={} current_generation={}",node,generation,current);return false;}int l=(int)(node>>>60);var pending=pendingSaves.get(pageKey(nx(node)<<(l+1),ny(node)<<(l+1),nz(node)<<(l+1)));if(pending!=null)pending.remove(node);return true;}
    public synchronized void checkpointIfDue(Runnable flushVoxels){long now=System.nanoTime();if(now-lastCheckpoint<1_000_000_000L)return;lastCheckpoint=now;checkpoint(flushVoxels);}
    /** Called by the receiver worker; save callbacks use this same monitor. */
    public synchronized void checkpoint(Runnable flushVoxels){
        var ready=new ArrayList<Long>();
        for(long id:changedPages){var pending=pendingSaves.get(id);if(pending==null||pending.isEmpty())ready.add(id);}
        if(ready.isEmpty())return;
        long started=DebugLog.start();
        try{flushVoxels.run();}finally{DebugLog.end(DebugLog.Metric.CLIENT_STORAGE_FLUSH,started);}
        var batch=new HashMap<byte[],byte[]>();var db=database.join();
        for(long id:ready){batch.put(key(id),encode(lru.get(id)));batch.put(guard(id),new byte[]{0});if(batch.size()==32){db.batch(batch);batch.clear();}}
        if(!batch.isEmpty())db.batch(batch);db.sync();
        for(long id:ready){changedPages.remove(id);unsafePages.remove(id);pendingSaves.remove(id);}
    }
    public record Stamp(long version,int level){}
    public synchronized Stamp column(int x,int z,int min,int max) {
        long version=-1;int level=0;
        for(int y=min;y<max;y++){Page p=page(x,y,z);int i=index(x,y,z);if(version==-1)version=p.versions[i];else if(version!=p.versions[i])return new Stamp(0,5);level=Math.max(level,(p.minimum[i]&REFRESH_PENDING)!=0?5:p.minimum[i]&7);}
        return new Stamp(Math.max(0,version),level);
    }
    public synchronized boolean fullGroup(long k){int x=nx(k)*4,y=ny(k)*4,z=nz(k)*4;Page p=page(x,y,z);return p.counts[0].get(counter(x,y,z,1))==64;}
    public synchronized boolean isCoarse(long k){return page(nx(k)*4,ny(k)*4,nz(k)*4).restricted;}
    private static byte[] encode(Page p){var b=ByteBuffer.allocate(1+32768*9).put((byte)(p.restricted?1:0));for(int i=0;i<32768;i++)b.put(p.minimum[i]).putLong(p.versions[i]);return b.array();}
    public synchronized void saveAfterWorldClosed(){var db=database.join();var batch=new HashMap<byte[],byte[]>();for(var e:lru.entrySet()){var pending=pendingSaves.get(e.getKey());if(pending!=null&&!pending.isEmpty()){if(DebugLog.enabled())for(long node:pending)DebugLog.log("CLIENT close_unconfirmed page={} node={} current_generation={}",e.getKey(),node,meshGeneration(node));continue;}batch.put(key(e.getKey()),encode(e.getValue()));batch.put(guard(e.getKey()),new byte[]{0});if(batch.size()==32){db.batch(batch);batch.clear();}}if(!batch.isEmpty())db.batch(batch);db.close();pages=new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();lru.clear();}
    public synchronized void closeUnconfirmed(){database.join().close();pages=new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();lru.clear();}
    private void migrate(LodDatabase db) {
        if(!Files.exists(path)||db.get(new byte[]{2})!=null)return;
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if(in.readInt()!=0x56444331)throw new IOException("Unknown coverage format");
            var old=new HashMap<Long,Page>();int count=in.readInt();
            for(int n=0;n<count;n++){long k=in.readLong(),bits=in.readLong();int x=nx(k)*4,y=ny(k)*4,z=nz(k)*4;Page p=old.computeIfAbsent(pageKey(x,y,z),v->new Page());for(int i=0;i<64;i++)if((bits&(1L<<i))!=0)p.minimum[index(x+(i&3),y+(i>>4),z+((i>>2)&3))]=0;}
            count=in.readInt();for(int n=0;n<count;n++){long k=in.readLong();in.readByte();old.computeIfAbsent(pageKey(nx(k)*4,ny(k)*4,nz(k)*4),v->new Page()).restricted=true;}
            for(var e:old.entrySet())db.put(key(e.getKey()),encode(e.getValue()));db.put(new byte[]{2},new byte[]{1});db.sync();
        }catch(IOException e){throw new UncheckedIOException("Coverage migration failed",e);}
    }
}
