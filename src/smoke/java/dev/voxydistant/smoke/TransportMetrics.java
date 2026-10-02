package dev.voxydistant.smoke;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;

/** Benchmark-only counters at the actual fragment receive boundary. */
public final class TransportMetrics {
    private static long first, last, bytes, fragments;
    private static final long[] batches = new long[129];
    public static synchronized void fragment(int offset, int length, int columns) {
        long now=System.nanoTime();if(first==0)first=now;last=now;bytes+=length;fragments++;
        if(offset==0)batches[columns]++;
    }
    public static synchronized void sample() throws IOException {
        var row=new java.util.LinkedHashMap<String,Object>();
        row.put("time_ns",System.nanoTime());row.put("first_fragment_ns",first);row.put("last_fragment_ns",last);
        row.put("payload_bytes",bytes);row.put("fragments",fragments);row.put("batches_by_columns",batches);
        Files.writeString(LodBenchmark.OUTPUT.resolve("transport.jsonl"),new Gson().toJson(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private TransportMetrics() {}
}
