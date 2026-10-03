package dev.voxydistant.movement;

/** Main-thread input, background prediction shared by generation and reception. */
public final class MovementPrediction {
    public static final MovementPrediction CLIENT=new MovementPrediction();
    private static final java.util.concurrent.ThreadPoolExecutor SAMPLER=new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(1),task->{var thread=new Thread(task,"Voxy Distant movement");thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);return thread;},new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    public record Settings(boolean enabled,double start,double maximum,double seconds){}
    public record Snapshot(int x,int z,double centerX,double centerZ,double directionX,double directionZ,double amount,
                           double speed,long revision,boolean reset,Object world){
        public RequestShape shape(int radius,int limit){return new RequestShape(x,z,centerX,centerZ,directionX,directionZ,amount,radius,limit);}
    }
    private Object world;
    private boolean wasPaused;
    private Settings settings;
    private double lastX,lastZ,directionX=1,directionZ;
    private final double[] history=new double[6];
    private int slot,moving,ticks,inputTicks;
    private long revision;
    private volatile Snapshot snapshot=new Snapshot(0,0,0,0,1,0,0,0,0,true,null);
    private Snapshot published;
    public Snapshot snapshot(){return snapshot;}
    public void capture(Object world,double x,double z,boolean paused,Settings settings){
        int tick=++inputTicks;
        SAMPLER.execute(()->sample(world,x,z,paused,settings,tick));
    }
    public void sample(Object nextWorld,double x,double z,boolean paused,Settings nextSettings){
        sample(nextWorld,x,z,paused,nextSettings,ticks+1);
    }
    private void sample(Object nextWorld,double x,double z,boolean paused,Settings nextSettings,int tick){
        int cx=(int)Math.floor(x/16),cz=(int)Math.floor(z/16);
        double dx=x-lastX,dz=z-lastZ;
        boolean reset=world!=nextWorld||paused!=wasPaused||Math.abs(dx)>512||Math.abs(dz)>512;
        boolean changed=!nextSettings.equals(settings);
        if(reset||changed){java.util.Arrays.fill(history,0);slot=0;moving=0;directionX=1;directionZ=0;}
        int elapsed=tick-ticks;double perSecond=20.0/elapsed;int previousTick=ticks;
        world=nextWorld;wasPaused=paused;settings=nextSettings;lastX=x;lastZ=z;ticks=tick;
        double speed=reset?0:Math.hypot(dx,dz)*perSecond;
        if(!nextSettings.enabled||reset||paused){dx=0;dz=0;speed=0;}
        if(speed>0){directionX=dx*perSecond/speed;directionZ=dz*perSecond/speed;}
        moving=speed>nextSettings.start?moving+elapsed:0;
        for(int i=0;i<Math.min(elapsed,history.length);i++)history[slot++%6]=moving>=6?Math.min(1,(speed-nextSettings.start)/(nextSettings.maximum-nextSettings.start)):0;
        double amount=0;for(double value:history)amount+=value/6;
        double centerX=nextSettings.enabled?x/16+dx*perSecond*nextSettings.seconds/16:cx;
        double centerZ=nextSettings.enabled?z/16+dz*perSecond*nextSettings.seconds/16:cz;
        if(reset||changed||ticks/5!=previousTick/5){
            if(reset||changed)revision++;
            if(published==null||published.x!=cx||published.z!=cz||published.centerX!=centerX||published.centerZ!=centerZ||published.directionX!=directionX||published.directionZ!=directionZ||published.amount!=amount)revision++;
            published=new Snapshot(cx,cz,centerX,centerZ,directionX,directionZ,amount,speed,revision,reset||changed,nextWorld);
        }
        snapshot=new Snapshot(cx,cz,centerX,centerZ,directionX,directionZ,amount,speed,revision,reset||changed,nextWorld);
    }
}
