package dev.voxydistant.smoke.mixin;

import dev.voxydistant.smoke.LodBenchmark;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.common.world.WorldEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import static org.lwjgl.opengl.GL45.*;

/** Synchronous GPU readback for visibility diagnosis only; excluded from performance runs. */
@Mixin(value=MDICSectionRenderer.class,remap=false)
public class VisibilityMetricsMixin {
    @Unique private long distant$lastSample;

    @Inject(method="buildDrawCalls(Lme/cortex/voxy/client/core/rendering/section/backend/mdic/MDICViewport;)V",at=@At("RETURN"))
    private void distant$visibility(MDICViewport viewport,CallbackInfo ci) {
        if(!Boolean.getBoolean("voxyDistant.visibilityDiagnostic"))return;
        long now=System.nanoTime();if(now-distant$lastSample<1_000_000_000L)return;distant$lastSample=now;
        var renderer=me.cortex.voxy.client.core.IGetVoxyRenderSystem.getNullable();
        var geometry=(BasicSectionGeometryData)dev.voxydistant.smoke.LodMetrics.field(renderer,"geometryData");
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
        int[] count=new int[1];glGetNamedBufferSubData(viewport.indirectLookupBuffer.id,0,count);
        int[] ids=new int[count[0]];glGetNamedBufferSubData(viewport.indirectLookupBuffer.id,4,ids);
        int max=-1;for(int id:ids)max=Math.max(max,id);
        int[] metadata=new int[(max+1)*8],visibility=new int[max+1];
        if(max>=0){glGetNamedBufferSubData(geometry.getMetadataBuffer().id,0,metadata);glGetNamedBufferSubData(viewport.visibilityBuffer.id,0,visibility);}
        int[] listed=new int[5],visible=new int[5];var rows=new StringBuilder();long epoch=System.currentTimeMillis();
        for(int id:ids){
            int offset=id*8;long pos=((long)metadata[offset]<<32)|Integer.toUnsignedLong(metadata[offset+1]);
            int level=WorldEngine.getLevel(pos),x=WorldEngine.getX(pos),y=WorldEngine.getY(pos),z=WorldEngine.getZ(pos),aabb=metadata[offset+2];
            boolean seen=(visibility[id]&0x7fffffff)==viewport.frameId;listed[level]++;if(seen)visible[level]++;
            if(level<3&&(Math.abs(x*(32<<level))>256||Math.abs(z*(32<<level))>256))continue;
            int scale=1<<level;
            double minX=(x*32+(aabb&31)-1)*scale,minY=(y*32+((aabb>>5)&31)-1)*scale,minZ=(z*32+((aabb>>10)&31)-1)*scale;
            double maxX=minX+(((aabb>>15)&31)+3)*scale,maxY=minY+(((aabb>>20)&31)+3)*scale,maxZ=minZ+(((aabb>>25)&31)+3)*scale;
            boolean inside=viewport.cameraX>=minX&&viewport.cameraX<=maxX&&viewport.cameraY>=minY&&viewport.cameraY<=maxY&&viewport.cameraZ>=minZ&&viewport.cameraZ<=maxZ;
            rows.append(epoch).append(',').append(viewport.frameId).append(',').append(pos).append(',').append(level).append(',').append(x).append(',').append(y).append(',').append(z).append(',').append(id).append(',').append(seen).append(',').append(inside).append(',').append(minX).append(',').append(minY).append(',').append(minZ).append(',').append(maxX).append(',').append(maxY).append(',').append(maxZ).append('\n');
        }
        try{
            var file=LodBenchmark.OUTPUT.resolve("gpu-visibility.csv");
            if(!Files.exists(file))Files.writeString(file,"epoch_ms,frame,node,level,x,y,z,geometry_id,visible,camera_inside,min_x,min_y,min_z,max_x,max_y,max_z\n");
            Files.writeString(file,rows,StandardOpenOption.APPEND);
            Files.writeString(LodBenchmark.OUTPUT.resolve("gpu-summary.jsonl"),"{\"epoch_ms\":"+epoch+",\"frame\":"+viewport.frameId+",\"listed\":"+java.util.Arrays.toString(listed)+",\"visible\":"+java.util.Arrays.toString(visible)+"}\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }catch(IOException ex){throw new UncheckedIOException(ex);}
    }
}
