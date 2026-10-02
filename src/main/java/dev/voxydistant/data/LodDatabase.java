package dev.voxydistant.data;

import org.rocksdb.*;
import dev.voxydistant.DebugLog;
import dev.voxydistant.config.DistantConfig;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.ByteArrayInputStream;
import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import java.util.*;

/** Owned by background workers. Separate namespace from Voxy's database. */
public final class LodDatabase implements AutoCloseable {
    static { RocksDB.loadLibrary(); }
    private static final String[] OPERATIONS={"get","put","batch","sync"};
    private final Options options;
    private final RocksDB db;
    private final Cache cache;
    private final WriteOptions writes = new WriteOptions();
    private final Path path;
    private final long[] calls=new long[4], nanos=new long[4], maximum=new long[4], bytes=new long[4], errors=new long[4];
    private long reportAt, missing;
    private static final byte[] COUNT="cached-columns".getBytes(StandardCharsets.UTF_8);
    private static final byte[] COUNT_PROGRESS="cached-columns-progress".getBytes(StandardCharsets.UTF_8);
    private volatile long cachedColumns=-1;
    private long counted;
    private byte[] countedThrough;
    public record Metadata(long version,long invalid,int mask) {
        public boolean current(){return mask!=0&&version>=invalid;}
        byte[] encode(){return ByteBuffer.allocate(17).putLong(version).putLong(invalid).put((byte)mask).array();}
        static Metadata decode(byte[] bytes){var b=ByteBuffer.wrap(bytes);return new Metadata(b.getLong(),b.getLong(),b.get()&255);}
    }
    public record CacheStats(long bytes, long columns) {}
    public LodDatabase(Path path, long cacheBytes) {
        this.path=path;
        long started=DebugLog.start();
        RocksDB.loadLibrary();
        cache = new LRUCache(cacheBytes);
        options = new Options().setCreateIfMissing(true).setCompressionType(CompressionType.LZ4_COMPRESSION)
                .setTableFormatConfig(new BlockBasedTableConfig().setBlockCache(cache))
                .setMaxOpenFiles(64).setWriteBufferSize(Math.min(cacheBytes / 2, 16L << 20))
                .setMaxWriteBufferNumber(2).setMaxBackgroundJobs(2);
        try {
            java.nio.file.Files.createDirectories(path);
            db = RocksDB.open(options, path.toString());
            byte[] count=db.get(COUNT),progress=db.get(COUNT_PROGRESS);
            if(count!=null)cachedColumns=ByteBuffer.wrap(count).getLong();
            else if(progress!=null){var b=ByteBuffer.wrap(progress);counted=b.getLong();countedThrough=new byte[b.remaining()];b.get(countedThrough);}
            else try(var iterator=db.newIterator()){
                iterator.seek(new byte[]{1});
                if(!iterator.isValid()||iterator.key()[0]!=1){cachedColumns=0;db.put(writes,COUNT,ByteBuffer.allocate(8).putLong(0).array());}
                iterator.status();
            }
            if(started!=0)DebugLog.log("DATABASE open path={} cache_bytes={} elapsed_ms={}",path,cacheBytes,DebugLog.millis(System.nanoTime()-started));
        } catch (RocksDBException | java.io.IOException e) { throw new IllegalStateException("Cannot open LOD database " + path, e); }
    }
    public byte[] get(byte[] key) {
        long started=DebugLog.start();byte[] value=null;boolean success=false;
        try { value=db.get(key);success=true;return value; } catch (RocksDBException e) { throw new IllegalStateException("LOD read failed", e); }
        finally { diagnostic(0,started,value==null?0:value.length,success,success&&value==null); }
    }
    public boolean contains(byte[] key){
        try{return db.get(key,new byte[0])!=RocksDB.NOT_FOUND;}
        catch(RocksDBException e){throw new IllegalStateException("LOD key lookup failed",e);}
    }
    public void put(byte[] key, byte[] value) {
        long started=DebugLog.start();boolean success=false;
        try { db.put(writes, key, value);success=true; } catch (RocksDBException e) { throw new IllegalStateException("LOD write failed", e); }
        finally { diagnostic(1,started,value.length,success,false); }
    }
    /** One bounded statistics pass, only requested by the settings UI. Writes share this monitor. */
    public synchronized boolean countCachedColumnsStep() {
        if(cachedColumns>=0)return true;
        try(var iterator=db.newIterator()) {
            iterator.seek(countedThrough==null?new byte[]{1}:countedThrough);
            if(countedThrough!=null&&iterator.isValid()&&Arrays.equals(iterator.key(),countedThrough))iterator.next();
            for(int n=0;n<1024&&iterator.isValid()&&iterator.key()[0]==1;n++){
                counted++;countedThrough=iterator.key();iterator.next();
            }
            iterator.status();
            boolean done=!iterator.isValid()||iterator.key()[0]!=1;
            try(var batch=new WriteBatch()){
                if(done){batch.put(COUNT,ByteBuffer.allocate(8).putLong(counted).array());batch.delete(COUNT_PROGRESS);}
                else batch.put(COUNT_PROGRESS,countProgress(counted));
                db.write(writes,batch);
            }
            if(done)cachedColumns=counted;
            return done;
        }catch(RocksDBException e){throw new IllegalStateException("LOD cache count failed",e);}
    }
    private byte[] countProgress(long count){return ByteBuffer.allocate(8+(countedThrough==null?0:countedThrough.length)).putLong(count).put(countedThrough==null?new byte[0]:countedThrough).array();}
    private void addColumn(WriteBatch batch,byte[] key)throws RocksDBException{
        if(cachedColumns>=0)batch.put(COUNT,ByteBuffer.allocate(8).putLong(cachedColumns+1).array());
        else if(countedThrough!=null&&Arrays.compareUnsigned(key,countedThrough)<=0)batch.put(COUNT_PROGRESS,countProgress(counted+1));
    }
    private void columnAdded(byte[] key){if(cachedColumns>=0)cachedColumns++;else if(countedThrough!=null&&Arrays.compareUnsigned(key,countedThrough)<=0)counted++;}
    public CacheStats cacheStats() {
        try(var files=Files.walk(path)) {
            long size=files.filter(Files::isRegularFile).mapToLong(file->{
                try{return Files.size(file);}catch(IOException e){throw new UncheckedIOException(e);}
            }).sum();
            return new CacheStats(size,cachedColumns);
        }catch(IOException e){throw new UncheckedIOException("LOD cache size failed",e);}
    }
    public synchronized void importColumn(byte[] key,byte[] value,long version,int mask) {
        // Import owns the terrain lane. A newer live invalidation stays dirty above this snapshot.
        var before=metadata(key);boolean absent=before.mask()==0;
        try(var batch=new WriteBatch()){
            byte[] invalid=db.get(keyWithKind(key,2));
            batch.put(key,value);batch.put(metadataKey(key),new Metadata(version,invalid==null?0:ByteBuffer.wrap(invalid).getLong(),mask).encode());
            if(absent)addColumn(batch,key);db.write(writes,batch);if(absent)columnAdded(key);
        }catch(RocksDBException e){throw new IllegalStateException("LOD import write failed",e);}
    }

