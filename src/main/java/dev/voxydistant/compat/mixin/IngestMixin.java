package dev.voxydistant.compat.mixin;

import dev.voxydistant.client.RemoteClient;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.world.level.chunk.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Capture immutable vanilla data and its revision together, before background conversion. */
@Mixin(value=VoxelIngestService.class,remap=false)
public abstract class IngestMixin {
    @Inject(method="enqueueIngest",at=@At("HEAD"),cancellable=true)
    private void distant$column(WorldEngine engine,LevelChunk chunk,CallbackInfoReturnable<Boolean> ci){
        if(RemoteClient.handles(engine))ci.setReturnValue(RemoteClient.full(engine,chunk));
    }
    @Inject(method="rawIngest0",at=@At("HEAD"),cancellable=true)
    private void distant$section(WorldEngine engine,LevelChunkSection section,int x,int y,int z,DataLayer block,DataLayer sky,CallbackInfoReturnable<Boolean> ci){
        if(RemoteClient.handles(engine)){RemoteClient.snapshot(engine,section,x,y,z,block,sky);ci.setReturnValue(true);}
    }
}
