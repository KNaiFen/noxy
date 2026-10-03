package dev.voxydistant.smoke;

import com.mojang.authlib.GameProfile;
import dev.voxydistant.data.*;
import dev.voxydistant.network.Protocol;
import dev.voxydistant.server.RemoteServer;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.*;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;
import java.lang.reflect.*;
import java.util.*;

/** Dedicated ModLauncher integration check; never included in the delivered mod. */
@Mod("distant_smoke")
public final class ServerSmoke {
    private static final net.minecraftforge.registries.DeferredRegister<net.minecraft.world.level.block.Block> BLOCKS = net.minecraftforge.registries.DeferredRegister.create(net.minecraftforge.registries.ForgeRegistries.BLOCKS,"distant_smoke");
    static final net.minecraftforge.registries.RegistryObject<net.minecraft.world.level.block.Block> IMPORT_BLOCK = BLOCKS.register("import_block",()->new net.minecraft.world.level.block.Block(net.minecraft.world.level.block.state.BlockBehaviour.Properties.copy(net.minecraft.world.level.block.Blocks.STONE)));
    private net.minecraft.server.MinecraftServer server;
    private int ticks,stage;
    private long start,oldVersion,lightVersion;
    private Long unloadedDirtyVersion;
    private boolean benchmark;
    private final List<Peer> peers=new ArrayList<>();
    private final List<Protocol.Fragment> fragments=new ArrayList<>();
    private final Queue<Runnable> receipts=new ArrayDeque<>();
    private int batchCase,batchRetryPhase;private long batchRetryCharge;private Peer batchPeer;private int expectedBatchColumns;
    private ServerSettingsSmoke settingsSmoke;
    private MaintenanceSmoke maintenanceSmoke;
    private int isolationCase,isolationTick;
    private Peer isolationPeer;
    private dev.voxydistant.config.ServerSettings.Snapshot isolationSettings;
    private static Object field(Object object,String name)throws ReflectiveOperationException{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static Object current()throws ReflectiveOperationException{var f=RemoteServer.class.getDeclaredField("instance");f.setAccessible(true);return f.get(null);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public ServerSmoke(){
        var regionBuffer=new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try{long[] versions=new long[1024];byte[] masks=new byte[1024];for(int i=0;i<1024;i++){versions[i]=i+9000;masks[i]=(byte)(i%32);}
            Protocol.writeRegion(new Protocol.RegionSummary(7,99,-4,3,versions,masks),regionBuffer);check(regionBuffer.readableBytes()==9236,"region summary fragment size");
            var decoded=Protocol.readRegion(regionBuffer);check(decoded.x()==-4&&decoded.id()==99&&Arrays.equals(versions,decoded.versions())&&Arrays.equals(masks,decoded.masks()),"region summary round trip");
        }finally{regionBuffer.release();}
        BLOCKS.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        if(Boolean.getBoolean("voxyDistant.lodBenchmark")){new LodBenchmark();return;}
        if(Boolean.getBoolean("voxyDistant.zstdBenchmark")){new ZstdBenchmark();return;}
        if(Boolean.getBoolean("voxyDistant.clientSmoke")){
            try{for(String name:List.of("common.world.WorldEngine","common.world.WorldSection","common.world.WorldUpdater","common.world.service.VoxelIngestService","common.world.service.SectionSavingService","commonImpl.VoxyInstance","commonImpl.WorldIdentifier","client.core.rendering.hierachical.NodeStore","client.core.rendering.hierachical.NodeManager","client.core.rendering.hierachical.AsyncNodeManager","client.core.rendering.building.RenderDataFactory","client.core.rendering.building.BuiltSection"))Class.forName("me.cortex.voxy."+name,false,getClass().getClassLoader());}
            catch(ClassNotFoundException e){throw new IllegalStateException(e);}
            System.out.println("DISTANT_CLIENT_MIXINS_PASS: all pinned Voxy targets transformed without creating a window");
            try{Class.forName("dev.voxydistant.smoke.ClientSmoke").getMethod("run").invoke(null);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
            return;
        }
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e)->server=e.getServer());MinecraftForge.EVENT_BUS.addListener(this::tick);
    }
    private final class Peer extends Connection {
        final ServerPlayer player;final List<LodColumn> columns=new ArrayList<>();final List<Protocol.RegionSummary> summaries=new ArrayList<>();final Map<Long,byte[]> buffers=new HashMap<>();final Map<Long,Protocol.BatchFragment> headers=new HashMap<>();final List<Integer> batches=new ArrayList<>();long bytes;boolean retry;int retries,aborts;
        Peer(int index){super(PacketFlow.SERVERBOUND);player=new ServerPlayer(server,server.overworld(),new GameProfile(new UUID(0,index+1),"Smoke"+index));player.setPos(0,100,0);player.connection=new ServerGamePacketListenerImpl(server,this,player);}
        @Override public void send(Packet<?> packet){capture(packet);}
        @Override public void send(Packet<?> packet,PacketSendListener listener){capture(packet);}
        private void capture(Packet<?> packet){
            if(!(packet instanceof ClientboundCustomPayloadPacket payload))return;
            var b=new FriendlyByteBuf(payload.getData().duplicate());int id=b.readVarInt();
            if(id==14){summaries.add(Protocol.readRegion(b));return;}
            if(id==3){b.readInt();b.readInt();b.readInt();b.readLong();b.readByte();b.readLong();retry=b.readByte()==1;if(retry)retries++;return;}
            if(id==6){b.readInt();long transfer=b.readLong();buffers.remove(transfer);headers.remove(transfer);aborts++;return;}
            if(id==7){
                var f=Protocol.readBatch(b);check(f.bytes().length<=32768,"batch fragment bound");bytes+=f.bytes().length+128+(f.offset()==0?21*f.members().size():0);
                if(f.offset()==0){var unique=new HashSet<Long>();for(var m:f.members())check(unique.add(net.minecraft.world.level.ChunkPos.asLong(m.x(),m.z())),"no duplicate coordinate in wire batch");headers.put(f.transfer(),f);batches.add(f.members().size());}
                byte[] target=buffers.computeIfAbsent(f.transfer(),k->new byte[f.totalLength()]);System.arraycopy(f.bytes(),0,target,f.offset(),f.bytes().length);
                if(f.offset()+f.bytes().length==target.length){
                    var h=headers.remove(f.transfer());var raw=java.nio.ByteBuffer.wrap(ColumnCodec.uncompress(new ColumnCodec.Encoded(h.compressed(),h.rawLength(),target)));
                    check(raw.getInt()==h.members().size(),"batch frame count");
                    for(var member:h.members()){int length=raw.getInt();check(length==member.rawLength(),"batch member length");byte[] value=new byte[length];raw.get(value);var column=ColumnCodec.decodeRaw(value,31);check(column.x()==member.x()&&column.z()==member.z()&&column.version()==member.version()&&column.minimumLevel()==member.level(),"batch metadata round trip");columns.add(column);}
                    check(!raw.hasRemaining(),"batch frame complete");buffers.remove(f.transfer());receipts.add(()->RemoteServer.receipt(player,new Protocol.Receipt(f.epoch(),f.transfer())));
                }
                return;
            }
            if(id!=2)return;
            var f=new Protocol.Fragment(b.readInt(),b.readLong(),b.readInt(),b.readInt(),b.readLong(),b.readUnsignedByte(),b.readLong(),b.readBoolean(),b.readInt(),b.readInt(),b.readInt(),b.readByteArray(32768));
            fragments.add(f);bytes+=f.bytes().length+128;check(f.bytes().length<=32768,"fragment bound");
            if(benchmark){if(f.offset()+f.bytes().length==f.totalLength())receipts.add(()->RemoteServer.receipt(player,new Protocol.Receipt(f.epoch(),f.transfer())));return;}
            byte[] target=buffers.computeIfAbsent(f.transfer(),k->new byte[f.totalLength()]);System.arraycopy(f.bytes(),0,target,f.offset(),f.bytes().length);
            if(f.offset()+f.bytes().length==target.length){columns.add(ColumnCodec.decode(new ColumnCodec.Encoded(f.compressed(),f.rawLength(),target)));buffers.remove(f.transfer());receipts.add(()->RemoteServer.receipt(player,new Protocol.Receipt(f.epoch(),f.transfer())));}
        }
    }
    @SuppressWarnings("unchecked") private void tick(TickEvent.ServerTickEvent event){
        if(server==null||event.phase!=TickEvent.Phase.END)return;
        try{
            while(!receipts.isEmpty())receipts.remove().run();
            if(++ticks>2400)throw new AssertionError("server integration timed out: "+RemoteServer.status());
            Object service=current();if(service==null||field(service,"world")==null)return;
            for(Peer p:peers)if(p.retry){p.retry=false;RemoteServer.requests(p.player,new Protocol.Requests(1,96,2,0,32<<20,List.of(new Protocol.Want(40,40,0,5))));}
            if(ticks%200==0)System.out.println("DISTANT_SMOKE_PROGRESS stage="+stage+" "+RemoteServer.status());
            if(stage==0){
                dev.voxydistant.config.DistantConfig.TOTAL_MBPS.set(8.0);
                dev.voxydistant.config.DistantConfig.MAX_BATCH_COLUMNS.set(1);
                dev.voxydistant.config.DistantConfig.COMPRESSION_LEVEL.set(9);
                // Keep incidental spawn refresh work from changing the foreground conversion counter.
                var missing=(Set<?>)field(service,"missingChecks");missing.clear();
                var dirty=(Map<?,?>)field(service,"dirty");dirty.clear();
                priorityCheck();pumpCheck(service);fragmentSliceCheck();
                var sessions=(Map<UUID,Object>)field(service,"players");var type=Class.forName("dev.voxydistant.server.RemoteServer$Session");var ctor=type.getDeclaredConstructor(ServerPlayer.class);ctor.setAccessible(true);
                for(int i=0;i<2;i++){Peer p=new Peer(i);peers.add(p);sessions.put(p.player.getUUID(),ctor.newInstance(p.player));RemoteServer.requests(p.player,new Protocol.Requests(1,96,2,0,32<<20,List.of(new Protocol.Want(40,40,0,5))));}
                start=System.nanoTime();stage=1;
            }
            if(stage==1&&peers.stream().allMatch(p->!p.columns.isEmpty())){
                var a=peers.getFirst().columns.getFirst();var b=peers.getLast().columns.getFirst();
                check(a.version()==b.version(),"overlapping players share revision");check(a.mask()==2,"distance requests exactly L1");
                check(((Long)field(service,"completed"))==1,"overlapping generation must convert once");
                oldVersion=a.version();
                var db=(LodDatabase)field(service,"database");var stored=ColumnCodec.decode(LodDatabase.unpack(db.get(LodDatabase.key("minecraft:overworld",net.minecraft.world.level.ChunkPos.asLong(40,40),1))));check(stored.mask()==31,"server stores all levels");
                // Both players request the same cached column, but now require different levels.
                peers.getLast().player.setPos(640,100,640);
                for(var p:peers)RemoteServer.requests(p.player,new Protocol.Requests(1,96,2,0,32<<20,List.of(new Protocol.Want(40,40,0,5,0,p==peers.getLast()?3:0))));
                stage=5;
            }
            if(stage==5&&peers.stream().allMatch(p->p.columns.size()>=2)){
                check(peers.getFirst().columns.getLast().mask()==2,"cached distant player receives L1");
                if(peers.getLast().columns.stream().noneMatch(c->c.mask()==8))return;
                check(peers.getLast().columns.stream().anyMatch(c->c.mask()==8),"client can request coarser L3 inside server L0 band");
                check(peers.stream().allMatch(p->p.columns.getLast().version()==oldVersion),"cached players share revision");
                check(((Long)field(service,"completed"))==1,"cached different levels do not regenerate");
                peers.getLast().player.setPos(0,100,0);
                check(server.overworld().getChunkSource().getChunkNow(40,40)==null,"cached column stays unloaded");
                unloadedDirtyVersion=(Long)((Map<?,?>)field(service,"dirty")).get(new RemoteServer.Key(net.minecraft.world.level.Level.OVERWORLD,40,40));
                RemoteServer.lightDirty(server.overworld(),40,40);stage=6;
            }
            if(stage==6&&!((Set<?>)field(service,"lightChanges")).contains(new RemoteServer.Key(net.minecraft.world.level.Level.OVERWORLD,40,40))){
                var key=new RemoteServer.Key(net.minecraft.world.level.Level.OVERWORLD,40,40);
                var dirtyVersion=((Map<?,?>)field(service,"dirty")).get(key);
                check(dirtyVersion==null||Objects.equals(dirtyVersion,unloadedDirtyVersion),"unloaded light notification does not add a new invalidation");
                var pos=new net.minecraft.world.level.ChunkPos(40,40);server.overworld().getChunkSource().addRegionTicket(TicketType.FORCED,pos,0,pos);stage=4;
            }
            if(stage==4){
                var chunk=server.overworld().getChunkSource().getChunkNow(40,40);if(chunk==null||!chunk.isLightCorrect())return;
                chunk.setBlockState(new net.minecraft.core.BlockPos(640,100,640),net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(),false);
                lightVersion=(Long)((Map<?,?>)field(service,"versions")).get(new RemoteServer.Key(net.minecraft.world.level.Level.OVERWORLD,40,40));
                RemoteServer.lightDirty(server.overworld(),40,40);stage=7;
            }
            if(stage==7&&!((Set<?>)field(service,"lightChanges")).contains(new RemoteServer.Key(net.minecraft.world.level.Level.OVERWORLD,40,40))){
                var key=new RemoteServer.Key(net.minecraft.world.level.Level.OVERWORLD,40,40);
                check(((Long)((Map<?,?>)field(service,"versions")).get(key))>lightVersion,"loaded light notification advances revision");
                lightVersion=(Long)((Map<?,?>)field(service,"versions")).get(key);stage=8;
            }else if(stage==8){
                var chunk=server.overworld().getChunkSource().getChunkNow(40,40);
                var biome=server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME).getHolderOrThrow(net.minecraft.world.level.biome.Biomes.DESERT);
                chunk.fillBiomesFromNoise((x,y,z,sampler)->biome,server.overworld().getChunkSource().randomState().sampler());
                server.overworld().getChunkSource().chunkMap.resendBiomesForChunks(List.of(chunk));
                check(((Long)((Map<?,?>)field(service,"versions")).get(new RemoteServer.Key(net.minecraft.world.level.Level.OVERWORLD,40,40)))>lightVersion,"biome synchronization advances revision");
                for(var p:peers)RemoteServer.requests(p.player,new Protocol.Requests(1,96,2,0,32<<20,List.of(new Protocol.Want(40,40,oldVersion,2))));stage=2;
            }
            if(stage==2&&peers.stream().allMatch(p->p.columns.size()>=3)){
                check(peers.getFirst().columns.getLast().version()>oldVersion,"dirty revision advances");
                check(peers.getFirst().columns.getLast().version()==peers.getLast().columns.getLast().version(),"dirty players share the same rebuild revision");
                long sent=peers.stream().mapToLong(p->p.bytes).sum();double seconds=(System.nanoTime()-start)/1e9;
                check(sent<=1_000_000*(seconds+.2),"global byte rate");
                requestWindowCheck(service);
                stage=9;batchCheck(service);return;
            }
            if(stage==9){if(!batchCheck(service))return;stage=12;}
            if(stage==12){if(!regionTestStarted&&!cacheIsolationCheck(service))return;
                var peer=peers.getFirst();
                if(!regionTestStarted){
                    var config=dev.voxydistant.config.DistantConfig.SPEC.getSpec().<net.minecraftforge.common.ForgeConfigSpec.ValueSpec>get(dev.voxydistant.config.DistantConfig.REGION_QUERY_WINDOW.getPath());
                    check(config.test(1)&&config.test(32)&&!config.test(0)&&!config.test(33),"region query config range");
                    var sessions=(Map<UUID,Object>)field(service,"players");var session=sessions.get(peer.player.getUUID());
                    for(int i=0;i<=Protocol.MAX_REGION_QUERIES;i++)RemoteServer.regionQuery(peer.player,new Protocol.RegionQuery(1,70001+i,1,1));
                    check(((Map<?,?>)field(session,"directories")).size()+((Deque<?>)field(session,"directoryReplies")).size()==32,"server accepts 32 region queries and bounds overflow");
                    RemoteServer.regionCancel(peer.player,new Protocol.RegionCancel(1,List.of(70032L)));
                    RemoteServer.regionQuery(peer.player,new Protocol.RegionQuery(1,70034,1,1));
                    regionTestStarted=true;return;
                }
                if(peer.summaries.size()<Protocol.MAX_REGION_QUERIES)return;
                check(peer.summaries.size()==32&&peer.summaries.stream().noneMatch(summary->summary.id()==70033||summary.id()==70032),"region query cancel frees window and suppresses cancelled reply");
                var summary=peer.summaries.stream().filter(reply->reply.id()==70001).findFirst().orElseThrow();check(summary.epoch()==1&&summary.x()==1&&summary.z()==1,"region query lifecycle");
                int slot=(40&31)|((40&31)<<5);check(summary.versions()[slot]>=oldVersion,"region metadata includes cached revision");
                System.out.println("DISTANT_REGION_DIRECTORY_PASS: config range 1-32, 32 queries complete, overflow bounded, migration, summary round trip, revision and transport budget");
                benchmark(service);
                for(var p:peers){var remove=RemoteServer.class.getDeclaredMethod("removePlayer",UUID.class);remove.setAccessible(true);remove.invoke(service,p.player.getUUID());}
                check(((Map<?,?>)field(service,"players")).isEmpty(),"logout cleanup");
                var pos=new net.minecraft.world.level.ChunkPos(40,40);server.overworld().getChunkSource().removeRegionTicket(TicketType.FORCED,pos,0,pos);
                settingsSmoke=new ServerSettingsSmoke(server,service);stage=10;
            }
            if(stage==10&&settingsSmoke.tick()){
                maintenanceSmoke=new MaintenanceSmoke(server,service);stage=11;
            }
            if(stage==11&&maintenanceSmoke.tick()){
                System.out.println("DISTANT_SMOKE_PASS: generation/cache, mixed-level fragments, invalidation, queues, batches, settings, byte limit and cleanup");
                stage=3;server.halt(false);
            }
        }catch(Exception|AssertionError e){e.printStackTrace();System.out.println("DISTANT_SMOKE_FAIL");stage=3;server.halt(false);server=null;}
    }
    private boolean regionTestStarted;
    private void fragmentSliceCheck(){
        byte[] data=new byte[70000];new Random(483).nextBytes(data);
        for(int length:new int[]{1,127,128,32768}){
            int offset=17003;byte[] slice=Arrays.copyOfRange(data,offset,offset+length);
            var actual=new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());var expected=new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try{
                Protocol.CHANNEL.encodeMessage(new Protocol.Fragment(7,91,40,-8,19,2,93,false,70000,70000,offset,data,offset,length),actual);
                Protocol.CHANNEL.encodeMessage(new Protocol.Fragment(7,91,40,-8,19,2,93,false,70000,70000,offset,slice),expected);
                check(io.netty.buffer.ByteBufUtil.equals(actual,expected),"single fragment slice keeps exact wire bytes");
                for(int transferOffset:new int[]{0,offset}){
                    actual.clear();expected.clear();var members=transferOffset==0?List.of(new Protocol.Member(40,-8,19,2,93,70000)):List.<Protocol.Member>of();
                    Protocol.writeBatch(new Protocol.BatchFragment(7,91,false,70000,70000,transferOffset,members,data,offset,length),actual);
                    Protocol.writeBatch(new Protocol.BatchFragment(7,91,false,70000,70000,transferOffset,members,slice),expected);
                    check(io.netty.buffer.ByteBufUtil.equals(actual,expected),"batch fragment slice keeps exact wire bytes");
                    var decoded=Protocol.readBatch(actual);check(Arrays.equals(decoded.bytes(),slice)&&decoded.members().equals(members),"slice decodes as bounded fragment");
                }
            }finally{actual.release();expected.release();}
        }
        System.out.println("DISTANT_FRAGMENT_SLICE_PASS: exact single/batch wire bytes, headers, varint boundaries and decoding");
    }
    private void pumpCheck(Object service)throws Exception{
        var complete=RemoteServer.class.getDeclaredMethod("complete",Runnable.class);complete.setAccessible(true);
        var pump=RemoteServer.class.getDeclaredMethod("pump");pump.setAccessible(true);
        var executed=new ArrayList<Integer>();int before=server.getPendingTasksCount();
        var futures=new ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        for(int i=0;i<4;i++){int worker=i;futures.add(java.util.concurrent.CompletableFuture.runAsync(()->{
            for(int n=0;n<32;n++){int id=worker*32+n;try{complete.invoke(service,(Runnable)()->{
                check(server.isSameThread(),"completion commits on server thread");executed.add(id);
            });}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
        }));}
        java.util.concurrent.CompletableFuture.allOf(futures.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
        check(server.getPendingTasksCount()==before+1,"128 worker completions queue one server task");
        pump.invoke(service);check(executed.size()==64,"completion pass commits at most 64 results");
        pump.invoke(service);check(executed.size()==128&&new HashSet<>(executed).size()==128,"all concurrent completions commit exactly once");
        for(int worker=0;worker<4;worker++){int previous=-1;for(int id:executed)if(id/32==worker){check(id>previous,"per-worker completion order");previous=id;}}
        complete.invoke(service,(Runnable)()->executed.add(128));pump.invoke(service);
        check(executed.size()==129,"completion after clearing scheduled flag still wakes server");
        System.out.println("DISTANT_SERVER_PUMP_PASS: concurrent completions, one wakeup, bounded commit, exact release and server ownership");
    }
    @SuppressWarnings("unchecked") private boolean cacheIsolationCheck(Object service)throws ReflectiveOperationException{
        var sessions=(Map<UUID,Object>)field(service,"players");
        var work=(Map<RemoteServer.Key,Object>)field(service,"work");
        if(isolationCase==0){
            if(!work.isEmpty())return false;
            isolationSettings=dev.voxydistant.config.ServerSettings.current();
            dev.voxydistant.config.DistantConfig.PLAYER_CONCURRENCY.set(1);
            dev.voxydistant.config.DistantConfig.SERVER_CONCURRENCY.set(1);
            dev.voxydistant.config.DistantConfig.SERVER_QUEUE.set(8);
            dev.voxydistant.config.DistantConfig.AUTO_THROTTLE.set(true);
            dev.voxydistant.config.DistantConfig.RESUME_SECONDS.set(60.0);
            dev.voxydistant.config.DistantConfig.MAX_BATCH_COLUMNS.set(16);
            var governor=(dev.voxydistant.server.LoadGovernor)field(service,"governor");
            governor.tick(System.nanoTime(),100,new dev.voxydistant.server.LoadGovernor.Settings(true,40,45,35,19,19.5,1,1,60,5));
            isolationPeer=new Peer(90);var type=Class.forName("dev.voxydistant.server.RemoteServer$Session");var ctor=type.getDeclaredConstructor(ServerPlayer.class);ctor.setAccessible(true);
            sessions.put(isolationPeer.player.getUUID(),ctor.newInstance(isolationPeer.player));
            RemoteServer.requests(isolationPeer.player,new Protocol.Requests(1,96,2,0,120<<20,List.of(new Protocol.Want(-80,0,0,5),new Protocol.Want(-81,0,0,5),new Protocol.Want(-82,0,0,5),new Protocol.Want(-83,0,0,5),new Protocol.Want(40,40,0,5))));
            isolationTick=ticks;isolationCase=1;return false;
        }
        check(ticks-isolationTick<160,"cache lane must make progress within eight seconds");
        Object s=sessions.get(isolationPeer.player.getUUID());
        if(isolationCase==1){
            if(isolationPeer.columns.isEmpty()||!((Map<?,?>)field(s,"pending")).isEmpty()||(int)field(s,"cacheActive")!=0)return false;
            check(isolationPeer.columns.getFirst().x()==40&&isolationPeer.columns.getFirst().z()==40,"cached data delivered past missing columns");
            check(((dev.voxydistant.server.LoadGovernor)field(service,"governor")).state()==dev.voxydistant.server.LoadGovernor.State.PAUSED,"cache transmitted while governor paused");
            check((int)field(s,"generating")==1&&isolationPeer.retries>=1,"missing work bounded independently of cache reads");
            check((int)field(s,"cacheActive")==0&&(long)field(s,"cacheReadBytes")==0,"cache reservation released");
            Object stuck=work.get(new RemoteServer.Key(net.minecraft.world.level.Level.OVERWORLD,-80,0));
            var detach=RemoteServer.class.getDeclaredMethod("accept",ServerPlayer.class,Protocol.Requests.class);detach.setAccessible(true);
            detach.invoke(service,isolationPeer.player,new Protocol.Requests(1,96,2,0,120<<20,List.of(),List.of(new Protocol.Cancel(-81,0,0),new Protocol.Cancel(-82,0,0))));
            check(!(boolean)field(stuck,"ticket"),"paused generation has no chunk ticket");
            var start=stuck.getClass().getDeclaredField("generationStarted");start.setAccessible(true);
            start.setLong(stuck,System.nanoTime()-java.util.concurrent.TimeUnit.SECONDS.toNanos(61));
            isolationCase=2;return false;
        }
        if(isolationCase==2){
            if(!work.isEmpty())return false;
            check((int)field(service,"generationActive")==0&&(long)field(service,"memory")==0,"timed out generation releases slot and snapshot reservation");
            check((int)field(s,"active")==0&&isolationPeer.retries>=2,"timed out request is retriable");
            // Hold a real ticket and occupy the only generation slot while another cache hit streams.
            dev.voxydistant.config.DistantConfig.AUTO_THROTTLE.set(false);
            RemoteServer.requests(isolationPeer.player,new Protocol.Requests(1,96,2,0,120<<20,List.of(new Protocol.Want(-70,0,0,5))));
            isolationCase=3;return false;
        }
        if(isolationCase==3){
            Object stuck=work.get(new RemoteServer.Key(net.minecraft.world.level.Level.OVERWORLD,-70,0));
            if(stuck==null||!(boolean)field(stuck,"ticket"))return false;
            RemoteServer.requests(isolationPeer.player,new Protocol.Requests(1,96,2,0,120<<20,List.of(new Protocol.Want(40,40,0,5))));
            isolationCase=4;return false;
        }
        if(isolationCase==4){
            if(isolationPeer.columns.stream().filter(c->c.x()==40&&c.z()==40).count()<2)return false;
            RemoteServer.requests(isolationPeer.player,new Protocol.Requests(2,96,2,0,120<<20,List.of()));
            check((int)field(s,"active")==0&&(int)field(s,"cacheActive")==0&&(int)field(s,"generating")==0,"epoch detaches both lanes");
            isolationCase=5;return false;
        }
        if(!work.isEmpty())return false;
        check((int)field(service,"cacheActive")==0&&(int)field(service,"generationActive")==0&&(long)field(service,"cacheReadMemory")==0&&(long)field(service,"memory")==0,"both lanes fully release after cancellation");
        sessions.remove(isolationPeer.player.getUUID());
        var values=isolationSettings.validate();
        for(int i=0;i<values.size();i++)((net.minecraftforge.common.ForgeConfigSpec.ConfigValue<Object>)dev.voxydistant.config.ServerSettings.FIELDS.get(i).value()).set(values.get(i));
        System.out.println("DISTANT_CACHE_ISOLATION_PASS: cache transfer during TPS pause, past missing columns and occupied generation slot; timeout, retry, epoch and memory release");
        return true;
    }
    private static void priorityCheck()throws ReflectiveOperationException{
        var type=net.minecraft.server.level.ChunkTaskPriorityQueue.class;var ctor=type.getDeclaredConstructor(String.class,int.class);ctor.setAccessible(true);Object queue=ctor.newInstance("distant smoke",16);
        var context=new dev.voxydistant.server.PriorityState.Context();context.view=new dev.voxydistant.server.PriorityState.View(1,List.of(new dev.voxydistant.server.PriorityState.Area(0,0,4)),List.of(new dev.voxydistant.server.PriorityState.Area(32,0,8)));
        ((dev.voxydistant.server.PriorityState.QueueAccess)queue).distant$priority(context);
        var submit=type.getDeclaredMethod("submit",Optional.class,long.class,int.class);submit.setAccessible(true);
        long remote=net.minecraft.world.level.ChunkPos.asLong(32,0),normal=net.minecraft.world.level.ChunkPos.asLong(1,1);
        submit.invoke(queue,Optional.of("extra"),remote,0);submit.invoke(queue,Optional.of("normal dependency"),normal,10);
        var pop=type.getDeclaredMethod("pop");pop.setAccessible(true);
        var first=(java.util.stream.Stream<?>)pop.invoke(queue);var value=(com.mojang.datafixers.util.Either<?,?>)first.findFirst().orElseThrow();check(value.left().orElseThrow().equals("normal dependency"),"normal dependency outranks extra even at worse vanilla priority");
        context.view=new dev.voxydistant.server.PriorityState.View(2,List.of(new dev.voxydistant.server.PriorityState.Area(32,0,8)),context.view.extra());
        var second=(java.util.stream.Stream<?>)pop.invoke(queue);check(second.findFirst().isPresent(),"queued extra promoted when player approaches");
        System.out.println("DISTANT_PRIORITY_PASS: transformed vanilla queue prioritizes normal dependencies and promotes queued work");
    }
    @SuppressWarnings("unchecked") private void requestWindowCheck(Object service)throws ReflectiveOperationException{
        var sessions=(Map<UUID,Object>)field(service,"players");var type=Class.forName("dev.voxydistant.server.RemoteServer$Session");var ctor=type.getDeclaredConstructor(ServerPlayer.class);ctor.setAccessible(true);
        var accept=RemoteServer.class.getDeclaredMethod("accept",ServerPlayer.class,Protocol.Requests.class);accept.setAccessible(true);
        var requestTick=type.getDeclaredField("requestTick");requestTick.setAccessible(true);
        for(int i=0;i<8;i++){
            Peer p=new Peer(10+i);Object session=ctor.newInstance(p.player);sessions.put(p.player.getUUID(),session);
            var scan=new dev.voxydistant.generation.NearbyChunks(64,2);var wants=new ArrayList<Protocol.Want>();
            for(int j=0;j<257;j++){var pos=scan.next();wants.add(new Protocol.Want(pos.x(),pos.z(),0,5));}
            for(int batch=0;batch<4;batch++)accept.invoke(service,p.player,new Protocol.Requests(1,96,2,0,120<<20,List.copyOf(wants.subList(batch*64,(batch+1)*64))));
            check(((Map<?,?>)field(session,"pending")).size()==256,"each session accepts four 64-column messages");
            check((int)field(session,"capacity")==120<<20,"120 MiB receive credit is not capped");
            var pending=(Map<?,?>)field(session,"pending");pending.clear();
            accept.invoke(service,p.player,new Protocol.Requests(1,96,2,0,120<<20,List.of(wants.get(256))));
            check(p.retries==1&&pending.isEmpty(),"257th request returns retry even when the pending queue is empty");
            requestTick.setLong(session,-1);
            for(int batch=0;batch<4;batch++)accept.invoke(service,p.player,new Protocol.Requests(1,96,2,0,120<<20,List.copyOf(wants.subList(batch*64,(batch+1)*64))));
            check(p.retries==1&&pending.size()==256,"next tick restores request allowance");
            requestTick.setLong(session,-1);
            accept.invoke(service,p.player,new Protocol.Requests(1,96,2,0,120<<20,List.of(wants.get(256))));
            check(p.retries==2&&pending.size()==256,"per-player queue rejects overflow on a fresh tick");
            if(i==4)check(sessions.size()==7,"five 256-column pending windows coexist");
        }
        Peer overflow=new Peer(18);Object extra=ctor.newInstance(overflow.player);sessions.put(overflow.player.getUUID(),extra);
        var request=new Protocol.Requests(1,96,2,0,120<<20,List.of(new Protocol.Want(40,40,0,5)));
        accept.invoke(service,overflow.player,request);
        check(overflow.retries==1&&((Map<?,?>)field(extra,"pending")).isEmpty(),"global 2048-column queue rejects overflow for an empty player");
        var full=(Map<?,?>)field(sessions.get(new UUID(0,11)),"pending");full.remove(full.keySet().iterator().next());
        accept.invoke(service,overflow.player,request);
        check(overflow.retries==1&&((Map<?,?>)field(extra,"pending")).size()==1,"global queue accepts a request after one slot is released");
        for(int i=0;i<9;i++)sessions.remove(new UUID(0,11+i));
        System.out.println("DISTANT_REQUEST_WINDOW_PASS: five sessions queue 1280 columns; eight fill global 2048; 120 MiB credit each; 64-column messages; 256 per tick; tick/player/global overflow retry and capacity recovery");
    }
    @SuppressWarnings("unchecked") private boolean batchCheck(Object service)throws ReflectiveOperationException{
        var sessions=(Map<UUID,Object>)field(service,"players");
        if(batchPeer!=null){
            Object s=sessions.get(batchPeer.player.getUUID());
            if(batchCase==5&&batchRetryPhase<2){
                if(((Map<?,?>)field(s,"ready")).isEmpty()||!((Map<?,?>)field(service,"work")).isEmpty())return false;
                check(((Map<?,?>)field(s,"ready")).size()==1,"repeated requests occupy one ready entry");
                if(batchRetryPhase++==0){
                    batchRetryCharge=(long)field(s,"bytes");
                    RemoteServer.requests(batchPeer.player,new Protocol.Requests(1,256,2,0,120<<20,List.of(new Protocol.Want(40,40,0,5))));return false;
                }
                check((long)field(s,"bytes")==batchRetryCharge,"replacement releases previous ready charge");
                var batching=s.getClass().getDeclaredField("batching");batching.setAccessible(true);batching.setBoolean(s,false);
            }
            if(batchPeer.columns.size()<expectedBatchColumns)return false;
            check(batchPeer.columns.size()==expectedBatchColumns,"batch columns delivered once");
            if(!((Map<?,?>)field(s,"inflight")).isEmpty())return false;
            check((long)field(s,"reserved")==0&&(long)field(s,"batchCredit")==0&&(long)field(s,"bytes")==0,"batch credit and memory released");
            if(batchCase==1)check(batchPeer.batches.equals(List.of(16)),"full 16-column batch");
            if(batchCase==2)check(batchPeer.batches.equals(List.of(128)),"full 128-column batch");
            if(batchCase==3)check(batchPeer.batches.size()>1&&batchPeer.batches.stream().allMatch(n->n<16),"credit splits batch without deadlock");
            if(batchCase==4)check(batchPeer.batches.equals(List.of(3)),"incomplete tail sent");
            sessions.remove(batchPeer.player.getUUID());batchPeer=null;
        }
        if(batchCase==5){batchLifecycleCheck(service);transferOrderCheck(service);dev.voxydistant.config.DistantConfig.PLAYER_SEND_MIB.set(32);dev.voxydistant.config.DistantConfig.TOTAL_MBPS.set(8.0);dev.voxydistant.config.DistantConfig.MAX_BATCH_COLUMNS.set(1);System.out.println("DISTANT_BATCH_PASS: 16/128, mixed levels, fragmentation, credit split, tail, repeated requests, stale/abort/epoch and exact budget release");return true;}
        batchCase++;int max=batchCase==2?128:16;expectedBatchColumns=batchCase==2?128:batchCase==4?3:batchCase==5?1:16;
        dev.voxydistant.config.DistantConfig.MAX_BATCH_COLUMNS.set(max);dev.voxydistant.config.DistantConfig.COMPRESSION_LEVEL.set(1);dev.voxydistant.config.DistantConfig.TOTAL_MBPS.set(30.0);
        batchPeer=new Peer(30+batchCase);var type=Class.forName("dev.voxydistant.server.RemoteServer$Session");var ctor=type.getDeclaredConstructor(ServerPlayer.class);ctor.setAccessible(true);Object session=ctor.newInstance(batchPeer.player);sessions.put(batchPeer.player.getUUID(),session);
        RemoteServer.requests(batchPeer.player,new Protocol.Requests(1,256,2,0,120<<20,List.of()));
        int limit=batchCase==2?1024<<20:batchCase==3?9<<20:120<<20;
        dev.voxydistant.config.DistantConfig.PLAYER_SEND_MIB.set(batchCase==2?256:32);
        var capacity=type.getDeclaredField("capacity");capacity.setAccessible(true);capacity.setInt(session,limit);
        var advertised=type.getDeclaredField("advertisedCapacity");advertised.setAccessible(true);advertised.setInt(session,limit);
        if(batchCase==5){var batching=type.getDeclaredField("batching");batching.setAccessible(true);batching.setBoolean(session,true);RemoteServer.requests(batchPeer.player,new Protocol.Requests(1,256,2,0,120<<20,List.of(new Protocol.Want(40,40,0,5))));return false;}
        var db=(LodDatabase)field(service,"database");var source=ColumnCodec.decode(LodDatabase.unpack(db.get(LodDatabase.key("minecraft:overworld",net.minecraft.world.level.ChunkPos.asLong(40,40),1))));
        var readyType=Class.forName("dev.voxydistant.server.RemoteServer$Ready");var readyCtor=readyType.getDeclaredConstructors()[0];readyCtor.setAccessible(true);var queue=(Map<Long,Object>)field(session,"ready");
        var bands=dev.voxydistant.config.DistanceBands.parse(List.of("32:0","64:1","96:2"));long charge=0;int totalRaw=4;var batchMembers=new ArrayList<Protocol.Member>();
        for(int i=0;i<expectedBatchColumns;i++){
            int x=20+i,level=bands.select(x,0,0,0);var column=new LodColumn(x,0,source.minY(),source.version(),source.states(),source.biomes(),source.sections());byte[] raw=ColumnCodec.encodeRaw(column,1<<level);
            queue.put(net.minecraft.world.level.ChunkPos.asLong(x,0),readyCtor.newInstance(new Protocol.Member(x,0,column.version(),level,raw.length),raw,-1,new RemoteServer.Key(net.minecraft.world.level.Level.OVERWORLD,x,0)));charge+=4L*(raw.length+4)+1024;
            totalRaw+=raw.length+4;batchMembers.add(new Protocol.Member(x,0,column.version(),level,raw.length));
        }
        if(batchCase==3){long full=Protocol.batchReservation(1024,totalRaw,batchMembers,source.sections().length),single=Protocol.batchReservation(1024,batchMembers.getFirst().rawLength()+8,List.of(batchMembers.getFirst()),source.sections().length);int splitLimit=(int)((full+single)/2);capacity.setInt(session,splitLimit);advertised.setInt(session,splitLimit);}
        var bytes=type.getDeclaredField("bytes");bytes.setAccessible(true);bytes.setLong(session,charge);
        return false;
    }
    @SuppressWarnings("unchecked") private void batchLifecycleCheck(Object service)throws ReflectiveOperationException{
        var sessions=(Map<UUID,Object>)field(service,"players");Peer peer=new Peer(80);
        var type=Class.forName("dev.voxydistant.server.RemoteServer$Session");var ctor=type.getDeclaredConstructor(ServerPlayer.class);ctor.setAccessible(true);Object s=ctor.newInstance(peer.player);sessions.put(peer.player.getUUID(),s);
        RemoteServer.requests(peer.player,new Protocol.Requests(1,256,2,0,120<<20,List.of()));
        var db=(LodDatabase)field(service,"database");var c=ColumnCodec.decode(LodDatabase.unpack(db.get(LodDatabase.key("minecraft:overworld",net.minecraft.world.level.ChunkPos.asLong(40,40),1))));
        var raw=ColumnCodec.encodeRaw(c,2);var members=List.of(new Protocol.Member(40,40,c.version(),1,raw.length));var data=BatchCodec.encode(List.of(raw),1);
        var transfer=Class.forName("dev.voxydistant.server.RemoteServer$Transfer");var tc=transfer.getDeclaredConstructor(long.class,net.minecraft.resources.ResourceKey.class,int.class,List.class,ColumnCodec.Encoded.class);tc.setAccessible(true);
        var bytes=type.getDeclaredField("bytes");bytes.setAccessible(true);var reserved=type.getDeclaredField("reserved");reserved.setAccessible(true);var bc=type.getDeclaredField("batchCredit");bc.setAccessible(true);
        var send=(Deque<Object>)field(s,"send");var inflight=(Map<Long,Object>)field(s,"inflight");long credit=Protocol.batchReservation(data.bytes().length,data.rawLength(),members,c.sections().length);
        var key=new RemoteServer.Key(net.minecraft.world.level.Level.OVERWORLD,40,40);var versions=(Map<RemoteServer.Key,Long>)field(service,"versions");long version=versions.get(key);versions.put(key,version+1);
        var flush=RemoteServer.class.getDeclaredMethod("flushSends",long.class);flush.setAccessible(true);
        send.add(tc.newInstance(80001L,net.minecraft.world.level.Level.OVERWORLD,1,members,data));bytes.setLong(s,data.bytes().length);bc.setLong(s,credit);
        flush.invoke(service,System.nanoTime());check(peer.retries==1&&send.isEmpty()&&(long)field(s,"bytes")==0&&(long)field(s,"batchCredit")==0,"stale unsent batch retries and releases");
        Object partial=tc.newInstance(80002L,net.minecraft.world.level.Level.OVERWORLD,1,members,data);var offset=transfer.getDeclaredField("offset");offset.setAccessible(true);offset.setInt(partial,1);send.add(partial);bytes.setLong(s,data.bytes().length);reserved.setLong(s,credit);
        var creditType=Class.forName("dev.voxydistant.server.RemoteServer$Credit");var cc=creditType.getDeclaredConstructors()[0];cc.setAccessible(true);inflight.put(80002L,cc.newInstance(credit,System.nanoTime(),partial));
        flush.invoke(service,System.nanoTime());check(peer.aborts==1&&send.isEmpty()&&!inflight.isEmpty(),"partial stale batch waits for abort receipt");
        RemoteServer.receipt(peer.player,new Protocol.Receipt(1,80002L));check((long)field(s,"reserved")==0,"late receipt cannot double release");
        versions.put(key,version);
        var large=new ColumnCodec.Encoded(false,4<<20,new byte[4<<20]);
        long largeCredit=Protocol.batchReservation(large.bytes().length,large.rawLength(),members,c.sections().length);
        Object streaming=tc.newInstance(80004L,net.minecraft.world.level.Level.OVERWORLD,1,members,large);
        ((dev.voxydistant.network.ByteBudget)field(service,"totalBudget")).update(System.nanoTime()+1_000_000_000L,3_750_000);
        ((dev.voxydistant.network.ByteBudget)field(s,"budget")).update(System.nanoTime()+1_000_000_000L,3_750_000);
        send.add(streaming);bytes.setLong(s,large.bytes().length);bc.setLong(s,largeCredit);
        flush.invoke(service,System.nanoTime());
        check(offset.getInt(streaming)>0&&offset.getInt(streaming)<large.bytes().length,"real fragmented batch remains in progress");
        versions.put(key,version+1);flush.invoke(service,System.nanoTime());
        check(peer.aborts==2&&send.isEmpty()&&!inflight.isEmpty(),"invalidation between send passes aborts previously validated transfer");
        RemoteServer.receipt(peer.player,new Protocol.Receipt(1,80004L));check((long)field(s,"reserved")==0,"streaming abort receipt releases credit once");versions.put(key,version);
        send.add(tc.newInstance(80003L,net.minecraft.world.level.Level.OVERWORLD,1,members,data));bytes.setLong(s,data.bytes().length);bc.setLong(s,credit);
        RemoteServer.requests(peer.player,new Protocol.Requests(2,256,2,0,120<<20,List.of()));
        check(send.isEmpty()&&(long)field(s,"bytes")==0&&(long)field(s,"batchCredit")==0,"new epoch clears queued batch");
        RemoteServer.receipt(peer.player,new Protocol.Receipt(1,80003L));check((long)field(s,"reserved")==0,"old epoch receipt ignored");sessions.remove(peer.player.getUUID());
    }
    @SuppressWarnings("unchecked") private void transferOrderCheck(Object service)throws ReflectiveOperationException{
        var sessions=(Map<UUID,Object>)field(service,"players");var peer=new Peer(81);
        var type=Class.forName("dev.voxydistant.server.RemoteServer$Session");var ctor=type.getDeclaredConstructor(ServerPlayer.class);ctor.setAccessible(true);Object session=ctor.newInstance(peer.player);
        sessions.put(peer.player.getUUID(),session);
        var transfer=Class.forName("dev.voxydistant.server.RemoteServer$Transfer");var tc=transfer.getDeclaredConstructor(long.class,net.minecraft.resources.ResourceKey.class,int.class,List.class,ColumnCodec.Encoded.class);tc.setAccessible(true);
        var offset=transfer.getDeclaredField("offset");offset.setAccessible(true);
        var select=RemoteServer.class.getDeclaredMethod("selectTransfer",type);select.setAccessible(true);
        var send=(Deque<Object>)field(session,"send");var shapes=type.getDeclaredField("shape");shapes.setAccessible(true);var order=type.getDeclaredField("sendOrder");order.setAccessible(true);
        var aged=type.getDeclaredField("lastSendAgedTick");aged.setAccessible(true);
        var random=new Random(497);int tick=(int)field(service,"ticks");
        for(int run=0;run<24;run++){
            double angle=run*.3;var shape=new dev.voxydistant.movement.RequestShape(0,0,.5,.25,Math.cos(angle),Math.sin(angle),run%2,256,256);shapes.set(session,shape);
            send.clear();order.set(session,null);aged.setInt(session,run%3==0?tick-20:tick);
            var members=new java.util.IdentityHashMap<Object,List<Protocol.Member>>();
            for(int i=0;i<64;i++){
                var list=new ArrayList<Protocol.Member>();for(int m=0;m<8;m++)list.add(new Protocol.Member(random.nextInt(101)-50,random.nextInt(101)-50,1,m%5,i*8L+m+1,1));
                Object t=tc.newInstance(i+1L,net.minecraft.world.level.Level.OVERWORLD,1,List.copyOf(list),new ColumnCodec.Encoded(false,1,new byte[]{0}));send.add(t);members.put(t,list);
            }
            while(!send.isEmpty()){
                boolean age=tick-aged.getInt(session)>=20;Object expected=send.getFirst();int bestX=0,bestZ=0;boolean found=false;
                for(Object t:send){
                    if(age){if((long)field(t,"created")<(long)field(expected,"created"))expected=t;}
                    else{
                        var first=members.get(t).getFirst();int x=first.x(),z=first.z();
                        for(var m:members.get(t))if(shape.compare(m.x(),m.z(),x,z)<0){x=m.x();z=m.z();}
                        if(!found||shape.compare(x,z,bestX,bestZ)<0){found=true;bestX=x;bestZ=z;expected=t;}
                    }
                }
                select.invoke(service,session);check(send.getFirst()==expected,"transfer heap matches exhaustive aging/directional priority");
                offset.setInt(expected,1);select.invoke(service,session);check(send.getFirst()==expected,"partial transfer stays at head");send.removeFirst();
            }
        }
        sessions.remove(peer.player.getUUID());
        System.out.println("DISTANT_TRANSFER_ORDER_PASS: exhaustive mixed-member order, prediction, aging and partial continuity");
    }
    @SuppressWarnings("unchecked") private void benchmark(Object service)throws ReflectiveOperationException{
        benchmark=true;var sessions=(Map<UUID,Object>)field(service,"players");var sessionType=Class.forName("dev.voxydistant.server.RemoteServer$Session");var sessionCtor=sessionType.getDeclaredConstructor(ServerPlayer.class);sessionCtor.setAccessible(true);
        Peer slow=new Peer(2);peers.add(slow);sessions.put(slow.player.getUUID(),sessionCtor.newInstance(slow.player));RemoteServer.requests(slow.player,new Protocol.Requests(1,96,2,0,0,List.of()));
        var transfer=Class.forName("dev.voxydistant.server.RemoteServer$Transfer");var ctor=transfer.getDeclaredConstructor(long.class,RemoteServer.Key.class,int.class,long.class,int.class,long.class,ColumnCodec.Encoded.class);ctor.setAccessible(true);
        var key=new RemoteServer.Key(net.minecraft.world.level.Level.OVERWORLD,40,40);long revision=peers.getFirst().columns.getLast().version();
        long id=100000;byte[] data=new byte[256<<10];new Random(91).nextBytes(data);
        for(Peer peer:peers){peer.bytes=0;Object session=sessions.get(peer.player.getUUID());var send=(Deque<Object>)field(session,"send");for(int i=0;i<16;i++)send.add(ctor.newInstance(++id,key,2,revision,1,0L,new ColumnCodec.Encoded(false,data.length,data)));var bytes=sessionType.getDeclaredField("bytes");bytes.setAccessible(true);bytes.setLong(session,16L*data.length);}
        var flush=RemoteServer.class.getDeclaredMethod("flushSends",long.class);flush.setAccessible(true);
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().threadId(),allocated=bean.getThreadAllocatedBytes(thread),started=System.nanoTime(),heap=0;long[] samples=new long[240];
        for(int i=0;i<samples.length;i++){long before=System.nanoTime();flush.invoke(service,before);while(!receipts.isEmpty())receipts.remove().run();samples[i]=System.nanoTime()-before;heap=Math.max(heap,Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());java.util.concurrent.locks.LockSupport.parkNanos(Math.max(0,50_000_000L-(System.nanoTime()-before)));}
        long elapsed=System.nanoTime()-started;allocated=bean.getThreadAllocatedBytes(thread)-allocated;Arrays.sort(samples);
        check(slow.bytes==0,"zero-credit client cannot consume bandwidth");check(Math.abs(peers.get(0).bytes-peers.get(1).bytes)<=32768,"byte fairness");check(peers.get(0).bytes>=4L*1024*1024&&peers.get(1).bytes>=4L*1024*1024,"both healthy clients make progress");check(peers.get(0).bytes+peers.get(1).bytes<=12_200_000,"aggregate bandwidth bound");
        String report=String.format(Locale.ROOT,"Actual sender, 2 healthy + 1 zero-credit clients, actual %.2f seconds: bytes=%d/%d/%d; sender wall %.2f ms; p50 %.3f ms; p99 %.3f ms; Java allocated %.2f MiB; sampled JVM heap peak %.2f MiB",elapsed/1e9,peers.get(0).bytes,peers.get(1).bytes,slow.bytes,Arrays.stream(samples).sum()/1e6,samples[120]/1e6,samples[237]/1e6,allocated/1048576d,heap/1048576d);
        System.out.println("DISTANT_FAIRNESS_PASS: "+report);
    }
}
