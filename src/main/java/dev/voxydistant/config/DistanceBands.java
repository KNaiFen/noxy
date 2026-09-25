package dev.voxydistant.config;

import java.util.List;

/** Distance is horizontal, in vanilla chunks. A split always selects the whole sibling group. */
public record DistanceBands(int[] radii, int[] levels) {
    public static DistanceBands parse(List<? extends String> values) {
        if (values.isEmpty() || values.size()>32) throw new IllegalArgumentException("距离档位数量须为 1～32");
        int[] radii = new int[values.size()], levels = new int[values.size()];
        int previous = 0;
        for (int i = 0; i < values.size(); i++) {
            String[] parts = values.get(i).split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException("距离格式为 区块半径:层级");
            radii[i] = Integer.parseInt(parts[0].trim());
            levels[i] = Integer.parseInt(parts[1].trim());
            if (radii[i] <= previous || radii[i] > 2048 || levels[i] < 0 || levels[i] > 4)
                throw new IllegalArgumentException("距离须递增且不超过 2048，层级须为 0～4");
            if (i > 0 && levels[i] < levels[i - 1]) throw new IllegalArgumentException("远处精度不能高于近处");
            previous = radii[i];
        }
        return new DistanceBands(radii, levels);
    }

    public int select(int x, int z, int playerX, int playerZ) {
        int result = levels[levels.length - 1];
        for (int i = 0; i < radii.length; i++) {
            // Parent of the requested level covers 2^(level+2) vanilla chunks.
            int size = 1 << (levels[i] + 2);
            int bx = Math.floorDiv(x, size) * size, bz = Math.floorDiv(z, size) * size;
            long dx = Math.max(Math.max(bx - playerX, playerX - (bx + size - 1)), 0);
            long dz = Math.max(Math.max(bz - playerZ, playerZ - (bz + size - 1)), 0);
            if (dx * dx + dz * dz <= (long) radii[i] * radii[i]) return levels[i];
        }
        return result;
    }
}
