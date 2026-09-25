package dev.voxydistant.compat.mixin;

import dev.voxydistant.compat.VoxyBridge;
import dev.voxydistant.generation.GenerationController;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VoxyInstance.class, remap = false)
public abstract class InstanceMixin {
    @Inject(method = "createWorld", at = @At("RETURN"))
    private void distant$attach(WorldIdentifier id, CallbackInfoReturnable<WorldEngine> cir) {
        VoxyBridge.attach(cir.getReturnValue(), id);
    }
    @Inject(method = "shutdown", at = @At("HEAD"))
    private void distant$stop(CallbackInfo ci) { dev.voxydistant.client.RemoteClient.shutdown(); GenerationController.beforeVoxyShutdown(); }
}
