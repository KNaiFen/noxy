package dev.voxydistant.smoke;

import dev.voxydistant.client.RemoteClient;
import dev.voxydistant.config.DistantConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public final class LodClientBenchmark {
    private final PrintWriter frames,samples,threads;
    private final AtomicLong incoming=new AtomicLong(),outgoing=new AtomicLong();
    private long lastFrame,lastSample;
    private final Gson json=new Gson();
    private String lastCommand="";
    private JsonObject capture;
    private int auditCursor;
    private int[] auditedLevels;
    private Channel channel;
    private float cameraYaw;
    private double cameraX,cameraZ;
    private int auditedInsufficient;
    private boolean recording;
    private volatile boolean recordingPending;
    private volatile RuntimeException recordingFailure;
    private long lastRecordedFrame;
    private int recordedFrames;
    private Object voxyImporter;
    private me.cortex.voxy.commonImpl.ImportManager voxyImportManager;
    private java.lang.reflect.Field voxyImportTasks;
    private me.cortex.voxy.common.world.service.SectionSavingService voxySaving;
    private final PrintWriter importSamples;
    public LodClientBenchmark()throws IOException {
        importSamples=new PrintWriter(Files.newBufferedWriter(LodBenchmark.OUTPUT.resolve("voxy-import.csv")));
        importSamples.println("epoch_ms,time_ns,running,completed,total,estimated,backlog,service_jobs,saving_jobs,native_buffer_bytes");
        frames=new PrintWriter(Files.newBufferedWriter(LodBenchmark.OUTPUT.resolve("frames.csv")));
        frames.println("time_ns,frame_ns,in_world");
        samples=new PrintWriter(Files.newBufferedWriter(LodBenchmark.OUTPUT.resolve("client-samples.csv")));
        samples.println("time_ns,incoming_tcp_payload,outgoing_tcp_payload,heap_bytes,process_cpu_ns,applied,received,memory,pending,queue,active,scanning,invalid,mesh_queue,geometry_bytes,geometry_sections,receive_limit,snapshot_limit,advertised_credit,full_retry,retries,status");
        threads=new PrintWriter(Files.newBufferedWriter(LodBenchmark.OUTPUT.resolve("threads.csv")));
        threads.println("time_ns,id,name,cpu_ns");
        MinecraftForge.EVENT_BUS.addListener(this::frame);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{frames.close();samples.close();threads.close();importSamples.close();}));
    }
    private void frame(TickEvent.RenderTickEvent e) {
        if(e.phase!=TickEvent.Phase.END)return;
        if(recordingFailure!=null)throw recordingFailure;
        long now=System.nanoTime();if(lastFrame!=0)frames.printf(Locale.ROOT,"%d,%d,%s%n",now,now-lastFrame,Minecraft.getInstance().level!=null);lastFrame=now;
        if(capture==null&&recording&&!recordingPending&&now-lastRecordedFrame>=200_000_000L){
            lastRecordedFrame=now;recordingPending=true;capture=new JsonObject();
            capture.addProperty("id",String.format(Locale.ROOT,"record-%06d",recordedFrames++));
        }
        if(capture!=null){
            JsonObject request=capture;capture=null;
            var mc=Minecraft.getInstance();var state=state();
            String id=request.get("id").getAsString();
            var path=LodBenchmark.OUTPUT.resolve("screenshots").resolve(id+".png");
            try{
                var pixels=Screenshot.takeScreenshot(mc.getMainRenderTarget());
                int width=pixels.getWidth(),height=pixels.getHeight();
                net.minecraft.Util.ioPool().execute(()->{
                    try(pixels){
                        Files.createDirectories(path.getParent());pixels.writeToFile(path);
                        state.addProperty("path",path.toAbsolutePath().toString());state.addProperty("width",width);state.addProperty("height",height);
                        ack(id,"ok",state);
                        if(id.startsWith("record-"))recordingPending=false;
                    }catch(IOException|RuntimeException ex){captureFailed(id,ex);}
                });
            }catch(RuntimeException ex){captureFailed(id,ex);}
        }
    }
    private JsonObject state(){
        var mc=Minecraft.getInstance();var state=new JsonObject();
        state.addProperty("epoch_ms",System.currentTimeMillis());
        state.addProperty("nano_time",System.nanoTime());
        state.addProperty("screen",mc.screen==null?"":mc.screen.getClass().getSimpleName());
        state.addProperty("overlay",mc.getOverlay()==null?"":mc.getOverlay().getClass().getSimpleName());
        state.addProperty("ready",mc.screen instanceof TitleScreen&&mc.getOverlay()==null);
        state.addProperty("in_world",mc.level!=null&&mc.player!=null);
        state.addProperty("lod_hello",RemoteClient.greeting()!=null);
        if(RemoteClient.greeting()!=null){state.addProperty("hello_radius",RemoteClient.greeting().radius());state.add("hello_bands",json.toJsonTree(RemoteClient.greeting().bands()));}
        state.addProperty("status",RemoteClient.status());
        state.addProperty("receive_memory_mib",DistantConfig.RECEIVE_MIB.get());
        state.addProperty("request_window_columns",DistantConfig.REQUEST_WINDOW.get());
        state.addProperty("mask_bypass",Boolean.getBoolean("voxyDistant.maskBypass"));
        SettingsClientCheck.state(state);
        if(mc.screen instanceof ConnectScreen){
            var connection=(net.minecraft.network.Connection)LodMetrics.field(mc.screen,"connection");
            if(connection!=null&&connection.channel()!=null){
                state.addProperty("connection_auto_read",connection.channel().config().isAutoRead());
                state.addProperty("connection_protocol",String.valueOf(connection.channel().attr(net.minecraft.network.Connection.ATTRIBUTE_PROTOCOL).get()));
            state.addProperty("connection_open",connection.channel().isOpen());
                try{
                    var field=io.netty.channel.nio.AbstractNioChannel.class.getDeclaredField("selectionKey");field.setAccessible(true);
                    var key=(java.nio.channels.SelectionKey)field.get(connection.channel());
                    state.addProperty("connection_interest_ops",key.interestOps());
                }catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}
            }
        }
        if(mc.player!=null){state.addProperty("x",mc.player.getX());state.addProperty("y",mc.player.getY());state.addProperty("z",mc.player.getZ());state.addProperty("yaw",mc.player.getYRot());state.addProperty("pitch",mc.player.getXRot());}
        return state;
    }
    private void writeJson(Path path,JsonObject value)throws IOException{
        Files.createDirectories(path.getParent());Path temporary=path.resolveSibling(path.getFileName()+".tmp");
        Files.writeString(temporary,json.toJson(value));Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private void ack(String id,String status,JsonObject details)throws IOException{
        details.addProperty("id",id);details.addProperty("result",status);
        writeJson(LodBenchmark.OUTPUT.resolve("acks").resolve(id+".json"),details);
        System.out.println("DISTANT_CLIENT_ACK "+json.toJson(details));
    }
    private void captureFailed(String id,Exception error){
        if(id.startsWith("record-"))recordingFailure=new IllegalStateException("Process recording failed",error);
        com.mojang.logging.LogUtils.getLogger().error("Client acceptance screenshot failed: "+id,error);
        var details=new JsonObject();details.addProperty("error",error.toString());
        try{ack(id,"error",details);}catch(IOException ex){throw new UncheckedIOException(ex);}
    }
    private void tick(TickEvent.ClientTickEvent e) {
        if(e.phase!=TickEvent.Phase.END)return;
        if(Boolean.getBoolean("voxyDistant.fixedCamera")&&Minecraft.getInstance().player!=null){var p=Minecraft.getInstance().player;p.setPos(cameraX,180,cameraZ);p.setYRot(cameraYaw);p.setXRot(15);}
        var mc=Minecraft.getInstance();long now=System.nanoTime();if(now-lastSample<1_000_000_000)return;lastSample=now;
        try {
            // A Windows reader can deny replacement of an open file. Keep complete state records.
            TransportMetrics.sample();
            Files.writeString(LodBenchmark.OUTPUT.resolve("states.jsonl"),json.toJson(state())+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            if(mc.screen instanceof ConnectScreen)System.out.println("DISTANT_LOGIN_STATE "+json.toJson(state()));
            Path control=LodBenchmark.OUTPUT.resolve("client-command.json");
            if(Files.exists(control)) {
                var request=json.fromJson(Files.readString(control),JsonObject.class);String id=request.get("id").getAsString();
                // Consume before acknowledging; the next command then has no reader or old target.
                Files.delete(control);
                if(!id.equals(lastCommand)){
                    if(!id.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("Invalid acceptance command id");
                    lastCommand=id;String command=request.get("command").getAsString();
                    try{
                        if(command.equals("connect")){
                            if(!state().get("ready").getAsBoolean())throw new IllegalStateException("Client title screen not ready");
                            ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString("127.0.0.1:25576"),new ServerData("LOD Acceptance","127.0.0.1:25576",false),false);
                        }
                        else if(command.equals("open-world")){
                            if(!state().get("ready").getAsBoolean())throw new IllegalStateException("Client title screen not ready");
                            mc.createWorldOpenFlows().loadLevel(new TitleScreen(),request.get("world").getAsString());
                        }
                        else if(command.equals("voxy-import")){
                            if(!net.minecraftforge.client.ClientCommandHandler.runCommand("voxy import current"))throw new IllegalStateException("Voxy import command not handled");
                            var instance=me.cortex.voxy.commonImpl.VoxyCommon.getInstance();voxyImportManager=instance.getImportManager();
                            voxyImportTasks=me.cortex.voxy.commonImpl.ImportManager.class.getDeclaredField("activeImporters");voxyImportTasks.setAccessible(true);
                            synchronized(voxyImportManager){
                                var tasks=(Map<?,?>)voxyImportTasks.get(voxyImportManager);
                                if(tasks.size()!=1)throw new IllegalStateException("Voxy import did not start");
                                var importer=Class.forName("me.cortex.voxy.commonImpl.ImportManager$ImportTask").getDeclaredField("importer");importer.setAccessible(true);
                                voxyImporter=importer.get(tasks.values().iterator().next());
                            }
                            var saving=me.cortex.voxy.commonImpl.VoxyInstance.class.getDeclaredField("savingService");saving.setAccessible(true);
                            voxySaving=(me.cortex.voxy.common.world.service.SectionSavingService)saving.get(instance);
                        }
                        else if(command.equals("receive")){if(mc.level==null||RemoteClient.greeting()==null)throw new IllegalStateException("World or LOD handshake not ready");recording=Boolean.getBoolean("voxyDistant.recordProcess");DistantConfig.RECEIVE.set(true);}
                        else if(command.equals("disconnect")){if(mc.level==null)throw new IllegalStateException("Not in a world");mc.level.disconnect();mc.clearLevel();mc.setScreen(new TitleScreen());}
                        else if(command.equals("quit")){mc.stop();}
                        else if(command.equals("reload")){DistantConfig.reload();}
                        else if(command.equals("renderer")){dev.voxydistant.compat.VoxyBridge.resetRenderer();}
                        else if(command.equals("turn")){cameraYaw+=90;}
                        else if(command.equals("move-camera")){cameraX=request.get("x").getAsDouble();cameraZ=request.get("z").getAsDouble();mc.player.setPos(cameraX,180,cameraZ);}
                        else if(command.equals("mask-off")){System.setProperty("voxyDistant.maskBypass","true");}
                        else if(command.equals("mask-on")){System.setProperty("voxyDistant.maskBypass","false");}
                        else if(command.equals("audit")){auditCursor=0;auditedLevels=new int[6];auditedInsufficient=0;}
                        else if(command.equals("request-check")){
                            RequestCompletionCheck.run().whenComplete((unused,error)->{
                                try{var details=state();if(error!=null)details.addProperty("error",error.toString());ack(id,error==null?"ok":"error",details);}
                                catch(IOException ex){throw new UncheckedIOException(ex);}
                            });
                        }
                        else if(command.equals("screenshot")){capture=request;}
                        else if(command.startsWith("settings-")){try{SettingsClientCheck.command(request);}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}}
                        else throw new IllegalArgumentException("Unknown acceptance command "+command);
                        if(!command.equals("screenshot")&&!command.equals("request-check"))ack(id,"ok",state());
                    }catch(RuntimeException ex){com.mojang.logging.LogUtils.getLogger().error("Client acceptance command failed: "+id,ex);var details=state();details.addProperty("error",ex.toString());ack(id,"error",details);}
                }
            }
            if(mc.getConnection()!=null) {
                Channel current=(Channel)LodMetrics.field(mc.getConnection().getConnection(),"channel");
                if(current!=channel) {
                    channel=current;
                    channel.pipeline().addFirst("lod-benchmark-bytes",new ChannelDuplexHandler(){
                        @Override public void channelRead(ChannelHandlerContext ctx,Object msg)throws Exception {if(msg instanceof ByteBuf b)incoming.addAndGet(b.readableBytes());super.channelRead(ctx,msg);}
                        @Override public void write(ChannelHandlerContext ctx,Object msg,ChannelPromise promise)throws Exception {if(msg instanceof ByteBuf b)outgoing.addAndGet(b.readableBytes());super.write(ctx,msg,promise);}
                    });
                }
            }
            var f=RemoteClient.class.getDeclaredField("session");f.setAccessible(true);Object session=f.get(null);
            long applied=0,received=0,memory=0,receiveLimit=0,snapshotLimit=0;int pending=0,queue=0,active=0,invalid=0,credit=0,fullRetry=0,retries=0;boolean scanning=false;
            if(session!=null) {
                receiveLimit=(long)LodMetrics.field(session,"receiveLimit");snapshotLimit=(long)LodMetrics.field(session,"snapshotLimit");credit=(int)LodMetrics.field(session,"networkCapacity");
                fullRetry=((Set<?>)LodMetrics.field(session,"fullRetry")).size();
                retries=((dev.voxydistant.client.RetryQueue)LodMetrics.field(session,"retries")).size();
                applied=(long)LodMetrics.field(session,"applied");received=(long)LodMetrics.field(session,"received");memory=((AtomicLong)LodMetrics.field(session,"memory")).get();pending=((Map<?,?>)LodMetrics.field(session,"pending")).size();
                var worker=(ThreadPoolExecutor)LodMetrics.field(session,"worker");queue=worker.getQueue().size();active=worker.getActiveCount();
                var discovery=LodMetrics.field(session,"discovery");var busy=discovery.getClass().getDeclaredMethod("busy");busy.setAccessible(true);scanning=(boolean)busy.invoke(discovery);invalid=((Set<?>)LodMetrics.field(session,"invalid")).size();
                if(auditedLevels!=null){
                    int radius=(int)LodMetrics.field(session,"radius"),width=radius*2+1;
                    var engine=(me.cortex.voxy.common.world.WorldEngine)LodMetrics.field(session,"engine");
                    var coverage=dev.voxydistant.compat.VoxyBridge.coverage(engine);
                    for(int budget=0;budget<8192&&auditCursor<width*width;budget++,auditCursor++){
                        int dx=auditCursor%width-radius,dz=auditCursor/width-radius;
                        if(dx*dx+dz*dz>radius*radius)continue;
                        int x=mc.player.chunkPosition().x+dx,z=mc.player.chunkPosition().z+dz;
                        var stamp=coverage.column(x,z,mc.level.getMinSection(),mc.level.getMaxSection());auditedLevels[stamp.level()]++;
                        if(stamp.level()>((dev.voxydistant.config.DistanceBands)LodMetrics.field(session,"bands")).select(x,z,mc.player.chunkPosition().x,mc.player.chunkPosition().z))auditedInsufficient++;
                        if(stamp.level()==5)System.out.println("DISTANT_AUDIT_MISSING x="+x+" z="+z+" full_retry="+fullRetry+" queue="+queue+" active="+active);
                    }
                    if(auditCursor==width*width){
                        Files.writeString(LodBenchmark.OUTPUT.resolve("coverage.json"),"{\"radius\":"+radius+",\"insufficient\":"+auditedInsufficient+",\"level_counts\":"+Arrays.toString(auditedLevels)+"}");auditedLevels=null;
                        var diagnostic=new StringBuilder();
                        diagnostic.append("fullRetry=").append(((Set<?>)LodMetrics.field(session,"fullRetry")).size()).append(" versions=").append(((Map<?,?>)LodMetrics.field(session,"versions")).size()).append('\n');
                        var renderer=me.cortex.voxy.client.core.IGetVoxyRenderSystem.getNullable();diagnostic.append("same render engine=").append(renderer!=null&&LodMetrics.field(renderer,"worldIn")==engine).append('\n');
                        if(renderer!=null){var lines=new ArrayList<String>();renderer.addDebugInfo(lines);diagnostic.append(String.join("\n",lines)).append('\n');}
                        Files.writeString(LodBenchmark.OUTPUT.resolve("diagnostics.txt"),diagnostic);
                    }
                }
            }
            int meshQueue=0,geometrySections=0;long geometryBytes=0;
            var renderer=me.cortex.voxy.client.core.IGetVoxyRenderSystem.getNullable();
            if(renderer!=null){
                meshQueue=((me.cortex.voxy.client.core.rendering.building.RenderGenerationService)LodMetrics.field(renderer,"renderGen")).getTaskCount();
                geometryBytes=((me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager)LodMetrics.field(renderer,"nodeManager")).getUsedGeometryCapacity();
                geometrySections=((me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData)LodMetrics.field(renderer,"geometryData")).getSectionCount();
            }
            var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
            if(voxyImporter!=null){
                boolean importing;synchronized(voxyImportManager){importing=!((Map<?,?>)voxyImportTasks.get(voxyImportManager)).isEmpty();}
                importSamples.printf(Locale.ROOT,"%d,%d,%b,%s,%s,%s,%d,%d,%d,%d%n",System.currentTimeMillis(),now,importing,
                        LodMetrics.field(voxyImporter,"chunksProcessed"),LodMetrics.field(voxyImporter,"totalChunks"),LodMetrics.field(voxyImporter,"estimatedTotalChunks"),
                        ((Collection<?>)LodMetrics.field(voxyImporter,"jobQueue")).size(),((me.cortex.voxy.common.thread.Service)LodMetrics.field(voxyImporter,"service")).numJobs(),
                        voxySaving.getTaskCount(),me.cortex.voxy.common.util.MemoryBuffer.getTotalSize());
                importSamples.flush();
            }
            samples.printf(Locale.ROOT,"%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s%n",now,incoming.get(),outgoing.get(),ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),os.getProcessCpuTime(),applied,received,memory,pending,queue,active,scanning,invalid,meshQueue,geometryBytes,geometrySections,receiveLimit,snapshotLimit,credit,fullRetry,retries,RemoteClient.status());
            var bean=ManagementFactory.getThreadMXBean();for(var info:bean.dumpAllThreads(false,false))threads.printf(Locale.ROOT,"%d,%d,%s,%d%n",now,info.getThreadId(),info.getThreadName(),bean.getThreadCpuTime(info.getThreadId()));
            frames.flush();samples.flush();threads.flush();LodMetrics.flush();
        }catch(IOException|ReflectiveOperationException ex){throw new IllegalStateException(ex);}
    }
}
