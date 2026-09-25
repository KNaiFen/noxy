package dev.voxydistant.compat.mixin;

import dev.voxydistant.compat.VoxyBridge;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.common.world.WorldSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = AsyncNodeManager.class, remap = false)
public abstract class NodeUpdatesMixin {
    @Redirect(method = "run", at = @At(value = "INVOKE", target = "Lme/cortex/voxy/common/world/WorldSection;getNonEmptyChildren()B"))
    private byte distant$children(WorldSection section) { return VoxyBridge.renderChildren(section); }
}
