package dev.voxydistant.smoke;

import com.mojang.authlib.GameProfile;
import dev.voxydistant.config.*;
import dev.voxydistant.data.*;
import dev.voxydistant.network.Protocol;
import dev.voxydistant.server.RemoteServer;
import dev.voxydistant.server.MaintenanceSupport;
import dev.voxydistant.generation.ChunkSnapshot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.*;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppedEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.voxydistant.config.DistantConfig.*;

/** Real RegionFile, command, cache and server lifecycle checks in an isolated smoke world. */
public final class MaintenanceSmoke {
    private static final int X = -10016, Z = -10016;
    private final MinecraftServer server;
    private final Object service;
    private final List<String> messages = new ArrayList<>();
    private final CommandSourceStack source;
    private final List<Path> fixtures = new ArrayList<>();
    private final Queue<Runnable> receipts = new ArrayDeque<>();
    private final Peer peer;
    private int stage, attached, refreshes;
    private long firstVersion, cachedCompleted;
    private Object task, stopTask;
    private String settingsError;
    private boolean settingsDone;
    private Thread scannerAtStop;

    private final class Peer extends Connection {
        final ServerPlayer player;
        final List<LodColumn> columns = new ArrayList<>();
        final Map<Long, byte[]> buffers = new HashMap<>();
        int unavailable;
        boolean paused, resumed;
        Peer() {
            super(PacketFlow.SERVERBOUND);
            player = new ServerPlayer(server, server.overworld(), new GameProfile(new UUID(918, 217), "MaintenanceSmoke"));
            player.connection = new ServerGamePacketListenerImpl(server, this, player);
        }
        @Override public void send(Packet<?> packet) { capture(packet); }
        @Override public void send(Packet<?> packet, PacketSendListener listener) { capture(packet); }
        private void capture(Packet<?> packet) {
            if (!(packet instanceof ClientboundCustomPayloadPacket payload)) return;
            var b = new FriendlyByteBuf(payload.getData().duplicate());
            int id=b.readVarInt();
            if(id==10){paused=b.readBoolean();if(!paused)resumed=true;return;}
            if(id==3){b.readInt();b.readInt();b.readInt();b.readLong();b.readByte();b.readLong();if(b.readByte()!=0)unavailable++;return;}
            if(id!=2)return;
            int epoch=b.readInt();long transfer=b.readLong();int x=b.readInt(),z=b.readInt();long version=b.readLong();int lod=b.readUnsignedByte();b.readLong();
            boolean compressed=b.readBoolean();int raw=b.readInt(),total=b.readInt(),offset=b.readInt();byte[] bytes=b.readByteArray(32768);
            byte[] target=buffers.computeIfAbsent(transfer,k->new byte[total]);System.arraycopy(bytes,0,target,offset,bytes.length);
            if(offset+bytes.length==total){
                columns.add(ColumnCodec.decode(new ColumnCodec.Encoded(compressed,raw,target)));buffers.remove(transfer);
                receipts.add(()->RemoteServer.receipt(player,new Protocol.Receipt(epoch,transfer)));
            }
        }
    }

