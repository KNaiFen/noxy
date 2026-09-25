package dev.voxydistant.compat.mixin;
import dev.voxydistant.compat.*;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=BuiltSection.class,remap=false)
public abstract class BuiltMeshMixin implements MeshStamp {
    @Shadow @Final public long position;
    @Unique private CoverageStore distant$coverage;
    @Unique private long distant$generation;
    public CoverageStore distant$coverage(){return distant$coverage;}
    public void distant$stamp(CoverageStore coverage,long generation){distant$coverage=coverage;distant$generation=generation;}
    public boolean distant$current(){return distant$coverage==null||distant$coverage.meshGeneration(position)==distant$generation&&(distant$generation&1)==0;}
    public long distant$generation(){return distant$generation;}
    public long distant$latestGeneration(){return distant$coverage==null?distant$generation:distant$coverage.meshGeneration(position);}
    @Inject(method="clone",at=@At("RETURN"))
    private void distant$clone(CallbackInfoReturnable<BuiltSection> ci){((MeshStamp)(Object)ci.getReturnValue()).distant$stamp(distant$coverage,distant$generation);}
}
