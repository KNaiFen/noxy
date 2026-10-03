package dev.voxydistant.client;

import dev.voxydistant.compat.CoverageStore;
import dev.voxydistant.config.DistanceBands;
import dev.voxydistant.movement.RequestShape;
import java.util.*;

/** Receive-lane region work. Scanlines build masks in bulk; uniform interiors share immutable masks. */
final class RegionDiscovery {
    static long key(int rx,int rz){return (rx&0xffffffffL)|((long)rz<<32);}
    static int x(long key){return (int)key;}
    static int z(long key){return (int)(key>>>32);}
    static final class Region {
        final int x,z;
        long[][] targets;
        CoverageStore.Directory local;
        long[][] localLevels; // Columns whose cached precision is coarser than each target level.
        final long[] needs=new long[16],verified=new long[16],urgent=new long[16],stale=new long[16];
        int needed,nearNeeded;
        boolean prepare=true,migrate,query,queried,maskPending;
        long preparedAt;
        RowQueue nearOrder,farOrder;
        int[] nearRows,farRows;
        double minimum;
        RequestShape orderedFor;
        long activeMove;
        Region(int x,int z){this.x=x;this.z=z;}
    }
    final it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<Region> regions=new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
    private final DistanceBands bands;
    private int turn;
    private long sequence;
    private long movement;
    private Region[] orderedRegions=new Region[0];
    private int firstRow;
    private long[] includedRows;
    private long[][] bandRows;
    private int minimumRegionX,minimumRegionZ,regionWidth;
    private long[][][] rowMasks;
    private static final long[] EMPTY=new long[16];
    private static final long[][][] FULL=new long[5][5][];
    static {long[] all=new long[16];Arrays.fill(all,-1L);for(int level=0;level<5;level++){Arrays.fill(FULL[level],EMPTY);FULL[level][level]=all;}}
    RequestShape shape;
    long boundaryChecks,prepared,checks; // boundaryChecks counts per-column shape tests, now eliminated.
    RegionDiscovery(DistanceBands bands){this.bands=bands;}
    void reset(){regions.clear();orderedRegions=new Region[0];includedRows=null;bandRows=null;rowMasks=null;shape=null;}
    void move(int x,int z,int r){
        move(RequestShape.circle(x,z,r));
        for(var region:regions.values())if(region.maskPending)applyMasks(region,shape,buildMasks(region,shape));
    }
    void move(RequestShape next){
        shape=next;
        rows(next);
        int maximumRegionX=next.maxX()>>5,maximumRegionZ=next.maxZ()>>5;
        minimumRegionX=next.minX()>>5;minimumRegionZ=next.minZ()>>5;regionWidth=maximumRegionX-minimumRegionX+1;
        rowMasks=new long[(maximumRegionZ-minimumRegionZ+1)*regionWidth][][];
        int[] extents=new int[(bandRows.length+1)*4];
        for(int rz=minimumRegionZ;rz<=maximumRegionZ;rz++){
            for(int i=0;i<=bandRows.length;i++){
                long[] ranges=i==0?includedRows:bandRows[i-1];int offset=i*4;
                extents[offset]=Integer.MAX_VALUE;extents[offset+1]=Integer.MIN_VALUE;extents[offset+2]=Integer.MAX_VALUE;extents[offset+3]=Integer.MIN_VALUE;
                int stride=i==0?1:Math.min(32,1<<(bands.levels()[i-1]+2));
                for(int z=rz*32;z<rz*32+32;z+=stride){
                    int row=z-firstRow;
                    if(row<0||row>=ranges.length||x(ranges[row])>z(ranges[row])){extents[offset+1]=Integer.MAX_VALUE;extents[offset+2]=Integer.MIN_VALUE;continue;}
                    int left=x(ranges[row]),right=z(ranges[row]);extents[offset]=Math.min(extents[offset],left);extents[offset+1]=Math.max(extents[offset+1],left);extents[offset+2]=Math.min(extents[offset+2],right);extents[offset+3]=Math.max(extents[offset+3],right);
                }
            }
            if(extents[0]>extents[3])continue;
            for(int rx=extents[0]>>5;rx<=extents[3]>>5;rx++){
                int left=rx*32,right=left+31,slot=(rz-minimumRegionZ)*regionWidth+rx-minimumRegionX;
                if(left>=extents[1]&&right<=extents[2])for(int i=0;i<=bandRows.length;i++){
                    int offset=(i+1)*4;
                    if(i<bandRows.length&&(right<extents[offset]||left>extents[offset+3]))continue;
                    if(i==bandRows.length||left>=extents[offset+1]&&right<=extents[offset+2])rowMasks[slot]=FULL[bands.levels()[i]];
                    break;
                }
                if(rowMasks[slot]!=null)continue;
                long[][] words=null;
                for(int z=Math.max(rz*32,firstRow);z<Math.min(rz*32+32,firstRow+includedRows.length);z+=2){
                    int row=z-firstRow,word=(z&31)>>1;long remaining=(rowBits(includedRows[row],left)&0xffffffffL)|((long)rowBits(includedRows[row+1],left)<<32);
                    for(int i=0;i<=bandRows.length&&remaining!=0;i++){
                        long bits=i==bandRows.length?remaining:remaining&((rowBits(bandRows[i][row],left)&0xffffffffL)*0x100000001L);int level=bands.levels()[i];if(bits==0)continue;
                        if(words==null){rowMasks[slot]=words=new long[5][];Arrays.fill(words,EMPTY);}
                        if(words[level]==EMPTY)words[level]=new long[16];words[level][word]|=bits;
                        remaining&=~bits;
                    }
                }
            }
        }
        movement++;
        for(int rz=minimumRegionZ;rz<=maximumRegionZ;rz++)for(int rx=minimumRegionX;rx<=maximumRegionX;rx++){
            int slot=(rz-minimumRegionZ)*regionWidth+rx-minimumRegionX;var words=rowMasks[slot];if(words==null)continue;
            long key=key(rx,rz);Region region=regions.get(key);
            if(region==null){region=new Region(rx,rz);regions.put(key,region);}
            region.activeMove=movement;
            if(region.targets==words){region.maskPending=false;continue;}
            if(region.targets!=null){boolean same=true;for(int level=0;level<5;level++){
                if(Arrays.equals(region.targets[level],words[level])){if(words!=FULL[level]&&words[level]!=EMPTY)words[level]=region.targets[level];}else same=false;
            }if(same){region.maskPending=false;rowMasks[slot]=region.targets;continue;}}
            region.maskPending=true;
        }
        for(var it=regions.values().iterator();it.hasNext();)if(it.next().activeMove!=movement)it.remove();
        orderedRegions=regions.values().toArray(Region[]::new);
        for(var region:orderedRegions)region.minimum=next.minimumScore(region.x*32,region.z*32,region.x*32+31,region.z*32+31);
        Arrays.sort(orderedRegions,Comparator.comparingDouble(r->r.minimum));
    }
    private void rows(RequestShape footprint){
        firstRow=footprint.minZ()&-2;int count=(footprint.maxZ()-firstRow+2)&-2;
        includedRows=new long[count];bandRows=new long[bands.radii().length-1][count];
        double[] span=new double[2];
        for(int row=0;row<count;row++){
            int z=firstRow+row;long circle=circleSpan(footprint.playerX(),footprint.playerZ(),z,z,footprint.limit());
            int left=1,right=0;
            if(x(circle)<=z(circle)&&footprint.spanX(z,z,footprint.radius(),span)){
                left=Math.max(x(circle),(int)Math.floor(span[0]));right=Math.min(z(circle),(int)Math.ceil(span[1]));
                if(left<=right){if(!footprint.contains(left,z))left++;else if(footprint.contains(left-1,z))left--;if(!footprint.contains(right,z))right--;else if(footprint.contains(right+1,z))right++;}
            }
            includedRows[row]=key(left,right);
        }
        for(int i=0;i<bandRows.length;i++){
            int size=1<<(bands.levels()[i]+2),reach=(int)Math.floor(bands.radii()[i]*footprint.precisionScale());
            for(int bz=firstRow&-size;bz<=footprint.maxZ();bz+=size){
                int left=1,right=0;long circle=circleSpan(footprint.playerX(),footprint.playerZ(),bz,bz+size-1,Math.min(footprint.limit(),bands.radii()[i]));
                if(x(circle)<=z(circle)&&footprint.spanX(bz,bz+size-1,reach,span)){
                    left=Math.max(x(circle)&-size,(int)Math.floor(span[0])&-size);right=Math.min(z(circle)&-size,(int)Math.ceil(span[1])&-size);
                    if(left<=right){
                        if(footprint.relation(left,bz,left+size-1,bz+size-1,reach)<0)left+=size;
                        if(footprint.relation(right,bz,right+size-1,bz+size-1,reach)<0)right-=size;
                        right+=size-1;
                    }
                }
                Arrays.fill(bandRows[i],Math.max(0,bz-firstRow),Math.min(count,bz+size-firstRow),key(left,right));
            }
        }
    }
    private static long circleSpan(int x,int z,int z0,int z1,int radius){
        long dz=Math.max(Math.max((long)z0-z,(long)z-z1),0);if(dz>radius)return key(1,0);
        int reach=(int)Math.floor(Math.sqrt((long)radius*radius-dz*dz));return key(x-reach,x+reach);
    }
    private static int rowBits(long range,int x){
        int left=Math.max(0,x(range)-x),right=Math.min(31,z(range)-x);
        return left>right?0:(-1<<left)&(-1>>>(31-right));
    }
    long[][] buildMasks(Region region,RequestShape footprint){
        return rowMasks[(region.z-minimumRegionZ)*regionWidth+region.x-minimumRegionX];
    }
    void applyMasks(Region region,RequestShape footprint,long[][] masks){
        if(shape!=footprint||regions.get(key(region.x,region.z))!=region)return;
        region.maskPending=false;region.preparedAt=++sequence;region.orderedFor=null;
        var previous=region.targets;
        region.targets=masks;
        if(region.local!=null)refresh(region,previous);
    }
    void local(Region region,CoverageStore.Directory directory){
        if(regions.get(key(region.x,region.z))!=region)return;
        if(directory==null){region.migrate=true;return;}
        region.prepare=false;region.local=directory;prepared++;
        region.localLevels=new long[5][16];long[] equal=new long[6];
        for(int word=0;word<16;word++){
            Arrays.fill(equal,0);int start=word*64,level=directory.levels[start];boolean uniform=true;
            for(int i=start+1;i<start+64;i++)if(directory.levels[i]!=level){uniform=false;break;}
            if(uniform)equal[level]=-1L;else for(int i=start;i<start+64;i++)equal[directory.levels[i]]|=1L<<i;
            long missing=equal[5];for(int target=4;target>=0;target--){region.localLevels[target][word]=missing;missing|=equal[target];}
        }
        checks+=1024;
        if(region.targets!=null)refresh(region,null);
    }
    private void refresh(Region region,long[][] previous){
        long[][] targets=region.targets;
        long[] t0=targets[0],t1=targets[1],t2=targets[2],t3=targets[3],t4=targets[4];
        long[] p0=previous==null?EMPTY:previous[0],p1=previous==null?EMPTY:previous[1],p2=previous==null?EMPTY:previous[2],p3=previous==null?EMPTY:previous[3],p4=previous==null?EMPTY:previous[4];
        long[] l0=region.localLevels[0],l1=region.localLevels[1],l2=region.localLevels[2],l3=region.localLevels[3],l4=region.localLevels[4];
        boolean cached=false;region.needed=0;region.nearNeeded=0;
        for(int word=0;word<16;word++){
            long a=t0[word],b=t1[word],c=t2[word],d=t3[word],e=t4[word];
            long included=a|b|c|d|e,changed=(a^p0[word])|(b^p1[word])|(c^p2[word])|(d^p3[word])|(e^p4[word]);
            long required=(a&l0[word])|(b&l1[word])|(c&l2[word])|(d&l3[word])|(e&l4[word]);
            required|=included&region.stale[word];region.verified[word]&=included;
            region.needs[word]=region.needs[word]&included&~changed|required&changed;region.needed+=Long.bitCount(region.needs[word]);
            region.urgent[word]=included&~region.localLevels[4][word]|targets[bands.levels()[0]][word];
            region.nearNeeded+=Long.bitCount(region.needs[word]&region.urgent[word]);
            cached|=(included&~required&~region.verified[word])!=0;
        }
        region.query=cached&&!region.queried;
        region.orderedFor=null;
    }
    Region nextQuery(){for(var r:orderedRegions)if(r.query&&!r.queried&&!r.maskPending){r.query=false;r.queried=true;return r;}return null;}
    void summary(Region region,long[] versions,byte[] masks){
        if(regions.get(key(region.x,region.z))!=region||region.local==null||region.targets==null)return;
        if(region.maskPending)applyMasks(region,shape,buildMasks(region,shape));
        region.queried=false;region.query=false;
        for(int target=0;target<5;target++)for(int word=0;word<16;word++)for(long bits=region.targets[target][word];bits!=0;bits&=bits-1){int i=word*64+Long.numberOfTrailingZeros(bits);
            if((masks[i]&(1<<target))!=0&&versions[i]==region.local.versions[i]&&region.local.levels[i]<=target){region.verified[word]|=1L<<i;region.stale[word]&=~(1L<<i);}
            else{needed(region,i,true);region.stale[word]|=1L<<i;}}
        refreshSummary(region);
        region.orderedFor=null;
    }
    void updated(long position,CoverageStore.Stamp stamp){
        int px=x(position),pz=z(position);var region=regions.get(key(px>>5,pz>>5));if(region==null||region.local==null)return;
        int slot=(px&31)|((pz&31)<<5);for(int target=0;target<5;target++){if(stamp.level()>target)region.localLevels[target][slot>>6]|=1L<<slot;else region.localLevels[target][slot>>6]&=~(1L<<slot);}region.local.versions[slot]=stamp.version();region.local.levels[slot]=(byte)stamp.level();
        long bit=1L<<slot;int word=slot>>6;boolean needed=(region.needs[word]&bit)!=0,wasUrgent=(region.urgent[word]&bit)!=0;
        if(stamp.level()<5){region.verified[word]|=bit;region.stale[word]&=~bit;}else{region.verified[word]&=~bit;region.stale[word]|=bit;}
        int target=shape.desired(bands,px,pz);boolean urgent=stamp.level()<5||target==bands.levels()[0];
        boolean required=shape.contains(px,pz)&&stamp.level()>target;needed(region,slot,required);if(urgent)region.urgent[word]|=bit;else region.urgent[word]&=~bit;if(required&&wasUrgent!=urgent)region.nearNeeded+=urgent?1:-1;
        // A satisfied response only removes a candidate; the heap discards it lazily.
        if(required&&(!needed||wasUrgent!=urgent))region.orderedFor=null;
    }
    void dirty(long position){var r=regions.get(key(x(position)>>5,z(position)>>5));if(r!=null){int slot=(x(position)&31)|((z(position)&31)<<5);r.verified[slot>>6]&=~(1L<<slot);r.stale[slot>>6]|=1L<<slot;}}
    private void refreshSummary(Region region){
        region.needed=0;region.nearNeeded=0;for(int word=0;word<16;word++){long included=0;for(var target:region.targets)included|=target[word];region.needs[word]&=included;region.needed+=Long.bitCount(region.needs[word]);region.nearNeeded+=Long.bitCount(region.needs[word]&region.urgent[word]);}
    }
    private static void needed(Region region,int slot,boolean value){
        long bit=1L<<slot;int word=slot>>6;if(((region.needs[word]&bit)!=0)==value)return;
        if(value){region.needs[word]|=bit;region.needed++;}else{region.needs[word]&=~bit;region.needed--;}
        if((region.urgent[word]&bit)!=0)region.nearNeeded+=value?1:-1;
    }
    Long poll(){
        boolean far=(turn++&3)==3;Long candidate=poll(far);return candidate==null?poll(!far):candidate;
    }
    private Long poll(boolean far){
        Region chosen=null;int slot=-1;double best=Double.POSITIVE_INFINITY;
        for(var region:orderedRegions){
            if(region.minimum>best)break;
            if(region.maskPending||(far?region.needed-region.nearNeeded:region.nearNeeded)==0)continue;
            if(region.orderedFor!=shape)order(region);
            var queue=far?region.farOrder:region.nearOrder;
            var rows=far?region.farRows:region.nearRows;
            while(!queue.isEmpty()&&(region.needs[queue.firstInt()>>6]&(1L<<queue.firstInt()))==0){
                int old=queue.firstInt(),row=old>>5;rows[row]&=~(1<<(old&31));
                rows[row]&=(int)(region.needs[row>>1]>>>((row&1)*32));
                if(rows[row]==0)queue.dequeueInt();else queue.replace(firstInRow(region,row,rows[row]));
            }
            if(queue.isEmpty())continue;
            int candidate=queue.firstInt(),px=region.x*32+(candidate&31),pz=region.z*32+(candidate>>5);
            double score=queue.scores[candidate>>5];
            if(chosen==null||score<best||score==best&&shape.compare(px,pz,chosen.x*32+(slot&31),chosen.z*32+(slot>>5))<0){chosen=region;slot=candidate;best=score;}
        }
        if(chosen==null)return null;
        needed(chosen,slot,false);int px=chosen.x*32+(slot&31),pz=chosen.z*32+(slot>>5);return key(px,pz);
    }
    private void order(Region region){
        int x=region.x*32,z=region.z*32;long[] needed=region.needs,urgent=region.urgent;
        if(region.nearOrder==null){region.nearRows=new int[32];region.farRows=new int[32];region.nearOrder=new RowQueue(x,z);region.farOrder=new RowQueue(x,z);}
        var near=region.nearOrder;var far=region.farOrder;near.size=0;far.size=0;near.shape=far.shape=shape;
        // A convex row only needs its closest available column on either side of the minimum.
        // Heap those 32 row heads, rather than every column in the region.
        for(int row=0;row<needed.length*2;row++){
            int shift=(row&1)*32,bits=(int)(needed[row>>1]>>>shift),priority=(int)(urgent[row>>1]>>>shift);
            region.nearRows[row]=bits&priority;region.farRows[row]=bits&~priority;
            if(region.nearRows[row]!=0)near.add(firstInRow(region,row,region.nearRows[row]));
            if(region.farRows[row]!=0)far.add(firstInRow(region,row,region.farRows[row]));
        }
        near.heapify();far.heapify();
        region.orderedFor=shape;
    }
    private static final class RowQueue {
        final int[] heap=new int[32];final double[] scores=new double[32];final int x,z;int size;RequestShape shape;
        RowQueue(int x,int z){this.x=x;this.z=z;}
        boolean isEmpty(){return size==0;}
        int firstInt(){return heap[0];}
        void add(int value){heap[size++]=value;scores[value>>5]=shape.score(x+(value&31),z+(value>>5));}
        void heapify(){for(int i=(size>>1)-1;i>=0;i--)down(i);}
        void dequeueInt(){heap[0]=heap[--size];if(size!=0)down(0);}
        void replace(int value){heap[0]=value;scores[value>>5]=shape.score(x+(value&31),z+(value>>5));down(0);}
        private int compare(int a,int b){
            int order=Double.compare(scores[a>>5],scores[b>>5]);
            if(order==0)order=Double.compare(shape.actualDistance(x+(a&31),z+(a>>5)),shape.actualDistance(x+(b&31),z+(b>>5)));
            if(order==0)order=Integer.compare(a&31,b&31);return order==0?Integer.compare(a>>5,b>>5):order;
        }
        private void down(int at){
            int value=heap[at];
            for(int child=at*2+1;child<size;child=at*2+1){if(child+1<size&&compare(heap[child+1],heap[child])<0)child++;if(compare(value,heap[child])<=0)break;heap[at]=heap[child];at=child;}
            heap[at]=value;
        }
    }
    private int firstInRow(Region region,int row,int bits){
        int x=region.x*32,z=region.z*32+row,split=(int)Math.max(0,Math.min(32,Math.floor(shape.minimumX(z))-x+1));
        int left=split==32?bits:bits&((1<<split)-1),right=split==32?0:bits&(-1<<split);
        int a=31-Integer.numberOfLeadingZeros(left),b=Integer.numberOfTrailingZeros(right);
        int col=left==0?b:right==0?a:shape.compare(x+a,z,x+b,z)<=0?a:b;
        return row*32+col;
    }
    boolean busy(){for(var r:regions.values())if(r.maskPending||r.prepare||r.query||r.needed!=0)return true;return false;}
}
