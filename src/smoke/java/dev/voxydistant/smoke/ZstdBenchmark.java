package dev.voxydistant.smoke;

import com.github.luben.zstd.Zstd;
import dev.voxydistant.data.*;
import dev.voxydistant.generation.ChunkSnapshot;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

/** Test-only real terrain corpus and tick/codec contention experiment. No player or GPU simulation. */
public final class ZstdBenchmark {
    private static final TicketType<ChunkPos> TICKET = TicketType.create("zstd_benchmark", Comparator.comparingLong(ChunkPos::toLong));
    private static final int[] MASKS = {1, 2, 4, 8, 16, 31};
    private static final Path OUTPUT = Path.of(System.getProperty("voxyDistant.benchmarkOutput"));
    private record Sample(String dimension, List<ChunkSnapshot> snapshots, LodColumn column) {}
    private record Frame(String dimension, int mask, byte[] raw) {}
    private static final class Pending {
        final ServerLevel level; final ChunkPos pos; final long started = System.nanoTime();
        final List<ChunkSnapshot> snapshots = new ArrayList<>();
        Pending(ServerLevel level, ChunkPos pos) { this.level=level; this.pos=pos; }
    }
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var t=new Thread(r,"Zstd benchmark worker");t.setPriority(Thread.MIN_PRIORITY);return t;
    });
    private final List<Sample> corpus = new ArrayList<>();
    private final List<Pending> pending = new ArrayList<>();
    private final List<Long> tickTimes = new ArrayList<>(), generationTimes = new ArrayList<>();
    private final int[] levels = Arrays.stream(System.getProperty("voxyDistant.benchmark.levels","1,3,6,9,12,15,19,22").split(",")).mapToInt(Integer::parseInt).toArray();
    private final int seconds = Integer.getInteger("voxyDistant.benchmark.seconds",20);
    private final int rounds = Integer.getInteger("voxyDistant.benchmark.rounds",2);
    private final int rate = Integer.getInteger("voxyDistant.benchmark.rate",80);
    private final String profile = System.getProperty("voxyDistant.benchmark.profile","mixed");
    private final List<Integer> order = new ArrayList<>();
    private MinecraftServer server;
    private Future<?> job;
    private int phase, submitted, liveIndex, tickCount, liveGenerated;
    private long tickStart, phaseStart, liveStart;
    private double generationTokens;
    private PrintWriter liveReport;

    public ZstdBenchmark() {
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e)->server=e.getServer());
        MinecraftForge.EVENT_BUS.addListener(this::tick);
    }

    private void tick(TickEvent.ServerTickEvent e) {
        if(server==null)return;
        if(e.phase==TickEvent.Phase.START){tickStart=System.nanoTime();return;}
        try {
            tickCount++;
            if(phase==0) {
                if(job!=null&&job.isDone()){job.get();job=null;}
                // 24 naturally generated columns across all three dimensions, fixed seed/positions.
                if(submitted<24&&pending.size()<2) {
                    int d=submitted/8,i=submitted%8;
                    var level=server.getLevel(d==0?Level.OVERWORLD:d==1?Level.NETHER:Level.END);
                    var pos=new ChunkPos(40+(i%4)*3,40+(i/4)*8);
                    level.getChunkSource().addRegionTicket(TICKET,pos,0,pos);
                    pending.add(new Pending(level,pos));submitted++;
                }
                if(job==null)capture();
                if(submitted==24&&pending.isEmpty()&&job==null) {
                    System.out.println("DISTANT_ZSTD_CORPUS columns="+corpus.size());
                    phase=1;job=worker.submit(()->{try{micro();}catch(IOException ex){throw new UncheckedIOException(ex);}});
                }
            } else if(phase==1&&job.isDone()) {
                job.get();
                liveReport=new PrintWriter(Files.newBufferedWriter(OUTPUT.resolve("live.csv")));
                liveReport.println("round,level,profile,offered_columns_s,seconds,ticks,tps,tick_p50_ms,tick_p95_ms,tick_p99_ms,tick_max_ms,ticks_over_50,generated,gen_p50_ms,gen_p95_ms,gen_max_ms");
                for(int round=0;round<rounds;round++){
                    var shuffled=new ArrayList<Integer>();for(int l:levels)shuffled.add(l);
                    Collections.shuffle(shuffled,new Random(9173+round));order.addAll(shuffled);
                }
                phase=2;startLive();
            } else if(phase==2) {
                tickTimes.add(System.nanoTime()-tickStart);
                pumpGeneration();
                if(job.isDone()) {
                    job.get();
                    if(pending.isEmpty()){
                        finishLive();liveIndex++;
                        if(liveIndex==order.size()){
                            liveReport.close();worker.shutdown();System.out.println("DISTANT_ZSTD_PASS");
                            phase=3;server.halt(false);
                        }else startLive();
                    }
                }
            }
            if(tickCount%400==0)System.out.println("DISTANT_ZSTD_PROGRESS phase="+phase+" corpus="+corpus.size()+" live="+liveIndex+" pending="+pending.size());
        }catch(IOException|ExecutionException|InterruptedException|RuntimeException|AssertionError ex){
            ex.printStackTrace();System.out.println("DISTANT_ZSTD_FAIL");
            for(var p:pending)p.level.getChunkSource().removeRegionTicket(TICKET,p.pos,0,p.pos);
            worker.shutdownNow();phase=3;server.halt(false);server=null;
        }
    }

    private void capture() {
        long deadline=System.nanoTime()+2_000_000;
        for(var it=pending.iterator();it.hasNext();) {
            var p=it.next();LevelChunk chunk=p.level.getChunkSource().getChunkNow(p.pos.x,p.pos.z);
            if(chunk==null||!chunk.isLightCorrect())continue;
            while(p.snapshots.size()<chunk.getSectionsCount()&&System.nanoTime()<deadline) {
                int index=p.snapshots.size(), y=chunk.getMinSection()+index;
                var section=chunk.getSections()[index];var pos=SectionPos.of(p.pos,y);
                var block=p.level.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
                var sky=p.level.getLightEngine().getLayerListener(LightLayer.SKY).getDataLayerData(pos);
                var biomes=section.getBiomes().recreate();
                for(int by=0;by<4;by++)for(int bz=0;bz<4;bz++)for(int bx=0;bx<4;bx++)biomes.getAndSetUnchecked(bx,by,bz,section.getBiomes().get(bx,by,bz));
                p.snapshots.add(new ChunkSnapshot(p.pos.x,y,p.pos.z,section.getStates().copy(),biomes,block==null?null:block.copy(),sky==null?null:sky.copy()));
            }
            if(p.snapshots.size()==chunk.getSectionsCount()){
                p.level.getChunkSource().removeRegionTicket(TICKET,p.pos,0,p.pos);it.remove();
                String dimension=p.level.dimension().location().toString();
                job=worker.submit(()->corpus.add(new Sample(dimension,List.copyOf(p.snapshots),ColumnConverter.convert(p.pos.x,p.pos.z,42,p.snapshots))));return;
            }
        }
    }

    private void micro() throws IOException {
        var frames=new ArrayList<Frame>();
        for(var sample:corpus)for(int mask:MASKS){
            var encoded=ColumnCodec.encode(sample.column,mask);
            byte[] raw=encoded.compressed()?Zstd.decompress(encoded.bytes(),encoded.rawLength()):encoded.bytes();
            frames.add(new Frame(sample.dimension,mask,raw));
        }
        Files.createDirectories(OUTPUT);
        Files.writeString(OUTPUT.resolve("environment.txt"),"java="+System.getProperty("java.runtime.version")+"\nos="+System.getProperty("os.name")+"\ncpu="+System.getenv("PROCESSOR_IDENTIFIER")+"\nlogical_processors="+Runtime.getRuntime().availableProcessors()+"\nzstd_jni=1.5.7-6\nseed=91739173\ncolumns="+corpus.size()+"\nprofile="+profile+"\nrate="+rate+"\n");
        try(var manifest=new PrintWriter(Files.newBufferedWriter(OUTPUT.resolve("corpus.csv")))){
            manifest.println("sample,dimension,mask,raw_bytes");int i=0;
            var folder=Files.createDirectories(OUTPUT.resolve("corpus"));
            for(var f:frames){Files.write(folder.resolve(i+".bin"),f.raw);manifest.printf(Locale.ROOT,"%d,%s,%d,%d%n",i++,f.dimension,f.mask,f.raw.length);}
        }
        // Warm JNI/JIT and every strategy before timed, interleaved passes.
        for(int repeat=0;repeat<2;repeat++)for(int l=1;l<=22;l++)for(var f:frames)Zstd.compress(f.raw,l);
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        bean.setThreadCpuTimeEnabled(true);bean.setThreadAllocatedMemoryEnabled(true);
        try(var report=new PrintWriter(Files.newBufferedWriter(OUTPUT.resolve("compression.csv")))) {
            report.println("round,sample,dimension,mask,level,raw_bytes,payload_bytes,wire_bytes,compress_ns,cpu_ns,java_allocated_bytes,decompress_ns");
            var shuffled=new ArrayList<Integer>();for(int l=1;l<=22;l++)shuffled.add(l);
            for(int round=0;round<5;round++){
                Collections.shuffle(shuffled,new Random(7381+round));
                for(int l:shuffled)for(int i=0;i<frames.size();i++){
                    var f=frames.get(i);long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId()),cpu=bean.getCurrentThreadCpuTime(),before=System.nanoTime();
                    byte[] compressed=Zstd.compress(f.raw,l);long elapsed=System.nanoTime()-before;
                    cpu=bean.getCurrentThreadCpuTime()-cpu;allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated;
                    before=System.nanoTime();byte[] restored=Zstd.decompress(compressed,f.raw.length);long decode=System.nanoTime()-before;
                    if(!Arrays.equals(restored,f.raw))throw new AssertionError("Zstd round trip");
                    int payload=compressed.length+16<f.raw.length?compressed.length:f.raw.length;
                    int wire=payload+128*((payload+32767)/32768);
                    report.printf(Locale.ROOT,"%d,%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",round,i,f.dimension,f.mask,l,f.raw.length,payload,wire,elapsed,cpu,allocated,decode);
                }
                report.flush();System.out.println("DISTANT_ZSTD_MICRO round="+round);
            }
        }
        // Warm the complete Java conversion path too, so the first live phase is not a JIT warmup.
        for(int repeat=0;repeat<10;repeat++)for(var sample:corpus){
            var column=ColumnConverter.convert(sample.column.x(),sample.column.z(),42,sample.snapshots);
            ColumnCodec.encode(column,31);ColumnCodec.encode(column,1,6);
        }
    }

    private void startLive() {
        tickTimes.clear();generationTimes.clear();phaseStart=System.nanoTime();liveStart=phaseStart;generationTokens=0;liveGenerated=0;
        int l=order.get(liveIndex), round=liveIndex/levels.length;
        System.out.println("DISTANT_ZSTD_LIVE round="+round+" level="+l);
        job=worker.submit(()->{try{pipeline(l,round);}catch(IOException ex){throw new UncheckedIOException(ex);}});
    }

    private void pipeline(int level,int round) throws IOException {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        var delays=new ArrayList<Long>();var encodeTimes=new ArrayList<Long>();long bytes=0, began=System.nanoTime();
        long cpu=bean.getCurrentThreadCpuTime(),allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        int count=rate*seconds;
        for(int i=0;i<count;i++){
            long due=began+(long)(i*1e9/rate);long wait=due-System.nanoTime();if(wait>0)LockSupport.parkNanos(wait);
            var sample=corpus.get(i%corpus.size());
            // Same conversion + complete cache compression + selected network encoding as production.
            var c=ColumnConverter.convert(sample.column.x(),sample.column.z(),42,sample.snapshots);
            ColumnCodec.encode(c,31);
            int mask=profile.equals("L0")?1:1<<(1+i%4);
            long before=System.nanoTime();var encoded=ColumnCodec.encode(c,mask,level);
            encodeTimes.add(System.nanoTime()-before);bytes+=encoded.bytes().length+128;delays.add(System.nanoTime()-due);
        }
        long elapsed=System.nanoTime()-began;
        cpu=bean.getCurrentThreadCpuTime()-cpu;allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated;
        var file=OUTPUT.resolve("pipeline-"+profile+"-"+round+"-"+level+".csv");
        try(var out=new PrintWriter(Files.newBufferedWriter(file))){
            out.println("round,level,columns,seconds,columns_s,worker_cpu_ms,java_allocated_mib,wire_bytes,delay_p50_ms,delay_p95_ms,delay_p99_ms,delay_max_ms,encode_p50_ms,encode_p99_ms");
            out.printf(Locale.ROOT,"%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",round,level,count,elapsed/1e9,count/(elapsed/1e9),cpu/1e6,allocated/1048576d,bytes,percentile(delays,.5),percentile(delays,.95),percentile(delays,.99),percentile(delays,1),percentile(encodeTimes,.5),percentile(encodeTimes,.99));
        }
    }

    private void pumpGeneration() {
        for(var it=pending.iterator();it.hasNext();){
            var p=it.next();var c=p.level.getChunkSource().getChunkNow(p.pos.x,p.pos.z);
            if(c!=null&&c.isLightCorrect()){
                generationTimes.add(System.nanoTime()-p.started);p.level.getChunkSource().removeRegionTicket(TICKET,p.pos,0,p.pos);it.remove();liveGenerated++;
            }
        }
        long now=System.nanoTime();generationTokens=Math.min(1,generationTokens+(now-liveStart)/1e9*4);liveStart=now;
        if(!job.isDone()&&pending.size()<2&&generationTokens>=1){
            // New adjacent terrain per phase; no reuse of a previously generated chunk.
            int x=256+liveIndex*32+(liveGenerated%8),z=256+(liveGenerated/8);
            var pos=new ChunkPos(x,z);var level=server.overworld();
            if(pending.stream().noneMatch(p->p.pos.equals(pos))){
                level.getChunkSource().addRegionTicket(TICKET,pos,0,pos);pending.add(new Pending(level,pos));generationTokens--;
            }
        }
    }

    private void finishLive() {
        double elapsed=(System.nanoTime()-phaseStart)/1e9;
        liveReport.printf(Locale.ROOT,"%d,%d,%s,%d,%.3f,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%.3f,%.3f,%.3f%n",liveIndex/levels.length,order.get(liveIndex),profile,rate,elapsed,tickTimes.size(),Math.min(20,tickTimes.size()/elapsed),percentile(tickTimes,.5),percentile(tickTimes,.95),percentile(tickTimes,.99),percentile(tickTimes,1),tickTimes.stream().filter(t->t>50_000_000).count(),liveGenerated,percentile(generationTimes,.5),percentile(generationTimes,.95),percentile(generationTimes,1));
        liveReport.flush();
    }
    private static double percentile(List<Long> values,double p){if(values.isEmpty())return 0;var sorted=values.stream().mapToLong(Long::longValue).sorted().toArray();return sorted[Math.min(sorted.length-1,(int)Math.ceil(p*sorted.length)-1)]/1e6;}
}
