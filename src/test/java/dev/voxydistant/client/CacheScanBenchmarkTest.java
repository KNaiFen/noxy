package dev.voxydistant.client;

import dev.voxydistant.compat.CoverageStore;
import dev.voxydistant.config.DistanceBands;
import dev.voxydistant.generation.NearbyChunks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The persisted 192-chunk cache used to compare restart scan latency. */
class CacheScanBenchmarkTest {
    private static final int CACHED=192,RADIUS=256,MIN_Y=-4,MAX_Y=20;
    private static final DistanceBands BANDS=DistanceBands.parse(List.of("32:0","64:1","96:2"));
    @TempDir Path directory;

    @Test void cachedCircleFindsOuterLodsBeforeFullVerification() {
        Path file=directory.resolve("coverage.bin");
        var cache=new CoverageStore(file);
        cache.remote(MIN_Y,MAX_Y,256);
        var fill=new NearbyChunks(CACHED,-1);
        NearbyChunks.Offset offset;
        int cached=0;
        while((offset=fill.next())!=null){
            int level=BANDS.select(offset.x(),offset.z(),0,0);
            for(int y=MIN_Y;y<MAX_Y;y++)cache.received(offset.x(),y,offset.z(),1,level,false);
            cached++;
        }
        cache.saveAfterWorldClosed();
        assertEquals(115781,cached);

        var baseline=scan(file,false);
        var fast=scan(file,true);
        assertEquals(453,baseline.firstOuterBatch);
        assertEquals(15,fast.firstOuterBatch);
        assertEquals(205861,baseline.wants);
        assertEquals(90080,fast.wants);
        assertEquals(0,fast.duplicates);
        assertEquals(0,fast.missed);
        System.out.printf("192/256 cache scan: baseline first batch=%d (%.1f ms), fast first batch=%d (%.1f ms), fast wants=%d, duplicates=%d, missed=%d%n",
                baseline.firstOuterBatch,baseline.firstOuterMillis,fast.firstOuterBatch,fast.firstOuterMillis,fast.wants,fast.duplicates,fast.missed);
    }

    private static Result scan(Path file,boolean fast){
        var cache=new CoverageStore(file);
        cache.remote(MIN_Y,MAX_Y,256);
        var scan=new NearbyChunks(RADIUS,-1);
        var wants=new HashSet<Long>();
        int checked=0,duplicates=0,firstBatch=0;
        double firstMillis=0;
        long started=System.nanoTime();
        NearbyChunks.Offset offset;
        boolean done=false;
        while(!done){
            int batchWants=0;
            for(int i=0;i<(fast?8192:256)&&batchWants<(fast?64:256);i++){
                offset=scan.next();
                if(offset==null){done=true;break;}
                checked++;
                var stamp=cache.column(offset.x(),offset.z(),MIN_Y,MAX_Y);
                if(fast&&stamp.level()<=BANDS.select(offset.x(),offset.z(),0,0))continue;
                batchWants++;
                if(!wants.add(pack(offset)))duplicates++;
                if(firstBatch==0&&offset.distanceSquared()>(long)CACHED*CACHED){
                    firstBatch=fast?(checked+8191)/8192:(checked+255)/256;
                    firstMillis=(System.nanoTime()-started)/1e6;
                }
            }
        }
        int missed=0;
        var verify=new NearbyChunks(RADIUS,-1);
        while((offset=verify.next())!=null)
            if(cache.column(offset.x(),offset.z(),MIN_Y,MAX_Y).level()>BANDS.select(offset.x(),offset.z(),0,0)
                    &&!wants.contains(pack(offset)))missed++;
        cache.closeUnconfirmed();
        return new Result(firstBatch,firstMillis,wants.size(),duplicates,missed);
    }

    private static long pack(NearbyChunks.Offset offset){return (long)offset.x()<<32|(offset.z()&0xffffffffL);}
    private record Result(int firstOuterBatch,double firstOuterMillis,int wants,int duplicates,int missed) {}
}
