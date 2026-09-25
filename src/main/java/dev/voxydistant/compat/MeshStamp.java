package dev.voxydistant.compat;

/** Mesh carries the coverage generation observed at build start. */
public interface MeshStamp {
    CoverageStore distant$coverage();
    void distant$stamp(CoverageStore coverage,long generation);
    boolean distant$current();
    long distant$generation();
    long distant$latestGeneration();
}
