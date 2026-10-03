package dev.voxydistant.server;

import dev.voxydistant.movement.RequestShape;
import dev.voxydistant.network.Protocol;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.PriorityQueue;

/** Server-owned requests: insertion order for aging, cached scores for spatial selection. */
final class PendingRequests extends LinkedHashMap<Long,Protocol.Want> {
    private record Ranked(Protocol.Want want,double score,long distance) implements Comparable<Ranked> {
        public int compareTo(Ranked other){
            int order=Double.compare(score,other.score);
            if(order==0)order=Long.compare(distance,other.distance);
            if(order==0)order=Integer.compare(want.x(),other.want.x());
            return order==0?Integer.compare(want.z(),other.want.z()):order;
        }
    }
    private PriorityQueue<Ranked> order;
    private RequestShape shape;
    private int x,z;

    @Override public Protocol.Want put(Long position,Protocol.Want want){
        var previous=super.put(position,want);
        if(order!=null){if(previous!=null)order=null;else order.add(rank(want));}
        return previous;
    }
    @Override public void clear(){super.clear();order=null;shape=null;}
    private Ranked rank(Protocol.Want want){
        long dx=(long)want.x()-x,dz=(long)want.z()-z,distance=dx*dx+dz*dz;
        return new Ranked(want,shape==null?distance:shape.score(want.x(),want.z()),distance);
    }
    Protocol.Want poll(RequestShape next,int px,int pz,boolean oldest){
        if(isEmpty())return null;
        Protocol.Want want;
        if(oldest)want=firstEntry().getValue();
        else{
            if(order==null||order.size()>size()*2+64||x!=px||z!=pz||!Objects.equals(shape,next)){
                shape=next;x=px;z=pz;
                var ranked=new java.util.ArrayList<Ranked>(size());for(var candidate:values())ranked.add(rank(candidate));
                order=new PriorityQueue<>(ranked);
            }
            while(true){want=order.remove().want();if(get(position(want))==want)break;}
        }
        remove(position(want));return want;
    }
    private static long position(Protocol.Want want){return (want.x()&0xffffffffL)|((long)want.z()<<32);}
}
