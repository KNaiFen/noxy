package dev.voxydistant.smoke;

import dev.voxydistant.compat.*;
import dev.voxydistant.data.*;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.*;
import me.cortex.voxy.common.world.other.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/** Real transformed Voxy ingest and mipper, with memory storage and no OpenGL context. */
public final class ClientSmoke {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void run() throws Exception {
        var storage=new SectionStorage(){
            public int loadSection(WorldSection section){return 1;}
            public void saveSection(WorldSection section){}
            public void putIdMapping(int id,ByteBuffer bytes){}
            public it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<byte[]> getIdMappingsData(){return new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();}
            public void flush(){}public void close(){}public void iteratePositions(int level,java.util.function.LongConsumer callback){}
        };
        var engine=new WorldEngine(storage);var index=new CoverageStore(Files.createTempDirectory("distant-client-smoke").resolve("coverage"));index.remote(0,32,64);((EngineAccess)engine).distant$setCoverage(index);
        engine.setSaveCallback((world,section,nonBlocking,acquired)->{section.setNotDirty();index.saved(section.key,index.meshGeneration(section.key));return false;});
        var registry=new MappedRegistry<Biome>(Registries.BIOME,com.mojang.serialization.Lifecycle.stable());
        registry.register(Biomes.PLAINS,new Biome.BiomeBuilder().hasPrecipitation(true).temperature(.8f).downfall(.4f).specialEffects(new BiomeSpecialEffects.Builder().fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build()).mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build(),com.mojang.serialization.Lifecycle.stable());registry.freeze();
        var registries=new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
        var published=new ArrayList<Long>();
        engine.setDirtyCallback((section,flags,neighbors)->{
            check((index.meshGeneration(section.key)&1)==0,"publish only completed voxel/coverage generations");published.add(section.key);
        });
        for(int level=4;level>=0;level--){CoarseLodReceiver.receive(engine,column(10,level,1),registries);check(index.column(-1,-1,0,1).level()==level,"progressive refinement "+level);}
        check(!published.isEmpty(),"completed generation publishes dirty nodes");
        published.clear();
        synchronized(index){
            index.meshBegin(-1,-1,0,1);index.meshBegin(-1,-1,0,1);
            var section=engine.acquire(0,-1,0,-1);
            try{engine.markDirty(section,WorldEngine.DEFAULT_UPDATE_FLAGS,63);}finally{section.release();}
            index.meshEnd(engine,-1,-1,0,1);check(published.isEmpty(),"nested update remains unpublished");
            index.meshEnd(engine,-1,-1,0,1);check(published.size()==1,"outer update publishes once");
        }
        long parent=CoverageStore.node(4,-1,0,-1);
        var parentSection=engine.acquire(parent);
        try{
            check(VoxyBridge.renderChildren(parentSection)!=0,"fine data permits parent subdivision");
            index.restrict(-1,-1,0,1,11);
            check(VoxyBridge.renderChildren(parentSection)!=0,"dirty column must not collapse a 512-block parent to L4");
            check(index.column(-1,-1,0,1).equals(new CoverageStore.Stamp(11,5)),"dirty geometry is visible but cannot be a cache hit");
        }finally{parentSection.release();}
        CoarseLodReceiver.receive(engine,column(9,3,0),registries);check(index.column(-1,-1,0,1).equals(new CoverageStore.Stamp(11,5)),"late packet cannot satisfy pending refresh");
        CoarseLodReceiver.receive(engine,column(11,2,0),registries);check(index.column(-1,-1,0,1).equals(new CoverageStore.Stamp(11,2)),"new coarse replaces stale fine");
        var coarse=engine.acquire(2,-1,0,-1);try{for(long value:coarse._unsafeGetRawDataArray())check(Mapper.isAir(value),"explicit new air clears parent");}finally{coarse.release();}
        var fine=engine.acquire(0,-1,0,-1);try{check(Arrays.stream(fine._unsafeGetRawDataArray()).anyMatch(v->!Mapper.isAir(v)),"stale L0 remains stored");}finally{fine.release();}
        compareMipper(engine.getMapper());checkNodeSwitch();checkCachedMesh(engine);checkIndexRebuild();index.closeUnconfirmed();
        System.out.println("DISTANT_CLIENT_INGEST_PASS: actual Voxy sparse L4/L3/L2/L1/L0, stale rejection, coarse replacement, retained L0, 10000 mipper comparisons");
    }
    private static void checkNodeSwitch()throws Exception{
        var geometry=new me.cortex.voxy.client.core.rendering.section.geometry.IGeometryManager(){int id;public int uploadSection(me.cortex.voxy.client.core.rendering.building.BuiltSection s){s.free();return id++;}public int uploadReplaceSection(int old,me.cortex.voxy.client.core.rendering.building.BuiltSection s){s.free();return old;}public void removeSection(int id){}public void downloadAndRemove(int id,java.util.function.Consumer<me.cortex.voxy.client.core.rendering.building.BuiltSection> c){}};
        var watching=new HashMap<Long,Integer>();
        var watcher=new me.cortex.voxy.client.core.rendering.ISectionWatcher(){public boolean watch(long p,int types){int old=get(p);watching.put(p,old|types);return (types&~old)!=0;}public boolean unwatch(long p,int types){int value=get(p)&~types;if(value==0)watching.remove(p);else watching.put(p,value);return value==0;}public int get(long p){return watching.getOrDefault(p,0);}};
        var nodes=new me.cortex.voxy.client.core.rendering.hierachical.NodeManager(1024,geometry,watcher);nodes.setTLNCallbacks(i->{},i->{});
        long parent=CoverageStore.node(2,0,0,0);nodes.insertTopLevelNode(parent);nodes.processGeometryResult(mesh(parent,255));nodes.processRequest(parent);
        for(int i=0;i<7;i++)nodes.processGeometryResult(mesh(CoverageStore.node(1,i&1,(i>>1)&1,(i>>2)&1),0));
        var field=nodes.getClass().getDeclaredField("activeSectionMap");field.setAccessible(true);var active=(it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap)field.get(nodes);
        check((active.get(parent)&0xc0000000)==0,"parent stays until all eight children are ready");
        nodes.processGeometryResult(mesh(CoverageStore.node(1,1,1,1),0));check((active.get(parent)&0xc0000000)==0x40000000,"group splits when final child arrives");
        nodes.processChildChange(parent,(byte)0);check((active.get(parent)&0xc0000000)==0x40000000,"collapse retains children before replacement");
        nodes.processGeometryResult(mesh(parent,0));check((active.get(parent)&0xc0000000)==0,"collapse completes after parent mesh upload");
        var emptyCoverage=new CoverageStore(Files.createTempDirectory("distant-empty-race").resolve("coverage"));emptyCoverage.remote(0,32,64);
        emptyCoverage.meshBegin(0,0,0,1);emptyCoverage.meshEnd(0,0,0,1);
        var oldEmpty=me.cortex.voxy.client.core.rendering.building.BuiltSection.empty(parent);
        ((MeshStamp)(Object)oldEmpty).distant$stamp(emptyCoverage,emptyCoverage.meshGeneration(parent));
        emptyCoverage.meshBegin(0,0,0,1);emptyCoverage.meshEnd(0,0,0,1);
        int beforeEmpty=active.get(parent)&0xffffff;
        check(storeGeometry(nodes,beforeEmpty)!=me.cortex.voxy.client.core.rendering.hierachical.NodeManager.EMPTY_GEOMETRY_ID,"parent has geometry before old empty result");
        nodes.processGeometryResult(oldEmpty);
        check(storeGeometry(nodes,active.get(parent)&0xffffff)!=me.cortex.voxy.client.core.rendering.hierachical.NodeManager.EMPTY_GEOMETRY_ID,"old unmarked empty result cannot erase newer parent geometry");
        // A zero child event can precede the final mesh; the latter must repair the mask while stationary.
        nodes.processChildChange(parent,(byte)0);nodes.processGeometryResult(mesh(parent,255));
        var storeField=nodes.getClass().getDeclaredField("nodeData");storeField.setAccessible(true);
        var store=(me.cortex.voxy.client.core.rendering.hierachical.NodeStore)storeField.get(nodes);
        check((store.getNodeChildExistence(active.get(parent)&0xffffff)&255)==255,"current mesh restores subdivision after temporary zero mask");
        var accepted=mesh(parent,37);((MeshStamp)(Object)accepted).distant$stamp(emptyCoverage,emptyCoverage.meshGeneration(parent));
        watcher.unwatch(parent,WorldEngine.UPDATE_TYPE_BLOCK_BIT);
        nodes.processGeometryResult(accepted);
        check(emptyCoverage.cachedMeshChildren(parent)==-1,"discarded current mesh cannot publish a child mask");
        watcher.watch(parent,WorldEngine.UPDATE_TYPE_BLOCK_BIT);
        nodes.processRequest(parent);
        var coverage=new CoverageStore(Files.createTempDirectory("distant-stale-mesh").resolve("coverage"));coverage.remote(0,32,64);
        long child=CoverageStore.node(1,0,0,0);var stale=mesh(child,0);
        ((MeshStamp)(Object)stale).distant$stamp(coverage,coverage.meshGeneration(child));
        synchronized(coverage){coverage.meshBegin(0,0,0,1);coverage.meshEnd(0,0,0,1);}
        nodes.processGeometryResult(stale);
        check((active.get(child)&0x80000000)!=0,"stale child does not satisfy split request");
        check((watcher.get(child)&WorldEngine.UPDATE_TYPE_BLOCK_BIT)!=0,"stale result retains geometry subscription");
        nodes.processChildChange(child,(byte)0);nodes.processGeometryResult(mesh(child,255));
        for(int i=1;i<8;i++)nodes.processGeometryResult(mesh(CoverageStore.node(1,i&1,(i>>1)&1,(i>>2)&1),0));
        check((active.get(parent)&0xc0000000)==0x40000000,"replacement meshes finish split without movement");
        check((store.getNodeChildExistence(active.get(child)&0xffffff)&255)==255,"child request keeps final mesh mask while siblings are pending");
        nodes.processChildChange(parent,(byte)0);
        nodes.processGeometryResult(me.cortex.voxy.client.core.rendering.building.BuiltSection.empty(parent));
        check((active.get(parent)&0xc0000000)==0&&store.getNodeGeometry(active.get(parent)&0xffffff)==me.cortex.voxy.client.core.rendering.hierachical.NodeManager.EMPTY_GEOMETRY_ID,"explicit air replaces children with valid empty geometry");
        check((store.getNodeChildExistence(active.get(parent)&0xffffff)&255)==0,"explicit air clears final child mask");
        nodes.removeTopLevelNode(parent);coverage.closeUnconfirmed();emptyCoverage.closeUnconfirmed();
        System.out.println("DISTANT_NODE_SWITCH_PASS: real Voxy split and collapse both wait for replacement meshes");
    }
    private static me.cortex.voxy.client.core.rendering.building.BuiltSection mesh(long pos,int children){return new me.cortex.voxy.client.core.rendering.building.BuiltSection(pos,(byte)children,0,new me.cortex.voxy.common.util.MemoryBuffer(8),new int[8],null);}
    private static int storeGeometry(me.cortex.voxy.client.core.rendering.hierachical.NodeManager nodes,int id)throws Exception{var field=nodes.getClass().getDeclaredField("nodeData");field.setAccessible(true);return ((me.cortex.voxy.client.core.rendering.hierachical.NodeStore)field.get(nodes)).getNodeGeometry(id);}
    private static void checkCachedMesh(WorldEngine engine)throws Exception{
        var original=VoxyBridge.coverage(engine);
        var path=Files.createTempDirectory("distant-cached-mesh").resolve("coverage");
        var written=new CoverageStore(path);written.remote(0,32,64);
        written.received(64,0,-64,123,0,false);written.received(68,0,-64,123,2,false);
        written.saveAfterWorldClosed();
        var reopened=new CoverageStore(path);reopened.remote(0,32,64);((EngineAccess)engine).distant$setCoverage(reopened);
        var fine=engine.acquire(1,16,0,-16);var coarse=engine.acquire(1,17,0,-16);
        fine._unsafeSetNonEmptyChildren((byte)255);coarse._unsafeSetNonEmptyChildren((byte)255);
        // Exercise the real transformed factory hook, before any column lookup or new packet.
        var factory=new me.cortex.voxy.client.core.rendering.building.RenderDataFactory(engine,null,false);
        var start=Arrays.stream(factory.getClass().getDeclaredMethods()).filter(m->m.getName().contains("distant$start")).findFirst().orElseThrow();start.setAccessible(true);
        try{
            check(!reopened.canSplit(fine.key),"cold coverage has not yet been read");
            start.invoke(factory,fine,new org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<me.cortex.voxy.client.core.rendering.building.BuiltSection>("generateMesh",false));
            check(VoxyBridge.renderChildrenForMesh(fine)==(byte)255,"reopened cached mesh restores children before its first build, without a packet or movement");
            check(VoxyBridge.renderChildren(coarse)==0,"persisted coarse coverage still blocks unsafe subdivision");
            check(reopened.column(64,-64,0,1).equals(new CoverageStore.Stamp(123,0)),"cache version and fine precision survive reopen");
            reopened.received(72,0,-64,124,13,false);
            long parentKey=CoverageStore.node(4,2,0,-2);var parent=engine.acquire(parentKey);
            try{
                parent._unsafeSetNonEmptyChildren((byte)255);
                reopened.prepareMesh(parentKey);
                check(!reopened.canSplit(parentKey)&&reopened.blockedOnlyByStale(parentKey),"reopened stale neighbor blocks the coarse parent split="+reopened.canSplit(parentKey)+" stale="+reopened.blockedOnlyByStale(parentKey)+" blockers="+reopened.missingBlockers(parentKey));
                var children=new ArrayList<WorldSection>();
                try{
                    for(int bit=0;bit<7;bit++)children.add(engine.acquire(3,4+(bit&1),(bit>>2)&1,-4+((bit>>1)&1)));
                    check(VoxyBridge.renderChildrenForMesh(parent)==0,"missing cached child retains the parent");
                    children.add(engine.acquire(3,5,1,-3));
                    check((VoxyBridge.renderChildrenForMesh(parent)&255)==255,"all cached children restore subdivision despite stale neighbor");
                    reopened.acceptedMeshChildren(parentKey,reopened.meshGeneration(parentKey),(byte)255);
                    check((VoxyBridge.renderChildren(parent)&255)==255,"child update uses accepted mesh mask");
                    synchronized(reopened){reopened.meshBegin(64,-64,0,1);reopened.meshEnd(64,-64,0,1);}
                    check(reopened.cachedMeshChildren(parentKey)==-1,"new generation invalidates accepted mask");
                }finally{for(var child:children)child.release();}
            }finally{parent.release();}
            synchronized(reopened){reopened.meshBegin(64,-64,0,1);reopened.meshEnd(64,-64,0,1);}
            check(reopened.meshGeneration(fine.key)>0,"later voxel writes still advance mesh generations");
        }finally{factory.free();fine.release();coarse.release();reopened.closeUnconfirmed();((EngineAccess)engine).distant$setCoverage(original);}
        System.out.println("DISTANT_CACHED_MESH_PASS: reopened disk coverage restored by transformed mesh hook before first mesh, with no network or movement");
    }
    private static void checkIndexRebuild()throws Exception{
        var backend=new me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend(Files.createTempDirectory("distant-rebuild-voxy").toString());
        var storage=new me.cortex.voxy.common.config.section.SectionSerializationStorage(backend);
        var world=new WorldEngine(storage);int stone=world.getMapper().getIdForBlockState(Blocks.STONE.defaultBlockState());
        // Real serialized Voxy LOD, initially with no VoxyDistant coverage at all.
        for(int level=0;level<=4;level++){
            var section=WorldSection._createRawUntrackedUnsafeSection(level,0,0,0);section.acquire();
            try{Arrays.fill(section._unsafeGetRawDataArray(),Mapper.AIR);section._unsafeGetRawDataArray()[0]=Mapper.composeMappingId((byte)0,stone,0);section._unsafeSetNonEmptyChildren((byte)1);if(level==0)section.addNonEmptyBlockCount(1);storage.saveSection(section);}finally{section.release();}
        }
        var empty=WorldSection._createRawUntrackedUnsafeSection(0,-17,-1,-17);empty.acquire();try{Arrays.fill(empty._unsafeGetRawDataArray(),Mapper.AIR);storage.saveSection(empty);}finally{empty.release();}storage.flush();
        var path=Files.createTempDirectory("distant-rebuild-index").resolve("coverage.bin");var index=new CoverageStore(path);index.remote(-4,32,64);((EngineAccess)world).distant$setCoverage(index);
        var database=CoverageStore.openDatabase(path.resolveSibling("coverage.bin.rocksdb")).join();
        // Reproduce the false-ready marker from the deleted startup check.
        database.put("coverage-directory-progress-v2".getBytes(java.nio.charset.StandardCharsets.UTF_8),ByteBuffer.allocate(9).put((byte)1).putLong(0).array());
        CoverageStore.closeDatabase(path.resolveSibling("coverage.bin.rocksdb"));
        check(index.column(0,0,0,1).level()==5,"before rebuild real Voxy data has no coverage");
        var result=index.rebuildIndex(world,()->false,p->{});
        check(result.sections()==6&&result.pages()==2,"rebuild enumerates actual Voxy storage even with old ready marker");
        check(index.meshGeneration(CoverageStore.node(1,0,0,0))>0,"rebuild rejects a mesh built before recovered coarse restrictions");
        check(!index.canSplit(CoverageStore.node(1,0,0,0)),"coarse-only neighbors keep parent subdivision blocked");
        check(index.column(0,0,0,1).equals(new CoverageStore.Stamp(0,5)),"recovered geometry does not invent a confirmed server version");
        check(!index.hasFull(0,0,0),"geometry recovery alone cannot certify a complete section");
        index.saveAfterWorldClosed();
        try(var options=new org.rocksdb.Options();var raw=org.rocksdb.RocksDB.open(options,path.resolveSibling("coverage.bin.rocksdb").toString())){
            byte[] page=raw.get(ByteBuffer.allocate(9).put((byte)1).putLong(CoverageStore.node(4,0,0,0)).array());
            check(page[1]==16,"L0 geometry precision recovered with pending validation");
            check(page[1+8*9]==20,"coarse L4 geometry precision recovered with pending validation");
            check(page[1+16*9]==5,"unobserved AIR octant remains unknown");
        }
        index=new CoverageStore(path);index.remote(-4,32,64);((EngineAccess)world).distant$setCoverage(index);
        for(int y=0;y<2;y++)index.received(0,y,0,12,2,true);
        index.restrict(0,0,0,2,13);index.received(0,0,0,13,1,false);
        check(index.rebuildIndex(world,()->false,p->{}).sections()==6,"second force pass scans data again");
        check(index.column(0,0,0,1).equals(new CoverageStore.Stamp(13,1)),"confirmed current detail survives merge");
        check(index.column(0,0,1,2).equals(new CoverageStore.Stamp(13,5)),"pending refresh survives merge");
        index.closeUnconfirmed();
        index=new CoverageStore(path);index.remote(-4,32,64);((EngineAccess)world).distant$setCoverage(index);
        index.rebuildIndex(world,()->false,p->{});check(index.directory(0,0,false).column(0).level()==5,"unsafe guard survives force rebuild and reopen");
        // Concurrent ingest during the storage scan must win when the page is merged.
        final CoverageStore current=index;boolean[] saved={false};
        var concurrentStorage=new SectionStorage(){
            public int loadSection(WorldSection section){return storage.loadSection(section);}
            public void saveSection(WorldSection section){storage.saveSection(section);}
            public void putIdMapping(int id,ByteBuffer bytes){storage.putIdMapping(id,bytes);}
            public it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<byte[]> getIdMappingsData(){return storage.getIdMappingsData();}
            public void flush(){storage.flush();}public void close(){}
            public void iteratePositions(int level,java.util.function.LongConsumer callback){storage.iteratePositions(level,key->{callback.accept(key);if(!saved[0]){saved[0]=true;current.beginWrite(0,0,0,1);current.received(0,0,0,99,0,false);current.checkpoint(storage::flush);}});}
        };
        var concurrent=new WorldEngine(concurrentStorage);
        try{index.rebuildIndex(concurrent,()->false,p->{});}finally{concurrent.free();}
        check(index.column(0,0,0,1).equals(new CoverageStore.Stamp(99,0)),"live save during scan wins over recovered geometry");
        try{index.rebuildIndex(world,()->true,p->{});throw new AssertionError("cancelled rebuild must stop");}catch(java.util.concurrent.CancellationException expected){}
        index.saveAfterWorldClosed();index=new CoverageStore(path);index.remote(-4,32,64);((EngineAccess)world).distant$setCoverage(index);
        check(index.directory(0,0,false)!=null,"rebuild persists directories without startup migration");
        check(index.column(0,0,0,1).equals(new CoverageStore.Stamp(99,0)),"rebuild preserves version after disk reopen");
        world.free();
        var dispatcher=new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
        dev.voxydistant.client.CacheIndexRebuild.commands(new net.minecraftforge.client.event.RegisterClientCommandsEvent(dispatcher,null));
        check(dispatcher.getRoot().getChild("voxydistant").getChild("rebuildindex")!=null,"client command registered without an OP requirement");
        System.out.println("DISTANT_INDEX_REBUILD_PASS: real Voxy RocksDB with no coverage, false-ready marker, five LOD levels, AIR, force repeat, live save, unsafe guard, cancellation, reopen and client command");
    }
    private static LodColumn column(long revision,int level,int state){long[][][] sections=new long[1][5][];sections[0][level]=new long[4096>>(3*level)];Arrays.fill(sections[0][level],LodColumn.voxel(state,0,0xf3,state==0?0:15));return new LodColumn(-1,-1,0,revision,List.of(Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState()),List.of("minecraft:plains"),sections);}
    private static void compareMipper(Mapper mapper){
        var states=List.of(Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState(),Blocks.GLASS.defaultBlockState(),Blocks.OAK_LEAVES.defaultBlockState(),Blocks.WATER.defaultBlockState());
        int[] ids=states.stream().mapToInt(mapper::getIdForBlockState).toArray();var random=new Random(27319);
        for(int n=0;n<10000;n++){
            long[] input=new long[8],reference=new long[8];for(int i=0;i<8;i++){int id=random.nextInt(ids.length),light=random.nextInt(256);input[i]=LodColumn.voxel(id,7,light,mapper.getBlockStateOpacity(ids[id]));reference[i]=id==0?Mapper.airWithLight(light):Mapper.composeMappingId((byte)light,ids[id],7);}
            long actual=LodColumn.reduce(input)[0],expected=Mipper.mip(reference[0],reference[1],reference[2],reference[3],reference[4],reference[5],reference[6],reference[7],mapper);
            check(ids[LodColumn.state(actual)]==Mapper.getBlockId(expected)&&LodColumn.light(actual)==Mapper.getLightId(expected),"mipper block/light match");if(!Mapper.isAir(expected))check(LodColumn.biome(actual)==Mapper.getBiomeId(expected),"mipper biome match");
        }
    }
}
