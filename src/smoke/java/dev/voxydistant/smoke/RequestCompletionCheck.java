package dev.voxydistant.smoke;

import dev.voxydistant.client.RemoteClient;
import dev.voxydistant.compat.*;
import dev.voxydistant.data.*;
import dev.voxydistant.network.Protocol;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs against the real client receive lane before the normal acceptance session starts. */
public final class RequestCompletionCheck {
    private static Field field(Class<?> type,String name)throws ReflectiveOperationException{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    @SuppressWarnings("unchecked")
    public static CompletableFuture<Void> run()throws ReflectiveOperationException{
        var mc=Minecraft.getInstance();var sessionField=field(RemoteClient.class,"session");
        check(sessionField.get(null)==null,"check must run before enabling reception");
        var paused=field(RemoteClient.class,"maintenancePaused");boolean oldPaused=paused.getBoolean(null);
        boolean oldReceive=dev.voxydistant.config.DistantConfig.RECEIVE.get();
        var engine=VoxyBridge.acquire(mc.level);check(engine!=null,"real Voxy engine available");
        var type=Class.forName("dev.voxydistant.client.RemoteClient$Session");var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        var greeting=RemoteClient.greeting();var hello=new Protocol.Hello(greeting.world(),greeting.dimension(),64,mc.level.getMinSection(),mc.level.getMaxSection(),List.of("4:0","64:2"));
        Object session=constructor.newInstance(engine,mc.level,hello);int epoch=900000;
        field(type,"epoch").setInt(session,epoch);field(type,"radius").setInt(session,64);field(type,"x").setInt(session,0);
        field(type,"shape").set(session,dev.voxydistant.movement.RequestShape.circle(0,0,64));
        field(type,"generation").setInt(session,2);sessionField.set(null,session);paused.setBoolean(null,true);
        dev.voxydistant.config.DistantConfig.RECEIVE.set(true);
        var worker=(ThreadPoolExecutor)field(type,"worker").get(session);
        var invalid=(Set<Long>)field(type,"invalid").get(session);var checked=(Map<Long,Integer>)field(type,"checked").get(session);
        var versions=(Map<Long,Long>)field(type,"versions").get(session);var fullRetry=(Set<Long>)field(type,"fullRetry").get(session);
        var retries=(dev.voxydistant.client.RetryQueue)field(type,"retries").get(session);
        var pending=(Map<Long,Object>)field(type,"pending").get(session);
        var pendingType=Class.forName("dev.voxydistant.client.RemoteClient$Pending");var pendingConstructor=pendingType.getDeclaredConstructors()[0];pendingConstructor.setAccessible(true);
        // An older position requires L2; move into L0 range before delivering its response.
        mc.player.setPos(-256,180,0);
        int oldTarget=dev.voxydistant.config.DistanceBands.parse(hello.bands()).select(4,0,mc.player.chunkPosition().x,mc.player.chunkPosition().z);
        check(oldTarget==2,"old player position requests coarse level");
        long p=ChunkPos.asLong(4,0);Object original=pendingConstructor.newInstance(System.nanoTime(),oldTarget,1,0L);pending.put(p,original);
        mc.player.setPos(0,180,0);
        long version=1_000_000;
        var chain=CompletableFuture.completedFuture(null).thenRun(()->packet(epoch,1,column(mc,4,0,version,2),false));
        chain=chain.thenRunAsync(()->{},worker).thenRunAsync(()->{
            check(invalid.contains(p)&&checked.get(p)==2,"delayed single coarse response refines while stationary");invalid.clear();pending.put(p,original);
            packet(epoch,2,column(mc,4,0,version,2),true);
        },mc);
        chain=chain.thenRunAsync(()->{},worker).thenRunAsync(()->{
            check(invalid.contains(p),"delayed batch response refines while stationary");invalid.clear();pending.put(p,original);
            RemoteClient.reply(new Protocol.Reply(epoch,4,0,version,2,0));
        },mc);
        chain=chain.thenRunAsync(()->{},worker).thenRunAsync(()->{
            check(invalid.contains(p),"unchanged coarse reply refines while stationary");invalid.clear();pending.put(p,original);
            versions.put(p,version+1);RemoteClient.reply(new Protocol.Reply(epoch,4,0,version,0,0));
        },mc);
        chain=chain.thenRunAsync(()->{},worker).thenRunAsync(()->{
            check(invalid.contains(p)&&!checked.containsKey(p),"old unchanged reply cannot confirm new version");pending.put(p,original);
            packet(epoch,3,column(mc,4,0,version,0),true);
        },mc);
        chain=chain.thenRunAsync(()->{},worker).thenRunAsync(()->{
            check(invalid.contains(p)&&!checked.containsKey(p),"old batch cannot confirm new version");pending.put(p,original);
            packet(epoch,4,column(mc,4,0,version+1,0),false);
        },mc);
        chain=chain.thenRunAsync(()->{},worker).thenRunAsync(()->{
            check(!invalid.contains(p)&&checked.get(p)==0,"latest fine response satisfies stationary demand");
            long overlap=ChunkPos.asLong(55,0);
            try{pending.put(overlap,pendingConstructor.newInstance(System.nanoTime(),2,2,2L));}
            catch(ReflectiveOperationException e){throw new CompletionException(e);}
            RemoteClient.reply(new Protocol.Reply(epoch,55,0,0,5,1L,1));
            check(pending.containsKey(overlap),"late retry must not clear a newer request at the same coordinate");
            long missing=ChunkPos.asLong(60,0);pending.put(missing,original);RemoteClient.reply(new Protocol.Reply(epoch,60,0,0,5,2));
            check(!checked.containsKey(missing)&&retries.scheduled(missing),"unavailable is delayed, never fake L0");
            RemoteClient.dirty(new Protocol.Dirty(hello.dimension(),60,0,version,false));
            check(!retries.scheduled(missing)&&invalid.contains(missing),"dirty wakes sleeping retry");
            var chunk=mc.level.getChunk(0,0);pending.put(0L,original);RemoteClient.reply(new Protocol.Reply(epoch,0,0,version,5,2));
            check(fullRetry.contains(0L),"available vanilla chunk queues ingest");
            RemoteClient.unload(chunk);check(invalid.contains(0L)&&!fullRetry.contains(0L),"vanilla unload rechecks coverage");
            invalid.clear();pending.clear();
            try{
                for(int z=32;z<40;z++)for(int x=32;x<40;x++)invalid.add(ChunkPos.asLong(x,z));
                Object discovery=field(type,"discovery").get(session);var move=discovery.getClass().getDeclaredMethod("move",int.class,int.class,int.class);move.setAccessible(true);move.invoke(discovery,0,0,64);
                var regions=(Map<?,?>)field(discovery.getClass(),"regions").get(discovery);
                var local=discovery.getClass().getDeclaredMethod("local",Class.forName("dev.voxydistant.client.RegionDiscovery$Region"),CoverageStore.Directory.class);local.setAccessible(true);
                for(Object region:regions.values()){int rx=field(region.getClass(),"x").getInt(region),rz=field(region.getClass(),"z").getInt(region);local.invoke(discovery,region,VoxyBridge.coverage(engine).directory(rx,rz,false));}
                field(type,"requestBudget").setInt(session,64);
                var request=RemoteClient.class.getDeclaredMethod("requestMore",type);request.setAccessible(true);
                paused.setBoolean(null,false);request.invoke(null,session);paused.setBoolean(null,true);
                check(pending.size()==64,"one batch fills reserved scan and repair slots");
                long repairs=pending.keySet().stream().filter(v->ChunkPos.getX(v)>=32&&ChunkPos.getZ(v)>=32).count();
                check(repairs==32,"repair traffic leaves half batch for unvisited scan");
                request.invoke(null,session);check(pending.size()==64,"paused scheduler adds no work");
                sessionField.set(null,null);
            }catch(ReflectiveOperationException e){throw new CompletionException(e);}
            invalid.clear();
            packet(epoch,5,column(mc,4,0,version+2,2),false);
            RemoteClient.reply(new Protocol.Reply(epoch,4,0,version+2,2,0));
            check(invalid.isEmpty(),"old session packets ignored");
            try{sessionField.set(null,session);}catch(IllegalAccessException e){throw new CompletionException(e);}
            pending.clear();invalid.clear();
            System.out.println("DISTANT_REQUEST_COMPLETION_PASS: single/batch/unchanged stationary refinement, stale revision, unavailable, vanilla unload, dirty wake, scan fairness, session switch");
        },mc);
        long delayed=ChunkPos.asLong(55,0);
        chain=chain.thenRunAsync(()->{
            try{pending.put(delayed,pendingConstructor.newInstance(System.nanoTime(),2,2,2L));}
            catch(ReflectiveOperationException e){throw new CompletionException(e);}
            RemoteClient.reply(new Protocol.Reply(epoch,55,0,0,5,1L,0));
            packet(epoch,20,column(mc,55,0,version+3,2),false,1L);
            packet(epoch,21,column(mc,55,0,version+3,2),true,1L);
        },mc);
        chain=chain.thenRunAsync(()->{},worker).thenRunAsync(()->{
            check(pending.containsKey(delayed),"late unchanged and data cannot complete newer request");
            packet(epoch,22,column(mc,55,0,version+4,2),false,2L);
        },mc);
        chain=chain.thenRunAsync(()->{},worker).thenRunAsync(()->{
            check(!pending.containsKey(delayed),"newer request completes after late old responses");
            try{pending.put(delayed,pendingConstructor.newInstance(System.nanoTime(),2,2,4L));}
            catch(ReflectiveOperationException e){throw new CompletionException(e);}
            byte[] partial={1};
            RemoteClient.fragment(new Protocol.Fragment(epoch,23,55,0,version+5,2,3L,false,100,100,0,partial));
            RemoteClient.abort(new Protocol.Abort(epoch,23));
            check(pending.containsKey(delayed),"late abort cannot clear newer request");
        },mc);
        return chain.whenCompleteAsync((unused,error)->{
            try{sessionField.set(null,null);paused.setBoolean(null,oldPaused);field(type,"closed").setBoolean(session,true);}
            catch(ReflectiveOperationException e){throw new CompletionException(e);}
            finally{dev.voxydistant.config.DistantConfig.RECEIVE.set(oldReceive);worker.execute(engine::releaseRef);worker.shutdown();}
        },mc);
    }
    private static LodColumn column(Minecraft mc,int x,int z,long version,int level){
        long[][][] sections=new long[mc.level.getSectionsCount()][5][];
        for(var section:sections){section[level]=new long[4096>>(level*3)];Arrays.fill(section[level],LodColumn.voxel(1,0,0xf3,15));}
        return new LodColumn(x,z,mc.level.getMinSection(),version,List.of(Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState()),List.of("minecraft:plains"),sections);
    }
    private static void packet(int epoch,long transfer,LodColumn column,boolean batch){packet(epoch,transfer,column,batch,0L);}
    private static void packet(int epoch,long transfer,LodColumn column,boolean batch,long requestId){
        int level=column.minimumLevel();byte[] raw=ColumnCodec.encodeRaw(column,1<<level);
        var encoded=batch?BatchCodec.encode(List.of(raw),1):ColumnCodec.compress(raw,1);
        if(batch)RemoteClient.batchFragment(new Protocol.BatchFragment(epoch,transfer,encoded.compressed(),encoded.rawLength(),encoded.bytes().length,0,List.of(new Protocol.Member(column.x(),column.z(),column.version(),level,requestId,raw.length)),encoded.bytes()));
        else RemoteClient.fragment(new Protocol.Fragment(epoch,transfer,column.x(),column.z(),column.version(),level,requestId,encoded.compressed(),encoded.rawLength(),encoded.bytes().length,0,encoded.bytes()));
    }
}
