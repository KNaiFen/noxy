package dev.voxydistant.client;

import dev.voxydistant.compat.CoverageStore;
import dev.voxydistant.config.DistanceBands;
import dev.voxydistant.movement.RequestShape;
import java.lang.management.ManagementFactory;
import java.util.List;

/** Same single-thread workload for original, algorithms-only, and asynchronous classes. */
public final class RegionCpuCheck {
    public static void main(String[] args)throws Exception {
        var bands=DistanceBands.parse(List.of("32:0","64:1","96:2"));
        var constructor=CoverageStore.Directory.class.getDeclaredConstructor(int.class,int.class);constructor.setAccessible(true);
        var cpu=ManagementFactory.getThreadMXBean();cpu.setThreadCpuTimeEnabled(true);
        String mode=args.length==0?"axis":args[0];int runs=args.length<2?100:Integer.parseInt(args[1]);
        long start=0,checks=0,boundaries=0,missing=0,polls=0,order=0;
        for(int run=-10;run<runs;run++){
            if(run==0)start=cpu.getCurrentThreadCpuTime();
            var discovery=new RegionDiscovery(bands);
            for(int step=0;step<12;step++){
                double angle=mode.equals("axis")||mode.equals("replies")?0:mode.equals("diagonal")?Math.PI/4:step*Math.PI/6;
                double dx=Math.cos(angle),dz=Math.sin(angle);
                int px=step,pz=mode.equals("diagonal")?step:0;
                var shape=new RequestShape(px,pz,px+dx*.6875,pz+dz*.6875,dx,dz,1,192,256);discovery.move(shape);
                for(var region:discovery.regions.values())if(region.maskPending)discovery.applyMasks(region,shape,discovery.buildMasks(region,shape));
                for(var region:discovery.regions.values())if(region.prepare){
                    var directory=constructor.newInstance(-4,20);
                    for(int i=0;i<1024;i++){int x=region.x*32+(i&31),z=region.z*32+(i>>5);if((long)x*x+(long)z*z<128L*128){directory.versions[i]=1;directory.levels[i]=0;}}
                    discovery.local(region,directory);
                }
                for(int i=0;i<64;i++){
                    Long p=discovery.poll();if(p==null)continue;
                    if(run>=0){polls++;order=order*31+p;}
                    if(mode.equals("replies"))discovery.updated(p,new CoverageStore.Stamp(1,0));
                }
            }
            if(run>=0){checks+=discovery.checks;boundaries+=discovery.boundaryChecks;for(var region:discovery.regions.values())missing+=region.needs.cardinality();}
        }
        long elapsed=cpu.getCurrentThreadCpuTime()-start;
        System.out.printf("REGION_CPU mode=%s runs=%d moves=%d requests=%d cpu_ms=%.3f boundary_checks=%d directory_checks=%d missing=%d order=%d%n",mode,runs,runs*12,polls,elapsed/1e6,boundaries,checks,missing,order);
    }
}
