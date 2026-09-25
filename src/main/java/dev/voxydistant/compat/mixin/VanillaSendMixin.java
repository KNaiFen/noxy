package dev.voxydistant.compat.mixin;

import dev.voxydistant.server.RemoteServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class VanillaSendMixin {
    @Shadow public ServerPlayer player;
    @Inject(method="send(Lnet/minecraft/network/protocol/Packet;)V",at=@At("HEAD"))
    private void distant$stamp(Packet<?> packet,CallbackInfo ci){
        if(packet instanceof ClientboundLevelChunkWithLightPacket p)RemoteServer.vanillaSent(player,p.getX(),p.getZ());
        else if(packet instanceof ClientboundBlockUpdatePacket p)RemoteServer.vanillaSent(player,p.getPos().getX()>>4,p.getPos().getZ()>>4);
        else if(packet instanceof ClientboundSectionBlocksUpdatePacket p){
            var pos=((SectionPacketAccessor)p).distant$position();RemoteServer.vanillaSent(player,pos.x(),pos.z());
        }
        else if(packet instanceof net.minecraft.network.protocol.game.ClientboundLightUpdatePacket p)RemoteServer.vanillaSent(player,p.getX(),p.getZ());
    }
}
