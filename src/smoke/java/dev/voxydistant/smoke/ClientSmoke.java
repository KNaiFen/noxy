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
        compareMipper(engine.getMapper());checkNodeSwitch();checkCachedMesh(engine);index.closeUnconfirmed();
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
