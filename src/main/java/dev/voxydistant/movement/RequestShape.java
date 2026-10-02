package dev.voxydistant.movement;

import dev.voxydistant.config.DistanceBands;

/** Horizontal request footprint, in chunk coordinates. Rendering never uses this shape. */
public record RequestShape(int playerX,int playerZ,double centerX,double centerZ,double directionX,double directionZ,
                           double amount,int radius,int limit,double precisionScale) {
    public RequestShape(int playerX,int playerZ,double centerX,double centerZ,double directionX,double directionZ,double amount,int radius,int limit){this(playerX,playerZ,centerX,centerZ,directionX,directionZ,amount,radius,limit,1);}
    public double score(double x,double z){
        double dx=x-centerX,dz=z-centerZ,u=dx*directionX+dz*directionZ,w=-dx*directionZ+dz*directionX;
        double side=1-.5*amount,front=u>0?1+.5*amount:side;
        return u*u/(front*front)+w*w/(side*side);
    }
    public double actualDistance(double x,double z){double dx=x-playerX,dz=z-playerZ;return dx*dx+dz*dz;}
    public int compare(int x,int z,int otherX,int otherZ){
        int order=Double.compare(score(x,z),score(otherX,otherZ));
        if(order==0)order=Double.compare(actualDistance(x,z),actualDistance(otherX,otherZ));
        if(order==0)order=Integer.compare(x,otherX);
        return order==0?Integer.compare(z,otherZ):order;
    }
    public boolean contains(int x,int z){return radius>0&&actualDistance(x,z)<=(double)limit*limit&&score(x,z)<=(double)radius*radius;}
    public RequestShape radius(int value){return new RequestShape(playerX,playerZ,centerX,centerZ,directionX,directionZ,amount,value,limit,precisionScale);}
    public RequestShape precision(double value){return new RequestShape(playerX,playerZ,centerX,centerZ,directionX,directionZ,amount,radius,limit,value);}
    public static RequestShape circle(int x,int z,int radius){return new RequestShape(x,z,x,z,1,0,0,radius,radius);}

    /** Convex footprint: corners prove containment, edge minima prove intersection. */
    public int relation(int x0,int z0,int x1,int z1){
        if(radius<=0)return -1;
        boolean inside=true;
        for(int x:new int[]{x0,x1})for(int z:new int[]{z0,z1})inside&=contains(x,z);
        if(inside)return 1;
        double ax=Math.max(x0,Math.min(x1,playerX)),az=Math.max(z0,Math.min(z1,playerZ));
        if(actualDistance(ax,az)>(double)limit*limit)return -1;
        return minimumScore(x0,z0,x1,z1)>(double)radius*radius?-1:0;
    }
    public double minimumScore(int x0,int z0,int x1,int z1){
        if(centerX>=x0&&centerX<=x1&&centerZ>=z0&&centerZ<=z1)return 0;
        return Math.min(Math.min(edge(x0,z0,x1,z0),edge(x0,z1,x1,z1)),Math.min(edge(x0,z0,x0,z1),edge(x1,z0,x1,z1)));
    }
    private double edge(double x0,double z0,double x1,double z1){
        double dx=x0-centerX,dz=z0-centerZ,u=dx*directionX+dz*directionZ,w=-dx*directionZ+dz*directionX;
        double du=(x1-x0)*directionX+(z1-z0)*directionZ,dw=-(x1-x0)*directionZ+(z1-z0)*directionX;
        double split=du==0?-1:-u/du,best=Math.min(score(x0,z0),score(x1,z1));
        double start=0;
        for(int part=0;part<2;part++){
            double end=part==0&&split>0&&split<1?split:1;
            double side=1-.5*amount,front=u+du*(start+end)*.5>0?1+.5*amount:side;
            double aa=du*du/(front*front)+dw*dw/(side*side);
            if(aa!=0){double t=Math.max(start,Math.min(end,-(u*du/(front*front)+w*dw/(side*side))/aa));best=Math.min(best,score(x0+(x1-x0)*t,z0+(z1-z0)*t));}
            if(end==1)break;start=end;
        }
        return best;
    }
    public int desired(DistanceBands bands,int x,int z){
        int desired=bands.levels()[bands.levels().length-1];
        for(int i=0;i<bands.radii().length;i++){
            int size=1<<(bands.levels()[i]+2),bx=Math.floorDiv(x,size)*size,bz=Math.floorDiv(z,size)*size;
            if(radius((int)Math.floor(bands.radii()[i]*precisionScale)).relation(bx,bz,bx+size-1,bz+size-1)>=0){desired=bands.levels()[i];break;}
        }
        return Math.max(desired,bands.select(x,z,playerX,playerZ));
    }
    public int minX(){return Math.max(playerX-limit,(int)Math.floor(centerX-radius*(1+.5*amount)));}
    public int maxX(){return Math.min(playerX+limit,(int)Math.ceil(centerX+radius*(1+.5*amount)));}
    public int minZ(){return Math.max(playerZ-limit,(int)Math.floor(centerZ-radius*(1+.5*amount)));}
    public int maxZ(){return Math.min(playerZ+limit,(int)Math.ceil(centerZ+radius*(1+.5*amount)));}
}
