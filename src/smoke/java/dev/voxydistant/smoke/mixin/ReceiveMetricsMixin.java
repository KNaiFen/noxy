package dev.voxydistant.smoke.mixin;

import dev.voxydistant.compat.CoarseLodReceiver;
import dev.voxydistant.data.LodColumn;
import dev.voxydistant.smoke.LodMetrics;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.core.RegistryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=CoarseLodReceiver.class,remap=false)
public class ReceiveMetricsMixin {
    @Inject(method="receive(Lme/cortex/voxy/common/world/WorldEngine;Ldev/voxydistant/data/LodColumn;Lnet/minecraft/core/RegistryAccess;ZLdev/voxydistant/compat/CoarseLodReceiver$Batch;)V",at=@At("HEAD"))
    private static void start(WorldEngine engine,LodColumn column,RegistryAccess registry,boolean authoritative,CoarseLodReceiver.Batch batch,CallbackInfo ci){LodMetrics.begin(2);}
    @Inject(method="receive(Lme/cortex/voxy/common/world/WorldEngine;Ldev/voxydistant/data/LodColumn;Lnet/minecraft/core/RegistryAccess;ZLdev/voxydistant/compat/CoarseLodReceiver$Batch;)V",at=@At("RETURN"))
    private static void end(WorldEngine engine,LodColumn column,RegistryAccess registry,boolean authoritative,CoarseLodReceiver.Batch batch,CallbackInfo ci){LodMetrics.end(2,column.minimumLevel(),0,0);}
}
