package dev.voxydistant.smoke;

import dev.voxydistant.config.*;
import dev.voxydistant.data.LodDatabase;
import dev.voxydistant.network.Protocol;
import dev.voxydistant.server.RemoteServer;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.nio.file.*;
import java.util.*;
import static dev.voxydistant.config.DistantConfig.*;

/** Exercises real server handlers and database lifecycle, without a second UI client. */
final class ServerSettingsSmoke {
    private final MinecraftServer server;
    private final Object service;
    private final Peer peer;
    private final ServerSettings.Snapshot original;
    private int stage;
    private long revision,id;
    private UUID world;
    private final byte[] key="settings-smoke".getBytes(java.nio.charset.StandardCharsets.UTF_8),data={4,3,2,1};
    private final class Peer extends Connection {
        final ServerPlayer player;Protocol.ConfigResult result;boolean connected=true;
        Peer(){super(PacketFlow.SERVERBOUND);player=new ServerPlayer(server,server.overworld(),new GameProfile(new UUID(17,29),"SettingsSmoke"));player.connection=new ServerGamePacketListenerImpl(server,this,player);}
        @Override public boolean isConnected(){return connected;}
        @Override public void send(Packet<?> packet){capture(packet);}
        @Override public void send(Packet<?> packet,PacketSendListener listener){capture(packet);}
        private void capture(Packet<?> packet){
            if(!(packet instanceof ClientboundCustomPayloadPacket p))return;
            var b=new FriendlyByteBuf(p.getData().duplicate());if(b.readVarInt()!=9)return;
            long request=b.readLong(),rev=b.readLong();boolean ok=b.readBoolean();String error=b.readUtf(2048);int count=b.readVarInt();var values=new ArrayList<String>();for(int i=0;i<count;i++)values.add(b.readUtf(1024));
            result=new Protocol.ConfigResult(request,rev,ok,error,values);
        }
    }
    ServerSettingsSmoke(MinecraftServer server,Object service){this.server=server;this.service=service;peer=new Peer();original=ServerSettings.current();}
    private static Object field(Object target,String name)throws ReflectiveOperationException{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    private void send(boolean save,long rev,List<String> values){
        peer.result=null;var request=new Protocol.ConfigRequest(++id,save,rev,values);var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try{Protocol.writeConfigRequest(request,buffer);var decoded=Protocol.readConfigRequest(buffer);check(decoded.equals(request)&&!buffer.isReadable(),"settings wire round trip");RemoteServer.configuration(peer.player,decoded);}finally{buffer.release();}
    }
    private List<String> changed(net.minecraftforge.common.ForgeConfigSpec.ConfigValue<?> value,String input){var values=new ArrayList<>(ServerSettings.current().values());values.set(ServerSettings.index(value),input);return values;}
    private void ok(){check(peer.result!=null&&peer.result.success(),"settings accepted: "+peer.result);revision=peer.result.revision();}
    private void rejected(String part){check(peer.result!=null&&!peer.result.success()&&peer.result.message().contains(part),"settings rejected: "+part+" / "+peer.result);}
    boolean tick()throws Exception{
        if(stage==0){
            var upgrade=DistantConfig.class.getDeclaredMethod("upgrade",Path.class);upgrade.setAccessible(true);
            Path migrationDir=Files.createTempDirectory(server.getServerDirectory().toPath(),"config-upgrade-");
            int migrationCase=0;
            for(String oldVersion:List.of("", "configVersion = 0\n", "configVersion = \"unreadable\"\n", "configVersion = 1\n")){
                Path file=migrationDir.resolve("case-"+(migrationCase++)+".toml");
                String previous=oldVersion+"[client]\ndownloadKiBPerSecond = 3456\n[server]\nradius = 192\ntotalBandwidthMbps = 17.5\n[server.generation]\nhealthySeconds = 4.5\n";
                Files.writeString(file,previous);upgrade.invoke(null,file);
                String upgraded=Files.readString(file);
                try(var migrated=com.electronwill.nightconfig.core.file.CommentedFileConfig.of(file)){
                    migrated.load();check(migrated.getInt("configVersion")==CURRENT_CONFIG_VERSION,"migration version");
                    check(migrated.getInt("client.downloadKiBPerSecond")==3456&&migrated.getInt("server.radius")==192,"migration preserves client and server values");
                    check(migrated.<Double>get("server.totalBandwidthMbps")==17.5&&migrated.<Double>get("server.generation.healthySeconds")==4.5,"migration preserves custom limits");
                    check(migrated.getInt("server.generation.pauseTriggerTicks")==20,"migration fills new defaults");
                    check(migrated.getInt("server.generation.columnTimeoutSeconds")==60,"migration fills column timeout");
                }
                try(var backups=Files.list(migrationDir)){
                    Path backup=backups.filter(p->p.getFileName().toString().startsWith(file.getFileName()+".v"+(oldVersion.contains("= 1")?1:0)+".")).findFirst().orElseThrow();
                    check(Files.readString(backup).equals(previous),"backup preserves original bytes");
                }
                upgrade.invoke(null,file);check(Files.readString(file).equals(upgraded),"current schema is not rewritten");
            }
            Path future=migrationDir.resolve("future.toml");String futureText="configVersion = 99\nfutureSetting = true\n";
            Files.writeString(future,futureText);upgrade.invoke(null,future);check(Files.readString(future).equals(futureText),"newer schema is not downgraded");
            System.out.println("DISTANT_CONFIG_UPGRADE_PASS: missing/old/unreadable version, values, defaults, original backup, idempotence, newer version");
            send(false,0,List.of());rejected("权限不足");check(peer.result.values().isEmpty(),"non-OP receives no settings");
            server.getPlayerList().op(peer.player.getGameProfile());send(false,0,List.of());ok();check(peer.result.values().equals(original.values()),"read all server fields");
            send(true,revision,changed(MAX_BATCH_COLUMNS,"129"));rejected("最大每批列数");
            send(true,revision,changed(TOTAL_MBPS,"NaN"));rejected("有限数字");
            send(true,revision,changed(BANDS,"64:1,32:2"));rejected("距离分档");
            send(true,revision,changed(RESUME_MS,"100"));rejected("自动节流");
            send(true,revision,changed(PAUSE_TICKS,"0"));rejected("暂停连续");
            send(true,revision,changed(SLOW_TICKS,"30"));ok();check(SLOW_TICKS.get()==30,"slow debounce persisted");
            send(true,revision,changed(PAUSE_TICKS,"40"));ok();check(PAUSE_TICKS.get()==40,"pause debounce persisted");
            send(true,revision,changed(RECOVERY_SECONDS,"2.5"));ok();check(RECOVERY_SECONDS.get()==2.5,"recovery ramp persisted");
            send(true,revision,changed(TOTAL_MBPS,"29.0"));ok();check(TOTAL_MBPS.get()==29&&DistantConfig.readServerFile().equals(ServerSettings.current()),"saved and applied snapshot");
            send(true,revision-1,original.values());rejected("其他管理员");
            server.getPlayerList().deop(peer.player.getGameProfile());send(true,revision,original.values());rejected("权限不足");server.getPlayerList().op(peer.player.getGameProfile());
            Path blocked=net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("voxy_distant.toml.remote.tmp");Files.createDirectory(blocked);
            try{send(true,revision,changed(TOTAL_MBPS,"28.0"));rejected("配置未应用");check(TOTAL_MBPS.get()==29,"failed save retains effective config");}finally{Files.delete(blocked);}
            var db=(LodDatabase)field(service,"database");db.put(key,data);world=db.worldId();
            send(true,revision,changed(SERVER_CACHE_MIB,"64"));check(peer.result==null,"cache update is asynchronous");stage=1;return false;
        }
        if(stage==1){if(peer.result==null)return false;ok();check(SERVER_CACHE_MIB.get()==64,"cache setting applied");
            var db=(LodDatabase)field(service,"database");check(db.worldId().equals(world)&&Arrays.equals(db.get(key),data),"reopen preserves data and world UUID");
            send(true,revision,changed(SERVER_CACHE_MIB,"96"));server.getPlayerList().deop(peer.player.getGameProfile());stage=2;return false;
        }
        if(stage==2){if(peer.result==null)return false;rejected("权限已撤销");check(SERVER_CACHE_MIB.get()==64,"revoked pending change not applied");
            server.getPlayerList().op(peer.player.getGameProfile());
            Files.createDirectory(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("voxy_distant.toml.remote.tmp"));
            send(true,revision,changed(SERVER_CACHE_MIB,"96"));stage=3;return false;
        }
        if(stage==3){if(peer.result==null)return false;rejected("配置未应用");
            Files.delete(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("voxy_distant.toml.remote.tmp"));
            check(SERVER_CACHE_MIB.get()==64&&Arrays.equals(((LodDatabase)field(service,"database")).get(key),data),"save failure after reopen restores database and setting");
            send(true,revision,original.values());stage=4;return false;
        }
        if(stage==4){if(peer.result==null)return false;ok();check(ServerSettings.current().equals(original)&&DistantConfig.readServerFile().equals(original),"restore and persisted snapshot");
            var db=(LodDatabase)field(service,"database");check(Arrays.equals(db.get(key),data),"data retained after restoring capacity");
            server.getPlayerList().deop(peer.player.getGameProfile());
            System.out.println("DISTANT_SETTINGS_PASS: all fields/wire, validation, OP/revocation, stale revision, save failure with/without reopen, asynchronous cache reopen, data/UUID and persistence");stage=5;
        }
        return true;
    }
}
