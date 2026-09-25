package dev.voxydistant.generation;

import java.util.Comparator;
import java.util.PriorityQueue;

/** Merge sorted rows to obtain exact radial order with O(radius) memory. */
public final class NearbyChunks {
    public record Offset(int x, int z) {
        public long distanceSquared() { return (long) x * x + (long) z * z; }
    }
    private final PriorityQueue<Offset> rows = new PriorityQueue<>(Comparator.comparingLong(Offset::distanceSquared)
            .thenComparingInt(Offset::z).thenComparingInt(Offset::x));
    private final long radiusSquared;
    public NearbyChunks(int radius, int view) {
        radiusSquared = (long) radius * radius;
        for (int z = -radius; z <= radius; z++) {
            int x = Math.abs(z) <= view ? view + 1 : 0;
            var candidate = new Offset(x, z);
            if (candidate.distanceSquared() <= radiusSquared) rows.add(candidate);
        }
    }
    public Offset next() {
        var result = rows.poll();
        if (result == null) return null;
        int nextX = result.x() > 0 ? -result.x() : 1 - result.x();
        var next = new Offset(nextX, result.z());
        if (next.distanceSquared() <= radiusSquared) rows.add(next);
        return result;
    }
}
