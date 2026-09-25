package dev.voxydistant.generation;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/** Owned copies; no Level, Chunk, or mutable light-engine references cross threads. */
public record ChunkSnapshot(int x, int y, int z, PalettedContainer<BlockState> blocks,
                            PalettedContainerRO<Holder<Biome>> biomes, DataLayer blockLight, DataLayer skyLight) {
    // Includes worst-case copied palettes, backing arrays, light arrays and object overhead.
    public static final long RESERVED_BYTES = 128 * 1024;
}
