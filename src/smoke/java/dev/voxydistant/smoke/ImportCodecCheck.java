package dev.voxydistant.smoke;

import dev.voxydistant.data.*;
import dev.voxydistant.generation.ChunkSnapshot;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import java.nio.file.*;
import java.util.*;

/** Real saved columns: original algorithm as an oracle and single-change ablations. */
public final class ImportCodecCheck {
    public ImportCodecCheck(){MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e)->{
        Thread thread=new Thread(()->{
            try{
                var level=e.getServer().overworld();
                var method=Class.forName("dev.voxydistant.server.RegionNbtImporter").getDeclaredMethod("read",net.minecraft.core.RegistryAccess.class,int.class,int.class,int.class,int.class,CompoundTag.class);method.setAccessible(true);
                var inputs=new ArrayList<List<ChunkSnapshot>>();
                Path regionDir=Path.of(System.getProperty("voxyDistant.importCodecCheck"));
                for(int[] p:new int[][]{{32,32},{-32,-32},{96,80},{-96,80},{180,-80},{-180,80},{0,240},{240,0},{-240,0},{0,-240},{128,128},{-128,-128}}){
                    var pos=new ChunkPos(p[0],p[1]);var file=regionDir.resolve("r."+Math.floorDiv(p[0],32)+"."+Math.floorDiv(p[1],32)+".mca");
                    try(var region=new RegionFile(file,regionDir,false);var in=region.getChunkDataInputStream(pos)){
                        @SuppressWarnings("unchecked") var parsed=(Optional<List<ChunkSnapshot>>)method.invoke(null,level.registryAccess(),level.getMinSection(),level.getSectionsCount(),p[0],p[1],NbtIo.read(in));
                        inputs.add(parsed.orElseThrow());
                    }
                }
                long[][] times=new long[3][4];
                for(int round=0;round<32;round++)for(var input:inputs){
                    long t=System.nanoTime();var old=ReferenceColumnConverter.convert(0,0,77,input);long a=System.nanoTime()-t;
                    t=System.nanoTime();var current=ColumnConverter.convert(0,0,77,input);long b=System.nanoTime()-t;
                    if(!old.states().equals(current.states())||!old.biomes().equals(current.biomes())||!Arrays.deepEquals(old.sections(),current.sections()))throw new AssertionError("Converter order/data changed");
                    t=System.nanoTime();var before=ReferenceColumnCodec.encodeRaw(old,31);long c=System.nanoTime()-t;
                    t=System.nanoTime();var after=ColumnCodec.encodeRaw(current,31);long d=System.nanoTime()-t;
                    if(!Arrays.equals(before,after))throw new AssertionError("Encoded bytes changed");
                    if(round>=2){var sample=times[(round-2)/10];sample[0]+=a;sample[1]+=b;sample[2]+=c;sample[3]+=d;}
                    if(round==0)for(int mask=1;mask<32;mask++)if(!Arrays.equals(ReferenceColumnCodec.encodeRaw(old,mask),ColumnCodec.encodeRaw(current,mask)))throw new AssertionError("Mask bytes changed: "+mask);
                }
                Files.writeString(LodBenchmark.OUTPUT.resolve("codec-check.json"),"{\"columns\":12,\"measured_rounds_per_sample\":10,\"nanoseconds_old_new_convert_old_new_encode\":"+Arrays.deepToString(times)+"}");
                System.out.println("DISTANT_IMPORT_CODEC_PASS "+Arrays.deepToString(times));
                String audit=System.getProperty("voxyDistant.importCacheAudit");
                if(audit!=null){
                    long count=0,stale=0,circle=0,minVersion=Long.MAX_VALUE,maxVersion=0;var dimensions=new TreeMap<String,Long>();
                    try(var options=new org.rocksdb.Options();var db=org.rocksdb.RocksDB.openReadOnly(options,audit);var iterator=db.newIterator()){
                        for(iterator.seekToFirst();iterator.isValid();iterator.next()){
                            byte[] key=iterator.key();if(key[0]!=1)continue;
                            byte[] raw=ColumnCodec.uncompress(LodDatabase.unpack(iterator.value()));
                            if(raw[24]!=31)throw new AssertionError("Incomplete LOD levels");
                            var column=ColumnCodec.decodeRaw(raw,0);
                            long position=java.nio.ByteBuffer.wrap(key,key.length-8,8).getLong();
                            if(column.x()!=ChunkPos.getX(position)||column.z()!=ChunkPos.getZ(position))throw new AssertionError("LOD coordinate/key mismatch");
                            String dimension=new String(key,1,key.length-10,java.nio.charset.StandardCharsets.UTF_8);
                            byte[] invalid=key.clone();invalid[0]=2;byte[] value=db.get(invalid);
                            if(value!=null&&java.nio.ByteBuffer.wrap(value).getLong()>column.version())stale++;
                            if(dimension.equals("minecraft:overworld")&&(long)column.x()*column.x()+(long)column.z()*column.z()<=256L*256)circle++;
                            minVersion=Math.min(minVersion,column.version());maxVersion=Math.max(maxVersion,column.version());
                            dimensions.merge(dimension,1L,Long::sum);count++;
                        }
                        iterator.status();
                    }
                    if(count!=206709||stale!=0||circle!=205861)throw new AssertionError("LOD count/stale/circle: "+count+"/"+stale+"/"+circle);
                    Files.writeString(LodBenchmark.OUTPUT.resolve("full-cache-audit.json"),"{\"columns\":"+count+",\"stale\":"+stale+",\"radius256_columns\":"+circle+",\"min_version\":"+minVersion+",\"max_version\":"+maxVersion+",\"all_palette_indexes_valid\":true}");
                    System.out.println("DISTANT_IMPORT_FULL_AUDIT_PASS "+count+" "+dimensions);
                }
            }catch(Exception|AssertionError ex){throw new IllegalStateException("Import codec comparison failed",ex);}
            finally{e.getServer().halt(false);}
        },"Import codec verification");thread.start();
    });}
}
