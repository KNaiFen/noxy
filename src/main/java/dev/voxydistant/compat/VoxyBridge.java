package dev.voxydistant.compat;

import dev.voxydistant.generation.ChunkSnapshot;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.world.level.Level;

public final class VoxyBridge {
    private static final ThreadLocal<Boolean> BACKGROUND = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<VoxelizedSection> BUFFER = ThreadLocal.withInitial(VoxelizedSection::createEmpty);

    public static CoverageStore coverage(WorldEngine world) {
        return ((EngineAccess) world).distant$getCoverage();
    }

    public static void attach(WorldEngine world, WorldIdentifier id) {
        var instance = (VoxyClientInstance) world.instanceIn;
        var coverage=new CoverageStore(instance.getStorageBasePath().resolve("distant-coverage").resolve(id.getWorldId()+".bin"));
        ((EngineAccess)world).distant$setCoverage(coverage);
        var hello=dev.voxydistant.client.RemoteClient.greeting();
        if(hello!=null)coverage.remote(hello.minY(),hello.maxY(),dev.voxydistant.config.DistantConfig.INDEX_MIB.get());
    }
    public static void resetRenderer(){var renderer=(me.cortex.voxy.client.core.IGetVoxyRenderSystem)net.minecraft.client.Minecraft.getInstance().levelRenderer;renderer.voxy$shutdownRenderer();renderer.voxy$createRenderer();}

    public static WorldEngine acquire(Level clientLevel) {
        var instance = VoxyCommon.getInstance();
        if (instance == null || !instance.isRunning() || !VoxyConfig.CONFIG.isRenderingEnabled()
                || !VoxyConfig.CONFIG.ingestEnabled) return null;
        return instance.getOrCreate(WorldIdentifier.of(clientLevel), true);
    }

    public static int radiusChunks() { return Math.max(0, (int) (VoxyConfig.CONFIG.sectionRenderDistance * 32)); }
    public static boolean enabled() { return VoxyConfig.CONFIG.isRenderingEnabled() && VoxyConfig.CONFIG.ingestEnabled; }

    public static boolean backedUp(WorldEngine world) {
        return world.instanceIn.getIngestService().getTaskCount() >= 512
                || !world.instanceIn.savingServiceRateLimiter.getAsBoolean();
    }

    public static void ingest(WorldEngine world, ChunkSnapshot snapshot, long revision) {
        var converted = BUFFER.get().setPosition(snapshot.x(), snapshot.y(), snapshot.z());
        WorldConversionFactory.convert(converted, world.getMapper(), snapshot.blocks(), snapshot.biomes(), (x, y, z) -> {
            int sky = snapshot.skyLight() == null ? 0 : snapshot.skyLight().get(x, y, z);
            int block = snapshot.blockLight() == null ? 0 : snapshot.blockLight().get(x, y, z);
            return (byte) (sky | (block << 4));
        });
        WorldVoxilizedSectionMipper.mipSection(converted, world.getMapper());
        var state = coverage(world);
        synchronized (state) {
            if (state.changedSince(snapshot.x(), snapshot.y(), snapshot.z(), revision)) return;
            BACKGROUND.set(true);
            try { WorldUpdater.insertUpdate(world, converted); }
            finally { BACKGROUND.remove(); }
        }
    }

    public static boolean background() { return BACKGROUND.get(); }

    public static void ingestRemote(WorldEngine world,ChunkSnapshot snapshot,long version) {
        var state=coverage(world);
        synchronized(state) {
            if(!state.accepts(snapshot.x(),snapshot.y(),snapshot.z(),version,0))return;
            state.meshBegin(snapshot.x(),snapshot.z(),snapshot.y(),snapshot.y()+1);
            try {
            ingest(world,snapshot,Long.MAX_VALUE);
            state.received(snapshot.x(),snapshot.y(),snapshot.z(),version,0,false);
            for(int l=1;l<=4;l++){
                var parent=world.acquire(l,snapshot.x()>>(l+1),snapshot.y()>>(l+1),snapshot.z()>>(l+1));
                try{world.markDirty(parent,WorldEngine.DEFAULT_UPDATE_FLAGS,63);}finally{parent.release();}
            }
            }finally{state.meshEnd(world,snapshot.x(),snapshot.z(),snapshot.y(),snapshot.y()+1);}
        }
    }

