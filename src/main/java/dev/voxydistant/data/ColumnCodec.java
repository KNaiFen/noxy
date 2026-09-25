package dev.voxydistant.data;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import it.unimi.dsi.fastutil.ints.Int2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;

public final class ColumnCodec {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    public record Encoded(boolean compressed, int rawLength, byte[] bytes) {}

    public static Encoded encode(LodColumn column, int mask) {
        return encode(column, mask, 1);
    }

    public static Encoded encode(LodColumn column, int mask, int compressionLevel) {
        return compress(encodeRaw(column,mask),compressionLevel);
    }

    public static Encoded compress(byte[] raw,int compressionLevel) {
        byte[] compressed = Zstd.compress(raw, compressionLevel);
        return compressed.length + 16 < raw.length ? new Encoded(true, raw.length, compressed) : new Encoded(false, raw.length, raw);
    }

    public static byte[] encodeRaw(LodColumn column,int mask) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeInt(1); buf.writeInt(column.x()); buf.writeInt(column.z()); buf.writeInt(column.minY());
            buf.writeLong(column.version()); buf.writeByte(mask);
            // Palette contains only values used by transmitted levels.
            var states = new Int2IntLinkedOpenHashMap(); states.put(0, 0);
            var biomes = new Int2IntLinkedOpenHashMap();
            for (var section : column.sections()) for (int l = 0; l < 5; l++) if ((mask & (1 << l)) != 0)
                for (long v : section[l]) {
                    states.putIfAbsent(LodColumn.state(v), states.size());
                    biomes.putIfAbsent(LodColumn.biome(v), biomes.size());
                }
            buf.writeVarInt(states.size());
            for (int id : states.keySet()) buf.writeNbt(NbtUtils.writeBlockState(column.states().get(id)));
            buf.writeVarInt(biomes.size());
            for (int id : biomes.keySet()) buf.writeUtf(column.biomes().get(id), 256);
            buf.writeVarInt(column.sections().length);
            for (var section : column.sections()) for (int l = 0; l < 5; l++) if ((mask & (1 << l)) != 0) {
                long[] values = section[l];
                var palette = new Long2IntLinkedOpenHashMap();
                for (long v : values) palette.putIfAbsent(v, palette.size());
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

    public static LodColumn decode(Encoded encoded) {
        return decodeLevels(encoded,31);
    }

    /** Read and validate every stored level, but only expand requested levels. */
    public static LodColumn decodeLevels(Encoded encoded,int wantedMask) {
        return decodeRaw(uncompress(encoded),wantedMask);
    }

    public static byte[] uncompress(Encoded encoded) {
        if (encoded.rawLength < 1 || encoded.rawLength > MAX_BYTES || encoded.bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("LOD payload exceeds memory bound");
        byte[] raw = encoded.compressed ? Zstd.decompress(encoded.bytes, encoded.rawLength) : encoded.bytes;
        if (raw.length != encoded.rawLength) throw new IllegalArgumentException("LOD length mismatch");
        return raw;
    }

    public static LodColumn decodeRaw(byte[] raw,int wantedMask) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
        try {
            if (buf.readInt() != 1) throw new IllegalArgumentException("Unknown LOD schema");
            int x = buf.readInt(), z = buf.readInt(), minY = buf.readInt(); long revision = buf.readLong();
            int mask = buf.readUnsignedByte();
            if (mask == 0 || mask > 31) throw new IllegalArgumentException("Invalid LOD mask");
            int count = bounded(buf.readVarInt(), 1, 65536);
            var states = new ArrayList<BlockState>(count);
            for (int i = 0; i < count; i++) states.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), Objects.requireNonNull(buf.readNbt())));
            if (!states.getFirst().isAir()) throw new IllegalArgumentException("Palette zero must be air");
            count = bounded(buf.readVarInt(), 1, 65536);
            var biomes = new ArrayList<String>(count);
            for (int i = 0; i < count; i++) biomes.add(buf.readUtf(256));
            count = bounded(buf.readVarInt(), 1, 256);
            if (minY < -256 || minY + count > 256) throw new IllegalArgumentException("Invalid height");
            long[][][] sections = new long[count][5][];
            for (var section : sections) for (int l = 0; l < 5; l++) if ((mask & (1 << l)) != 0) {
                int length = 4096 >> (l * 3);
                long[] palette = new long[bounded(buf.readVarInt(), 1, length)];
                for (int i = 0; i < palette.length; i++) {
                    int state = bounded(buf.readVarInt(), 0, states.size() - 1), biome = bounded(buf.readVarInt(), 0, biomes.size() - 1);
                    palette[i] = LodColumn.voxel(state, biome, buf.readUnsignedByte(), bounded(buf.readUnsignedByte(), 0, 15));
                }
                if((wantedMask&(1<<l))!=0){
                    long[] values = section[l] = new long[length];
                    if (palette.length == 1) Arrays.fill(values, palette[0]);
                    else for (int i = 0; i < length; i++) values[i] = palette[bounded(buf.readVarInt(), 0, palette.length - 1)];
                }else if(palette.length>1)for(int i=0;i<length;i++)bounded(buf.readVarInt(),0,palette.length-1);
            }
            if (buf.isReadable()) throw new IllegalArgumentException("Trailing LOD bytes");
            return new LodColumn(x, z, minY, revision, List.copyOf(states), List.copyOf(biomes), sections);
        } finally { buf.release(); }
    }
    public static int bounded(int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException("LOD value outside bounds: " + value);
        return value;
    }
    private ColumnCodec() {}
}