    public MaintenanceSmoke(MinecraftServer server, Object service) throws Exception {
        this.server=server;this.service=service;peer=new Peer();
        CommandSource sink=new CommandSource(){
            public void sendSystemMessage(Component text){messages.add(text.getString());}
            public boolean acceptsSuccess(){return true;}
            public boolean acceptsFailure(){return true;}
            public boolean shouldInformAdmins(){return false;}
        };
        source=new CommandSourceStack(sink,Vec3.ZERO,Vec2.ZERO,server.overworld(),4,"MaintenanceSmoke",Component.literal("MaintenanceSmoke"),server,null);
        AUTO_THROTTLE.set(false);SERVER_GENERATE.set(false);MAX_BATCH_COLUMNS.set(1);SERVER_RATE.set(8192);SERVER_THREADS.set(1);
        IMPORT_THREADS.set(1);IMPORT_MEMORY.set(64);
        parserCheck();
        var level=server.overworld();var input=parse(level.registryAccess(),level.getMinSection(),level.getSectionsCount(),X,Z,chunk(X,Z,level.getMinSection())).orElseThrow();
        var reference=ReferenceColumnConverter.convert(X,Z,77,input);var optimized=ColumnConverter.convert(X,Z,77,input);
        check(reference.states().equals(optimized.states())&&reference.biomes().equals(optimized.biomes())&&Arrays.deepEquals(reference.sections(),optimized.sections()),"converter matches original including palette order");
        for(int mask=1;mask<32;mask++)check(Arrays.equals(ReferenceColumnCodec.encodeRaw(reference,mask),ColumnCodec.encodeRaw(optimized,mask)),"byte-identical optimized codec mask "+mask);
        System.out.println("DISTANT_IMPORT_CODEC_PASS: original converter and all 31 encoded masks match");
        MinecraftForge.EVENT_BUS.addListener(this::stopped);
    }
    private static Object field(Object target,String name)throws ReflectiveOperationException{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,String name,Object value)throws ReflectiveOperationException{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void command(String command){server.getCommands().performPrefixedCommand(source,command);}
    private LodDatabase db()throws Exception{return (LodDatabase)field(service,"database");}
    private LodColumn column(ServerLevel level,int x,int z)throws Exception{
        byte[] bytes=db().get(LodDatabase.key(level.dimension().location().toString(),ChunkPos.asLong(x,z),1));
        return bytes==null?null:ColumnCodec.decode(LodDatabase.unpack(bytes));
    }
    private static CompoundTag chunk(int x,int z,int sectionY){
        var chunk=new CompoundTag();chunk.putString("Status","minecraft:full");chunk.putInt("xPos",x);chunk.putInt("zPos",z);
        var section=new CompoundTag();section.putByte("Y",(byte)sectionY);
        var states=new CompoundTag();var palette=new ListTag();var block=new CompoundTag();block.putString("Name","minecraft:gold_block");palette.add(block);states.put("palette",palette);section.put("block_states",states);
        var biomes=new CompoundTag();var biomePalette=new ListTag();biomePalette.add(StringTag.valueOf("minecraft:desert"));biomes.put("palette",biomePalette);section.put("biomes",biomes);
        byte[] light=new byte[2048];Arrays.fill(light,(byte)0x77);section.putByteArray("BlockLight",light);Arrays.fill(light,(byte)0xbb);section.putByteArray("SkyLight",light.clone());
        // putByteArray retains the array, so keep block/sky storage independent.
        byte[] blocks=new byte[2048];Arrays.fill(blocks,(byte)0x77);section.putByteArray("BlockLight",blocks);
        var sections=new ListTag();sections.add(section);chunk.put("sections",sections);return chunk;
    }
    @SuppressWarnings("unchecked")
    private static Optional<List<ChunkSnapshot>> parse(RegistryAccess registries,int min,int count,int x,int z,CompoundTag tag)throws Exception{
        var method=Class.forName("dev.voxydistant.server.RegionNbtImporter").getDeclaredMethod("read",RegistryAccess.class,int.class,int.class,int.class,int.class,CompoundTag.class);
        method.setAccessible(true);return (Optional<List<ChunkSnapshot>>)method.invoke(null,registries,min,count,x,z,tag);
    }
    private void parserCheck()throws Exception{
        var level=server.overworld();int min=level.getMinSection(),count=level.getSectionsCount();
        var tag=chunk(X,Z,min+1);var lightOnly=new CompoundTag();lightOnly.putByte("Y",(byte)(min-1));lightOnly.putByteArray("SkyLight",new byte[2048]);tag.getList("sections",10).add(lightOnly);
        var snapshots=parse(level.registryAccess(),min,count,X,Z,tag).orElseThrow();
        check(snapshots.size()==count&&snapshots.getFirst().y()==min,"full vertical range including missing air sections");
        check(snapshots.getFirst().blocks().get(0,0,0).isAir(),"missing section stays air");
        var present=snapshots.get(1);check(present.blocks().get(0,0,0).is(Blocks.GOLD_BLOCK),"NBT palette decoded");
        check(present.blockLight().get(0,0,0)==7&&present.skyLight().get(0,0,0)==11,"saved light preserved without live engine");
        check(present.biomes().get(0,0,0).unwrapKey().orElseThrow().location().toString().equals("minecraft:desert"),"saved biome preserved");
        tag.getList("sections",10).getCompound(0).getByteArray("BlockLight")[0]=0;check(present.blockLight().get(0,0,0)==7,"detached light snapshot");
        tag.getList("sections",10).getCompound(0).putByteArray("BlockLight",new byte[3]);
        check(parse(level.registryAccess(),min,count,X,Z,tag).isEmpty(),"invalid light rejected");
        tag=chunk(X,Z,min);tag.getList("sections",10).getCompound(0).put("block_states",new CompoundTag());
        check(parse(level.registryAccess(),min,count,X,Z,tag).isEmpty(),"invalid block palette rejected");
        tag=chunk(X,Z,min);tag.put("sections",new ListTag());
        check(parse(level.registryAccess(),min,count,X,Z,tag).orElseThrow().stream().allMatch(s->s.blocks().get(0,0,0).isAir()),"empty FULL column retains complete vertical air coverage");
        System.out.println("DISTANT_MAINTENANCE_NBT_PASS: palettes, biomes, saved light, detached data, air padding and boundary light sections");
    }
    private void fixtures()throws Exception{
        for(var level:server.getAllLevels()){
            Path dir=DimensionType.getStorageFolder(level.dimension(),server.getWorldPath(LevelResource.ROOT)).resolve("region");Files.createDirectories(dir);
            Path file=dir.resolve("r."+(X>>5)+"."+(Z>>5)+".mca");fixtures.add(file);
            try(var region=new RegionFile(file,dir,false)){
                for(int i=0;i<4;i++){
                    var tag=chunk(X+i,Z,level.getMinSection());
                    if(i==0){
                        // A real Forge-registered mod block plus irrelevant NBT larger than the old 3 MiB cap.
                        var section=tag.getList("sections",10).getCompound(0).copy();section.putByte("Y",(byte)(level.getMinSection()+1));
                        section.getCompound("block_states").getList("palette",10).getCompound(0).putString("Name","distant_smoke:import_block");
                        tag.getList("sections",10).add(section);tag.putByteArray("ForgeCaps",new byte[4<<20]);
                    }
                    if(i==1)tag.putString("Status","minecraft:carvers");
                    if(i==2)tag.putInt("xPos",X+3);
                    try(var output=region.getChunkDataOutputStream(new ChunkPos(X+i,Z))){if(i==3)output.writeByte(127);else NbtIo.write(tag,output);}
                }
            }
            var old=ColumnConverter.convert(X,Z,1,parse(level.registryAccess(),level.getMinSection(),level.getSectionsCount(),X,Z,chunk(X,Z,level.getMinSection())).orElseThrow());
            db().put(LodDatabase.key(level.dimension().location().toString(),ChunkPos.asLong(X,Z),1),LodDatabase.pack(ColumnCodec.encode(old,31)));
        }
    }
    @SuppressWarnings("unchecked")
    public boolean tick()throws Exception{
        while(!receipts.isEmpty())receipts.remove().run();
        var work=(Map<RemoteServer.Key,Object>)field(service,"work");
        if(stage==0){
            peer.player.setPos(X*16.0,100,Z*16.0);
            var sessions=(Map<UUID,Object>)field(service,"players");var type=Class.forName("dev.voxydistant.server.RemoteServer$Session");var ctor=type.getDeclaredConstructor(ServerPlayer.class);ctor.setAccessible(true);sessions.put(peer.player.getUUID(),ctor.newInstance(peer.player));
            fixtures();
            // Start import while an online cache read owns session counters.
            RemoteServer.requests(peer.player,new Protocol.Requests(1,96,2,0,32<<20,List.of(new Protocol.Want(X,Z,0,5))));
            check(!work.isEmpty(),"online work exists before import");
            command("voxydistant import");task=field(service,"maintenance");command("voxydistant pregen 1 0 0");
            Object session=sessions.get(peer.player.getUUID());
            check((int)field(session,"active")==0&&(int)field(session,"cacheActive")==0&&(int)field(session,"generating")==0&&(long)field(session,"cacheReadBytes")==0,"import detaches online lane reservations");
            check(field(service,"maintenance")==task,"one maintenance task only");
            stage=1;return false;
        }
        if(stage==1){
            if(attached==0&&field(task,"phase").equals("导入")){
                check(peer.paused,"client receives maintenance pause");
                RemoteServer.requests(peer.player,new Protocol.Requests(1,96,2,0,32<<20,List.of(new Protocol.Want(X,Z,0,5))));
                check(work.isEmpty()&&peer.columns.isEmpty(),"import excludes online work and payloads");attached=1;
                RemoteServer.markDirty(server.getLevel(Level.NETHER),X,Z);
            }
            if(attached==1&&refreshes==0){
                var values=new ArrayList<>(ServerSettings.current().values());values.set(ServerSettings.index(SERVER_CACHE_MIB),"96");
                values.set(ServerSettings.index(IMPORT_THREADS),"2");values.set(ServerSettings.index(IMPORT_MEMORY),"128");
                var apply=RemoteServer.class.getDeclaredMethod("applyConfiguration",ServerSettings.Snapshot.class,java.util.function.BooleanSupplier.class,java.util.function.Consumer.class);apply.setAccessible(true);
                apply.invoke(service,new ServerSettings.Snapshot(values),(java.util.function.BooleanSupplier)()->true,(java.util.function.Consumer<String>)error->{settingsError=error;settingsDone=true;});
                check((boolean)field(service,"maintenancePaused"),"maintenance paused for cache reopen");refreshes++;
            }
            if(field(service,"maintenance")!=null)return false;
            check(settingsDone&&settingsError.isEmpty(),"cache reopened during import");
            check(IMPORT_THREADS.get()==2&&IMPORT_MEMORY.get()==128,"dedicated import budget reload");
            check(attached==1&&peer.resumed&&peer.columns.isEmpty(),"maintenance resumes without payloads during import");
            check((long)field(task,"failed")==0,"invalid chunk input is skipped, not task failure");
            check((long)field(task,"completed")+(long)field(task,"skipped")== (long)field(task,"total"),"all scanned slots counted exactly once");
            check((long)field(task,"skipped")>=3*1023,"empty/non-FULL/corrupt/mismatched slots skipped");
            check(((Map<?,?>)field(service,"work")).isEmpty()&&(long)field(service,"memory")==0,"import releases NBT and snapshot reservations");
            for(var level:server.getAllLevels()){
                var c=column(level,X,Z);check(c!=null&&c.version()>1,"force refresh per dimension");
                if(level.dimension()==Level.NETHER){var key=new RemoteServer.Key(Level.NETHER,X,Z);byte[] invalid=db().get(LodDatabase.key("minecraft:the_nether",ChunkPos.asLong(X,Z),2));long revision=((Map<RemoteServer.Key,Long>)field(service,"versions")).getOrDefault(key,0L);if(invalid!=null)revision=Math.max(revision,java.nio.ByteBuffer.wrap(invalid).getLong());check(revision>c.version(),"live edit after import revision remains newer than disk data");}
                check(c.minY()==level.getMinSection()&&c.sections().length==level.getSectionsCount(),"dimension height isolation");
                long voxel=c.sections()[0][0][0];check(LodColumn.light(voxel)==0x7b&&c.states().get(LodColumn.state(voxel)).is(Blocks.GOLD_BLOCK),"disk data reached all LOD levels");
                check(c.states().get(LodColumn.state(c.sections()[1][0][0])).is(ServerSmoke.IMPORT_BLOCK.get()),"registered mod block survives large-NBT import and RocksDB round trip");
                for(int i=1;i<5;i++)check(column(level,X+i,Z)==null&&level.getChunkSource().getChunkNow(X+i,Z)==null,"import never generates missing or rejected chunks");
                check(level.getChunkSource().getChunkNow(X,Z)==null,"import keeps existing disk chunk unloaded");
            }
            check(messages.stream().anyMatch(s->s.contains("import 维护任务已结束")),"completion feedback");
            long hits=(long)field(service,"hits");peer.columns.clear();peer.player.setPos(X*16.0,100,Z*16.0);
            RemoteServer.requests(peer.player,new Protocol.Requests(2,96,2,0,32<<20,List.of(new Protocol.Want(X,Z,0,5))));
            cachedCompleted=hits;stage=8;return false;
        }
        if(stage==8){
            if(peer.columns.isEmpty())return false;
            check((long)field(service,"hits")>cachedCompleted&&peer.columns.getFirst().version()==column(server.overworld(),X,Z).version(),"fresh client request hits refreshed cache");
            var workers=(ThreadPoolExecutor)field(service,"workers");
            if(!work.isEmpty()||workers.getActiveCount()!=0||!workers.getQueue().isEmpty()||((java.util.concurrent.atomic.AtomicLong)field(service,"dirtyWrites")).get()!=0)return false;
            // A real read-only RocksDB handle rejects puts without touching a live native handle.
            var storage=db();((org.rocksdb.RocksDB)field(storage,"db")).close();
            set(storage,"db",org.rocksdb.RocksDB.openReadOnly((org.rocksdb.Options)field(storage,"options"),server.getWorldPath(LevelResource.ROOT).resolve("data/voxy-distant").toString()));
            command("voxydistant import");task=field(service,"maintenance");stage=9;return false;
        }
        if(stage==9){
            if(field(service,"maintenance")!=null)return false;
            check((boolean)field(service,"importStorageFailed")&&peer.paused,"fatal write failure keeps clients paused");
            check((long)field(task,"failed")>0&&(long)field(task,"importMemory")==0&&(int)field(task,"active")==0,"failed import releases permits and reports failure");
            check(messages.stream().anyMatch(s->s.contains("LOD invalidation write failed")||s.contains("LOD import write failed")||s.contains("LOD write failed")||s.contains("LOD batch failed")),"fatal write error reaches command source");
            command("voxydistant reload");stage=10;return false;
        }
        if(stage==10){
            if(field(service,"configChange")!=null)return false;
            check(!(boolean)field(service,"importStorageFailed")&&!peer.paused,"successful cache reopen restores online service");
            System.out.println("DISTANT_IMPORT_FAILURE_PASS: read-only write failure, release, paused clients and reload recovery");
            ((Map<?,?>)field(service,"players")).clear();
            command("voxydistant pregen 1 1281 1281");task=field(service,"maintenance");stage=2;return false;
        }
        if(stage==2){
            if(field(service,"maintenance")!=null)return false;
            check(!SERVER_GENERATE.get(),"live generation disabled during explicit pregen");
            check((long)field(task,"completed")==5&&(long)field(task,"failed")==0,"pregen builds all five circle columns");
            for(var p:MaintenanceSupport.circularCoordinates(1))check(column(server.overworld(),80+p.dx(),80+p.dz())!=null,"circle LOD coverage");
            check(column(server.overworld(),81,81)==null,"circle excludes corner LOD");
            firstVersion=column(server.overworld(),80,80).version();cachedCompleted=(long)field(service,"completed");
            command("voxydistant pregen 1 1281 1281");task=field(service,"maintenance");stage=3;return false;
        }
        if(stage==3){
            if(field(service,"maintenance")!=null)return false;
            if(!work.isEmpty())return false;
            check(column(server.overworld(),80,80).version()==firstVersion&&(long)field(task,"completed")==5&&(long)field(task,"failed")==0,"pregen valid cache needs no conversion");
            check((long)field(service,"memory")==0&&work.isEmpty(),"completed maintenance releases snapshots");
            // Start a large, slow pregen; a config save must requeue stage-1 work instead of failing it.
            SERVER_RATE.set(1);command("voxydistant pregen 1 -2561 -2561");task=field(service,"maintenance");stage=4;return false;
        }
        if(stage==4){
            if(work.values().stream().noneMatch(w->{try{return (int)field(w,"stage")==1;}catch(Exception e){throw new RuntimeException(e);}}))return false;
            command("voxydistant reload");check((boolean)field(service,"maintenancePaused"),"reload pauses active generation");stage=5;return false;
        }
        if(stage==5){
            if(field(service,"maintenance")!=null)return false;
            check((long)field(task,"completed")==5&&(long)field(task,"failed")==0,"reload resumes all requeued pregen coordinates");
            // Stop with a saturated scanner queue. No conversion threads may be held by the producer.
            Path dir=fixtures.getFirst().getParent();
            for(int r=0;r<5;r++)try(var region=new RegionFile(dir.resolve("r."+(1000+r)+".1000.mca"),dir,false)){
                for(int i=0;i<1024;i++){int x=(1000+r)*32+(i&31),z=32000+(i>>5);var tag=chunk(x,z,0);tag.putString("Status","minecraft:carvers");try(var out=region.getChunkDataOutputStream(new ChunkPos(x,z))){NbtIo.write(tag,out);}}
            }
            SERVER_ENABLED.set(false);command("voxydistant import");stopTask=field(service,"maintenance");stage=6;return false;
        }
        if(stage==6){
            if(field(stopTask,"phase").equals("准备"))return false;
            var queue=(BlockingQueue<?>)field(stopTask,"queue");check(queue.size()<=4096,"bounded import references");
            scannerAtStop=(Thread)field(service,"maintenanceScanner");
            System.out.println("DISTANT_MAINTENANCE_PASS: FULL-only disk import, overwrite, dimensions/light, live demand, pregen/cache, reload/reopen, exact counts and bounded queue");
            stage=7;return true;
        }
        return false;
    }
    private void stopped(ServerStoppedEvent event){
        try{
            check(stage==7,"maintenance reached stop acceptance");
            check((scannerAtStop==null||!scannerAtStop.isAlive())&&((ThreadPoolExecutor)field(service,"workers")).isTerminated(),"scanner and workers stopped");
            var importer=(java.util.concurrent.ExecutorService)field(stopTask,"importWorkers");check(importer==null||importer.isTerminated(),"dedicated import workers stopped");
            check((long)field(stopTask,"importMemory")==0,"import memory released");
            check(((Map<?,?>)field(service,"work")).isEmpty()&&(long)field(service,"memory")==0,"stopping released all work/memory");
            check(((BlockingQueue<?>)field(stopTask,"queue")).isEmpty(),"stopping cleared saturated import queue");
            System.out.println("DISTANT_MAINTENANCE_STOP_PASS");
        }catch(Exception|AssertionError e){e.printStackTrace();System.out.println("DISTANT_MAINTENANCE_STOP_FAIL");}
    }
}
