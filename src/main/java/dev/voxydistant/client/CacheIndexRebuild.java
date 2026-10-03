package dev.voxydistant.client;

import com.mojang.logging.LogUtils;
import dev.voxydistant.compat.VoxyBridge;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/** A single explicit rebuild, bound to the level and connection that issued the command. */
public final class CacheIndexRebuild {
    private static final org.slf4j.Logger LOG=LogUtils.getLogger();
    private static Task active;
    public static void commands(RegisterClientCommandsEvent event){
        event.getDispatcher().register(Commands.literal("voxydistant")
                .then(Commands.literal("rebuildindex").executes(context->start(context.getSource()))));
    }
    private static int start(CommandSourceStack source){
        var mc=Minecraft.getInstance();
        if(mc.level==null){source.sendFailure(Component.literal("请先进入需要重建索引的世界"));return 0;}
        if(active!=null&&active.worker.isAlive()){source.sendFailure(Component.literal("已有索引重建正在进行，请等待完成"));return 0;}
        var world=VoxyBridge.acquire(mc.level);
        if(world==null){source.sendFailure(Component.literal("请先开启 Voxy 渲染与摄取"));return 0;}
        var task=new Task(world,mc.level,mc.getConnection());active=task;
        source.sendSuccess(()->Component.literal("开始在后台强制重建当前服务器 / 当前维度的 Voxy LOD 索引："+mc.level.dimension().location()),false);
        task.worker.start();return 1;
    }
    public static void tick(){
        var task=active;if(task==null)return;
        if(!task.current()||!VoxyBridge.enabled())task.cancelled=true;
        if(!task.worker.isAlive())active=null;
    }
    public static void shutdown(){
        var task=active;if(task==null)return;task.cancelled=true;
        try{task.worker.join();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        active=null;
    }
    private static final class Task {
        final WorldEngine world;
        final net.minecraft.client.multiplayer.ClientLevel level;
        final net.minecraft.client.multiplayer.ClientPacketListener connection;
        final String worldId;
        final Thread worker;
        volatile boolean cancelled;
        Task(WorldEngine world,net.minecraft.client.multiplayer.ClientLevel level,net.minecraft.client.multiplayer.ClientPacketListener connection){
            this.world=world;this.level=level;this.connection=connection;
            worldId=WorldIdentifier.of(level).getWorldId();
            worker=new Thread(this::run,"Voxy Distant rebuild index");worker.setDaemon(true);worker.setPriority(Thread.MIN_PRIORITY);
        }
        boolean current(){var mc=Minecraft.getInstance();return mc.level==level&&mc.getConnection()==connection&&WorldIdentifier.of(level).getWorldId().equals(worldId);}
        void message(String text){
            LOG.info("Voxy Distant {}：{}",level.dimension().location(),text);
            Minecraft.getInstance().execute(()->{if(current()&&!cancelled)Minecraft.getInstance().gui.getChat().addMessage(Component.literal(text));});
        }
        void run(){
            long started=System.nanoTime();
            try{
                var result=VoxyBridge.coverage(world).rebuildIndex(world,()->cancelled,p->message("索引重建中：已读取 "+p.sections()+" 个 Voxy LOD，覆盖页 "+p.pages()));
                if(!cancelled){RemoteClient.indexRebuilt(world);message(String.format(Locale.ROOT,"索引重建完成：读取 %,d 个 Voxy LOD，重建 %,d 页，耗时 %.1f 秒；旧缓存的未知版本仍会向服务器校验",result.sections(),result.pages(),(System.nanoTime()-started)/1e9));}
            }catch(CancellationException e){LOG.info("Voxy Distant 索引重建已取消：{}",level.dimension().location());}
            catch(RuntimeException e){LOG.error("Voxy Distant 索引重建失败：{}",level.dimension().location(),e);message("索引重建失败："+e.getMessage()+"；详情见日志");}
            finally{world.releaseRef();}
        }
    }
    private CacheIndexRebuild(){}
}
