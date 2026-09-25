package dev.voxydistant.compat.mixin;

import me.cortex.voxy.client.core.rendering.hierachical.NodeStore;
import me.cortex.voxy.common.world.WorldEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = NodeStore.class, remap = false)
public abstract class NodeStoreMixin {
    @Redirect(method = "writeNode", at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/rendering/hierachical/NodeStore;isNodeRequestInFlight(I)Z"))
    private boolean distant$stopMissingChildRequests(NodeStore nodes, int id) {
        // GPU request flag only; CPU state remains unchanged. A terminal L1 node
        // keeps rendering its mesh without requesting its missing L0 every frame.
        return nodes.isNodeRequestInFlight(id)
                || WorldEngine.getLevel(nodes.nodePosition(id)) > 0 && nodes.getNodeChildExistence(id) == 0;
    }
}
