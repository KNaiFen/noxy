package dev.voxydistant.smoke.mixin;

import me.cortex.voxy.common.world.WorldEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records actual queue insertion, including model retries, for the fixed camera's near nodes. */
@Mixin(targets="me.cortex.voxy.client.core.rendering.building.RenderGenerationService$BuildTask",remap=false)
public class MeshQueueMetricsMixin {
    @Shadow @Final private long position;
    @Shadow private long priority;
    @Shadow private int attempts;

    @Inject(method="updatePriority",at=@At("RETURN"))
    private void distant$queued(CallbackInfo ci) {
        if(!Boolean.getBoolean("voxyDistant.visibilityDiagnostic"))return;
        int level=WorldEngine.getLevel(position),scale=32<<level;
        int x=WorldEngine.getX(position)*scale,z=WorldEngine.getZ(position)*scale;
        if(x < -256 || x > 256 || z < -256 || z > 256)return;
        System.out.println("DISTANT_MESH_QUEUED epoch_ms="+System.currentTimeMillis()+" node="+position+" level="+level+" priority="+Long.toUnsignedString(priority)+" attempts="+attempts);
    }
}
