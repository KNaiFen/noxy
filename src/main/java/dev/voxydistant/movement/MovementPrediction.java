package dev.voxydistant.movement;

/** One client-tick sample shared by local generation and remote reception. */
public final class MovementPrediction {
    public static final MovementPrediction CLIENT=new MovementPrediction();
    public record Settings(boolean enabled,double start,double maximum,double seconds){}
    public record Snapshot(int x,int z,double centerX,double centerZ,double directionX,double directionZ,double amount,
                           double speed,long revision,boolean reset){
        public RequestShape shape(int radius,int limit){return new RequestShape(x,z,centerX,centerZ,directionX,directionZ,amount,radius,limit);}
    }
    private Object world;
    private boolean wasPaused;
    private Settings settings;
    private double lastX,lastZ,directionX=1,directionZ;
    private final double[] history=new double[6];
    private int slot,moving,ticks;
    private long revision;
    private Snapshot snapshot=new Snapshot(0,0,0,0,1,0,0,0,0,true);
    private Snapshot published;
    public Snapshot snapshot(){return snapshot;}
    public void sample(Object nextWorld,double x,double z,boolean paused,Settings nextSettings){
        int cx=(int)Math.floor(x/16),cz=(int)Math.floor(z/16);
        double dx=x-lastX,dz=z-lastZ;
        boolean reset=world!=nextWorld||paused!=wasPaused||Math.abs(dx)>512||Math.abs(dz)>512;
        boolean changed=!nextSettings.equals(settings);
        if(reset||changed){java.util.Arrays.fill(history,0);slot=0;moving=0;directionX=1;directionZ=0;}
        world=nextWorld;wasPaused=paused;settings=nextSettings;lastX=x;lastZ=z;ticks++;
        double speed=reset?0:Math.hypot(dx,dz)*20;
        if(!nextSettings.enabled||reset||paused){dx=0;dz=0;speed=0;}
        if(speed>0){directionX=dx*20/speed;directionZ=dz*20/speed;}
        moving=speed>nextSettings.start?moving+1:0;
        history[slot++%6]=moving>=6?Math.min(1,(speed-nextSettings.start)/(nextSettings.maximum-nextSettings.start)):0;
        double amount=0;for(double value:history)amount+=value/6;
        double centerX=nextSettings.enabled?x/16+dx*20*nextSettings.seconds/16:cx;
        double centerZ=nextSettings.enabled?z/16+dz*20*nextSettings.seconds/16:cz;
        if(reset||changed||ticks%5==0){
            if(reset||changed)revision++;
            if(published==null||published.x!=cx||published.z!=cz||published.centerX!=centerX||published.centerZ!=centerZ||published.directionX!=directionX||published.directionZ!=directionZ||published.amount!=amount)revision++;
            published=new Snapshot(cx,cz,centerX,centerZ,directionX,directionZ,amount,speed,revision,reset||changed);
        }
        snapshot=new Snapshot(cx,cz,centerX,centerZ,directionX,directionZ,amount,speed,revision,reset||changed);
    }
}
