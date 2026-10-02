package dev.voxydistant.smoke;

import dev.voxydistant.server.RemoteServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class LodMetrics {
    private static final com.sun.management.ThreadMXBean THREADS=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static final ThreadLocal<long[]> START=ThreadLocal.withInitial(()->new long[9]);
    private static PrintWriter operations;
    private final PrintWriter ticks;
    private final PrintWriter threads;
    private final PrintWriter serverSamples;
    private final PrintWriter maintenance;
    private long tickStart,lastFlush;
    public LodMetrics() {
        try {
            Files.createDirectories(LodBenchmark.OUTPUT);
            operations=new PrintWriter(Files.newBufferedWriter(LodBenchmark.OUTPUT.resolve("operations.csv")));
            operations.println("time_ns,operation,thread,level,wall_ns,cpu_ns,allocated_bytes,payload_bytes,raw_bytes");
            ticks=new PrintWriter(Files.newBufferedWriter(LodBenchmark.OUTPUT.resolve("server-ticks.csv")));
            ticks.println("time_ns,tick_ns,heap_bytes,status");
            threads=new PrintWriter(Files.newBufferedWriter(LodBenchmark.OUTPUT.resolve("server-threads.csv")));
            threads.println("time_ns,id,name,cpu_ns");
            serverSamples=new PrintWriter(Files.newBufferedWriter(LodBenchmark.OUTPUT.resolve("server-samples.csv")));
            serverSamples.println("time_ns,process_cpu_ns,hits,misses,completed,sent_bytes,work,worker_queue,worker_active,pending,queued_bytes,reserved,players,effective_credit");
            maintenance=new PrintWriter(Files.newBufferedWriter(LodBenchmark.OUTPUT.resolve("maintenance.csv")));
            maintenance.println("epoch_ms,time_ns,type,running,scanning,paused,scanned,queued,completed,skipped,failed,total,queue,pending,active,tickets,snapshot_bytes,dirty_writes,phase,import_threads,import_memory,read_ns,convert_ns,encode_ns,write_ns");
        }catch(IOException e){throw new UncheckedIOException(e);}
        THREADS.setThreadAllocatedMemoryEnabled(true);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent e)->{ticks.close();threads.close();serverSamples.close();maintenance.close();synchronized(LodMetrics.class){operations.close();}});
    }
    public static void begin(int operation) {
        long[] s=START.get();int p=operation*3;s[p]=System.nanoTime();s[p+1]=THREADS.getCurrentThreadCpuTime();s[p+2]=THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
    public static void end(int operation,int level,int payload,int raw) {
        if(operations==null)return;
        long now=System.nanoTime();long[] s=START.get();int p=operation*3;
        long cpu=THREADS.getCurrentThreadCpuTime()-s[p+1],allocated=THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId())-s[p+2];
        synchronized(LodMetrics.class){operations.printf(Locale.ROOT,"%d,%d,%s,%d,%d,%d,%d,%d,%d%n",now,operation,Thread.currentThread().getName(),level,now-s[p],cpu,allocated,payload,raw);}
    }
    private void tick(TickEvent.ServerTickEvent e) {
        if(e.phase==TickEvent.Phase.START){tickStart=System.nanoTime();return;}
        long now=System.nanoTime();ticks.printf(Locale.ROOT,"%d,%d,%d,%s%n",now,now-tickStart,ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),RemoteServer.status());
        if(now-lastFlush>=1_000_000_000){
            lastFlush=now;for(var info:THREADS.dumpAllThreads(false,false))threads.printf(Locale.ROOT,"%d,%d,%s,%d%n",now,info.getThreadId(),info.getThreadName(),THREADS.getThreadCpuTime(info.getThreadId()));
            try {
                var f=RemoteServer.class.getDeclaredField("instance");f.setAccessible(true);Object server=f.get(null);
                if(server!=null){
                    var workers=(ThreadPoolExecutor)field(server,"workers");long pending=0,queued=0,reserved=0,credit=0;
                    var players=((Map<?,?>)field(server,"players")).values();
                    for(Object session:players){pending+=((Map<?,?>)field(session,"pending")).size();queued+=(long)field(session,"bytes");reserved+=(long)field(session,"reserved");credit+=(int)field(session,"capacity");}
                    serverSamples.printf(Locale.ROOT,"%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",now,((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime(),field(server,"hits"),field(server,"misses"),field(server,"completed"),field(server,"sentBytes"),((Map<?,?>)field(server,"work")).size(),workers.getQueue().size(),workers.getActiveCount(),pending,queued,reserved,players.size(),credit);
                    Files.writeString(LodBenchmark.OUTPUT.resolve("batch-flushes.jsonl"),"{\"time_ns\":"+now+",\"full_budget_tail_tick\":"+Arrays.toString((long[])field(server,"batchFlushes"))+"}\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    Object task=field(server,"maintenance");boolean running=task!=null;
                    if(task==null)task=field(server,"lastMaintenance");
                    if(task!=null){
                        long tickets=((Map<?,?>)field(server,"work")).values().stream().filter(w->(boolean)field(w,"ticket")).count();
                        maintenance.printf(Locale.ROOT,"%d,%d,%s,%b,%s,%s,%s,%s,%s,%s,%s,%s,%d,%d,%s,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                                System.currentTimeMillis(),now,field(task,"type"),running,field(task,"scanning"),field(server,"maintenancePaused"),
                                field(task,"scanned"),field(task,"queued"),field(task,"completed"),field(task,"skipped"),field(task,"failed"),field(task,"total"),
                                ((Collection<?>)field(task,"queue")).size(),((Collection<?>)field(task,"pending")).size(),field(task,"active"),tickets,field(server,"memory"),field(server,"dirtyWrites"),field(task,"phase"),field(task,"importThreads"),field(task,"importMemory"),field(task,"readNanos"),field(task,"convertNanos"),field(task,"encodeNanos"),field(task,"writeNanos"));
                    }
                }
            }catch(ReflectiveOperationException|IOException ex){throw new IllegalStateException(ex);}
            ticks.flush();threads.flush();serverSamples.flush();maintenance.flush();flush();
        }
    }
    public static synchronized void flush(){operations.flush();}
    public static Object field(Object object,String name) {
        try{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
}
