package dev.voxydistant.compat.mixin;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = WorldUpdater.class, remap = false)
public interface UpdaterAccessor {
    @Invoker("insertSectionLvlIntoWorld")
    static long distant$insertLevel(VoxelizedSection source, WorldSection destination) { throw new AssertionError(); }
}
