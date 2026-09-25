package dev.voxydistant.network;

import io.netty.channel.Channel;

public interface ConnectionAccess {
    Channel distant$channel();
}
