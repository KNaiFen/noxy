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
    private volatile long cachedColumns;
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
            if(started!=0)DebugLog.log("DATABASE open path={} cache_bytes={} elapsed_ms={}",path,cacheBytes,DebugLog.millis(System.nanoTime()-started));
        } catch (RocksDBException | java.io.IOException e) { throw new IllegalStateException("Cannot open LOD database " + path, e); }
    }
    public byte[] get(byte[] key) {
        long started=DebugLog.start();byte[] value=null;boolean success=false;
        try { value=db.get(key);success=true;return value; } catch (RocksDBException e) { throw new IllegalStateException("LOD read failed", e); }
        finally { diagnostic(0,started,value==null?0:value.length,success,success&&value==null); }
    }
    public void put(byte[] key, byte[] value) {
        long started=DebugLog.start();boolean success=false;
        try { db.put(writes, key, value);success=true; } catch (RocksDBException e) { throw new IllegalStateException("LOD write failed", e); }
        finally { diagnostic(1,started,value.length,success,false); }
    }
    /** Count stored LOD columns (kind 1), excluding invalidations and pending work. */
    public synchronized void countCachedColumns() {
        long count=0;
        try(var iterator=db.newIterator()) {
            iterator.seek(new byte[]{1});
            while(iterator.isValid()&&iterator.key()[0]==1){count++;iterator.next();}
            iterator.status();
        }catch(RocksDBException e){throw new IllegalStateException("LOD cache count failed",e);}
        cachedColumns=count;
    }
    public CacheStats cacheStats() {
        try(var files=Files.walk(path)) {
            long size=files.filter(Files::isRegularFile).mapToLong(file->{
                try{return Files.size(file);}catch(IOException e){throw new UncheckedIOException(e);}
            }).sum();
            return new CacheStats(size,cachedColumns);
        }catch(IOException e){throw new UncheckedIOException("LOD cache size failed",e);}
    }
    public synchronized void importColumn(byte[] key,byte[] value) {
        boolean absent=get(key)==null;
        put(key,value);
        if(absent)cachedColumns++;
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
            for(var entry:entries.entrySet())batch.put(entry.getKey(),entry.getValue());
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
    public synchronized boolean storeColumn(byte[] key, byte[] value, long version) {
        try (var batch = new WriteBatch()) {
            byte[] invalid=db.get(keyWithKind(key,2));
            if(invalid!=null && ByteBuffer.wrap(invalid).getLong()>version)return false;
            byte[] stored=db.get(key);
            if(stored!=null && storedVersion(stored)>version)return false;
            batch.put(key, value);
            for (int kind = 3; kind <= 4; kind++) {
                byte[] pendingKey = keyWithKind(key, kind), pending = db.get(pendingKey);
                if (pending != null && ByteBuffer.wrap(pending).getLong() <= version) batch.delete(pendingKey);
            }
            db.write(writes, batch);
            if(stored==null)cachedColumns++;
            return true;
        } catch (RocksDBException e) { throw new IllegalStateException("LOD column write failed", e); }
    }
    private static long storedVersion(byte[] stored) {
        return ColumnCodec.decodeLevels(unpack(stored),0).version();
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
