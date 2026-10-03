package dev.voxydistant.server;

import dev.voxydistant.movement.RequestShape;
import dev.voxydistant.network.Protocol;
import java.util.LinkedHashMap;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PendingRequestsTest {
    @Test void movementCancellationReplacementAndAgingMatchFullScan(){
        var expected=new LinkedHashMap<Long,Protocol.Want>();var actual=new PendingRequests();var random=new Random(903);
        long request=0;
        for(int step=0;step<80;step++){
            int px=step/4,pz=step/8;double angle=(step/4)/2.0;
            var shape=step%7==0?null:new RequestShape(px,pz,px+.5,pz+.25,Math.cos(angle),Math.sin(angle),.8,192,256);
            for(int i=0;i<64;i++){
                int x=random.nextInt(64)-32,z=random.nextInt(64)-32;long p=position(x,z);var want=new Protocol.Want(x,z,0,5,++request);
                actual.put(p,want);expected.put(p,want);
            }
            for(int i=0;i<16;i++){long p=position(random.nextInt(64)-32,random.nextInt(64)-32);actual.remove(p);expected.remove(p);}
            for(int i=0;i<48;i++){
                if(i%12==0){
                    int x=128+step,z=i;long p=position(x,z);var want=new Protocol.Want(x,z,0,5,++request);
                    actual.put(p,want);expected.put(p,want);
                    long cancelled=expected.firstEntry().getKey();actual.remove(cancelled);expected.remove(cancelled);
                }
                boolean oldest=(step*48+i)%20==0;
                Protocol.Want first=null;
                for(var want:expected.values())if(first==null||!oldest&&compare(shape,px,pz,want,first)<0)first=want;
                assertSame(first,actual.poll(shape,px,pz,oldest),"step="+step+" request="+i);
                if(first!=null)expected.remove(position(first.x(),first.z()));
            }
            assertEquals(expected,actual);
        }
        actual.clear();assertNull(actual.poll(null,0,0,false));
    }
    private static int compare(RequestShape shape,int px,int pz,Protocol.Want a,Protocol.Want b){
        long ax=(long)a.x()-px,az=(long)a.z()-pz,bx=(long)b.x()-px,bz=(long)b.z()-pz,ad=ax*ax+az*az,bd=bx*bx+bz*bz;
        int order=Double.compare(shape==null?ad:shape.score(a.x(),a.z()),shape==null?bd:shape.score(b.x(),b.z()));
        if(order==0)order=Long.compare(ad,bd);if(order==0)order=Integer.compare(a.x(),b.x());return order==0?Integer.compare(a.z(),b.z()):order;
    }
    private static long position(int x,int z){return (x&0xffffffffL)|((long)z<<32);}
}
