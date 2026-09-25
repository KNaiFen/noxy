package dev.voxydistant.compat.mixin;

import dev.voxydistant.client.RemoteClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientLightMixin {
    @Shadow private ClientLevel level;

    @Inject(method="updateLevelChunk",at=@At("HEAD"))
    private void distant$chunkPending(int x,int z,ClientboundLevelChunkPacketData data,CallbackInfo ci){
        RemoteClient.lightPending(level,x,z);
    }

    @Inject(method="applyLightData",at=@At("TAIL"))
    private void distant$lightApplied(int x,int z,ClientboundLightUpdatePacketData data,CallbackInfo ci){
        RemoteClient.lightApplied(level,x,z);
    }
}
