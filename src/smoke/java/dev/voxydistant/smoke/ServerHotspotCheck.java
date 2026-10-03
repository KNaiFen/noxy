package dev.voxydistant.smoke;

import dev.voxydistant.data.ColumnCodec;
import dev.voxydistant.movement.RequestShape;
import dev.voxydistant.network.Protocol;
import dev.voxydistant.server.RemoteServer;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Synthetic queues invoking the real chooser/cancellation methods inside ModLauncher. */
final class ServerHotspotCheck {
    private static Field field(Class<?> type,String name)throws ReflectiveOperationException{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    @SuppressWarnings("unchecked") static void run(MinecraftServer server,Object service)throws ReflectiveOperationException{
        try{measure(server,service);}catch(ReflectiveOperationException e){throw e;}catch(Throwable e){throw new IllegalStateException(e);}
    }
    @SuppressWarnings("unchecked") private static void measure(MinecraftServer server,Object service)throws Throwable{
        var type=Class.forName("dev.voxydistant.server.RemoteServer$Session");var ctor=type.getDeclaredConstructor(ServerPlayer.class);ctor.setAccessible(true);
        var player=new ServerPlayer(server,server.overworld(),new com.mojang.authlib.GameProfile(new UUID(0,99001),"Hotspot"));player.setPos(0,100,0);
        Object session=ctor.newInstance(player);var sessions=(Map<UUID,Object>)field(RemoteServer.class,"players").get(service);sessions.put(player.getUUID(),session);
        var send=(Deque<Object>)field(type,"send").get(session);var inflight=(Map<Long,Object>)field(type,"inflight").get(session);
        var order=field(type,"sendOrder");var shape=field(type,"shape");var aged=field(type,"lastSendAgedTick");int tick=field(RemoteServer.class,"ticks").getInt(service);
        var transfer=Class.forName("dev.voxydistant.server.RemoteServer$Transfer");var tc=transfer.getDeclaredConstructor(long.class,net.minecraft.resources.ResourceKey.class,int.class,List.class,ColumnCodec.Encoded.class);tc.setAccessible(true);
        var offset=field(transfer,"offset");var cancelled=field(transfer,"cancelled");
        var selectMethod=RemoteServer.class.getDeclaredMethod("selectTransfer",type);selectMethod.setAccessible(true);
        var select=MethodHandles.lookup().unreflect(selectMethod).asType(MethodType.methodType(void.class,Object.class,Object.class));
        java.lang.reflect.Method cancelMethod;boolean previous;
        try{cancelMethod=RemoteServer.class.getDeclaredMethod("cancel",type,List.class);previous=false;}
        catch(NoSuchMethodException e){cancelMethod=RemoteServer.class.getDeclaredMethod("cancel",type,Protocol.Cancel.class);previous=true;}
        cancelMethod.setAccessible(true);var cancel=MethodHandles.lookup().unreflect(cancelMethod).asType(MethodType.methodType(void.class,Object.class,Object.class,Object.class));
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadCpuTimeEnabled(true);bean.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().threadId();
        final int count=64,width=128,runs=2000;var transfers=new ArrayList<Object>();var metadata=new ArrayList<List<Protocol.Member>>();var ids=new IdentityHashMap<Object,Integer>();var random=new Random(704);
        var data=new ColumnCodec.Encoded(false,1024,new byte[1024]);
        for(int i=0;i<count;i++){
            var members=new ArrayList<Protocol.Member>();for(int m=0;m<width;m++)members.add(new Protocol.Member(random.nextInt(201)-100,random.nextInt(201)-100,1,m%5,i*width+m+1,1));
            var list=List.copyOf(members);Object t=tc.newInstance(i+1L,Level.OVERWORLD,1,list,data);transfers.add(t);metadata.add(list);ids.put(t,i);
        }
        long cpu=0,allocated=0,checksum=0;long[] samples=new long[runs];
        for(int run=-100;run<runs;run++){
            if(run==0){cpu=bean.getCurrentThreadCpuTime();allocated=bean.getThreadAllocatedBytes(thread);}
            long started=System.nanoTime();send.clear();order.set(session,null);aged.setInt(session,tick);double angle=run*.1;
            shape.set(session,new RequestShape(0,0,.5,.25,Math.cos(angle),Math.sin(angle),1,256,256));
            for(Object t:transfers){offset.setInt(t,0);send.add(t);}
            for(int pass=0;pass<count/4;pass++){
                // The previous production flushSends discarded its heap at each pass boundary.
                if(previous)order.set(session,null);
                for(int n=0;n<4;n++){
                    select.invokeExact(service,session);Object t=send.getFirst();
                    if(!previous){var heap=(PriorityQueue<Object>)order.get(session);if(heap!=null)heap.remove();}
                    offset.setInt(t,1);send.removeFirst();if(run>=0)checksum=checksum*31+ids.get(t);
                }
            }
            if(run>=0)samples[run]=System.nanoTime()-started;
        }
        report("selection",previous,bean,thread,runs,cpu,allocated,checksum,samples);
        var creditType=Class.forName("dev.voxydistant.server.RemoteServer$Credit");var cc=creditType.getDeclaredConstructors()[0];cc.setAccessible(true);var creditCancelled=field(creditType,"cancelled");
        for(int i=0;i<count;i++){offset.setInt(transfers.get(i),i<count/2?0:1);if(i>=count/2)inflight.put(i+1L,cc.newInstance(1L,System.nanoTime(),transfers.get(i)));}
        var cancels=new ArrayList<Protocol.Cancel>();
        for(int i=0;i<16;i++){var m=metadata.get(i%2==0?i:i+32).getLast();cancels.add(new Protocol.Cancel(m.x(),m.z(),i<8?m.requestId():-m.requestId()));}
        var packet=List.copyOf(cancels);checksum=0;
        for(int run=-100;run<runs;run++){
            if(run==0){cpu=bean.getCurrentThreadCpuTime();allocated=bean.getThreadAllocatedBytes(thread);}
            long started=System.nanoTime();send.clear();send.addAll(transfers);order.set(session,null);
            for(Object t:transfers)cancelled.set(t,null);for(Object c:inflight.values())creditCancelled.set(c,null);
            if(previous){for(Object item:packet)cancel.invokeExact(service,session,item);}else cancel.invokeExact(service,session,(Object)packet);
            if(run>=0){samples[run]=System.nanoTime()-started;long matched=0;for(Object t:transfers){var set=(Set<?>)cancelled.get(t);if(set!=null)matched+=set.size();}for(Object c:inflight.values()){var set=(Set<?>)creditCancelled.get(c);if(set!=null)matched+=set.size();}checksum=checksum*31+matched;}
        }
        report("cancel",previous,bean,thread,runs,cpu,allocated,checksum,samples);
        sessions.remove(player.getUUID());System.out.println("DISTANT_SERVER_HOTSPOT_PASS");
    }
    private static void report(String kind,boolean previous,com.sun.management.ThreadMXBean bean,long thread,int runs,long cpu,long allocated,long checksum,long[] samples){
        long elapsed=bean.getCurrentThreadCpuTime()-cpu,bytes=bean.getThreadAllocatedBytes(thread)-allocated;Arrays.sort(samples);
        System.out.printf(Locale.ROOT,"SERVER_HOTSPOT kind=%s mode=%s runs=%d transfers=64 members=128 cpu_ms=%.3f allocated_mib=%.3f queue_p99_ms=%.3f queue_max_ms=%.3f checksum=%d%n",kind,previous?"before":"after",runs,elapsed/1e6,bytes/1048576d,samples[(int)(runs*.99)-1]/1e6,samples[runs-1]/1e6,checksum);
    }
}
