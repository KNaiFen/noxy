package dev.voxydistant.compat.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Connection.class)
public abstract class ConnectionReadMixin implements dev.voxydistant.network.ConnectionAccess {
    @Shadow private Channel channel;
    @Override public Channel distant$channel(){return channel;}

    // Netty's off-thread clearReadPending can otherwise run after Forge re-enables reads,
    // leaving autoRead=true with OP_READ removed until the login times out.
    @Redirect(method="sendPacket",at=@At(value="INVOKE",target="Lio/netty/channel/ChannelConfig;setAutoRead(Z)Lio/netty/channel/ChannelConfig;",remap=false))
    private ChannelConfig distant$orderReadChange(ChannelConfig config,boolean enabled){
        if(channel.eventLoop().inEventLoop())config.setAutoRead(enabled);
        else channel.eventLoop().execute(()->config.setAutoRead(enabled));
        return config;
    }
}
