package dev.voxydistant.compat.mixin;

import dev.voxydistant.client.RemoteClient;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL41C.glProgramUniform1f;

@Mixin(value=MDICSectionRenderer.class,remap=false)
public abstract class RenderRadiusMixin {
    @Shadow @Final private Shader terrainShader;
    @Shadow @Final private Shader translucentTerrainShader;
    @Unique private int distant$opaqueLocation=-2,distant$translucentLocation=-2;

    @Inject(method="renderOpaque",at=@At("HEAD"))
    private void distant$opaqueRadius(CallbackInfo ci){
        if(distant$opaqueLocation==-2){distant$opaqueLocation=glGetUniformLocation(terrainShader.id(),"distantRadiusSquared");if(distant$opaqueLocation<0)throw new IllegalStateException("Voxy opaque radius uniform missing");}
        glProgramUniform1f(terrainShader.id(),distant$opaqueLocation,RemoteClient.renderRadiusSquared());
    }

    @Inject(method="renderTranslucent",at=@At("HEAD"))
    private void distant$translucentRadius(CallbackInfo ci){
        if(distant$translucentLocation==-2){distant$translucentLocation=glGetUniformLocation(translucentTerrainShader.id(),"distantRadiusSquared");if(distant$translucentLocation<0)throw new IllegalStateException("Voxy translucent radius uniform missing");}
        glProgramUniform1f(translucentTerrainShader.id(),distant$translucentLocation,RemoteClient.renderRadiusSquared());
    }
}
