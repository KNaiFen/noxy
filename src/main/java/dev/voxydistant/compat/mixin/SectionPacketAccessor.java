package dev.voxydistant.compat.mixin;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ClientboundSectionBlocksUpdatePacket.class)
public interface SectionPacketAccessor { @Accessor("sectionPos") SectionPos distant$position(); }
