package dev.voxydistant.network;

import dev.voxydistant.client.RemoteClient;
import dev.voxydistant.DebugLog;
import dev.voxydistant.server.RemoteServer;
import dev.voxydistant.data.ColumnCodec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.*;
import java.util.function.Supplier;

public final class Protocol {
    public static final int FRAGMENT_BYTES = 32768;
    public static long reservation(int total,int raw,int level,int sections){return total+raw*3L+(long)sections*(37448>>(level*3))+(2L<<20);}
    public static long batchReservation(int total,int raw,List<Member> members,int sections){
        long bytes=total+raw*3L;for(Member m:members)bytes+=(long)sections*(37448>>(m.level()*3))+(2L<<20);return bytes;
    }
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation("voxy_distant", "lod"),
            () -> "12", Protocol::compatible, Protocol::compatible);
    private static boolean compatible(String version) {
        return version.equals("12") || version.equals(NetworkRegistry.ABSENT) || version.equals(NetworkRegistry.ACCEPTVANILLA);
    }
    public record Hello(UUID world,String dimension, int radius, int minY, int maxY, List<String> bands) {}
    public record Maintenance(boolean paused) {}
    public record Want(int x, int z, long version, int level, long requestId) {
        public Want(int x,int z,long version,int level){this(x,z,version,level,0);}
    }
    public record Cancel(int x,int z,long requestId) {}
    public record Requests(int epoch, int radius, int view, int bandwidth, int capacity, List<Want> wants,List<Cancel> cancels) {
        public Requests(int epoch,int radius,int view,int bandwidth,int capacity,List<Want> wants){this(epoch,radius,view,bandwidth,capacity,wants,List.of());}
    }
    public record Fragment(int epoch, long transfer, int x, int z, long version, int level, long requestId, boolean compressed,
                           int rawLength, int totalLength, int offset, byte[] bytes) {}
    public record Reply(int epoch, int x, int z, long version, int level, long requestId, int status) { // 0 current, 1 retry, 2 unavailable
        public Reply(int epoch,int x,int z,long version,int level,int status){this(epoch,x,z,version,level,0,status);}
    }
    public record Member(int x,int z,long version,int level,long requestId,int rawLength) {
        public Member(int x,int z,long version,int level,int rawLength){this(x,z,version,level,0,rawLength);}
    }
    public record BatchFragment(int epoch,long transfer,boolean compressed,int rawLength,int totalLength,int offset,List<Member> members,byte[] bytes) {}
    public record Receipt(int epoch, long transfer) {}
    public record Abort(int epoch,long transfer) {}
    public record Dirty(String dimension,int x, int z, long version, boolean vanilla) {}
    public record ConfigRequest(long id,boolean save,long revision,List<String> values) {}
    public record ConfigResult(long id,long revision,boolean success,String message,List<String> values) {}
    public record CacheStatsRequest() {}
    public record CacheStats(long bytes,long columns) {}

    public static void register() {
        CHANNEL.registerMessage(10, Maintenance.class, (m,b)->b.writeBoolean(m.paused), b->new Maintenance(b.readBoolean()),
                (m,c)->client(c,()->RemoteClient.maintenance(m)), Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(0, Hello.class, (m,b) -> {
            b.writeUUID(m.world);b.writeUtf(m.dimension,256); b.writeVarInt(m.radius); b.writeInt(m.minY); b.writeInt(m.maxY);
            b.writeVarInt(m.bands.size()); m.bands.forEach(s -> b.writeUtf(s, 32));
        }, b -> {
            var id = b.readUUID();String dimension=b.readUtf(256); int radius = b.readVarInt(), min = b.readInt(), max = b.readInt();
            int count = ColumnCodec.bounded(b.readVarInt(), 1, 32); var bands = new ArrayList<String>();
            for (int i = 0; i < count; i++) bands.add(b.readUtf(32)); return new Hello(id,dimension, radius, min, max, bands);
        }, (m,c) -> client(c, () -> RemoteClient.hello(m)), Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, Requests.class, (m,b) -> {
            b.writeInt(m.epoch); b.writeVarInt(m.radius); b.writeVarInt(m.view); b.writeVarInt(m.bandwidth); b.writeVarInt(m.capacity);
            b.writeVarInt(m.wants.size()); for (var w : m.wants) { b.writeInt(w.x); b.writeInt(w.z); b.writeLong(w.version); b.writeByte(w.level); b.writeLong(w.requestId); }
            b.writeVarInt(m.cancels.size()); for(var cancel:m.cancels){b.writeInt(cancel.x);b.writeInt(cancel.z);b.writeLong(cancel.requestId);}
        }, b -> {
            int epoch = b.readInt(), radius = b.readVarInt(), view = b.readVarInt(), bandwidth = b.readVarInt(), capacity = b.readVarInt();
            int count = ColumnCodec.bounded(b.readVarInt(), 0, 64); var wants = new ArrayList<Want>(count);
            for (int i = 0; i < count; i++) wants.add(new Want(b.readInt(), b.readInt(), b.readLong(), b.readUnsignedByte(),b.readLong()));
            int cancelled=ColumnCodec.bounded(b.readVarInt(),0,16);var cancels=new ArrayList<Cancel>(cancelled);
            for(int i=0;i<cancelled;i++)cancels.add(new Cancel(b.readInt(),b.readInt(),b.readLong()));
            return new Requests(epoch, radius, view, bandwidth, capacity, List.copyOf(wants),List.copyOf(cancels));
        }, (m,c) -> { var ctx=c.get(); long queued=DebugLog.start();ctx.enqueueWork(() -> {DebugLog.end(DebugLog.Metric.SERVER_REQUEST_CONTROL,queued);RemoteServer.requests(ctx.getSender(),m);}); ctx.setPacketHandled(true); }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, Fragment.class, (m,b) -> {
            b.writeInt(m.epoch); b.writeLong(m.transfer); b.writeInt(m.x); b.writeInt(m.z); b.writeLong(m.version); b.writeByte(m.level);
            b.writeLong(m.requestId);b.writeBoolean(m.compressed); b.writeInt(m.rawLength); b.writeInt(m.totalLength); b.writeInt(m.offset); b.writeByteArray(m.bytes);
        }, b -> new Fragment(b.readInt(),b.readLong(),b.readInt(),b.readInt(),b.readLong(),b.readUnsignedByte(),b.readLong(),b.readBoolean(),
                b.readInt(),b.readInt(),b.readInt(),b.readByteArray(FRAGMENT_BYTES)), (m,c) -> {
            // Queue only; decoding and applying happen in the bounded client worker.
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> RemoteClient.fragment(m)); c.get().setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(3, Reply.class, (m,b) -> { b.writeInt(m.epoch); b.writeInt(m.x); b.writeInt(m.z); b.writeLong(m.version); b.writeByte(m.level); b.writeLong(m.requestId);b.writeByte(m.status); },
                b -> new Reply(b.readInt(),b.readInt(),b.readInt(),b.readLong(),b.readUnsignedByte(),b.readLong(),b.readUnsignedByte()),
                (m,c) -> client(c, () -> RemoteClient.reply(m)), Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(4, Receipt.class, (m,b) -> { b.writeInt(m.epoch); b.writeLong(m.transfer); }, b -> new Receipt(b.readInt(),b.readLong()),
                (m,c) -> { var ctx=c.get(); long queued=DebugLog.start();ctx.enqueueWork(() -> {DebugLog.end(DebugLog.Metric.SERVER_RECEIPT_CONTROL,queued);RemoteServer.receipt(ctx.getSender(),m);}); ctx.setPacketHandled(true); }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(5, Dirty.class, (m,b) -> {b.writeUtf(m.dimension,256); b.writeInt(m.x); b.writeInt(m.z); b.writeLong(m.version); b.writeBoolean(m.vanilla); },
                b -> new Dirty(b.readUtf(256),b.readInt(),b.readInt(),b.readLong(),b.readBoolean()), (m,c) -> client(c, () -> RemoteClient.dirty(m)), Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(6,Abort.class,(m,b)->{b.writeInt(m.epoch);b.writeLong(m.transfer);},b->new Abort(b.readInt(),b.readLong()),(m,c)->client(c,()->RemoteClient.abort(m)),Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(7,BatchFragment.class,Protocol::writeBatch,Protocol::readBatch,(m,c)->{
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->RemoteClient.batchFragment(m));c.get().setPacketHandled(true);
        },Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(8,ConfigRequest.class,Protocol::writeConfigRequest,Protocol::readConfigRequest,(m,c)->{
            var ctx=c.get();ctx.enqueueWork(()->RemoteServer.configuration(ctx.getSender(),m));ctx.setPacketHandled(true);
        },Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(9,ConfigResult.class,(m,b)->{
            b.writeLong(m.id);b.writeLong(m.revision);b.writeBoolean(m.success);b.writeUtf(m.message,2048);writeSettings(b,m.values);
        },b->new ConfigResult(b.readLong(),b.readLong(),b.readBoolean(),b.readUtf(2048),readSettings(b)),
            (m,c)->{var ctx=c.get();ctx.enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->dev.voxydistant.client.ServerConfigScreen.result(ctx.getNetworkManager(),m)));ctx.setPacketHandled(true);},Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(11,CacheStatsRequest.class,(m,b)->{},b->new CacheStatsRequest(),(m,c)->{
            var ctx=c.get();ctx.enqueueWork(()->RemoteServer.cacheStats(ctx.getSender()));ctx.setPacketHandled(true);
        },Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(12,CacheStats.class,(m,b)->{b.writeLong(m.bytes);b.writeLong(m.columns);},
                b->new CacheStats(b.readLong(),b.readLong()),
                (m,c)->{var ctx=c.get();ctx.enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->RemoteClient.cacheStats(ctx.getNetworkManager(),m)));ctx.setPacketHandled(true);},Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    public static void writeConfigRequest(ConfigRequest m,FriendlyByteBuf b){b.writeLong(m.id);b.writeBoolean(m.save);b.writeLong(m.revision);writeSettings(b,m.values);}
    public static ConfigRequest readConfigRequest(FriendlyByteBuf b){return new ConfigRequest(b.readLong(),b.readBoolean(),b.readLong(),readSettings(b));}
    private static void writeSettings(FriendlyByteBuf b,List<String> values){b.writeVarInt(values.size());for(String value:values)b.writeUtf(value,1024);}
    private static List<String> readSettings(FriendlyByteBuf b){int size=ColumnCodec.bounded(b.readVarInt(),0,dev.voxydistant.config.ServerSettings.FIELDS.size());var values=new ArrayList<String>(size);for(int i=0;i<size;i++)values.add(b.readUtf(1024));return List.copyOf(values);}
    public static void writeBatch(BatchFragment m,FriendlyByteBuf b) {
        b.writeInt(m.epoch);b.writeLong(m.transfer);b.writeBoolean(m.compressed);b.writeInt(m.rawLength);b.writeInt(m.totalLength);b.writeInt(m.offset);
        if(m.offset==0){b.writeVarInt(m.members.size());for(Member c:m.members){b.writeInt(c.x);b.writeInt(c.z);b.writeLong(c.version);b.writeByte(c.level);b.writeLong(c.requestId);b.writeInt(c.rawLength);}}
        b.writeByteArray(m.bytes);
    }
    public static BatchFragment readBatch(FriendlyByteBuf b) {
        int epoch=b.readInt();long transfer=b.readLong();boolean compressed=b.readBoolean();int raw=b.readInt(),total=b.readInt(),offset=b.readInt();
        var members=new ArrayList<Member>();
        if(offset==0){int count=ColumnCodec.bounded(b.readVarInt(),1,128);for(int i=0;i<count;i++)members.add(new Member(b.readInt(),b.readInt(),b.readLong(),ColumnCodec.bounded(b.readUnsignedByte(),0,4),b.readLong(),ColumnCodec.bounded(b.readInt(),1,ColumnCodec.MAX_BYTES)));}
        return new BatchFragment(epoch,transfer,compressed,raw,total,offset,List.copyOf(members),b.readByteArray(FRAGMENT_BYTES));
    }
    private static void client(Supplier<NetworkEvent.Context> context, Runnable action) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> action.run()));
        context.get().setPacketHandled(true);
    }
    public static void send(ServerPlayer player, Object message) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message); }
    private Protocol() {}
}
