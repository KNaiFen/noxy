package dev.voxydistant.smoke;
import dev.voxydistant.data.*;
// Frozen pre-optimization oracle, smoke tests only.

import com.github.luben.zstd.Zstd;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

public final class ReferenceColumnCodec {
    public static byte[] encodeRaw(LodColumn column,int mask) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeInt(1); buf.writeInt(column.x()); buf.writeInt(column.z()); buf.writeInt(column.minY());
            buf.writeLong(column.version()); buf.writeByte(mask);
            // Palette contains only values used by transmitted levels.
            var states = new LinkedHashMap<Integer, Integer>(); states.put(0, 0);
            var biomes = new LinkedHashMap<Integer, Integer>();
            for (var section : column.sections()) for (int l = 0; l < 5; l++) if ((mask & (1 << l)) != 0)
                for (long v : section[l]) {
                    states.computeIfAbsent(LodColumn.state(v), k -> states.size());
                    biomes.computeIfAbsent(LodColumn.biome(v), k -> biomes.size());
                }
            buf.writeVarInt(states.size());
            for (int id : states.keySet()) buf.writeNbt(NbtUtils.writeBlockState(column.states().get(id)));
            buf.writeVarInt(biomes.size());
            for (int id : biomes.keySet()) buf.writeUtf(column.biomes().get(id), 256);
            buf.writeVarInt(column.sections().length);
            for (var section : column.sections()) for (int l = 0; l < 5; l++) if ((mask & (1 << l)) != 0) {
                long[] values = section[l];
                var palette = new LinkedHashMap<Long, Integer>();
                for (long v : values) palette.computeIfAbsent(v, k -> palette.size());
                buf.writeVarInt(palette.size());
                for (long v : palette.keySet()) {
                    buf.writeVarInt(states.get(LodColumn.state(v))); buf.writeVarInt(biomes.get(LodColumn.biome(v)));
                    buf.writeByte(LodColumn.light(v)); buf.writeByte((int) (v >>> 48) & 15);
                }
                if (palette.size() > 1) for (long v : values) buf.writeVarInt(palette.get(v));
            }
            byte[] raw = new byte[buf.readableBytes()]; buf.readBytes(raw);
            return raw;
        } finally { buf.release(); }
    }

    private ReferenceColumnCodec() {}
}
