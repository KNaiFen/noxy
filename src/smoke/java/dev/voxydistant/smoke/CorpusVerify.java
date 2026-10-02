package dev.voxydistant.smoke;

import dev.voxydistant.data.*;
import dev.voxydistant.generation.ChunkSnapshot;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import java.nio.file.*;
import java.util.*;

/** Compare offline corpus reconstruction with loaded, light-correct production snapshots. */
public final class CorpusVerify {
    private net.minecraft.server.MinecraftServer server;
    private final TicketType<ChunkPos> ticket=TicketType.create("lod_verify",Comparator.comparingLong(ChunkPos::toLong));
    private int index,ticks;
    private ChunkPos pending;
    private final int[][] positions={{32,32},{-32,-32},{96,80},{-96,80},{180,-80},{-180,80},{0,240},{240,0},{-240,0},{0,-240},{128,128},{-128,-128}};
    public CorpusVerify(){MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e)->server=e.getServer());MinecraftForge.EVENT_BUS.addListener(this::tick);}
    private void tick(TickEvent.ServerTickEvent event) {
        if(server==null||event.phase!=TickEvent.Phase.END)return;
        var level=server.overworld();
        if(++ticks>2400)throw new AssertionError("Corpus validation timed out");
        if(pending==null){pending=new ChunkPos(positions[index][0],positions[index][1]);level.getChunkSource().addRegionTicket(ticket,pending,0,pending);return;}
        var chunk=level.getChunkSource().getChunkNow(pending.x,pending.z);if(chunk==null||!chunk.isLightCorrect())return;
        var snapshots=new ArrayList<ChunkSnapshot>();
        for(int i=0;i<chunk.getSectionsCount();i++) {
            int y=chunk.getMinSection()+i;var section=chunk.getSections()[i];var pos=SectionPos.of(pending,y);
            var block=level.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);var sky=level.getLightEngine().getLayerListener(LightLayer.SKY).getDataLayerData(pos);
            var biomes=section.getBiomes().recreate();for(int by=0;by<4;by++)for(int bz=0;bz<4;bz++)for(int bx=0;bx<4;bx++)biomes.getAndSetUnchecked(bx,by,bz,section.getBiomes().get(bx,by,bz));
            snapshots.add(new ChunkSnapshot(pending.x,y,pending.z,section.getStates().copy(),biomes,block==null?null:block.copy(),sky==null?null:sky.copy()));
        }
        var actual=ColumnConverter.convert(pending.x,pending.z,1,snapshots);
        try(var db=new LodDatabase(Path.of(System.getProperty("voxyDistant.verifyCache")),8L<<20)) {
            var expected=ColumnCodec.decode(LodDatabase.unpack(db.get(LodDatabase.key("minecraft:overworld",pending.toLong(),1))));
            long mismatch=0;
            for(int s=0;s<24;s++)for(int l=0;l<5;l++)for(int i=0;i<actual.sections()[s][l].length;i++) {
                long a=actual.sections()[s][l][i],b=expected.sections()[s][l][i];
                if(!actual.states().get(LodColumn.state(a)).equals(expected.states().get(LodColumn.state(b)))||!actual.biomes().get(LodColumn.biome(a)).equals(expected.biomes().get(LodColumn.biome(b)))||(a>>>40)!=(b>>>40))mismatch++;
            }
            if(mismatch!=0)throw new AssertionError("Offline/live mismatch "+pending+" voxels="+mismatch);
        }
        level.getChunkSource().removeRegionTicket(ticket,pending,0,pending);pending=null;
        if(++index==positions.length){System.out.println("DISTANT_LOD_LIVE_CORPUS_PASS columns="+index);server.halt(false);server=null;}
    }
}
