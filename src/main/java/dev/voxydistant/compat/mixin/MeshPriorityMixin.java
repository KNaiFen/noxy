package dev.voxydistant.compat.mixin;

import dev.voxydistant.client.RemoteClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="me.cortex.voxy.client.core.rendering.building.RenderGenerationService$BuildTask",remap=false)
public class MeshPriorityMixin {
    @Shadow private long priority;

    @Inject(method="updatePriority",at=@At("RETURN"))
    private void distant$fairQueue(CallbackInfo ci) {
        // Keep the existing FIFO sequence, including requeued model requests.
        // An endless stream of coarse updates must not starve nearby fine meshes.
        if(RemoteClient.greeting()!=null)priority&=0xffffffffL;
    }
}
