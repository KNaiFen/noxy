package dev.voxydistant.client;

import dev.voxydistant.compat.CoverageStore;
import dev.voxydistant.config.DistanceBands;
import dev.voxydistant.movement.RequestShape;
import java.util.*;

/** Main-thread region work. Interior masks survive movement; only boundary masks are rebuilt. */
final class RegionDiscovery {
    static long key(int rx,int rz){return (rx&0xffffffffL)|((long)rz<<32);}
    static int x(long key){return (int)key;}
    static int z(long key){return (int)(key>>>32);}
    static final class Region {
        final int x,z;
        BitSet[] targets;
        CoverageStore.Directory local;
        final BitSet needs=new BitSet(1024),verified=new BitSet(1024),urgent=new BitSet(1024),stale=new BitSet(1024);
        boolean prepare=true,migrate,query,queried,maskPending;
        int[] order;int nearCursor,farCursor;long preparedAt;
        Region(int x,int z){this.x=x;this.z=z;}
    }
    final LinkedHashMap<Long,Region> regions=new LinkedHashMap<>();
    private final DistanceBands bands;
    private int turn;
    RequestShape shape;
    long boundaryChecks,prepared,checks;
    RegionDiscovery(DistanceBands bands){this.bands=bands;}
    void reset(){regions.clear();}
    void move(int x,int z,int r){
        move(RequestShape.circle(x,z,r));
        for(var region:regions.values())applyMasks(region,shape,buildMasks(region,shape));
    }
    void move(RequestShape next){
        shape=next;
        var active=new HashSet<Long>();
        for(int rz=Math.floorDiv(next.minZ(),32);rz<=Math.floorDiv(next.maxZ(),32);rz++)for(int rx=Math.floorDiv(next.minX(),32);rx<=Math.floorDiv(next.maxX(),32);rx++){
            if(next.relation(rx*32,rz*32,rx*32+31,rz*32+31)<0)continue;
            long key=key(rx,rz);active.add(key);Region region=regions.computeIfAbsent(key,k->new Region(x(k),z(k)));
            int uniform=uniform(region,next);
            if(region.targets!=null&&uniform>=0&&region.targets[uniform].cardinality()==1024){region.maskPending=false;continue;}
            region.maskPending=true;
        }
        regions.keySet().retainAll(active);
    }
    record Masks(BitSet[] targets,long checks,int[] order){}
    private int uniform(Region region,RequestShape footprint){
        int x=region.x*32,z=region.z*32;
        if(footprint.relation(x,z,x+31,z+31)!=1)return -1;
        for(int i=0;i<bands.radii().length;i++){
            int size=1<<(bands.levels()[i]+2),bx=Math.floorDiv(x,size)*size,bz=Math.floorDiv(z,size)*size,ex=Math.floorDiv(x+31,size)*size+size-1,ez=Math.floorDiv(z+31,size)*size+size-1;
            int relation=footprint.radius(bands.radii()[i]).relation(bx,bz,ex,ez);
            if(relation==0)return -1;
            if(relation==1){
                // The server circle can still cross this region; all four corners must agree.
                int level=bands.levels()[i];for(int px:new int[]{x,x+31})for(int pz:new int[]{z,z+31})if(bands.select(px,pz,footprint.playerX(),footprint.playerZ())!=level)return -1;
                return level;
            }
        }
        return bands.levels()[bands.levels().length-1];
    }
    Masks buildMasks(Region region,RequestShape footprint){
        int rx=region.x,rz=region.z,x=rx*32,z=rz*32;var result=new BitSet[5];for(int i=0;i<5;i++)result[i]=new BitSet(1024);
        int uniform=uniform(region,footprint);
        if(uniform>=0)result[uniform].set(0,1024);
        else for(int i=0;i<1024;i++){int px=x+(i&31),pz=z+(i>>5);if(footprint.contains(px,pz))result[footprint.desired(bands,px,pz)].set(i);}
        var slots=new ArrayList<Integer>();for(int i=0;i<1024;i++)for(var mask:result)if(mask.get(i)){slots.add(i);break;}
        slots.sort(Comparator.comparingDouble((Integer i)->footprint.score(x+(i&31),z+(i>>5))).thenComparingDouble(i->footprint.actualDistance(x+(i&31),z+(i>>5))).thenComparingInt(Integer::intValue));
        return new Masks(result,uniform>=0?0:1024,slots.stream().mapToInt(Integer::intValue).toArray());
    }
    void applyMasks(Region region,RequestShape footprint,Masks masks){
        if(shape!=footprint||regions.get(key(region.x,region.z))!=region)return;
        region.maskPending=false;region.preparedAt=++prepared;region.order=masks.order();region.nearCursor=region.farCursor=0;boundaryChecks+=masks.checks();boolean changed=region.targets==null||!Arrays.equals(region.targets,masks.targets());region.targets=masks.targets();
        var included=new BitSet(1024);for(var mask:region.targets)included.or(mask);region.verified.and(included);region.needs.and(included);
        if(changed&&region.local!=null)refresh(region);
    }
    void local(Region region,CoverageStore.Directory directory){
        if(regions.get(key(region.x,region.z))!=region)return;
        if(directory==null){region.migrate=true;return;}
        region.prepare=false;region.local=directory;prepared++;if(region.targets!=null)refresh(region);
    }
    private void refresh(Region region){
        region.needs.clear();region.urgent.clear();region.nearCursor=region.farCursor=0;boolean cached=false;
        for(int target=0;target<5;target++)for(int i=region.targets[target].nextSetBit(0);i>=0;i=region.targets[target].nextSetBit(i+1)){
            checks++;
            if(target==bands.levels()[0]||region.local.levels[i]<5)region.urgent.set(i);
            if(region.local.levels[i]>target||region.stale.get(i))region.needs.set(i);
            else if(!region.verified.get(i))cached=true;
        }
        region.query=cached&&!region.queried;
    }
    Region nextQuery(){return regions.values().stream().filter(r->r.query&&!r.queried&&!r.maskPending).min(Comparator.comparingDouble(r->shape.minimumScore(r.x*32,r.z*32,r.x*32+31,r.z*32+31))).map(r->{r.query=false;r.queried=true;return r;}).orElse(null);}
    void summary(Region region,long[] versions,byte[] masks){
        if(regions.get(key(region.x,region.z))!=region||region.local==null||region.targets==null)return;
        region.queried=false;region.query=false;
        var included=new BitSet(1024);for(var mask:region.targets)included.or(mask);
        for(int i=0;i<1024;i++){
            if(!included.get(i))continue;
            int px=region.x*32+(i&31),pz=region.z*32+(i>>5),target=shape.desired(bands,px,pz);
            if((masks[i]&(1<<target))!=0&&versions[i]==region.local.versions[i]&&region.local.levels[i]<=target){region.verified.set(i);region.stale.clear(i);}
            else{region.needs.set(i);region.stale.set(i);}
        }
        refreshSummary(region);
        region.nearCursor=region.farCursor=0;
    }
    void updated(long position,CoverageStore.Stamp stamp){
        int px=x(position),pz=z(position);var region=regions.get(key(px>>5,pz>>5));if(region==null||region.local==null)return;
        int slot=(px&31)|((pz&31)<<5);region.local.versions[slot]=stamp.version();region.local.levels[slot]=(byte)stamp.level();
        region.nearCursor=region.farCursor=0;
        if(stamp.level()<5){region.verified.set(slot);region.stale.clear(slot);}else{region.verified.clear(slot);region.stale.set(slot);}
        if(shape.contains(px,pz)&&stamp.level()>shape.desired(bands,px,pz))region.needs.set(slot);else region.needs.clear(slot);
        if(stamp.level()<5||shape.desired(bands,px,pz)==bands.levels()[0])region.urgent.set(slot);
    }
    void dirty(long position){var r=regions.get(key(x(position)>>5,z(position)>>5));if(r!=null){int slot=(x(position)&31)|((z(position)&31)<<5);r.verified.clear(slot);r.stale.set(slot);}}
    private void refreshSummary(Region region){
        var active=new BitSet(1024);for(var mask:region.targets)active.or(mask);region.needs.and(active);
    }
    private double distance(Region region){return shape.minimumScore(region.x*32,region.z*32,region.x*32+31,region.z*32+31);}
    Long poll(){
        boolean far=(turn++&3)==3;Long candidate=poll(far);return candidate==null?poll(!far):candidate;
    }
    private Long poll(boolean far){
        Region chosen=null;double best=Double.POSITIVE_INFINITY;BitSet eligible=null;
        for(var region:regions.values())if(!region.maskPending&&!region.needs.isEmpty()&&distance(region)<best){
            var bits=(BitSet)region.needs.clone();if(far)bits.andNot(region.urgent);else bits.and(region.urgent);
            if(!bits.isEmpty()){chosen=region;eligible=bits;best=distance(region);}
        }
        if(chosen==null)return null;int slot=-1,cursor=far?chosen.farCursor:chosen.nearCursor;
        for(;cursor<chosen.order.length;cursor++)if(eligible.get(chosen.order[cursor])){slot=chosen.order[cursor++];break;}
        if(far)chosen.farCursor=cursor;else chosen.nearCursor=cursor;
        if(slot<0)return null;
        chosen.needs.clear(slot);int px=chosen.x*32+(slot&31),pz=chosen.z*32+(slot>>5);return key(px,pz);
    }
    boolean busy(){for(var r:regions.values())if(r.maskPending||r.prepare||r.query||!r.needs.isEmpty())return true;return false;}
}