    /** Region-major metadata keys make a directory one contiguous RocksDB range. */
    private static byte[] metadataKey(byte[] column){
        int end=column.length-8;long pos=ByteBuffer.wrap(column,end,8).getLong();int x=(int)pos,z=(int)(pos>>>32);
        return ByteBuffer.allocate(end+10).put((byte)5).put(column,1,end-1).putInt(x>>5).putInt(z>>5).putShort((short)((x&31)|((z&31)<<5))).array();
    }
    private static byte[] regionKey(String dimension,int rx,int rz,int kind){
        byte[] name=dimension.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(name.length+10).put((byte)kind).put(name).put((byte)0).putInt(rx).putInt(rz).array();
    }
    public synchronized Metadata metadata(byte[] column){
        byte[] existing=get(metadataKey(column));if(existing!=null)return Metadata.decode(existing);
        Metadata result=legacyMetadata(column);put(metadataKey(column),result.encode());return result;
    }
    private Metadata legacyMetadata(byte[] column){
        byte[] stored=get(column),invalid=get(keyWithKind(column,2));long revision=invalid==null?0:ByteBuffer.wrap(invalid).getLong();
        if(stored==null)return new Metadata(0,revision,0);
        try{
            byte[] header;
            if(stored[0]!=0)try(var stream=new ZstdInputStreamNoFinalizer(new ByteArrayInputStream(stored,5,stored.length-5))){header=stream.readNBytes(25);}
            else header=Arrays.copyOfRange(stored,5,30);
            if(header.length!=25)throw new IllegalArgumentException("Truncated LOD header");
            var b=ByteBuffer.wrap(header);if(b.getInt()!=1)throw new IllegalArgumentException("Unknown LOD schema");
            return new Metadata(b.getLong(16),revision,b.get(24)&31);
        }catch(IOException e){throw new UncheckedIOException("LOD header read failed",e);}
    }
    /** Persist migration progress; a region takes at most 64 legacy header reads per turn. */
    public synchronized boolean prepareRegion(String dimension,int rx,int rz){
        byte[] progressKey=regionKey(dimension,rx,rz,6),progress=get(progressKey);int start=progress==null?0:ByteBuffer.wrap(progress).getInt();
        if(start==1024)return true;
        int end=Math.min(1024,start+64);
        try(var batch=new WriteBatch()){
            for(int i=start;i<end;i++){
                int x=rx*32+(i&31),z=rz*32+(i>>5);byte[] column=key(dimension,(x&0xffffffffL)|((long)z<<32),1),meta=metadataKey(column);
                if(db.get(meta)==null)batch.put(meta,legacyMetadata(column).encode());
            }
            batch.put(progressKey,ByteBuffer.allocate(4).putInt(end).array());db.write(writes,batch);
        }catch(RocksDBException e){throw new IllegalStateException("LOD directory migration failed",e);}
        return end==1024;
    }
    public synchronized Metadata[] region(String dimension,int rx,int rz){
        var result=new Metadata[1024];byte[] prefix=regionKey(dimension,rx,rz,5);
        try(var iterator=db.newIterator()){
            iterator.seek(prefix);
            while(iterator.isValid()){
                byte[] key=iterator.key();if(key.length!=prefix.length+2||!Arrays.equals(prefix,Arrays.copyOf(key,prefix.length)))break;
                result[Short.toUnsignedInt(ByteBuffer.wrap(key,key.length-2,2).getShort())]=Metadata.decode(iterator.value());iterator.next();
            }
            iterator.status();
        }catch(RocksDBException e){throw new IllegalStateException("LOD directory read failed",e);}
        return result;
    }
    public void batch(Map<byte[], byte[]> entries) {
        long started=DebugLog.start(),size=0;boolean success=false;
        try (var batch = new WriteBatch()) {
            for (var e : entries.entrySet()) {batch.put(e.getKey(), e.getValue());if(started!=0)size+=e.getValue().length;}
            db.write(writes, batch);success=true;
        } catch (RocksDBException e) { throw new IllegalStateException("LOD batch failed", e); }
        finally { diagnostic(2,started,size,success,false); }
    }
    public record Pending(String dimension, long position, int kind, long version, long retryAt) {}
    public record PendingPage(List<Pending> entries, byte[] last, boolean exhausted) {}
    /** Kinds 3 and 4 are new background refresh and missing-cache jobs; old invalidations are never enumerated. */
    public synchronized PendingPage pending(int kind, byte[] after, int limit) {
        var entries = new ArrayList<Pending>(limit);
        byte[] last = after;
        try (var iterator = db.newIterator()) {
            if (after == null) iterator.seek(new byte[]{(byte)kind});
            else {
                iterator.seek(after);
                if (iterator.isValid() && Arrays.equals(iterator.key(), after)) iterator.next();
            }
            while (iterator.isValid() && iterator.key()[0] == (byte)kind && entries.size() < limit) {
                byte[] key = iterator.key(), value = iterator.value();
                int end = key.length - Long.BYTES;
                if (end > 2 && key[end - 1] == 0 && value.length == 16) {
                    String dimension = new String(key, 1, end - 2, StandardCharsets.UTF_8);
                    entries.add(new Pending(dimension, ByteBuffer.wrap(key, end, Long.BYTES).getLong(), kind,
                            ByteBuffer.wrap(value).getLong(), ByteBuffer.wrap(value, Long.BYTES, Long.BYTES).getLong()));
                }
                last = key;
                iterator.next();
            }
            iterator.status();
            return new PendingPage(List.copyOf(entries), last, !iterator.isValid() || iterator.key()[0] != (byte)kind);
        } catch (RocksDBException e) { throw new IllegalStateException("LOD pending scan failed", e); }
    }
    public static byte[] pendingValue(long version, long retryAt) {
        return ByteBuffer.allocate(16).putLong(version).putLong(retryAt).array();
    }
    public synchronized void invalidationsAndMissing(Map<byte[],byte[]> entries, Collection<byte[]> checks) {
        try (var batch=new WriteBatch()) {
            for(var entry:entries.entrySet()){
                byte[] key=entry.getKey();batch.put(key,entry.getValue());
                if(key[0]==2){byte[] metaKey=metadataKey(key),meta=db.get(metaKey);if(meta!=null){var before=Metadata.decode(meta);batch.put(metaKey,new Metadata(before.version,ByteBuffer.wrap(entry.getValue()).getLong(),before.mask).encode());}}
            }
            for(byte[] key:checks)if(db.get(key)==null&&db.get(keyWithKind(key,3))==null
                    &&db.get(keyWithKind(key,1))==null)batch.put(key,pendingValue(0,0));
            db.write(writes,batch);
        }catch(RocksDBException ex){throw new IllegalStateException("LOD invalidation write failed",ex);}
    }
    public synchronized byte[] pendingValue(byte[] key) { return get(key); }
    public synchronized void retryPending(byte[] key, long version, long retryAt) {
        byte[] current = get(key);
        if (current != null && ByteBuffer.wrap(current).getLong() == version) put(key, pendingValue(version, retryAt));
    }
    public synchronized void resolvePending(byte[] key, long coveredVersion) {
        try (var batch = new WriteBatch()) {
            for (int kind = 3; kind <= 4; kind++) {
                byte[] pendingKey = keyWithKind(key, kind), value = db.get(pendingKey);
                if (value != null && ByteBuffer.wrap(value).getLong() <= coveredVersion) batch.delete(pendingKey);
            }
            db.write(writes, batch);
        } catch (RocksDBException e) { throw new IllegalStateException("LOD pending completion failed", e); }
    }
    public synchronized boolean storeColumn(byte[] key, byte[] value, long version,int mask) {
        try (var batch = new WriteBatch()) {
            byte[] invalid=db.get(keyWithKind(key,2));
            if(invalid!=null && ByteBuffer.wrap(invalid).getLong()>version)return false;
            Metadata before=metadata(key);boolean absent=before.mask()==0;
            if(before.version()>version)return false;
            batch.put(key, value);
            batch.put(metadataKey(key),new Metadata(version,invalid==null?0:ByteBuffer.wrap(invalid).getLong(),mask).encode());
            if(absent)addColumn(batch,key);
            for (int kind = 3; kind <= 4; kind++) {
                byte[] pendingKey = keyWithKind(key, kind), pending = db.get(pendingKey);
                if (pending != null && ByteBuffer.wrap(pending).getLong() <= version) batch.delete(pendingKey);
            }
            db.write(writes, batch);
            if(absent)columnAdded(key);
            return true;
        } catch (RocksDBException e) { throw new IllegalStateException("LOD column write failed", e); }
    }
    private static byte[] keyWithKind(byte[] key, int kind) {
        byte[] result = key.clone(); result[0] = (byte)kind; return result;
    }
    public void sync() {
        long started=DebugLog.start();boolean success=false;
        try { db.flushWal(true);success=true; } catch (RocksDBException e) { throw new IllegalStateException("LOD WAL flush failed", e); }
        finally { diagnostic(3,started,0,success,false); }
    }
    private void diagnostic(int operation,long started,long size,boolean success,boolean absent){
        if(started==0)return;
        long now=System.nanoTime(),elapsed=now-started;
        synchronized(calls){
            if(reportAt==0)reportAt=started;
            calls[operation]++;nanos[operation]+=elapsed;maximum[operation]=Math.max(maximum[operation],elapsed);bytes[operation]+=size;
            if(!success)errors[operation]++;if(absent)missing++;
            if(!success||DebugLog.verbose()&&elapsed>=25_000_000L)DebugLog.log("DATABASE operation path={} op={} bytes={} success={} missing={} elapsed_ms={}",path,OPERATIONS[operation],size,success,absent,DebugLog.millis(elapsed));
            if(now-reportAt>=java.util.concurrent.TimeUnit.SECONDS.toNanos(DistantConfig.DEBUG_INTERVAL.get())){
                report(now);
            }
        }
    }
    private void report(long now){
        for(int i=0;i<calls.length;i++)if(calls[i]>0)DebugLog.log("DATABASE interval path={} op={} interval_ms={} count={} bytes={} errors={} missing={} total_ms={} avg_ms={} max_ms={}",path,OPERATIONS[i],DebugLog.millis(now-reportAt),calls[i],bytes[i],errors[i],i==0?missing:0,DebugLog.millis(nanos[i]),DebugLog.millis(nanos[i])/calls[i],DebugLog.millis(maximum[i]));
        Arrays.fill(calls,0);Arrays.fill(bytes,0);Arrays.fill(errors,0);Arrays.fill(nanos,0);Arrays.fill(maximum,0);missing=0;reportAt=now;
    }
    public UUID worldId() {
        byte[] key = "world-id".getBytes(StandardCharsets.UTF_8), value = get(key);
        if (value != null) { var b = ByteBuffer.wrap(value); return new UUID(b.getLong(), b.getLong()); }
        UUID id = UUID.randomUUID(); put(key, ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array());
        sync(); return id;
    }
    public static byte[] key(String dimension, long position, int kind) {
        byte[] name = dimension.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(name.length + 10).put((byte) kind).put(name).put((byte) 0).putLong(position).array();
    }
    public static byte[] pack(ColumnCodec.Encoded encoded) {
        return ByteBuffer.allocate(encoded.bytes().length + 5).put((byte) (encoded.compressed() ? 1 : 0))
                .putInt(encoded.rawLength()).put(encoded.bytes()).array();
    }
    public static ColumnCodec.Encoded unpack(byte[] value) {
        var b = ByteBuffer.wrap(value); boolean compressed = b.get() != 0; int size = b.getInt();
        byte[] bytes = new byte[b.remaining()]; b.get(bytes); return new ColumnCodec.Encoded(compressed, size, bytes);
    }
    @Override public void close() { try { sync();if(DebugLog.enabled())synchronized(calls){report(System.nanoTime());} } finally { db.close(); writes.close(); options.close(); cache.close(); } }
}
