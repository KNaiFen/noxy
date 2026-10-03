package dev.voxydistant.server;

import dev.voxydistant.movement.RequestShape;
import dev.voxydistant.network.Protocol;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Random;
import net.minecraft.network.FriendlyByteBuf;

/** Same mixed-member selection and payload-copy workloads; no Minecraft runtime is started. */
public final class ServerSendCpuCheck {
    private static final com.sun.management.ThreadMXBean CPU=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static final class Candidate implements Comparable<Candidate>{
        final int id;final List<Protocol.Member> members;double score;long distance;int x,z;
        Candidate(int id,List<Protocol.Member> members){this.id=id;this.members=members;}
        public int compareTo(Candidate other){int c=Double.compare(score,other.score);if(c==0)c=Long.compare(distance,other.distance);if(c==0)c=Integer.compare(x,other.x);if(c==0)c=Integer.compare(z,other.z);return c==0?Integer.compare(id,other.id):c;}
    }
    public static void main(String[] args)throws Exception{
        CPU.setThreadCpuTimeEnabled(true);CPU.setThreadAllocatedMemoryEnabled(true);
        boolean optimized=args[1].equals("optimized");int runs=Integer.parseInt(args[2]);
        if(args[0].equals("fragment")){fragments(optimized,runs);return;}
        int count=Integer.parseInt(args[3]),width=Integer.parseInt(args[4]);
        var transfers=new ArrayList<Candidate>();var random=new Random(714);
        for(int i=0;i<count;i++){
            var columns=new ArrayList<Protocol.Member>();for(int m=0;m<width;m++)columns.add(new Protocol.Member(random.nextInt(512)-256,random.nextInt(512)-256,1,m%5,i*128L+m+1,1));
            transfers.add(new Candidate(i,List.copyOf(columns)));
        }
        long started=0,allocated=0,checksum=0;
        for(int run=-100;run<runs;run++){
            if(run==0){started=CPU.getCurrentThreadCpuTime();allocated=CPU.getThreadAllocatedBytes(Thread.currentThread().threadId());}
            double angle=run*Math.PI/12;var shape=new RequestShape(0,0,.5,.25,Math.cos(angle),Math.sin(angle),1,256,256);
            if(optimized){
                for(var t:transfers){
                    var best=t.members.getFirst();double bestScore=shape.score(best.x(),best.z());long bestDistance=squared(best);
                    for(int m=1;m<width;m++){var candidate=t.members.get(m);double value=shape.score(candidate.x(),candidate.z());long d=squared(candidate);
                        int c=Double.compare(value,bestScore);if(c==0)c=Long.compare(d,bestDistance);if(c==0)c=Integer.compare(candidate.x(),best.x());if(c==0)c=Integer.compare(candidate.z(),best.z());
                        if(c<0){best=candidate;bestScore=value;bestDistance=d;}
                    }
                    t.score=bestScore;t.distance=bestDistance;t.x=best.x();t.z=best.z();
                }
                var heap=new PriorityQueue<>(transfers);
                var pending=new ArrayDeque<>(transfers);
                while(!heap.isEmpty()){var t=heap.remove();pending.remove(t);pending.addFirst(t);pending.removeFirst();if(run>=0)checksum=checksum*31+t.id;}
            }else{
                var pending=new ArrayDeque<>(transfers);
                while(!pending.isEmpty()){
                    Candidate chosen=pending.getFirst();int bestX=0,bestZ=0;boolean found=false;
                    for(var t:pending){
                        var list=t.members;var first=list.getFirst();int x=first.x(),z=first.z();
                        for(var m:list)if(shape.compare(m.x(),m.z(),x,z)<0){x=m.x();z=m.z();}
                        if(!found||shape.compare(x,z,bestX,bestZ)<0){found=true;chosen=t;bestX=x;bestZ=z;}
                    }
                    pending.remove(chosen);pending.addFirst(chosen);pending.removeFirst();if(run>=0)checksum=checksum*31+chosen.id;
                }
            }
        }
        report("selection",optimized,runs,count,width,started,allocated,checksum);
    }
    private static long squared(Protocol.Member m){return (long)m.x()*m.x()+(long)m.z()*m.z();}
    private static void fragments(boolean optimized,int runs){
        byte[] data=new byte[1<<20];new Random(43).nextBytes(data);long started=0,allocated=0,checksum=0;
        var buffer=new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer(Protocol.FRAGMENT_BYTES+128));
        try{
            for(int run=-100;run<runs;run++){
                if(run==0){started=CPU.getCurrentThreadCpuTime();allocated=CPU.getThreadAllocatedBytes(Thread.currentThread().threadId());}
                for(int offset=0;offset<data.length;offset+=Protocol.FRAGMENT_BYTES){
                    int size=Math.min(Protocol.FRAGMENT_BYTES,data.length-offset);buffer.clear();
                    buffer.writeVarInt(size);
                    if(optimized)buffer.writeBytes(data,offset,size);else buffer.writeBytes(java.util.Arrays.copyOfRange(data,offset,offset+size));
                    if(run>=0)checksum=checksum*31+buffer.getByte(buffer.writerIndex()-1);
                }
            }
            report("fragment",optimized,runs,32,0,started,allocated,checksum);
        }finally{buffer.release();}
    }
    private static void report(String kind,boolean optimized,int runs,int count,int width,long started,long allocated,long checksum){
        System.out.printf(Locale.ROOT,"SERVER_SEND kind=%s mode=%s runs=%d count=%d members=%d cpu_ms=%.3f allocated_mib=%.3f order=%d%n",kind,optimized?"optimized":"scan",runs,count,width,(CPU.getCurrentThreadCpuTime()-started)/1e6,(CPU.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated)/1048576d,checksum);
    }
}
