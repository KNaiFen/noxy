package dev.voxydistant.server;

/** Consecutive tick overload detection, healthy dwell and gradual recovery. */
public final class LoadGovernor {
    public record Settings(boolean enabled, double slowMs, double pauseMs, double resumeMs,
                           double pauseTps, double resumeTps, int slowTicks, int pauseTicks,
                           double healthySeconds, double recoverySeconds) {}
    public enum State { NORMAL, SLOWING, PAUSED, RECOVERING }
    private long sampleTime, healthySince, lastTime;
    private int samples, slowTicks, pauseTicks, lowTpsTicks;
    private State state = State.NORMAL;
    private double tps = 20, tickTps = 20, factor = 1;

    public double tick(long now, double mspt, Settings settings) {
        if (lastTime != 0) {
            long elapsed = now - lastTime;
            tickTps = Math.min(20, 1e9 / elapsed);
            samples++;
            if (now - sampleTime >= 1_000_000_000L) {
                tps = Math.min(20, samples * 1e9 / (now - sampleTime));
                sampleTime = now; samples = 0;
            }
        } else sampleTime = now;
        double seconds = lastTime == 0 ? 0.05 : Math.min(1, (now - lastTime) / 1e9);
        lastTime = now;
        if (!settings.enabled) {
            slowTicks = pauseTicks = lowTpsTicks = 0; healthySince = 0;
            state = State.NORMAL; return factor = 1;
        }
        slowTicks = mspt > settings.slowMs ? Math.min(settings.slowTicks, slowTicks + 1) : 0;
        pauseTicks = mspt > settings.pauseMs ? Math.min(settings.pauseTicks, pauseTicks + 1) : 0;
        // A held one-second TPS sample must not count as 20 independent bad ticks.
        lowTpsTicks = tickTps < settings.pauseTps ? Math.min(settings.pauseTicks, lowTpsTicks + 1) : 0;
        if (pauseTicks >= settings.pauseTicks || lowTpsTicks >= settings.pauseTicks) {
            state = State.PAUSED; healthySince = 0; return factor = 0;
        }
        if (state != State.PAUSED && slowTicks >= settings.slowTicks) {
            state = State.SLOWING; healthySince = 0;
            return factor = Math.min(factor, Math.max(0.05, factor - seconds * 0.5));
        }
        if (state != State.NORMAL) {
            // Sampled TPS during recovery avoids resetting the dwell on timer jitter.
            if (mspt < settings.resumeMs && tps >= settings.resumeTps) {
                if (healthySince == 0) healthySince = now;
                if (now - healthySince >= settings.healthySeconds * 1e9) {
                    state = State.RECOVERING;
                    factor = settings.recoverySeconds == 0 ? 1 : Math.min(1, factor + seconds / settings.recoverySeconds);
                    if (factor == 1) state = State.NORMAL;
                }
            } else healthySince = 0;
        }
        return factor;
    }
    public State state() { return state; }
    public double tps() { return tps; }
    public double tickTps() { return tickTps; }
    public int slowTicks() { return slowTicks; }
    public int pauseTicks() { return pauseTicks; }
    public int lowTpsTicks() { return lowTpsTicks; }
    public double healthySeconds(long now) { return healthySince == 0 ? 0 : (now - healthySince) / 1e9; }
}
