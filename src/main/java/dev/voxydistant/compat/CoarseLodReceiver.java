package dev.voxydistant.compat;

import dev.voxydistant.data.LodColumn;
import dev.voxydistant.DebugLog;
import static dev.voxydistant.DebugLog.Metric.*;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import dev.voxydistant.compat.mixin.UpdaterAccessor;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;

/** Network column boundary. The sparse mask never causes fine levels to be manufactured. */
public final class CoarseLodReceiver {
    public static void receive(WorldEngine world,LodColumn column,RegistryAccess registries) {
        receive(world,column,registries,false);
    }
    public static void receive(WorldEngine world,LodColumn column,RegistryAccess registries,boolean authoritative) {
        long mapping=DebugLog.start();int min=column.minimumLevel();var mapper=world.getMapper();
        int[] states=new int[column.states().size()],biomes=new int[column.biomes().size()];
        for(int i=0;i<states.length;i++)states[i]=mapper.getIdForBlockState(column.states().get(i));
        var registry=registries.registryOrThrow(Registries.BIOME);
        for(int i=0;i<biomes.length;i++)biomes[i]=mapper.getIdForBiome(registry.getHolderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.BIOME,new ResourceLocation(column.biomes().get(i)))));
        var coverage=VoxyBridge.coverage(world);
        DebugLog.end(CLIENT_MAP,mapping);long lock=DebugLog.start();
        synchronized(coverage) {
            DebugLog.end(CLIENT_COVERAGE_LOCK,lock);long voxels=DebugLog.start();
            coverage.restrict(column.x(),column.z(),column.minY(),column.minY()+column.sections().length,column.version(),authoritative);
            coverage.meshBegin(column.x(),column.z(),column.minY(),column.minY()+column.sections().length);
            try {
            var touched=new java.util.HashMap<Long,Integer>();
            var vs=VoxelizedSection.createEmpty();
            for(int s=0;s<column.sections().length;s++) {
                int y=column.minY()+s;
                if(!coverage.accepts(column.x(),y,column.z(),column.version(),min))continue;
                var section=column.sections()[s];
                for(int l=min+1;l<=4;l++)if(section[l]==null)section[l]=LodColumn.reduce(section[l-1]);
                vs.setPosition(column.x(),y,column.z());
                boolean air=true;
                for(int l=min;l<=4;l++) {
                    int offset=VoxelizedSection.getBaseIndexForLevel(l);
                    for(int i=0;i<section[l].length;i++) {
                        long v=section[l][i];int block=states[LodColumn.state(v)];
                        vs.section[offset+i]=block==0?Mapper.airWithLight(LodColumn.light(v)):Mapper.composeMappingId((byte)LodColumn.light(v),block,biomes[LodColumn.biome(v)]);
                        if(l==min&&block!=0)air=false;
                    }
                    var target=world.acquire(l,column.x()>>(l+1),y>>(l+1),column.z()>>(l+1));
                    try {
                        long status=UpdaterAccessor.distant$insertLevel(vs,target);
                        if(l==0){int nonAir=0;for(long v:section[0])if(LodColumn.state(v)!=0)nonAir++;target.addNonEmptyBlockCount(nonAir-(4096-(int)((status>>1)&8191)));target.updateLvl0State();}
                        int neighbors=0;
                        if((status&1)!=0){
                            neighbors|=((y^(y-1))>>(l+1))==0?0:1;
                            neighbors|=((y^(y+1))>>(l+1))==0?0:2;
                            neighbors|=((column.x()^(column.x()-1))>>(l+1))==0?0:4;
                            neighbors|=((column.x()^(column.x()+1))>>(l+1))==0?0:8;
                            neighbors|=((column.z()^(column.z()-1))>>(l+1))==0?0:16;
                            neighbors|=((column.z()^(column.z()+1))>>(l+1))==0?0:32;
                        }
                        if(min>0&&l>0){int child=me.cortex.voxy.common.world.WorldSection.getChildIndex((column.x()>>l)&1,(y>>l)&1,(column.z()>>l)&1);neighbors|=(1<<child)<<8;}
                        touched.merge(target.key,neighbors,(a,b)->a|b);
                    } finally {target.release();}
                }
                coverage.received(column.x(),y,column.z(),column.version(),min,air);
            }
            // Publish the now-complete child masks after coverage counters were updated.
            DebugLog.end(CLIENT_VOXELS,voxels);long publish=DebugLog.start();
            for(var entry:touched.entrySet()){long key=entry.getKey();var section=world.acquire(key);try{if(section.lvl>0){if(min==0)VoxyBridge.occupancy(section);else VoxyBridge.occupancy(section,entry.getValue()>>>8);}world.markDirty(section,WorldEngine.DEFAULT_UPDATE_FLAGS,entry.getValue()&63);coverage.awaitingSave(key);world.saveSection(section,true,false);}finally{section.release();}}
            DebugLog.end(CLIENT_PUBLISH_SAVE,publish);
            }finally{coverage.meshEnd(world,column.x(),column.z(),column.minY(),column.minY()+column.sections().length);}
        }
    }
    private CoarseLodReceiver(){}
}
