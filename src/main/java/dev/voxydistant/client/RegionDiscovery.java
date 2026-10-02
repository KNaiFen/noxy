package dev.voxydistant.client;

import dev.voxydistant.compat.CoverageStore;
import dev.voxydistant.config.DistanceBands;
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
        boolean prepare=true,migrate,query,queried;
        Region(int x,int z){this.x=x;this.z=z;}
    }
    final LinkedHashMap<Long,Region> regions=new LinkedHashMap<>();
    private final DistanceBands bands;
    private int cx,cz,radius,turn;
    long boundaryChecks,prepared,checks;
    RegionDiscovery(DistanceBands bands){this.bands=bands;}
    void reset(){regions.clear();}
    void move(int x,int z,int r){
        cx=x;cz=z;radius=r;
        var active=new HashSet<Long>();
        for(int rz=Math.floorDiv(z-r,32);rz<=Math.floorDiv(z+r,32);rz++)for(int rx=Math.floorDiv(x-r,32);rx<=Math.floorDiv(x+r,32);rx++){
            long dx=near(rx*32,rx*32+31,x),dz=near(rz*32,rz*32+31,z);
            if(dx*dx+dz*dz>(long)r*r)continue;
            long key=key(rx,rz);active.add(key);Region region=regions.computeIfAbsent(key,k->new Region(x(k),z(k)));
            var masks=masks(rx,rz);boolean changed=region.targets==null||!Arrays.equals(region.targets,masks);
            region.targets=masks;
            var included=new BitSet(1024);for(var mask:masks)included.or(mask);region.verified.and(included);
            if(changed&&region.local!=null)refresh(region);
        }
        regions.keySet().retainAll(active);
    }
    private static long near(int lo,int hi,int p){return Math.max(Math.max((long)lo-p,(long)p-hi),0);}
    private static long far(int lo,int hi,int p){return Math.max(Math.abs((long)lo-p),Math.abs((long)hi-p));}
    private BitSet[] masks(int rx,int rz){
        var result=new BitSet[5];for(int i=0;i<5;i++)result[i]=new BitSet(1024);
        int x=rx*32,z=rz*32;long fx=far(x,x+31,cx),fz=far(z,z+31,cz);
        int minimum=bands.levels()[bands.levels().length-1],maximum=minimum;
        for(int i=0;i<bands.radii().length;i++){
            int size=1<<(bands.levels()[i]+2),loX=Math.floorDiv(x,size)*size,hiX=Math.floorDiv(x+31,size)*size,
                    loZ=Math.floorDiv(z,size)*size,hiZ=Math.floorDiv(z+31,size)*size;
            long nx=near(loX,hiX+size-1,cx),nz=near(loZ,hiZ+size-1,cz),
                    mx=Math.max(near(loX,loX+size-1,cx),near(hiX,hiX+size-1,cx)),
                    mz=Math.max(near(loZ,loZ+size-1,cz),near(hiZ,hiZ+size-1,cz));
            long squared=(long)bands.radii()[i]*bands.radii()[i];
            if(nx*nx+nz*nz<=squared)minimum=Math.min(minimum,bands.levels()[i]);
            if(mx*mx+mz*mz<=squared)maximum=Math.min(maximum,bands.levels()[i]);
        }
        if(fx*fx+fz*fz<=(long)radius*radius&&minimum==maximum){result[minimum].set(0,1024);return result;}
        for(int i=0;i<1024;i++){
            boundaryChecks++;int px=x+(i&31),pz=z+(i>>5);long dx=(long)px-cx,dz=(long)pz-cz;
            if(dx*dx+dz*dz<=(long)radius*radius)result[bands.select(px,pz,cx,cz)].set(i);
        }
        return result;
    }
    void local(Region region,CoverageStore.Directory directory){
        if(regions.get(key(region.x,region.z))!=region)return;
        if(directory==null){region.migrate=true;return;}
        region.prepare=false;region.local=directory;prepared++;refresh(region);
    }
    private void refresh(Region region){
        region.needs.clear();region.urgent.clear();boolean cached=false;
        for(int target=0;target<5;target++)for(int i=region.targets[target].nextSetBit(0);i>=0;i=region.targets[target].nextSetBit(i+1)){
            checks++;
            if(target==bands.levels()[0]||region.local.levels[i]<5)region.urgent.set(i);
            if(region.local.levels[i]>target||region.stale.get(i))region.needs.set(i);
            else if(!region.verified.get(i))cached=true;
        }
        region.query=cached&&!region.queried;
    }
    Region nextQuery(){for(var region:regions.values())if(region.query&&!region.queried){region.query=false;region.queried=true;return region;}return null;}
    void summary(Region region,long[] versions,byte[] masks){
        if(regions.get(key(region.x,region.z))!=region||region.local==null)return;
        region.queried=false;region.query=false;
        var included=new BitSet(1024);for(var mask:region.targets)included.or(mask);
        for(int i=0;i<1024;i++){
            if(!included.get(i))continue;
            int px=region.x*32+(i&31),pz=region.z*32+(i>>5),target=bands.select(px,pz,cx,cz);
            if((masks[i]&(1<<target))!=0&&versions[i]==region.local.versions[i]&&region.local.levels[i]<=target){region.verified.set(i);region.stale.clear(i);}
            else{region.needs.set(i);region.stale.set(i);}
        }
        refreshSummary(region);
    }
    void updated(long position,CoverageStore.Stamp stamp){
        int px=x(position),pz=z(position);var region=regions.get(key(px>>5,pz>>5));if(region==null||region.local==null)return;
        int slot=(px&31)|((pz&31)<<5);region.local.versions[slot]=stamp.version();region.local.levels[slot]=(byte)stamp.level();
        if(stamp.level()<5){region.verified.set(slot);region.stale.clear(slot);}else{region.verified.clear(slot);region.stale.set(slot);}
        long dx=(long)px-cx,dz=(long)pz-cz;
        if(dx*dx+dz*dz<=(long)radius*radius&&stamp.level()>bands.select(px,pz,cx,cz))region.needs.set(slot);else region.needs.clear(slot);
        if(stamp.level()<5||bands.select(px,pz,cx,cz)==bands.levels()[0])region.urgent.set(slot);
    }
    void dirty(long position){var r=regions.get(key(x(position)>>5,z(position)>>5));if(r!=null){int slot=(x(position)&31)|((z(position)&31)<<5);r.verified.clear(slot);r.stale.set(slot);}}
    private void refreshSummary(Region region){
        var active=new BitSet(1024);for(var mask:region.targets)active.or(mask);region.needs.and(active);
    }
    private long distance(Region region){long dx=near(region.x*32,region.x*32+31,cx),dz=near(region.z*32,region.z*32+31,cz);return dx*dx+dz*dz;}
    Long poll(){
        boolean far=(turn++&3)==3;Long candidate=poll(far);return candidate==null?poll(!far):candidate;
    }
    private Long poll(boolean far){
        Region chosen=null;long best=Long.MAX_VALUE;BitSet eligible=null;
        for(var region:regions.values())if(!region.needs.isEmpty()&&distance(region)<best){
            var bits=(BitSet)region.needs.clone();if(far)bits.andNot(region.urgent);else bits.and(region.urgent);
            if(!bits.isEmpty()){chosen=region;eligible=bits;best=distance(region);}
        }
        if(chosen==null)return null;int slot=eligible.nextSetBit(0);chosen.needs.clear(slot);int px=chosen.x*32+(slot&31),pz=chosen.z*32+(slot>>5);return key(px,pz);
    }
    boolean busy(){for(var r:regions.values())if(r.prepare||r.query||!r.needs.isEmpty())return true;return false;}
}
