package dev.voxydistant.compat.mixin;

import dev.voxydistant.compat.CoverageStore;
import dev.voxydistant.compat.EngineAccess;
import me.cortex.voxy.common.world.WorldEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WorldEngine.class, remap = false)
public abstract class EngineMixin implements EngineAccess {
    @Unique private CoverageStore distant$coverage;
    public CoverageStore distant$getCoverage() { return distant$coverage; }
    public void distant$setCoverage(CoverageStore coverage) { distant$coverage = coverage; }
    @Redirect(method="markDirty(Lme/cortex/voxy/common/world/WorldSection;II)V",at=@At(value="INVOKE",target="Lme/cortex/voxy/common/world/WorldEngine$ISectionChangeCallback;accept(Lme/cortex/voxy/common/world/WorldSection;II)V"))
    private void distant$publish(WorldEngine.ISectionChangeCallback callback,me.cortex.voxy.common.world.WorldSection section,int flags,int neighbors){
        if(distant$coverage==null||!distant$coverage.deferUpdate(section,flags,neighbors))callback.accept(section,flags,neighbors);
    }
    @Inject(method = "free", at = @At("RETURN"))
    private void distant$saveCoverage(CallbackInfo ci) {
        // Voxy has drained section saves and closed the database before this callback.
        if (distant$coverage != null) distant$coverage.saveAfterWorldClosed();
    }
}
