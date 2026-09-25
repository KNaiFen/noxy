package dev.voxydistant.compat.mixin;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

@Mixin(value=RenderSectionManager.class,remap=false)
public abstract class SolidBoundsMixin {
    @Shadow @Final private ClientLevel world;
    @Unique private final Set<Long> distant$nonSolidBounds=new HashSet<>();

    @Inject(method="updateSectionInfo",at=@At("TAIL"))
    private void distant$solidBounds(RenderSection section,BuiltSectionInfo info,CallbackInfo ci){
        var renderer=((IGetVoxyRenderSystem)(Object)net.minecraft.client.Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
        if(renderer==null)return;
        int chunkX=section.getChunkX(),sectionY=section.getChunkY(),chunkZ=section.getChunkZ();
        long pos=SectionPos.asLong(chunkX,sectionY,chunkZ);
        if(section.getFlags()==0){distant$nonSolidBounds.remove(pos);return;}
        var blocks=world.getChunkSource().getChunk(chunkX,chunkZ,ChunkStatus.FULL,false)
                .getSections()[world.getSectionIndexFromSectionY(sectionY)];
        var blockPos=new BlockPos.MutableBlockPos();
        boolean solid=true;
        // Voxy's depth box covers all 16^3 blocks, including air above land.
        // Only a completely opaque section can safely contribute that box.
        outer: for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            if(!blocks.getBlockState(x,y,z).isSolidRender(world,blockPos.set((chunkX<<4)+x,(sectionY<<4)+y,(chunkZ<<4)+z))){
                solid=false;break outer;
            }
        }
        if(!solid){
            if(distant$nonSolidBounds.add(pos))renderer.chunkBoundRenderer.removeSection(pos);
        }else if(distant$nonSolidBounds.remove(pos)){
            renderer.chunkBoundRenderer.addSection(pos);
        }
    }

    @Inject(method="onSectionRemoved",at=@At("HEAD"))
    private void distant$removeNonSolidBound(int x,int y,int z,CallbackInfo ci){
        distant$nonSolidBounds.remove(SectionPos.asLong(x,y,z));
    }
}
