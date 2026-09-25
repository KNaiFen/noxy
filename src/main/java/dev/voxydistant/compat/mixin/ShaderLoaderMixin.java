package dev.voxydistant.compat.mixin;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=ShaderLoader.class,remap=false)
public class ShaderLoaderMixin {
    @Inject(method="parse",at=@At("RETURN"),cancellable=true)
    private static void distant$cameraInside(String id,CallbackInfoReturnable<String> cir) {
        if(id.equals("voxy:lod/gl46/quads3.vert")){
            String source=cir.getReturnValue();
            String declaration="layout(location = 0) out flat uvec4 interData;";
            String anchor="uvec2 pos = positionBuffer[gl_BaseInstance];";
            if(!source.contains(declaration)||!source.contains(anchor))throw new IllegalStateException("Unsupported Voxy quad vertex shader");
            cir.setReturnValue(source.replace(declaration,declaration+"\nlayout(location = 2) out flat uint distantLevel;")
                    .replace(anchor,anchor+"\ndistantLevel = getLoDLevel(pos);"));
            return;
        }
        if(id.equals("voxy:lod/gl46/quads.frag")){
            String source=cir.getReturnValue();
            String declaration="layout(location = 0) in flat uvec4 interData;";
            String anchor="if (DEPTH_SCALAR_COMPARE(gl_FragCoord.z, texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r)) {";
            if(!source.contains(declaration)||!source.contains(anchor))throw new IllegalStateException("Unsupported Voxy quad fragment shader");
            // Vanilla pixels are already protected by stencil. Its section volume does not
            // match coarse surfaces and cuts sky holes at the seam; retain that mask for L0.
            cir.setReturnValue(source.replace(declaration,declaration+"\nlayout(location = 2) in flat uint distantLevel;")
                    .replace(anchor,"if (distantLevel == 0u && DEPTH_SCALAR_COMPARE(gl_FragCoord.z, texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r)) {"));
            return;
        }
        if(id.equals("voxy:lod/hierarchical/traversal_dev.comp")){
            String source=cir.getReturnValue();
            String anchor="shouldRenderSelf = (far.x*far.x+far.z*far.z+(16*16*16))<=renderDistance;";
            if(!source.contains(anchor))throw new IllegalStateException("Unsupported Voxy traversal shader");
            // A coarse boundary node can contain received terrain inside the radius.
            cir.setReturnValue(source.replace(anchor,"shouldRenderSelf = isWithinRenderDistance(node);"));
            return;
        }
        if(!id.equals("voxy:lod/gl46/cull/raster.vert"))return;
        String source=cir.getReturnValue();
        String anchor="value = (frameId&0x7fffffffu)|(uint(wasVisibleLastFrame)<<31);";
        if(!source.contains(anchor))throw new IllegalStateException("Unsupported Voxy visibility shader");
        cir.setReturnValue(source.replace(anchor,anchor+"""

            // A box containing the camera has no conservative rasterized front surface.
            vec3 relativeCamera = cameraSubPos - vec3(pos);
            vec3 boxMin = vec3(aabbOffset - EXPANSION) * float(1 << detail);
            vec3 boxMax = vec3(aabbOffset + size + EXPANSION) * float(1 << detail);
            if (all(greaterThanEqual(relativeCamera, boxMin)) && all(lessThanEqual(relativeCamera, boxMax))) {
                visibilityData[sid] = value;
            }
            """));
    }
}
