package dev.voxydistant.client;

import dev.voxydistant.compat.CoverageStore;
import dev.voxydistant.config.DistanceBands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RegionDiscoveryTest {
    @org.junit.jupiter.api.Test void predictionCpuBenchmark()throws Exception{
        var bean=java.lang.management.ManagementFactory.getThreadMXBean();bean.setThreadCpuTimeEnabled(true);
        var bands=dev.voxydistant.config.DistanceBands.parse(java.util.List.of("32:0","64:1","96:2"));
        var discovery=new RegionDiscovery(bands);long start=bean.getCurrentThreadCpuTime();long missing=0;
        for(int step=0;step<12;step++){
            var shape=new dev.voxydistant.movement.RequestShape(step,0,step+.6875,0,1,0,1,192,256);discovery.move(shape);
            for(var r:discovery.regions.values())if(r.maskPending)discovery.applyMasks(r,shape,discovery.buildMasks(r,shape));
            if(step==0)for(var r:discovery.regions.values()){
                var ctor=dev.voxydistant.compat.CoverageStore.Directory.class.getDeclaredConstructor(int.class,int.class);ctor.setAccessible(true);var directory=ctor.newInstance(-4,20);
                for(int i=0;i<1024;i++){int x=r.x*32+(i&31),z=r.z*32+(i>>5);if((long)x*x+(long)z*z<128L*128){directory.versions[i]=1;directory.levels[i]=0;}}
                discovery.local(r,directory);
            }
        }
        long cpu=bean.getCurrentThreadCpuTime()-start;for(var r:discovery.regions.values())missing+=r.needs.cardinality();
        System.out.printf(java.util.Locale.ROOT,"PREDICTION_REMOTE_CPU steps=12 radius=192 limit=256 cpu_ms=%.3f regions=%d boundary_checks=%d directory_checks=%d missing=%d%n",cpu/1e6,discovery.regions.size(),discovery.boundaryChecks,discovery.checks,missing);
    }
    @org.junit.jupiter.api.Test void predictionMasksMatchReferenceAndStopRechecking(){
        var bands=dev.voxydistant.config.DistanceBands.parse(java.util.List.of("32:0","64:1","96:2"));
        for(int heading=0;heading<8;heading++){
            double angle=heading*Math.PI/4;var shape=new dev.voxydistant.movement.RequestShape(-3,-9,-2,-8,Math.cos(angle),Math.sin(angle),1,96,128).precision(heading%2==0?1:.5);
            var discovery=new RegionDiscovery(bands);discovery.move(shape);
            for(var r:discovery.regions.values())discovery.applyMasks(r,shape,discovery.buildMasks(r,shape));
            for(int z=-140;z<140;z++)for(int x=-140;x<140;x++){
                var r=discovery.regions.get(RegionDiscovery.key(x>>5,z>>5));int slot=(x&31)|((z&31)<<5),actual=5;
                if(r!=null)for(int l=0;l<5;l++)if(r.targets[l].get(slot))actual=l;
                assertEquals(shape.contains(x,z)?shape.desired(bands,x,z):5,actual,"heading="+heading+" x="+x+" z="+z);
            }
        }
    }
    @Test void convexRowQueueMatchesExhaustiveRequestOrder()throws Exception{
        var bands=DistanceBands.parse(List.of("4:0","16:1","32:2"));
        var constructor=CoverageStore.Directory.class.getDeclaredConstructor(int.class,int.class);constructor.setAccessible(true);
        for(int heading=0;heading<12;heading++){
            double angle=heading*Math.PI/6;
            var shape=new dev.voxydistant.movement.RequestShape(-3,-9,-2.3125,-8.6875,Math.cos(angle),Math.sin(angle),.75,32,64);
            var scan=new RegionDiscovery(bands);scan.move(shape);
            for(var r:scan.regions.values()){scan.applyMasks(r,shape,scan.buildMasks(r,shape));scan.local(r,constructor.newInstance(-4,20));}
            java.util.Comparator<Long> order=(a,b)->shape.compare(RegionDiscovery.x(a),RegionDiscovery.z(a),RegionDiscovery.x(b),RegionDiscovery.z(b));
            var near=new java.util.TreeSet<Long>(order);var far=new java.util.TreeSet<Long>(order);
            for(int z=shape.minZ();z<=shape.maxZ();z++)for(int x=shape.minX();x<=shape.maxX();x++)if(shape.contains(x,z))(shape.desired(bands,x,z)==0?near:far).add(RegionDiscovery.key(x,z));
            for(int i=0;i<512;i++){
                var queue=(i&3)==3?far:near;if(queue.isEmpty())queue=(i&3)==3?near:far;
                assertEquals(queue.pollFirst(),scan.poll(),"heading="+heading+" request="+i);
            }
        }
    }
    @TempDir Path path;
    @Test void movementMatchesExactDistanceBandsAndDoesNotRecheckInterior(){
        var bands=DistanceBands.parse(List.of("32:0","64:1","96:2"));
        var scan=new RegionDiscovery(bands);var cache=new CoverageStore(path.resolve("coverage.bin"));cache.remote(0,1,256);
        scan.move(-7,3,256);
        for(var r:scan.regions.values())scan.local(r,cache.directory(r.x,r.z,false));
        verify(scan,bands,-7,3,256);
        long checks=scan.checks;scan.move(-6,3,256);verify(scan,bands,-6,3,256);
        assertTrue(scan.checks-checks<100000,"one chunk move must not recheck the full disk");
        long idle=scan.checks;for(int i=0;i<10;i++)scan.busy();assertEquals(idle,scan.checks);
        cache.closeUnconfirmed();
    }
    @Test void quotaAndRevisionRefreshSurviveMovement(){
        var bands=DistanceBands.parse(List.of("32:0","64:1","96:2"));
        var scan=new RegionDiscovery(bands);var cache=new CoverageStore(path.resolve("quota.bin"));cache.remote(0,1,256);scan.move(0,0,128);
        for(var r:scan.regions.values())scan.local(r,cache.directory(r.x,r.z,false));
        int near=0,far=0;for(int i=0;i<64;i++){long p=scan.poll();if(bands.select(RegionDiscovery.x(p),RegionDiscovery.z(p),0,0)==0)near++;else far++;}
        assertEquals(48,near);assertEquals(16,far);
        for(var r:scan.regions.values()){r.urgent.clear();r.orderedFor=null;}
        for(int i=0;i<64;i++)assertNotNull(scan.poll(),"far queue borrows all empty near quota");
        for(var r:scan.regions.values()){r.urgent.or(r.needs);r.orderedFor=null;}
        for(int i=0;i<64;i++)assertNotNull(scan.poll(),"near queue borrows all empty far quota");
        var region=scan.regions.get(RegionDiscovery.key(1,0));long pos=RegionDiscovery.key(40,0);scan.updated(pos,new CoverageStore.Stamp(10,1));
        long[] versions=new long[1024];byte[] masks=new byte[1024];java.util.Arrays.fill(versions,11);java.util.Arrays.fill(masks,(byte)31);
        scan.summary(region,versions,masks);assertTrue(region.needs.get(8));
        scan.move(1,0,128);assertTrue(region.needs.get(8),"movement cannot erase stale-version demand");
        scan.updated(pos,new CoverageStore.Stamp(11,0));assertFalse(region.needs.get(8));
        scan.reset();scan.summary(region,versions,masks);assertTrue(scan.regions.isEmpty());
        cache.closeUnconfirmed();
    }
    private static void verify(RegionDiscovery scan,DistanceBands bands,int cx,int cz,int radius){
        for(var r:scan.regions.values())for(int i=0;i<1024;i++){
            int x=r.x*32+(i&31),z=r.z*32+(i>>5);long dx=(long)x-cx,dz=(long)z-cz;
            for(int l=0;l<5;l++)assertEquals(dx*dx+dz*dz<=(long)radius*radius&&bands.select(x,z,cx,cz)==l,r.targets[l].get(i));
        }
    }
}
