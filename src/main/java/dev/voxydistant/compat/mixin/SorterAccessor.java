package dev.voxydistant.compat.mixin;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;
@Mixin(ChunkTaskPriorityQueueSorter.class)
public interface SorterAccessor { @Accessor("queues") Map<?,?> distant$queues(); }
