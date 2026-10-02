package dev.voxydistant.smoke;

import dev.voxydistant.data.*;
import dev.voxydistant.generation.ChunkSnapshot;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in benchmark mod; never included in the distributable extension. */
public final class LodBenchmark {
    public static final Path OUTPUT=Path.of(System.getProperty("voxyDistant.benchmarkOutput"));
    public LodBenchmark() {
        if(System.getProperty("voxyDistant.importCodecCheck")!=null){new ImportCodecCheck();return;}
        if(System.getProperty("voxyDistant.verifyCache")!=null){new CorpusVerify();return;}
        if(System.getProperty("voxyDistant.benchmarkRegion")!=null) {
            MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e)->{
                Thread t=new Thread(()->{
                    try { corpus(e.getServer().overworld().registryAccess().registryOrThrow(Registries.BIOME)); }
                    catch(Exception ex){throw new IllegalStateException("LOD corpus failed",ex);}
                    finally {e.getServer().halt(false);}
                },"LOD corpus coordinator");t.start();
            });
        } else {
            new LodMetrics();
            if(FMLEnvironment.dist.isClient()) {
                try {Class.forName("dev.voxydistant.smoke.LodClientBenchmark").getConstructor().newInstance();}
                catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
            }
        }
    }

    private static void corpus(net.minecraft.core.Registry<Biome> registry)throws Exception {
        Path regions=Path.of(System.getProperty("voxyDistant.benchmarkRegion"));
        Files.createDirectories(OUTPUT.resolve("columns"));
        var blocks=PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY,BlockState.CODEC,PalettedContainer.Strategy.SECTION_STATES,Blocks.AIR.defaultBlockState());
        var biomes=PalettedContainer.codecRO(registry.asHolderIdMap(),registry.holderByNameCodec(),PalettedContainer.Strategy.SECTION_BIOMES,registry.getHolderOrThrow(Biomes.PLAINS));
        AtomicInteger completed=new AtomicInteger();
        int radius=Integer.getInteger("voxyDistant.benchmarkRadius",256);
        long revision=System.currentTimeMillis()<<16;
        try(var database=new LodDatabase(OUTPUT.resolve("full-cache"),64L<<20);var pool=Executors.newFixedThreadPool(8)) {
            database.worldId();
            var jobs=new ArrayList<Future<?>>();
            try(var files=Files.list(regions)) {
                for(Path path:files.filter(p->p.toString().endsWith(".mca")).sorted().toList())jobs.add(pool.submit(()->{
                    String[] parts=path.getFileName().toString().split("\\.");
                    int rx=Integer.parseInt(parts[1]),rz=Integer.parseInt(parts[2]);
                    try(var region=new RegionFile(path,regions,false);var csv=new PrintWriter(Files.newBufferedWriter(OUTPUT.resolve("columns/"+rx+"_"+rz+".csv")))) {
                        csv.println("x,z,mask,raw_bytes,payload_bytes,encode_ns,decode_ns,convert_ns,decoded_bytes");
                        for(int z=rz*32;z<rz*32+32;z++)for(int x=rx*32;x<rx*32+32;x++) {
                            if((long)x*x+(long)z*z>(long)radius*radius)continue;
                            var pos=new ChunkPos(x,z);
                            CompoundTag nbt;
                            try(var in=region.getChunkDataInputStream(pos)) {
                                if(in==null)throw new IllegalStateException("Missing pregenerated chunk "+pos);
                                nbt=NbtIo.read(in);
                            }
                            if(!nbt.getString("Status").equals("minecraft:full")||!nbt.getBoolean("isLightOn"))throw new IllegalStateException("Incomplete chunk "+pos);
                            var sections=new HashMap<Integer,CompoundTag>();
                            for(var tag:nbt.getList("sections",10)){var section=(CompoundTag)tag;sections.put((int)section.getByte("Y"),section);}
                            var snapshots=new ArrayList<ChunkSnapshot>();
                            for(int y=-4;y<20;y++) {
                                var section=Objects.requireNonNull(sections.get(y),"Missing section "+pos+"/"+y);
                                var states=blocks.parse(NbtOps.INSTANCE,section.getCompound("block_states")).getOrThrow(false,s->{throw new IllegalStateException(s);});
                                var biome=biomes.parse(NbtOps.INSTANCE,section.getCompound("biomes")).getOrThrow(false,s->{throw new IllegalStateException(s);});
                                snapshots.add(new ChunkSnapshot(x,y,z,states,biome,section.contains("BlockLight",7)?new DataLayer(section.getByteArray("BlockLight")):null,section.contains("SkyLight",7)?new DataLayer(section.getByteArray("SkyLight")):null));
                            }
                            long start=System.nanoTime();var column=ColumnConverter.convert(x,z,revision,snapshots);long convert=System.nanoTime()-start;
                            for(int mask:new int[]{1,2,4,8,16,31}) {
                                start=System.nanoTime();var encoded=ColumnCodec.encode(column,mask,1);long encode=System.nanoTime()-start;
                                start=System.nanoTime();var decoded=ColumnCodec.decode(encoded);long decode=System.nanoTime()-start;
                                if(decoded.mask()!=mask||decoded.x()!=x||decoded.z()!=z)throw new AssertionError("Codec metadata mismatch");
                                // Palette IDs may be compacted; compare semantic state/biome/light values.
                                for(int sy=0;sy<24;sy++)for(int l=0;l<5;l++)if((mask&(1<<l))!=0)for(int i=0;i<decoded.sections()[sy][l].length;i++) {
                                    long a=column.sections()[sy][l][i],b=decoded.sections()[sy][l][i];
                                    if(!column.states().get(LodColumn.state(a)).equals(decoded.states().get(LodColumn.state(b)))||!column.biomes().get(LodColumn.biome(a)).equals(decoded.biomes().get(LodColumn.biome(b)))||(a>>>40)!=(b>>>40))throw new AssertionError("Codec voxel mismatch");
                                }
                                csv.printf(Locale.ROOT,"%d,%d,%d,%d,%d,%d,%d,%d,%d%n",x,z,mask,encoded.rawLength(),encoded.bytes().length,encode,decode,convert,decoded.bytes());
                                if(mask==31)database.put(LodDatabase.key("minecraft:overworld",pos.toLong(),1),LodDatabase.pack(encoded));
                            }
                            int n=completed.incrementAndGet();if(n%4096==0)System.out.println("DISTANT_LOD_CORPUS columns="+n);
                        }
                    }catch(IOException e){throw new UncheckedIOException(e);}
                }));
            }
            for(var job:jobs)job.get();
            database.sync();
        }
        int expected=0;for(int z=-radius;z<=radius;z++)for(int x=-radius;x<=radius;x++)if(x*x+z*z<=radius*radius)expected++;
        if(completed.get()!=expected)throw new AssertionError("Incomplete corpus "+completed+" expected "+expected);
        Files.writeString(OUTPUT.resolve("corpus-complete.txt"),"columns="+completed+"\nrevision="+revision+"\n");
        System.out.println("DISTANT_LOD_CORPUS_PASS columns="+completed);
    }
}
