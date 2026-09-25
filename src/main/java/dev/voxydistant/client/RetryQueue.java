package dev.voxydistant.client;

import java.util.*;
import java.util.function.LongPredicate;
import java.util.function.LongToIntFunction;
import java.util.concurrent.TimeUnit;

/** Main-thread retry deadlines. Taking a request retains its failure count until success. */
public final class RetryQueue {
    public record Retry(long position, long due, int attempts, int desired, boolean urgent) {}
    private static final Comparator<Retry> ORDER=Comparator.comparingLong(Retry::due).thenComparingLong(Retry::position);
    private final Map<Long,Retry> entries=new HashMap<>();
    private final NavigableSet<Retry> urgent=new TreeSet<>(ORDER), distant=new TreeSet<>(ORDER);
    private NavigableSet<Retry> queue(Retry r){return r.urgent()?urgent:distant;}
    public Retry failed(long position,int desired,boolean priority,long now){
        Retry previous=entries.get(position);
        int attempts=previous==null?1:previous.attempts()+1;
        if(previous!=null)queue(previous).remove(previous);
        long seconds=priority?2:2L<<Math.min(3,attempts-1);
        Retry next=new Retry(position,now+TimeUnit.SECONDS.toNanos(seconds),attempts,desired,priority);
        entries.put(position,next);queue(next).add(next);return next;
    }
    /** Wait for a finer distance band or a Dirty notification after an identical insufficient result. */
    public void stalled(long position,int desired,boolean priority){
        Retry previous=entries.get(position);if(previous!=null)queue(previous).remove(previous);
        Retry next=new Retry(position,Long.MAX_VALUE,previous==null?1:previous.attempts()+1,desired,priority);
        entries.put(position,next);queue(next).add(next);
    }
    public Long poll(long now){
        var ready=!urgent.isEmpty()&&urgent.first().due()<=now?urgent:distant;
        if(ready.isEmpty()||ready.first().due()>now)return null;
        return ready.pollFirst().position();
    }
    public boolean scheduled(long position){Retry r=entries.get(position);return r!=null&&queue(r).contains(r);}
    public void remove(long position){Retry r=entries.remove(position);if(r!=null)queue(r).remove(r);}
    public void clear(){entries.clear();urgent.clear();distant.clear();}
    public int size(){return urgent.size()+distant.size();}
    public void moved(LongPredicate inRange,LongToIntFunction desired,LongPredicate priority,long now){
        for(var it=entries.entrySet().iterator();it.hasNext();){
            Retry r=it.next().getValue();
            if(!inRange.test(r.position())){queue(r).remove(r);it.remove();continue;}
            int level=desired.applyAsInt(r.position());boolean near=priority.test(r.position());
            boolean promote=level<r.desired()||near&&!r.urgent();
            boolean scheduled=queue(r).remove(r);
            Retry next=new Retry(r.position(),promote?now:r.due(),promote?0:r.attempts(),level,near);
            entries.put(r.position(),next);if(scheduled)queue(next).add(next);
        }
    }
    public List<Retry> oldest(int limit){
        return java.util.stream.Stream.concat(urgent.stream().limit(limit),distant.stream().limit(limit))
                .sorted(ORDER).limit(limit).toList();
    }
}
