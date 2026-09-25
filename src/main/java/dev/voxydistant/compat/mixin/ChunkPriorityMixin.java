package dev.voxydistant.compat.mixin;

import dev.voxydistant.server.PriorityState;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.*;

/** Two priority bands in the existing queue; no replacement executors or generation futures. */
@Mixin(ChunkTaskPriorityQueue.class)
public abstract class ChunkPriorityMixin<T> implements PriorityState.QueueAccess {
    @Shadow @Final private static int PRIORITY_LEVEL_COUNT;
    @Shadow @Final private List<Long2ObjectLinkedOpenHashMap<List<Optional<T>>>> taskQueue;
    @Shadow private volatile int firstQueue;
    @Unique private PriorityState.Context distant$context;
    @Unique private long distant$revision=-1;
    @Unique private final Long2IntOpenHashMap distant$positions=new Long2IntOpenHashMap();
    @Unique private int distant$count;
    @Override public void distant$priority(PriorityState.Context context) {
        distant$context=context;distant$count=taskQueue.size();
        for(int i=0;i<distant$count;i++)taskQueue.add(new Long2ObjectLinkedOpenHashMap<>());
        if(firstQueue==distant$count)firstQueue=distant$count*2;
    }
    @Redirect(method="hasWork",at=@At(value="FIELD",target="Lnet/minecraft/server/level/ChunkTaskPriorityQueue;PRIORITY_LEVEL_COUNT:I"))
    private int distant$size(){return distant$context==null?PRIORITY_LEVEL_COUNT:PRIORITY_LEVEL_COUNT*2;}
    @ModifyVariable(method="submit",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private int distant$submitLevel(int level,Optional<T> task,long pos,int original) {
        if(distant$context==null)return level;
        if(distant$positions.containsKey(pos))return distant$positions.get(pos);
        int result=level+(distant$context.view.background(pos)?distant$count:0);distant$positions.put(pos,result);return result;
    }
    @Inject(method="resortChunkTasks",at=@At("HEAD"),cancellable=true)
    private void distant$resort(int old,ChunkPos pos,int level,CallbackInfo ci) {
        if(distant$context==null)return;ci.cancel();
        long key=pos.toLong();if(!distant$positions.containsKey(key))return;
        int previous=distant$positions.get(key),next=Math.min(level,distant$count-1)+(distant$context.view.background(key)?distant$count:0);
        var values=taskQueue.get(previous).remove(key);
        if(values!=null)taskQueue.get(next).computeIfAbsent(key,k -> new ArrayList<>()).addAll(values);
        distant$positions.put(key,next);distant$first();
    }
    @Inject(method="pop",at=@At("HEAD"))
    private void distant$promote(CallbackInfoReturnable<?> ci) {
        if(distant$context==null)return;
        var view=distant$context.view;
        if(distant$revision!=view.revision()) {
            distant$revision=view.revision();
            for(var e:distant$positions.long2IntEntrySet()) {
                long key=e.getLongKey();int previous=e.getIntValue(),next=previous%distant$count+(view.background(key)?distant$count:0);
                if(previous==next)continue;
                var values=taskQueue.get(previous).remove(key);
                if(values!=null)taskQueue.get(next).computeIfAbsent(key,k -> new ArrayList<>()).addAll(values);
                e.setValue(next);
            }
            distant$first();
        }
    }
    @Inject(method="pop",at=@At(value="INVOKE",target="Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;removeFirst()Ljava/lang/Object;",remap=false))
    private void distant$popped(CallbackInfoReturnable<?> ci){if(distant$context!=null)distant$positions.remove(taskQueue.get(firstQueue).firstLongKey());}
    @Inject(method="release",at=@At("RETURN"))
    private void distant$released(long pos,boolean clear,CallbackInfo ci){if(distant$context!=null && clear)distant$positions.remove(pos);}
    @Unique private void distant$first(){firstQueue=0;while(firstQueue<taskQueue.size()&&taskQueue.get(firstQueue).isEmpty())firstQueue++;}
}
