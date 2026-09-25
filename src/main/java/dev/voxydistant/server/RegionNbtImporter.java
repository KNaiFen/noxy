package dev.voxydistant.server;

import dev.voxydistant.generation.ChunkSnapshot;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagTypes;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads saved chunk NBT without loading a chunk into a live ServerLevel. */
final class RegionNbtImporter {
    private static final int LIGHT_BYTES = 2048;

    private final Registry<Biome> biomes;
    private final Holder<Biome> defaultBiome;
    private final DynamicOps<Tag> biomeOps;
    private final Codec<PalettedContainer<BlockState>> blockCodec;
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
    RegionNbtImporter(RegistryAccess registries) {
        biomes=registries.registryOrThrow(Registries.BIOME);
        defaultBiome=biomes.getHolderOrThrow(Biomes.PLAINS);
        biomeOps=RegistryOps.create(NbtOps.INSTANCE,registries);
        blockCodec=PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY,BlockState.CODEC,PalettedContainer.Strategy.SECTION_STATES,Blocks.AIR.defaultBlockState());
        biomeCodec=PalettedContainer.codecRO(biomes.asHolderIdMap(),biomes.holderByNameCodec(),PalettedContainer.Strategy.SECTION_BIOMES,defaultBiome);
    }

    /** Retain only LOD input; scheduled ticks, block entities and mod attachments need no allocation. */
    static CompoundTag readNbt(DataInput input, long budget) throws IOException {
        if (input.readUnsignedByte() != Tag.TAG_COMPOUND) throw new IOException("Chunk NBT root must be a compound");
        var accounter = new NbtAccounter(budget);
        accounter.accountBytes(53); // Root type, object and compound allocation, as in NbtIo/CompoundTag.
        accounter.readUTF(input.readUTF());
        var chunk = new CompoundTag();
        int type;
        while ((type = input.readUnsignedByte()) != Tag.TAG_END) {
            String name = input.readUTF();
            if (name.equals("Status") || name.equals("xPos") || name.equals("zPos") || name.equals(ChunkSerializer.SECTIONS_TAG)) {
                accounter.accountBytes(1);
                accounter.readUTF(name);
                accounter.accountBytes(68L + 2L * name.length());
                chunk.put(name, TagTypes.getType(type).load(input, 1, accounter));
            } else {
                TagTypes.getType(type).skip(input);
            }
        }
        accounter.accountBytes(1);
        return chunk;
    }

    static Optional<List<ChunkSnapshot>> read(RegistryAccess registries, int minSection, int sectionCount,
                                             int expectedX, int expectedZ, CompoundTag chunk) {
        return new RegionNbtImporter(registries).read(minSection,sectionCount,expectedX,expectedZ,chunk);
    }
    Optional<List<ChunkSnapshot>> read(int minSection, int sectionCount, int expectedX, int expectedZ, CompoundTag chunk) {
        if (!isFullChunk(expectedX, expectedZ, chunk)
                || !chunk.contains(ChunkSerializer.SECTIONS_TAG, Tag.TAG_LIST)) {
            return Optional.empty();
        }

        ListTag sections = (ListTag)chunk.get(ChunkSerializer.SECTIONS_TAG);
        if (sections.getElementType()!=Tag.TAG_COMPOUND && !sections.isEmpty())return Optional.empty();


        Map<Integer, CompoundTag> byY = new HashMap<>();
        for (Tag value : sections) {
            if (!(value instanceof CompoundTag section) || !section.contains("Y", Tag.TAG_BYTE)) {
                return Optional.empty();
            }
            int y = section.getByte("Y");
            int maxSection = minSection + sectionCount;
            if (y < minSection || y >= maxSection) {
                if ((y != minSection-1 && y != maxSection) || section.contains("block_states") || section.contains("biomes")) return Optional.empty();
                continue;
            }
            if (byY.put(y, section) != null) {
                return Optional.empty();
            }
        }

        List<ChunkSnapshot> snapshots = new ArrayList<>(sectionCount);
        for (int y = minSection; y < minSection + sectionCount; y++) {
            CompoundTag section = byY.get(y);
            PalettedContainer<BlockState> blocks = emptyBlocks();
            PalettedContainerRO<Holder<Biome>> sectionBiomes = emptyBiomes(biomes, defaultBiome);
            DataLayer blockLight = null;
            DataLayer skyLight = null;
            if (section != null) {
                if (section.contains("block_states") && !section.contains("block_states", Tag.TAG_COMPOUND)) return Optional.empty();
                if (section.contains("biomes") && !section.contains("biomes", Tag.TAG_COMPOUND)) return Optional.empty();
                if (section.contains("block_states", Tag.TAG_COMPOUND)) {
                    blocks = blockCodec.parse(NbtOps.INSTANCE, section.get("block_states")).result().orElse(null);
                    if (blocks == null) return Optional.empty();
                }
                if (section.contains("biomes", Tag.TAG_COMPOUND)) {
                    sectionBiomes = biomeCodec.parse(biomeOps, section.get("biomes")).result().orElse(null);
                    if (sectionBiomes == null) return Optional.empty();
                }
                blockLight = light(section, ChunkSerializer.BLOCK_LIGHT_TAG);
                if (blockLight == INVALID_LIGHT) return Optional.empty();
                skyLight = light(section, ChunkSerializer.SKY_LIGHT_TAG);
                if (skyLight == INVALID_LIGHT) return Optional.empty();
            }
            snapshots.add(new ChunkSnapshot(expectedX, y, expectedZ, blocks, sectionBiomes, blockLight, skyLight));
        }
        return Optional.of(List.copyOf(snapshots));
    }

    static boolean isFullChunk(int expectedX, int expectedZ, CompoundTag chunk) {
        return chunk.contains("Status", Tag.TAG_STRING)
                && ("full".equalsIgnoreCase(chunk.getString("Status"))
                || "minecraft:full".equalsIgnoreCase(chunk.getString("Status")))
                && chunk.contains("xPos", Tag.TAG_INT)
                && chunk.contains("zPos", Tag.TAG_INT)
                && chunk.getInt("xPos") == expectedX
                && chunk.getInt("zPos") == expectedZ;
    }

    private static final DataLayer INVALID_LIGHT = new DataLayer(new byte[LIGHT_BYTES]);

    private static DataLayer light(CompoundTag section, String key) {
        if (!section.contains(key)) return null;
        if (!section.contains(key, Tag.TAG_BYTE_ARRAY)) return INVALID_LIGHT;
        byte[] bytes = section.getByteArray(key);
        if (bytes.length == 0) return null;
        if (bytes.length != LIGHT_BYTES) return INVALID_LIGHT;
        return new DataLayer(bytes.clone());
    }

    private static PalettedContainer<BlockState> emptyBlocks() {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
    }

    private static PalettedContainerRO<Holder<Biome>> emptyBiomes(
            Registry<Biome> biomes, Holder<Biome> defaultBiome) {
        return new PalettedContainer<>(biomes.asHolderIdMap(), defaultBiome,
                PalettedContainer.Strategy.SECTION_BIOMES);
    }
}
