package dev.voxydistant.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.voxydistant.compat.VoxyBridge;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = WorldUpdater.class, remap = false)
public abstract class UpdaterMixin {
    @WrapMethod(method = "insertUpdate")
    private static void distant$fullUpdate(WorldEngine world, VoxelizedSection section, Operation<Void> original) {
        var state = VoxyBridge.coverage(world);
        if(state.remote()&&!VoxyBridge.background())return;
        synchronized (state) {
            state.beginWrite(section.x,section.z,section.y,section.y+1);
            state.meshBegin(section.x,section.z,section.y,section.y+1);
            try{original.call(world, section);VoxyBridge.afterFull(world, section);}
            finally{state.meshEnd(world,section.x,section.z,section.y,section.y+1);}
        }
    }
}
