package dev.voxydistant.compat.mixin;

import dev.voxydistant.client.RemoteClient;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=WorldIdentifier.class,remap=false)
public abstract class IdentifierMixin {
    @Inject(method="of",at=@At("RETURN"),cancellable=true)
    private static void distant$world(Level level,CallbackInfoReturnable<WorldIdentifier> ci){
        var world=RemoteClient.worldId();var id=ci.getReturnValue();
        if(world!=null&&id!=null&&level.isClientSide)ci.setReturnValue(new WorldIdentifier(id.key,id.biomeSeed^world.getMostSignificantBits()^Long.rotateLeft(world.getLeastSignificantBits(),23),id.dimension));
    }
}
