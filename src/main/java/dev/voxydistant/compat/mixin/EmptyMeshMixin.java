package dev.voxydistant.compat.mixin;

import dev.voxydistant.compat.*;
import me.cortex.voxy.client.core.rendering.building.*;
import me.cortex.voxy.common.world.WorldEngine;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(value=RenderGenerationService.class,remap=false)
public abstract class EmptyMeshMixin {
    @Shadow @Final private WorldEngine world;

    @Redirect(method="processJob",at=@At(value="INVOKE",target="Lme/cortex/voxy/client/core/rendering/building/BuiltSection;empty(J)Lme/cortex/voxy/client/core/rendering/building/BuiltSection;"))
    private BuiltSection distant$stampEmpty(long position){
        var mesh=BuiltSection.empty(position);
        var coverage=VoxyBridge.coverage(world);
        if(coverage!=null&&coverage.remote()){
            coverage.prepareMesh(position);
            ((MeshStamp)(Object)mesh).distant$stamp(coverage,coverage.meshGeneration(position));
        }
        return mesh;
    }
}
