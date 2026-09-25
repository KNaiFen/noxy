package dev.voxydistant.compat.mixin;

import dev.voxydistant.server.RemoteServer;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class ServerLightMixin {
    @Shadow @Final private ServerLevel level;
    @Inject(method="onLightUpdate",at=@At("HEAD"))
    private void distant$light(LightLayer layer,SectionPos pos,CallbackInfo ci){RemoteServer.lightDirty(level,pos.x(),pos.z());}
}
