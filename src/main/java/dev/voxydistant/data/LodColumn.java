package dev.voxydistant.data;

import net.minecraft.world.level.block.state.BlockState;
import java.util.List;

/** Immutable column. Each section has a sparse L0..L4 array; null means unavailable, never air. */
public record LodColumn(int x, int z, int minY, long version, List<BlockState> states,
                        List<String> biomes, long[][][] sections) {
    public int mask() {
        int mask = 0;
        for (int l = 0; l < 5; l++) if (sections[0][l] != null) mask |= 1 << l;
        return mask;
    }
    public int minimumLevel() { return Integer.numberOfTrailingZeros(mask()); }
    public long bytes() {
        long n = 1024L + states.size() * 128L + biomes.size() * 128L;
        for (var section : sections) for (var level : section) if (level != null) n += level.length * 8L;
        return n;
    }
    public static long voxel(int state, int biome, int light, int opacity) {
        return (state & 0xffffffL) | ((long) biome << 24) | ((long) light << 40) | ((long) opacity << 48);
    }
    public static int state(long v) { return (int) (v & 0xffffff); }
    public static int biome(long v) { return (int) ((v >>> 24) & 65535); }
    public static int light(long v) { return (int) ((v >>> 40) & 255); }

    public static long[] reduce(long[] input) {
        int size = switch (input.length) { case 4096 -> 16; case 512 -> 8; case 64 -> 4; case 8 -> 2;
            default -> throw new IllegalArgumentException("Invalid LOD dimensions"); };
        int out = size / 2;
        long[] result = new long[out * out * out];
        for (int y = 0; y < out; y++) for (int z = 0; z < out; z++) for (int x = 0; x < out; x++) {
            long selected = 0;
            int score = -1, sky = 0, block = 0;
            for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
                long v = input[((y * 2 + dy) * size + z * 2 + dz) * size + x * 2 + dx];
                sky += light(v) & 15; block += light(v) >>> 4;
                // Match the pinned mipper's I100/I010/I001 tie order: X, Y, Z.
                int candidate = (int) ((v >>> 48) & 15) * 16 + dx * 4 + dy * 2 + dz;
                if (state(v) != 0 && candidate > score) { selected = v; score = candidate; }
            }
            if (score < 0) selected = voxel(0, 0, ((block / 8) << 4) | ((sky + 7) / 8), 0);
            result[(y * out + z) * out + x] = selected;
        }
        return result;
    }
}
