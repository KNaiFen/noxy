package dev.voxydistant.smoke.mixin;

import dev.voxydistant.client.RemoteClient;
import dev.voxydistant.network.Protocol;
import dev.voxydistant.smoke.TransportMetrics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=RemoteClient.class,remap=false)
public class TransportMetricsMixin {
    @Inject(method="fragment",at=@At("HEAD"))
    private static void fragment(Protocol.Fragment f,CallbackInfo ci){TransportMetrics.fragment(f.offset(),f.bytes().length,1);}
    @Inject(method="batchFragment",at=@At("HEAD"))
    private static void batchFragment(Protocol.BatchFragment f,CallbackInfo ci){TransportMetrics.fragment(f.offset(),f.bytes().length,f.members().size());}
}
