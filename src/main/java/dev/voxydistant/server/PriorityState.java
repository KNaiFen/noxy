package dev.voxydistant.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Immutable spatial snapshots, published to the vanilla sorter mailbox. */
public final class PriorityState {
    public record Area(int x,int z,int radius) {
        boolean contains(long pos) { return Math.abs((long)ChunkPos.getX(pos)-x)<=radius && Math.abs((long)ChunkPos.getZ(pos)-z)<=radius; }
    }
    public record View(long revision,List<Area> foreground,List<Area> extra) {
        public boolean background(long pos) {
            for(Area a:foreground)if(a.contains(pos))return false;
            for(Area a:extra)if(a.contains(pos))return true;
            return false;
        }
    }
    public interface QueueAccess { void distant$priority(Context context); }
    public interface MapAccess { void distant$attach(Context context); }
    public static final class Context {
        public volatile View view=new View(0,List.of(),List.of());
        private final Set<Long> extra=new HashSet<>();
        private List<Area> players=List.of();
        private void publish() {
            var areas=new ArrayList<Area>();for(long p:extra)areas.add(new Area(ChunkPos.getX(p),ChunkPos.getZ(p),ChunkStatus.maxDistance()));
            view=new View(REVISIONS.incrementAndGet(),players,List.copyOf(areas));
        }
    }
    private static final AtomicLong REVISIONS=new AtomicLong();
    private static final Map<ServerLevel,Context> LEVELS=new ConcurrentHashMap<>();
    private static int pending, scanPlayer, scanCell, scanPending;
    public static void attach(ServerLevel level,MapAccess map){Context c=new Context();LEVELS.put(level,c);map.distant$attach(c);}
    public static void add(ServerLevel level,ChunkPos pos){Context c=LEVELS.get(level);c.extra.add(pos.toLong());c.publish();}
    public static void remove(ServerLevel level,ChunkPos pos){Context c=LEVELS.get(level);c.extra.remove(pos.toLong());c.publish();}
    public static void tick(MinecraftServer server) {
        int view=server.getPlayerList().getViewDistance();
        for(var e:LEVELS.entrySet()) {
            var foreground=new ArrayList<Area>();
            for(var p:e.getKey().players())foreground.add(new Area(p.chunkPosition().x,p.chunkPosition().z,view+ChunkStatus.maxDistance()+1));
            Context c=e.getValue();if(!foreground.equals(c.players)){c.players=List.copyOf(foreground);c.publish();pending=1;scanPlayer=scanCell=scanPending=0;}
        }
        var players=server.getPlayerList().getPlayers();if(players.isEmpty()){pending=0;return;}
        int width=view*2+1;
        for(int budget=0;budget<512;budget++) {
            if(scanPlayer>=players.size()){pending=scanPending;scanPlayer=scanCell=scanPending=0;break;}
            var p=players.get(scanPlayer);int x=p.chunkPosition().x+scanCell%width-view,z=p.chunkPosition().z+scanCell/width-view;
            var chunk=p.serverLevel().getChunkSource().getChunkNow(x,z);
            if(net.minecraft.server.level.ChunkMap.isChunkInRange(x,z,p.chunkPosition().x,p.chunkPosition().z,view)
                    && (chunk==null || !chunk.isLightCorrect())){scanPending++;pending=Math.max(pending,1);}
            if(++scanCell>=width*width){scanCell=0;scanPlayer++;}
        }
    }
    public static int foregroundPending(){return pending;}
    public static void clear(){LEVELS.clear();pending=scanPlayer=scanCell=scanPending=0;}
    private PriorityState(){}
}
