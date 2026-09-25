package dev.voxydistant.data;

import dev.voxydistant.generation.ChunkSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

public final class ColumnConverter {
    public static LodColumn convert(int x, int z, long version, List<ChunkSnapshot> snapshots) {
        var states = new ArrayList<BlockState>();
        states.add(Blocks.AIR.defaultBlockState());
        var ids = new HashMap<BlockState, Integer>(); ids.put(states.getFirst(), 0);
        var opacities = new ArrayList<Integer>(); opacities.add(0);
        var biomes = new ArrayList<String>();
        var biomeIds = new HashMap<String, Integer>();
        long[][][] sections = new long[snapshots.size()][5][];
        for (int s = 0; s < snapshots.size(); s++) {
            var snapshot = snapshots.get(s);
            int[] sectionBiomes = new int[64];
            for(int by=0;by<4;by++)for(int bz=0;bz<4;bz++)for(int bx=0;bx<4;bx++){
                String biome=snapshot.biomes().get(bx,by,bz).unwrapKey().orElseThrow().location().toString();
                sectionBiomes[(by*4+bz)*4+bx]=biomeIds.computeIfAbsent(biome,key->{biomes.add(key);return biomes.size()-1;});
            }
            long[] fine = sections[s][0] = new long[4096];
            for (int by = 0; by < 16; by++) for (int bz = 0; bz < 16; bz++) for (int bx = 0; bx < 16; bx++) {
                var state = snapshot.blocks().get(bx, by, bz);
                int id = 0;
                if (!state.isAir()) id = ids.computeIfAbsent(state, key -> {
                    int index = states.size(); states.add(key);
                    opacities.add(key.getBlock() instanceof LeavesBlock ? 15 : key.getLightBlock(new BlockGetter(){
                        public int getHeight(){return 0;}public int getMinBuildHeight(){return 0;}
                        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos){return null;}
                        public BlockState getBlockState(BlockPos pos){return key;}
                        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos){return key.getFluidState();}
                    }, BlockPos.ZERO));
                    return index;
                });
                int bid = sectionBiomes[((by>>2)*4+(bz>>2))*4+(bx>>2)];
                int light = (snapshot.skyLight() == null ? 0 : snapshot.skyLight().get(bx, by, bz))
                        | ((snapshot.blockLight() == null ? 0 : snapshot.blockLight().get(bx, by, bz)) << 4);
                fine[(by * 16 + bz) * 16 + bx] = LodColumn.voxel(id, bid, light, opacities.get(id));
            }
            for (int l = 1; l < 5; l++) sections[s][l] = LodColumn.reduce(sections[s][l - 1]);
        }
        return new LodColumn(x, z, snapshots.getFirst().y(), version, List.copyOf(states), List.copyOf(biomes), sections);
    }
    private ColumnConverter() {}
}