    /** Only rendering calls this. Storage/emptiness retain the real occupancy mask. */
    public static byte renderChildren(WorldSection section) {
        var tracker=((dev.voxydistant.compat.mixin.SectionAccessor)(Object)section).distant$getTracker();
        if(tracker!=null&&tracker.engine!=null){
            var state=coverage(tracker.engine);
            if(state!=null){int mask=state.cachedMeshChildren(section.key);if(mask>=0)return (byte)mask;}
        }
        return renderChildren(section,false);
    }
    public static byte renderChildrenForMesh(WorldSection section) {return renderChildren(section,true);}
    private static byte renderChildren(WorldSection section,boolean checkCachedChildren) {
        var tracker = ((dev.voxydistant.compat.mixin.SectionAccessor) (Object) section).distant$getTracker();
        if (tracker == null || tracker.engine == null) return section.getNonEmptyChildren();
        var state = coverage(tracker.engine);
        if (state == null) return section.getNonEmptyChildren();
        if(section.lvl==0||state.canSplit(section.key))return section.getNonEmptyChildren();
        if(!checkCachedChildren||!state.blockedOnlyByStale(section.key))return 0;
        // A missing coverage entry means the server version is uncertain, not that
        // the persisted Voxy child has vanished. Confirm every occupied child
        // before exposing the group; otherwise retain the coarse parent.
        int mask=section.getNonEmptyChildren()&255;
        for(int bit=0;bit<8;bit++)if((mask&(1<<bit))!=0){
            var child=tracker.engine.acquireIfExists(section.lvl-1,section.x*2+(bit&1),section.y*2+((bit>>2)&1),section.z*2+((bit>>1)&1));
            if(child==null){
                if(dev.voxydistant.DebugLog.mesh())dev.voxydistant.DebugLog.log("CLIENT cached_child_block node={} missing_child={}",section.key,bit);
                return 0;
            }
            child.release();
        }
        if(dev.voxydistant.DebugLog.mesh())dev.voxydistant.DebugLog.log("CLIENT cached_child_split node={} level={} x={} y={} z={} missing_blockers={} children={}",section.key,section.lvl,section.x,section.y,section.z,state.missingBlockers(section.key),mask);
        return (byte)mask;
    }

    public static void afterFull(WorldEngine world, VoxelizedSection converted) {
        var state = coverage(world);
        long group = CoverageStore.group(converted.x, converted.y, converted.z);
        state.markFull(converted.x, converted.y, converted.z, background());
        if (state.remote() || state.isCoarse(group)) {
        // Voxy stops propagation when L0 is unchanged. First receipt of real AIR has
        // unchanged empty L0, but must still erase the old coarse terrain above it.
        for (int level = 1; level <= 4; level++) {
            var upper = world.acquire(level, converted.x >> (level + 1), converted.y >> (level + 1), converted.z >> (level + 1));
            try {
                dev.voxydistant.compat.mixin.UpdaterAccessor.distant$insertLevel(converted, upper);
                world.markDirty(upper, WorldEngine.DEFAULT_UPDATE_FLAGS, 63);
            } finally { upper.release(); }
        }
        for(int l=1;l<=4;l++){
            var parent=world.acquire(l,converted.x>>(l+1),converted.y>>(l+1),converted.z>>(l+1));
            // The source section changes only one octant of this 32-block parent.
            int child=WorldSection.getChildIndex((converted.x>>l)&1,(converted.y>>l)&1,(converted.z>>l)&1);
            try{occupancy(parent,1<<child);world.markDirty(parent,WorldEngine.DEFAULT_UPDATE_FLAGS,63);}finally{parent.release();}
        }
        }
        for(int l=0;l<=4;l++){
            var target=world.acquire(l,converted.x>>(l+1),converted.y>>(l+1),converted.z>>(l+1));
            try{state.awaitingSave(target.key);world.saveSection(target,true,false);}finally{target.release();}
        }
    }

    static void occupancy(WorldSection section) {
        int mask=0;long[] data=section._unsafeGetRawDataArray();
        for(int i=0;i<data.length;i++)if(!me.cortex.voxy.common.world.other.Mapper.isAir(data[i]))
            mask|=1<<WorldSection.getChildIndex((i&31)>>4,(i>>10)>>4,((i>>5)&31)>>4);
        section._unsafeSetNonEmptyChildren((byte)mask);
    }

    static void occupancy(WorldSection section,int changedChildren) {
        int mask=section.getNonEmptyChildren()&255;long[] data=section._unsafeGetRawDataArray();
        for(int bit=0;bit<8;bit++)if((changedChildren&(1<<bit))!=0){
            int base=((bit&1)<<4)|((bit&2)<<8)|((bit&4)<<12);
            boolean occupied=false;
            for(int y=0;y<16&&!occupied;y++)for(int z=0;z<16&&!occupied;z++)
                for(int x=0;x<16;x++)if(!me.cortex.voxy.common.world.other.Mapper.isAir(data[base+(y<<10)+(z<<5)+x])){occupied=true;break;}
            mask=occupied?mask|(1<<bit):mask&~(1<<bit);
        }
        section._unsafeSetNonEmptyChildren((byte)mask);
    }

    private VoxyBridge() {}
}
