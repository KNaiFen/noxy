package dev.voxydistant.compat.mixin;

import dev.voxydistant.server.RemoteServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(LevelChunk.class)
public abstract class ServerBlockMixin {
    @Shadow @Final private Level level;
    @Inject(method="setBlockState",at=@At("RETURN"))
    private void distant$dirty(BlockPos pos,BlockState state,boolean moved,CallbackInfoReturnable<BlockState> ci){
        if(level instanceof ServerLevel server && ci.getReturnValue()!=null)RemoteServer.markDirty(server,pos.getX()>>4,pos.getZ()>>4);
    }
}
