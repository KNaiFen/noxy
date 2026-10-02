package dev.voxydistant.client;

import dev.voxydistant.compat.CoverageStore;
import dev.voxydistant.config.DistanceBands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RegionDiscoveryTest {
    @org.junit.jupiter.api.Test void predictionMasksMatchReferenceAndStopRechecking(){
        var bands=dev.voxydistant.config.DistanceBands.parse(java.util.List.of("32:0","64:1","96:2"));
        for(int heading=0;heading<8;heading++){
            double angle=heading*Math.PI/4;var shape=new dev.voxydistant.movement.RequestShape(-3,-9,-2,-8,Math.cos(angle),Math.sin(angle),1,96,128);
            var discovery=new RegionDiscovery(bands);discovery.move(shape);
            for(var r:discovery.regions.values())discovery.applyMasks(r,shape,discovery.buildMasks(r,shape));
            for(int z=-140;z<140;z++)for(int x=-140;x<140;x++){
                var r=discovery.regions.get(RegionDiscovery.key(x>>5,z>>5));int slot=(x&31)|((z&31)<<5),actual=5;
                if(r!=null)for(int l=0;l<5;l++)if(r.targets[l].get(slot))actual=l;
                assertEquals(shape.contains(x,z)?shape.desired(bands,x,z):5,actual,"heading="+heading+" x="+x+" z="+z);
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
