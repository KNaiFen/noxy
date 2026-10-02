package dev.voxydistant.smoke.mixin;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Isolates the vanilla section-depth mask without changing voxel data or node selection. */
@Mixin(value=ChunkBoundRenderer.class,remap=false)
public class MaskDiagnosticMixin {
    @Shadow @Final private RenderProperties properties;

    @Inject(method="render",at=@At("HEAD"),cancellable=true)
    private void distant$bypass(Viewport<?> viewport,CallbackInfo ci) {
        if(!Boolean.getBoolean("voxyDistant.maskBypass"))return;
        viewport.depthBoundingBuffer.clear(properties.inverseClearDepth());
        ci.cancel();
    }
}
