package dev.voxydistant.movement;

import dev.voxydistant.config.DistanceBands;

/** Horizontal request footprint, in chunk coordinates. Rendering never uses this shape. */
public record RequestShape(int playerX,int playerZ,double centerX,double centerZ,double directionX,double directionZ,
                           double amount,int radius,int limit,double precisionScale) {
    public RequestShape(int playerX,int playerZ,double centerX,double centerZ,double directionX,double directionZ,double amount,int radius,int limit){this(playerX,playerZ,centerX,centerZ,directionX,directionZ,amount,radius,limit,1);}
    public double score(double x,double z){
        if(amount==0){double dx=x-centerX,dz=z-centerZ;return dx*dx+dz*dz;}
        double dx=x-centerX,dz=z-centerZ,u=dx*directionX+dz*directionZ,w=-dx*directionZ+dz*directionX;
        double side=1-.5*amount,front=u>0?1+.5*amount:side;
        return u*u/(front*front)+w*w/(side*side);
    }
    public double actualDistance(double x,double z){double dx=x-playerX,dz=z-playerZ;return dx*dx+dz*dz;}
    /** Continuous minimum along a horizontal row of the convex footprint. */
    public double minimumX(int z){
        double dz=z-centerZ;if(amount==0||dz*directionZ<=0)return centerX;
        double side=1-.5*amount,front=1+.5*amount;
        return centerX+2*amount*dz*directionX*directionZ/(directionX*directionX*side*side+directionZ*directionZ*front*front);
    }
    /** Project the footprint within a horizontal slab. The limit circle is clipped separately. */
    public boolean spanX(double z0,double z1,int reach,double[] result){
        if(reach<=0)return false;
        double side=1-.5*amount,front=1+.5*amount,ss=side*side,ff=front*front;
        double horizontal=ss*directionX*directionX+ff*directionZ*directionZ;
        double vertical=ff*directionX*directionX+ss*directionZ*directionZ;
        double sideRadius=reach*side,frontZ=reach*Math.sqrt(horizontal);
        z0=Math.max(z0,centerZ-(directionZ<0?frontZ:sideRadius));
        z1=Math.min(z1,centerZ+(directionZ>0?frontZ:sideRadius));
        if(z0>z1)return false;
        double slope=(ff-ss)*directionX*directionZ/horizontal;
        result[0]=Double.POSITIVE_INFINITY;result[1]=Double.NEGATIVE_INFINITY;
        for(int i=0;i<(z0==z1?1:2);i++){
            double dz=(i==0?z0:z1)-centerZ,center=slope*dz;
            double half=Math.sqrt(Math.max(0,ss*ff/horizontal*((double)reach*reach-dz*dz/horizontal)));
            double left=center-half,right=center+half;
            if(left*directionX+dz*directionZ<0)left=-Math.sqrt(Math.max(0,sideRadius*sideRadius-dz*dz));
            if(right*directionX+dz*directionZ<0)right=Math.sqrt(Math.max(0,sideRadius*sideRadius-dz*dz));
            result[0]=Math.min(result[0],centerX+left);result[1]=Math.max(result[1],centerX+right);
        }
        double frontX=reach*Math.sqrt(vertical),supportZ=reach*(ff-ss)*directionX*directionZ/Math.sqrt(vertical);
        double leftZ=centerZ-(directionX<0?supportZ:0),rightZ=centerZ+(directionX>0?supportZ:0);
        if(leftZ>=z0&&leftZ<=z1)result[0]=Math.min(result[0],centerX-(directionX<0?frontX:sideRadius));
        if(rightZ>=z0&&rightZ<=z1)result[1]=Math.max(result[1],centerX+(directionX>0?frontX:sideRadius));
        return true;
    }
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
        return relation(x0,z0,x1,z1,radius);
    }
    public int relation(int x0,int z0,int x1,int z1,int reach){
        if(reach<=0)return -1;
        double maxX=Math.max(Math.abs(x0-playerX),Math.abs(x1-playerX)),maxZ=Math.max(Math.abs(z0-playerZ),Math.abs(z1-playerZ));
        double ax=Math.max(x0,Math.min(x1,playerX)),az=Math.max(z0,Math.min(z1,playerZ));
        if(actualDistance(ax,az)>(double)limit*limit)return -1;
        if(amount==0&&centerX==playerX&&centerZ==playerZ){double squared=(double)Math.min(reach,limit)*Math.min(reach,limit);return actualDistance(ax,az)>squared?-1:maxX*maxX+maxZ*maxZ<=squared?1:0;}
        if(maxX*maxX+maxZ*maxZ<=(double)limit*limit&&Math.max(Math.max(score(x0,z0),score(x0,z1)),Math.max(score(x1,z0),score(x1,z1)))<=(double)reach*reach)return 1;
        return minimumScore(x0,z0,x1,z1)>(double)reach*reach?-1:0;
    }
    public double minimumScore(int x0,int z0,int x1,int z1){
        if(amount==0){double dx=Math.max(Math.max(x0-centerX,centerX-x1),0),dz=Math.max(Math.max(z0-centerZ,centerZ-z1),0);return dx*dx+dz*dz;}
        if(directionX==0||directionZ==0)return score(Math.max(x0,Math.min(x1,centerX)),Math.max(z0,Math.min(z1,centerZ)));
        double dx=Math.max(x0,Math.min(x1,centerX))-centerX,dz=Math.max(z0,Math.min(z1,centerZ))-centerZ;
        if(dx*directionX+dz*directionZ<=0){double side=1-.5*amount;return (dx*dx+dz*dz)/(side*side);}
        // Moving from any point toward the center strictly lowers the metric.
        // Only rectangle edges facing the center can contain its minimum.
        double minimum=Double.POSITIVE_INFINITY;
        if(centerX<x0)minimum=edge(x0,z0,x0,z1);else if(centerX>x1)minimum=edge(x1,z0,x1,z1);
        if(centerZ<z0)minimum=Math.min(minimum,edge(x0,z0,x1,z0));else if(centerZ>z1)minimum=Math.min(minimum,edge(x0,z1,x1,z1));
        return minimum==Double.POSITIVE_INFINITY?0:minimum;
    }
    private double edge(double x0,double z0,double x1,double z1){
        double dx=x0-centerX,dz=z0-centerZ,u=dx*directionX+dz*directionZ,w=-dx*directionZ+dz*directionX;
        double ex=x1-x0,ez=z1-z0,du=ex*directionX+ez*directionZ,dw=-ex*directionZ+ez*directionX;
        if(ex==0&&ez==0)return score(x0,z0);
        double t=Math.max(0,Math.min(1,-(dx*ex+dz*ez)/(ex*ex+ez*ez)));
        // Both halves have the same derivative at u=0. Its sign selects the half
        // containing the minimum, so a split-edge search is unnecessary.
        if(u+du*t>0){
            double side=1-.5*amount,front=1+.5*amount,ss=side*side,ff=front*front;
            t=Math.max(0,Math.min(1,-(u*du*ss+w*dw*ff)/(du*du*ss+dw*dw*ff)));
        }
        return score(x0+ex*t,z0+ez*t);
    }
    public int desired(DistanceBands bands,int x,int z){
        if(amount==0&&centerX==playerX&&centerZ==playerZ&&precisionScale==1)return bands.select(x,z,playerX,playerZ);
        int desired=bands.levels()[bands.levels().length-1];
        for(int i=0;i<bands.radii().length;i++){
            int size=1<<(bands.levels()[i]+2),bx=x&-size,bz=z&-size;
            if(relation(bx,bz,bx+size-1,bz+size-1,(int)Math.floor(bands.radii()[i]*precisionScale))>=0){desired=bands.levels()[i];break;}
        }
        return Math.max(desired,bands.select(x,z,playerX,playerZ));
    }
    public int minX(){return Math.max(playerX-limit,(int)Math.floor(centerX-radius*(1+.5*amount)));}
    public int maxX(){return Math.min(playerX+limit,(int)Math.ceil(centerX+radius*(1+.5*amount)));}
    public int minZ(){return Math.max(playerZ-limit,(int)Math.floor(centerZ-radius*(1+.5*amount)));}
    public int maxZ(){return Math.min(playerZ+limit,(int)Math.ceil(centerZ+radius*(1+.5*amount)));}
}
