package dev.voxydistant.compat.mixin;

import dev.voxydistant.compat.VoxyBridge;
import me.cortex.voxy.client.core.rendering.building.RenderDataFactory;
import me.cortex.voxy.common.world.WorldSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import dev.voxydistant.compat.*;
import dev.voxydistant.DebugLog;

@Mixin(value = RenderDataFactory.class, remap = false)
public abstract class MeshMixin {
    @Unique private CoverageStore distant$index;
    @Unique private long distant$generation;
    @Unique private long distant$buildStarted;
    @Inject(method="generateMesh",at=@At("HEAD"))
    private void distant$start(WorldSection section,CallbackInfoReturnable<BuiltSection> ci){
        var tracker=((SectionAccessor)(Object)section).distant$getTracker();
        distant$index=tracker==null?null:VoxyBridge.coverage(tracker.engine);
        // Cached voxels can reach meshing before RemoteClient scans their coverage page.
        // Do the disk lookup on this mesh worker, never on the node/render thread.
        if(distant$index!=null)distant$index.prepareMesh(section.key);
        distant$generation=distant$index==null?0:distant$index.meshGeneration(section.key);distant$buildStarted=DebugLog.start();
    }
    @Inject(method="generateMesh",at=@At("RETURN"))
    private void distant$stamp(WorldSection section,CallbackInfoReturnable<BuiltSection> ci){
        ((MeshStamp)(Object)ci.getReturnValue()).distant$stamp(distant$index,distant$generation);
        if(DebugLog.mesh()&&distant$index!=null)DebugLog.log("CLIENT mesh_built node={} level={} x={} y={} z={} generation={} current_generation={} children={} occupancy={} can_split={} empty={} build_ms={}",section.key,section.lvl,section.x,section.y,section.z,distant$generation,distant$index.meshGeneration(section.key),ci.getReturnValue().childExistence&255,section.getNonEmptyChildren()&255,distant$index.canSplit(section.key),ci.getReturnValue().isEmpty(),distant$buildStarted==0?0:(System.nanoTime()-distant$buildStarted)/1e6);
    }
    @Redirect(method = "generateMesh", at = @At(value = "INVOKE", target = "Lme/cortex/voxy/common/world/WorldSection;getNonEmptyChildren()B"), require = 2)
    private byte distant$children(WorldSection section) { return VoxyBridge.renderChildrenForMesh(section); }
}
