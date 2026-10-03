package dev.voxydistant.server;

import dev.voxydistant.movement.RequestShape;
import dev.voxydistant.network.Protocol;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Random;

/** Same full-drain request workload for the original scan and production heap. */
public final class ServerQueueCpuCheck {
    public static void main(String[] args){
        boolean heap=args[0].equals("heap");int size=Integer.parseInt(args[1]),runs=Integer.parseInt(args[2]);
        var requests=new Protocol.Want[size];var random=new Random(991);for(int i=0;i<size;i++)requests[i]=new Protocol.Want(i-size/2,random.nextInt(512)-256,0,5,i+1);
        var cpu=ManagementFactory.getThreadMXBean();cpu.setThreadCpuTimeEnabled(true);long start=0,checksum=0;
        for(int run=-100;run<runs;run++){
            if(run==0)start=cpu.getCurrentThreadCpuTime();
            LinkedHashMap<Long,Protocol.Want> queue=heap?new PendingRequests():new LinkedHashMap<>();
            for(var want:requests)queue.put(position(want),want);
            double angle=run*Math.PI/12;var shape=new RequestShape(0,0,.6875,0,Math.cos(angle),Math.sin(angle),1,192,256);
            for(int i=0;i<size;i++){
                Protocol.Want want;
                if(heap)want=((PendingRequests)queue).poll(shape,0,0,i%20==0);
                else{want=queue.firstEntry().getValue();if(i%20!=0)for(var candidate:queue.values())if(shape.compare(candidate.x(),candidate.z(),want.x(),want.z())<0)want=candidate;queue.remove(position(want));}
                if(run>=0)checksum=checksum*31+position(want);
            }
        }
        System.out.printf("SERVER_QUEUE mode=%s size=%d runs=%d requests=%d cpu_ms=%.3f order=%d%n",heap?"heap":"scan",size,runs,size*runs,(cpu.getCurrentThreadCpuTime()-start)/1e6,checksum);
    }
    private static long position(Protocol.Want want){return (want.x()&0xffffffffL)|((long)want.z()<<32);}
}
