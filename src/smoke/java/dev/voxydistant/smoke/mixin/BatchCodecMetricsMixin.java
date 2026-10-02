package dev.voxydistant.smoke.mixin;

import dev.voxydistant.data.*;
import dev.voxydistant.smoke.LodMetrics;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=BatchCodec.class,remap=false)
public class BatchCodecMetricsMixin {
    @Inject(method="encode",at=@At("HEAD"))
    private static void start(List<byte[]> columns,int level,CallbackInfoReturnable<ColumnCodec.Encoded> ci){LodMetrics.begin(0);}
    @Inject(method="encode",at=@At("RETURN"))
    private static void end(List<byte[]> columns,int level,CallbackInfoReturnable<ColumnCodec.Encoded> ci){var e=ci.getReturnValue();LodMetrics.end(0,-1,e.bytes().length,e.rawLength());}
}
