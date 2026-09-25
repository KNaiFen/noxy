package dev.voxydistant;

import com.mojang.logging.LogUtils;
import dev.voxydistant.config.DistantConfig;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Opt-in diagnostics; counters are cumulative for this JVM, timings are wall time. */
public final class DebugLog {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    public enum Metric {
        CLIENT_INDEX, CLIENT_QUEUE, CLIENT_DECODE, CLIENT_APPLY, CLIENT_CHECKPOINT, CLIENT_RECEIPT_CONTROL,
        CLIENT_MESH_ACCEPT, CLIENT_MESH_REJECT, CLIENT_MESH_REBUILD, CLIENT_MESH_RECOVERED, CLIENT_REFINE, CLIENT_NO_PROGRESS, CLIENT_OLD_VERSION_REPLACED,
        CLIENT_COVERAGE_MESH_LOAD, CLIENT_COVERAGE_DISK_PAGE, CLIENT_COVERAGE_NEW_PAGE,
        CLIENT_MAP, CLIENT_COVERAGE_LOCK, CLIENT_VOXELS, CLIENT_PUBLISH_SAVE,
        CLIENT_REQUEST_WINDOW, CLIENT_REQUEST_MEMORY, CLIENT_REQUEST_BACKLOG, CLIENT_REQUEST_BATCH, CLIENT_REQUEST_IDLE, CLIENT_REQUEST_CONTROL,
        CLIENT_PACE, CLIENT_STORAGE_SAVE, CLIENT_STORAGE_FLUSH,
        SERVER_CACHE, SERVER_CACHE_DECODE, SERVER_WORKER_QUEUE, SERVER_CONVERT, SERVER_PACE, SERVER_SNAPSHOT_RESTART,
        SERVER_ENCODE, SERVER_BATCH_ENCODE, SERVER_COMPLETION, SERVER_CREDIT_ROUND_TRIP,
        SERVER_REQUEST_CONTROL, SERVER_RECEIPT_CONTROL,
        SERVER_CREDIT_BLOCK, SERVER_PLAYER_BANDWIDTH_BLOCK, SERVER_TOTAL_BANDWIDTH_BLOCK;
        final LongAdder count = new LongAdder(), nanos = new LongAdder();
        final AtomicLong maximum = new AtomicLong();
        long reportedCount, reportedNanos;
    }
    public static boolean enabled() { return DistantConfig.SPEC.isLoaded() && DistantConfig.DEBUG.get(); }
    public static boolean verbose() { return enabled() && DistantConfig.DEBUG_VERBOSE.get(); }
    public static boolean mesh() { return enabled() && DistantConfig.DEBUG_MESH.get(); }
    public static long start() { return enabled() ? System.nanoTime() : 0; }
    public static long end(Metric metric, long start) {
        if (start == 0) return 0;
        long elapsed = System.nanoTime() - start;
        metric.count.increment(); metric.nanos.add(elapsed); metric.maximum.accumulateAndGet(elapsed, Math::max);
        return elapsed;
    }
    public static void count(Metric metric) { if (enabled()) metric.count.increment(); }
    public static void log(String message, Object... values) { LOG.info("[VD_DEBUG] " + message + " epoch_ms=" + System.currentTimeMillis(), values); }
    public static double millis(long nanos) { return nanos / 1e6; }
    public static double mbps(long bytes, long nanos) { return nanos > 0 ? bytes * 8000.0 / nanos : 0; }
    public static synchronized void timings(String side) {
        for (Metric metric : Metric.values()) {
            long count = metric.count.sum();
            if (!metric.name().startsWith(side) || count == 0) continue;
            long nanos=metric.nanos.sum(), delta=count-metric.reportedCount, elapsed=nanos-metric.reportedNanos;
            log(String.format(Locale.ROOT, "%s count=%d total_ms=%.3f max_ms=%.3f interval_count=%d interval_total_ms=%.3f interval_avg_ms=%.3f", metric, count, millis(nanos), millis(metric.maximum.get()),delta,millis(elapsed),delta==0?0:millis(elapsed)/delta));
            metric.reportedCount=count;metric.reportedNanos=nanos;
        }
    }
    /** Local receive timings; a receipt releases memory, it does not certify a disk flush. */
    public static final class ReceiveTrace {
        private final int epoch;
        private final long transfer, first=start();
        private long last, ready;
        private int fragments;
        private long maxGap;
        public long decode, apply;
        public int applied;
        public ReceiveTrace(int epoch,long transfer){this.epoch=epoch;this.transfer=transfer;}
        public void fragment(int offset,int length,int total,int raw,int columns){
            if(first==0)return;
            long now=System.nanoTime();if(last!=0)maxGap=Math.max(maxGap,now-last);last=now;fragments++;
            if(DebugLog.verbose()&&offset==0)log("CLIENT receive_begin epoch={} transfer={} columns={} payload_bytes={} raw_bytes={}",epoch,transfer,columns,total,raw);
            if(offset+length==total){ready=now;if(DebugLog.verbose())log("CLIENT received epoch={} transfer={} fragments={} payload_bytes={} assembly_ms={} max_fragment_gap_ms={}",epoch,transfer,fragments,total,millis(now-first),millis(maxGap));}
        }
        public void applied(long started,long paced,String result){
            if(first!=0&&DebugLog.verbose())log("CLIENT processed epoch={} transfer={} result={} applied_columns={} queue_ms={} decode_ms={} apply_ms={} worker_ms={} pace_ms={}",epoch,transfer,result,applied,millis(started-ready),millis(decode),millis(apply),millis(paced-started),millis(System.nanoTime()-paced));
        }
        public void receipt(){if(first!=0&&DebugLog.verbose())log("CLIENT receipt epoch={} transfer={} receive_to_receipt_ms={}",epoch,transfer,millis(System.nanoTime()-first));}
    }
    public static void connection(String side,net.minecraft.network.Connection connection,Object peer){
        var channel=((dev.voxydistant.network.ConnectionAccess)connection).distant$channel();
        if(channel==null)return; // Synthetic integration peers have no socket.
        log("{} connection peer={} open={} writable={} auto_read={} bytes_before_unwritable={} bytes_before_writable={}",side,peer,channel.isOpen(),channel.isWritable(),channel.config().isAutoRead(),channel.bytesBeforeUnwritable(),channel.bytesBeforeWritable());
    }
    private DebugLog() {}
}
