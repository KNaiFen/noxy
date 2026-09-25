package dev.voxydistant.network;

/** Burst capped at 100 ms or one fragment; monotonic time, byte accounting. */
public final class ByteBudget {
    private long last;
    private double credit;
    public void update(long now, double bytesPerSecond) {
        double cap = Math.max(32768 + 256, bytesPerSecond * 0.1);
        if (last == 0) { last = now; credit = Math.min(cap, bytesPerSecond / 20); }
        credit = Math.min(cap, credit + Math.max(0, now - last) / 1e9 * bytesPerSecond); last = now;
    }
    public int available() { return (int) Math.min(Integer.MAX_VALUE, credit); }
    public void spend(int bytes) {
        if (bytes < 0 || bytes > available()) throw new IllegalArgumentException("Bandwidth exceeded");
        credit -= bytes;
    }
}
