package dev.voxydistant.compat.mixin;

import dev.voxydistant.server.PriorityState;
import dev.voxydistant.server.RemoteServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.ChunkAccess;
import java.util.List;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ChunkMap.class)
public abstract class ServerChunkMapMixin implements PriorityState.MapAccess {
    @Shadow @Final private ChunkTaskPriorityQueueSorter queueSorter;
    @Shadow @Final private ServerLevel level;
    @Inject(method="<init>",at=@At("RETURN"))
    private void distant$init(CallbackInfo ci){PriorityState.attach(level,this);}
    public void distant$attach(PriorityState.Context context){
        for(Object queue:((SorterAccessor)queueSorter).distant$queues().values())((PriorityState.QueueAccess)queue).distant$priority(context);
    }
    @Inject(method="resendBiomesForChunks",at=@At("HEAD"))
    private void distant$biomes(List<ChunkAccess> chunks,CallbackInfo ci){
        for(var chunk:chunks)RemoteServer.markDirty(level,chunk.getPos().x,chunk.getPos().z);
    }
    @Inject(method="save",at=@At("RETURN"))
    private void distant$saved(ChunkAccess chunk,CallbackInfoReturnable<Boolean> ci){
        RemoteServer.chunkSaved(level,chunk.getPos().x,chunk.getPos().z);
    }
}
