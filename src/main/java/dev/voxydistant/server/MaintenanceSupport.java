package dev.voxydistant.server;

import java.util.Iterator;
import java.util.Optional;
import java.util.NoSuchElementException;

/** Small, deterministic helpers shared by maintenance commands and tests. */
public final class MaintenanceSupport {
    private MaintenanceSupport() {}

    public record Region(int x, int z) {}

    public static Optional<Region> parseRegionFileName(String name) {
        if (name == null || !name.matches("r\\.-?[0-9]+\\.-?[0-9]+\\.mca")) return Optional.empty();
        String[] parts = name.substring(2, name.length() - 4).split("\\.", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return Optional.empty();
        try { return Optional.of(new Region(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]))); }
        catch (NumberFormatException ignored) { return Optional.empty(); }
    }

    public record Coordinate(int dx, int dz) {}

    public static Iterable<Coordinate> circularCoordinates(int radius) {
        validateRadius(radius);
        return () -> new Iterator<>() {
            private final long squared = (long) radius * radius;
            private int dx = -radius;
            private int dz = -radius;
            private Coordinate next = findNext();

            private Coordinate findNext() {
                while (dz <= radius) {
                    int currentDx = dx;
                    int currentDz = dz;
                    if (dx++ >= radius) {
                        dx = -radius;
                        dz++;
                    }
                    if ((long) currentDx * currentDx + (long) currentDz * currentDz <= squared) {
                        return new Coordinate(currentDx, currentDz);
                    }
                }
                return null;
            }

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public Coordinate next() {
                if (next == null) throw new NoSuchElementException();
                Coordinate result = next;
                next = findNext();
                return result;
            }
        };
    }

    public static long circularCoordinateCount(int radius) {
        validateRadius(radius);
        long squared = (long) radius * radius, count = 0;
        for (int dz = -radius; dz <= radius; dz++)
            count += 2 * (long) Math.sqrt(squared - (long) dz * dz) + 1;
        return count;
    }

    private static void validateRadius(int radius) {
        if (radius < 1 || radius > 2048) throw new IllegalArgumentException("半径必须在 1～2048 之间");
    }
}
