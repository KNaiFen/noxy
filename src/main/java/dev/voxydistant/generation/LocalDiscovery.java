package dev.voxydistant.generation;

import dev.voxydistant.movement.RequestShape;
import net.minecraft.world.level.ChunkPos;
import java.util.*;
import java.util.function.BiPredicate;

/** Server-thread owned incremental local generation footprint. */
final class LocalDiscovery {
    private static final class Region {
        final int x,z;BitSet active=new BitSet(1024),todo=new BitSet(1024),candidates=new BitSet(1024);boolean update=true;long preparedAt;
        Region(int x,int z){this.x=x;this.z=z;}
    }
    private final Map<Long,Region> regions=new LinkedHashMap<>();
    private RequestShape shape;
    private int view;
    private long sequence;
    long checks,boundaryChecks;
    private static long key(int x,int z){return ChunkPos.asLong(x,z);}
    void move(RequestShape next,int nextView){
        shape=next;view=nextView;var active=new HashSet<Long>();
        for(int rz=Math.floorDiv(next.minZ(),32);rz<=Math.floorDiv(next.maxZ(),32);rz++)for(int rx=Math.floorDiv(next.minX(),32);rx<=Math.floorDiv(next.maxX(),32);rx++){
            if(next.relation(rx*32,rz*32,rx*32+31,rz*32+31)<0)continue;
            long key=key(rx,rz);active.add(key);var region=regions.computeIfAbsent(key,k->new Region(rxFor(k),rzFor(k)));
            int x=rx*32,z=rz*32;boolean outside=x+31<next.playerX()-view||x>next.playerX()+view||z+31<next.playerZ()-view||z>next.playerZ()+view;
            region.update=!(region.active.cardinality()==1024&&outside&&next.relation(x,z,x+31,z+31)==1);
        }
        regions.keySet().retainAll(active);
    }
    private static int rxFor(long key){return ChunkPos.getX(key);}
    private static int rzFor(long key){return ChunkPos.getZ(key);}
    boolean contains(int x,int z){return shape!=null&&shape.contains(x,z)&&Math.max(Math.abs((long)x-shape.playerX()),Math.abs((long)z-shape.playerZ()))>view;}
    void step(int budget,int capacity,BiPredicate<Integer,Integer> missing){
        long deadline=System.nanoTime()+2_000_000L;int count=candidateCount();
        var ordered=new ArrayList<>(regions.values());ordered.sort(Comparator.comparingLong((Region r)->r.preparedAt).thenComparingDouble(r->shape.minimumScore(r.x*32,r.z*32,r.x*32+31,r.z*32+31)));
        for(var region:ordered){
            if(region.update){
                var mask=new BitSet(1024);int x=region.x*32,z=region.z*32;
                int relation=shape.relation(x,z,x+31,z+31);
                boolean outsideVanilla=x+31<shape.playerX()-view||x>shape.playerX()+view||z+31<shape.playerZ()-view||z>shape.playerZ()+view;
                if(relation==1&&outsideVanilla)mask.set(0,1024);
                else for(int i=0;i<1024;i++){boundaryChecks++;if(contains(x+(i&31),z+(i>>5)))mask.set(i);}
                var added=(BitSet)mask.clone();added.andNot(region.active);region.todo.and(mask);region.todo.or(added);region.candidates.and(mask);region.active=mask;region.update=false;region.preparedAt=++sequence;
                if(System.nanoTime()>=deadline)return;
            }
            for(int slot=region.todo.nextSetBit(0);slot>=0&&budget>0&&count<capacity;slot=region.todo.nextSetBit(slot+1)){
                region.todo.clear(slot);budget--;checks++;int x=region.x*32+(slot&31),z=region.z*32+(slot>>5);
                if(contains(x,z)&&missing.test(x,z)){region.candidates.set(slot);count++;}
                if(System.nanoTime()>=deadline)return;
            }
            if(budget==0)return;
        }
    }
    int candidateCount(){int count=0;for(var r:regions.values())count+=r.candidates.cardinality();return count;}
    ChunkPos poll(){
        Region selected=null;double best=Double.POSITIVE_INFINITY;int slot=-1;
        for(var r:regions.values())if(!r.update&&!r.candidates.isEmpty()){
            double minimum=shape.minimumScore(r.x*32,r.z*32,r.x*32+31,r.z*32+31);if(minimum>best)continue;
            for(int i=r.candidates.nextSetBit(0);i>=0;i=r.candidates.nextSetBit(i+1)){
                int x=r.x*32+(i&31),z=r.z*32+(i>>5);double score=shape.score(x,z);
                if(selected==null||shape.compare(x,z,selected.x*32+(slot&31),selected.z*32+(slot>>5))<0){best=score;selected=r;slot=i;}
            }
        }
        if(selected==null)return null;selected.candidates.clear(slot);return new ChunkPos(selected.x*32+(slot&31),selected.z*32+(slot>>5));
    }
    void retry(ChunkPos pos){var r=regions.get(key(pos.x>>5,pos.z>>5));if(r!=null)r.todo.set((pos.x&31)|((pos.z&31)<<5));}
    void clear(){regions.clear();}
    boolean busy(){for(var r:regions.values())if(r.update||!r.todo.isEmpty()||!r.candidates.isEmpty())return true;return false;}
}
