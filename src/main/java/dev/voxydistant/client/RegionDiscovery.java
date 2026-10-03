package dev.voxydistant.client;

import dev.voxydistant.compat.CoverageStore;
import dev.voxydistant.config.DistanceBands;
import dev.voxydistant.movement.RequestShape;
import java.util.*;

/** Receive-lane region work. Interior masks survive movement; only boundary masks are rebuilt. */
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
        long preparedAt;
        it.unimi.dsi.fastutil.ints.IntHeapPriorityQueue nearOrder,farOrder;
        int[] nearRows,farRows;
        double minimum;
        RequestShape orderedFor;
        Region(int x,int z){this.x=x;this.z=z;}
    }
    final LinkedHashMap<Long,Region> regions=new LinkedHashMap<>();
    private final DistanceBands bands;
    private int turn;
    private long sequence;
    private Region[] orderedRegions=new Region[0];
    RequestShape shape;
    long boundaryChecks,prepared,checks;
    RegionDiscovery(DistanceBands bands){this.bands=bands;}
    void reset(){regions.clear();orderedRegions=new Region[0];}
    void move(int x,int z,int r){
        move(RequestShape.circle(x,z,r));
        for(var region:regions.values())if(region.maskPending)applyMasks(region,shape,buildMasks(region,shape));
    }
    void move(RequestShape next){
        shape=next;
        var active=new HashSet<Long>();
        for(int rz=Math.floorDiv(next.minZ(),32);rz<=Math.floorDiv(next.maxZ(),32);rz++)for(int rx=Math.floorDiv(next.minX(),32);rx<=Math.floorDiv(next.maxX(),32);rx++){
            int relation=next.relation(rx*32,rz*32,rx*32+31,rz*32+31);if(relation<0)continue;
            long key=key(rx,rz);active.add(key);Region region=regions.computeIfAbsent(key,k->new Region(x(k),z(k)));
            int uniform=relation==1?uniform(rx*32,rz*32,32,next):-1;
            if(region.targets!=null&&uniform>=0&&region.targets[uniform].cardinality()==1024){region.maskPending=false;continue;}
            region.maskPending=true;
        }
        regions.keySet().retainAll(active);
        orderedRegions=regions.values().toArray(Region[]::new);
        for(var region:orderedRegions)region.minimum=next.minimumScore(region.x*32,region.z*32,region.x*32+31,region.z*32+31);
        Arrays.sort(orderedRegions,Comparator.comparingDouble(r->r.minimum));
    }
    record Masks(BitSet[] targets,long checks){}
    private int uniform(int x,int z,int span,RequestShape footprint){
        for(int i=0;i<bands.radii().length;i++){
            int size=1<<(bands.levels()[i]+2),bx=x&-size,bz=z&-size,ex=(x+span-1)|(size-1),ez=(z+span-1)|(size-1);
            int relation=footprint.relation(bx,bz,ex,ez,(int)Math.floor(bands.radii()[i]*footprint.precisionScale()));
            if(relation==0)return -1;
            if(relation==1){
                int minimum=bands.levels()[bands.levels().length-1],maximum=minimum;
                for(int j=0;j<bands.radii().length;j++){
                    int width=1<<(bands.levels()[j]+2),loX=x&-width,hiX=(x+span-1)&-width,loZ=z&-width,hiZ=(z+span-1)&-width;
                    long nx=near(loX,hiX+width-1,footprint.playerX()),nz=near(loZ,hiZ+width-1,footprint.playerZ()),mx=Math.max(near(loX,loX+width-1,footprint.playerX()),near(hiX,hiX+width-1,footprint.playerX())),mz=Math.max(near(loZ,loZ+width-1,footprint.playerZ()),near(hiZ,hiZ+width-1,footprint.playerZ()));
                    long squared=(long)bands.radii()[j]*bands.radii()[j];if(nx*nx+nz*nz<=squared)minimum=Math.min(minimum,bands.levels()[j]);if(mx*mx+mz*mz<=squared)maximum=Math.min(maximum,bands.levels()[j]);
                }
                return minimum==maximum?Math.max(bands.levels()[i],minimum):-1;
            }
        }
        return bands.levels()[bands.levels().length-1];
    }
    private static long near(int lo,int hi,int p){return Math.max(Math.max((long)lo-p,(long)p-hi),0);}
    Masks buildMasks(Region region,RequestShape footprint){
        var result=new BitSet[5];for(int i=0;i<5;i++)result[i]=new BitSet(1024);
        return new Masks(result,buildTargets(region.x*32,region.z*32,0,0,32,footprint,result));
    }
    private long buildTargets(int rx,int rz,int bx,int bz,int span,RequestShape footprint,BitSet[] result){
        int x=rx+bx,z=rz+bz,relation=footprint.relation(x,z,x+span-1,z+span-1);
        if(relation<0)return 0;
        int width=1<<(bands.levels()[0]+2),target=span<=width?footprint.desired(bands,x,z):relation==1?uniform(x,z,span,footprint):-1;
        if(target>=0&&relation==1){for(int row=bz;row<bz+span;row++)result[target].set(row*32+bx,row*32+bx+span);return 0;}
        if(span<=width){for(int dz=0;dz<span;dz++)for(int dx=0;dx<span;dx++)if(footprint.contains(x+dx,z+dz))result[target].set((bz+dz)*32+bx+dx);return (long)span*span;}
        int half=span/2;
        return buildTargets(rx,rz,bx,bz,half,footprint,result)+buildTargets(rx,rz,bx+half,bz,half,footprint,result)
                +buildTargets(rx,rz,bx,bz+half,half,footprint,result)+buildTargets(rx,rz,bx+half,bz+half,half,footprint,result);
    }
    void applyMasks(Region region,RequestShape footprint,Masks masks){
        if(shape!=footprint||regions.get(key(region.x,region.z))!=region)return;
        region.maskPending=false;region.preparedAt=++sequence;region.orderedFor=null;boundaryChecks+=masks.checks();
        var included=new BitSet(1024);for(var mask:masks.targets())included.or(mask);
        var previous=region.targets;
        region.targets=masks.targets();region.verified.and(included);region.needs.and(included);region.urgent.and(included);
        if(region.local!=null){
            for(int target=0;target<5;target++){
                var slots=(BitSet)region.targets[target].clone();if(previous!=null)slots.andNot(previous[target]);
                for(int i=slots.nextSetBit(0);i>=0;i=slots.nextSetBit(i+1)){
                    checks++;region.urgent.set(i,target==bands.levels()[0]||region.local.levels[i]<5);
                    region.needs.set(i,region.local.levels[i]>target||region.stale.get(i));
                    if(!region.needs.get(i)&&!region.verified.get(i)&&!region.queried)region.query=true;
                }
            }
        }
    }
    void local(Region region,CoverageStore.Directory directory){
        if(regions.get(key(region.x,region.z))!=region)return;
        if(directory==null){region.migrate=true;return;}
        region.prepare=false;region.local=directory;prepared++;if(region.targets!=null)refresh(region);
    }
    private void refresh(Region region){
        region.needs.clear();region.urgent.clear();region.orderedFor=null;boolean cached=false;
        for(int target=0;target<5;target++)for(int i=region.targets[target].nextSetBit(0);i>=0;i=region.targets[target].nextSetBit(i+1)){
            checks++;
            if(target==bands.levels()[0]||region.local.levels[i]<5)region.urgent.set(i);
            if(region.local.levels[i]>target||region.stale.get(i))region.needs.set(i);
            else if(!region.verified.get(i))cached=true;
        }
        region.query=cached&&!region.queried;
    }
    Region nextQuery(){for(var r:orderedRegions)if(r.query&&!r.queried&&!r.maskPending){r.query=false;r.queried=true;return r;}return null;}
    void summary(Region region,long[] versions,byte[] masks){
        if(regions.get(key(region.x,region.z))!=region||region.local==null||region.targets==null)return;
        if(region.maskPending)applyMasks(region,shape,buildMasks(region,shape));
        region.queried=false;region.query=false;
        for(int target=0;target<5;target++)for(int i=region.targets[target].nextSetBit(0);i>=0;i=region.targets[target].nextSetBit(i+1)){
            if((masks[i]&(1<<target))!=0&&versions[i]==region.local.versions[i]&&region.local.levels[i]<=target){region.verified.set(i);region.stale.clear(i);}
            else{region.needs.set(i);region.stale.set(i);}
        }
        refreshSummary(region);
        region.orderedFor=null;
    }
    void updated(long position,CoverageStore.Stamp stamp){
        int px=x(position),pz=z(position);var region=regions.get(key(px>>5,pz>>5));if(region==null||region.local==null)return;
        int slot=(px&31)|((pz&31)<<5);region.local.versions[slot]=stamp.version();region.local.levels[slot]=(byte)stamp.level();
        boolean needed=region.needs.get(slot),wasUrgent=region.urgent.get(slot);
        if(stamp.level()<5){region.verified.set(slot);region.stale.clear(slot);}else{region.verified.clear(slot);region.stale.set(slot);}
        int target=shape.desired(bands,px,pz);boolean urgent=stamp.level()<5||target==bands.levels()[0];
        region.needs.set(slot,shape.contains(px,pz)&&stamp.level()>target);region.urgent.set(slot,urgent);
        // A satisfied response only removes a candidate; the heap discards it lazily.
        if(region.needs.get(slot)&&(!needed||wasUrgent!=urgent))region.orderedFor=null;
    }
    void dirty(long position){var r=regions.get(key(x(position)>>5,z(position)>>5));if(r!=null){int slot=(x(position)&31)|((z(position)&31)<<5);r.verified.clear(slot);r.stale.set(slot);}}
    private void refreshSummary(Region region){
        var active=new BitSet(1024);for(var mask:region.targets)active.or(mask);region.needs.and(active);
    }
    Long poll(){
        boolean far=(turn++&3)==3;Long candidate=poll(far);return candidate==null?poll(!far):candidate;
    }
    private Long poll(boolean far){
        Region chosen=null;int slot=-1;double best=Double.POSITIVE_INFINITY;
        for(var region:orderedRegions){
            if(region.minimum>best)break;
            if(region.maskPending||region.needs.isEmpty())continue;
            if(region.orderedFor!=shape)order(region);
            var queue=far?region.farOrder:region.nearOrder;
            var rows=far?region.farRows:region.nearRows;
            while(!queue.isEmpty()&&!region.needs.get(queue.firstInt())){
                int old=queue.dequeueInt(),row=old>>5;rows[row]&=~(1<<(old&31));
                if(rows[row]!=0)queue.enqueue(firstInRow(region,row,rows[row]));
            }
            if(queue.isEmpty())continue;
            int candidate=queue.firstInt(),px=region.x*32+(candidate&31),pz=region.z*32+(candidate>>5);
            if(chosen==null||shape.compare(px,pz,chosen.x*32+(slot&31),chosen.z*32+(slot>>5))<0){chosen=region;slot=candidate;best=shape.score(px,pz);}
        }
        if(chosen==null)return null;
        chosen.needs.clear(slot);int px=chosen.x*32+(slot&31),pz=chosen.z*32+(slot>>5);return key(px,pz);
    }
    private void order(Region region){
        int x=region.x*32,z=region.z*32;long[] needed=region.needs.toLongArray(),urgent=region.urgent.toLongArray();
        region.nearRows=new int[32];region.farRows=new int[32];
        int[] near=new int[32],far=new int[32];int nearCount=0,farCount=0;
        // A convex row only needs its closest available column on either side of the minimum.
        // Heap those 32 row heads, rather than every column in the region.
        for(int row=0;row<needed.length*2;row++){
            int shift=(row&1)*32,bits=(int)(needed[row>>1]>>>shift),priority=row/2<urgent.length?(int)(urgent[row>>1]>>>shift):0;
            region.nearRows[row]=bits&priority;region.farRows[row]=bits&~priority;
            if(region.nearRows[row]!=0)near[nearCount++]=firstInRow(region,row,region.nearRows[row]);
            if(region.farRows[row]!=0)far[farCount++]=firstInRow(region,row,region.farRows[row]);
        }
        var footprint=shape;
        it.unimi.dsi.fastutil.ints.IntComparator comparator=(a,b)->footprint.compare(x+(a&31),z+(a>>5),x+(b&31),z+(b>>5));
        region.nearOrder=new it.unimi.dsi.fastutil.ints.IntHeapPriorityQueue(near,nearCount,comparator);
        region.farOrder=new it.unimi.dsi.fastutil.ints.IntHeapPriorityQueue(far,farCount,comparator);
        region.orderedFor=shape;
    }
    private int firstInRow(Region region,int row,int bits){
        int x=region.x*32,z=region.z*32+row,split=(int)Math.max(0,Math.min(32,Math.floor(shape.minimumX(z))-x+1));
        int left=split==32?bits:bits&((1<<split)-1),right=split==32?0:bits&(-1<<split);
        int a=31-Integer.numberOfLeadingZeros(left),b=Integer.numberOfTrailingZeros(right);
        int col=left==0?b:right==0?a:shape.compare(x+a,z,x+b,z)<=0?a:b;
        return row*32+col;
    }
    boolean busy(){for(var r:regions.values())if(r.maskPending||r.prepare||r.query||!r.needs.isEmpty())return true;return false;}
}
