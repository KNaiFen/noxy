package dev.voxydistant.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.voxydistant.compat.VoxyBridge;
import dev.voxydistant.DebugLog;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.service.SectionSavingService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A coverage checkpoint may follow only a successful save of the current voxel data. */
@Mixin(value=SectionSavingService.class,remap=false)
public abstract class SaveMixin {
    @WrapOperation(method="processJob",at=@At(value="INVOKE",target="Lme/cortex/voxy/common/config/section/SectionStorage;saveSection(Lme/cortex/voxy/common/world/WorldSection;)V"))
    private void distant$saved(SectionStorage storage,WorldSection section,Operation<Void> original){
        var tracker=((SectionAccessor)(Object)section).distant$getTracker();
        var coverage=VoxyBridge.coverage(tracker.engine);
        // Saves of one node must reach storage in snapshot order. The receive lane
        // only holds coverage while copying, never while serializing or writing.
        synchronized(section){
            var snapshot=WorldSection._createRawUntrackedUnsafeSection(section.lvl,section.x,section.y,section.z);
            snapshot.acquire();
            try{
                long generation;
                synchronized(coverage){
                    generation=coverage.meshGeneration(section.key);
                    section.copyDataTo(snapshot._unsafeGetRawDataArray());
                    snapshot._unsafeSetNonEmptyChildren(section.getNonEmptyChildren());
                    snapshot.addNonEmptyBlockCount(section.getNonEmptyBlockCount());
                }
                long started=DebugLog.start();
                try{original.call(storage,snapshot);}finally{DebugLog.end(DebugLog.Metric.CLIENT_STORAGE_SAVE,started);}
                boolean current;
                synchronized(coverage){current=coverage.saved(section.key,generation);coverage.checkpointIfDue(storage::flush);}
                if(!current){
                    section.markDirty();
                    tracker.engine.saveSection(section,true,false);
                }
            }finally{snapshot.release();}
        }
    }
}
