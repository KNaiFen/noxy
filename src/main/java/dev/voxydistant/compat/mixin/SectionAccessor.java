package dev.voxydistant.compat.mixin;

import me.cortex.voxy.common.world.ActiveSectionTracker;
import me.cortex.voxy.common.world.WorldSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = WorldSection.class, remap = false)
public interface SectionAccessor {
    @Accessor("tracker") ActiveSectionTracker distant$getTracker();
}
