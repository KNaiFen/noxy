package dev.voxydistant.client;

import dev.voxydistant.movement.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovementPredictionTest {
    @Test void activationStopAndActualVelocity(){
        var tracker=new MovementPrediction();var world=new Object();var config=new MovementPrediction.Settings(true,2.15,22,.5);
        tracker.sample(world,0,0,false,config);
        for(int i=1;i<=5;i++){tracker.sample(world,i*1.1,0,false,config);assertEquals(0,tracker.snapshot().amount());}
        for(int i=6;i<=15;i++)tracker.sample(world,i*1.1,0,false,config);
        assertEquals(1,tracker.snapshot().amount(),1e-9);
        double x=16.5;
        for(int i=0;i<4;i++)tracker.sample(world,x+=2.2,0,false,config);
        assertEquals((x+22)/16,tracker.snapshot().centerX(),1e-9);
        for(int i=0;i<6;i++)tracker.sample(world,x,0,false,config);
        assertEquals(0,tracker.snapshot().amount());
        tracker.sample(new Object(),2000,0,false,config);assertEquals(0,tracker.snapshot().speed());
    }
    @Test void directionalFootprintAndRectangleClassification(){
        for(int heading=0;heading<8;heading++){
            double angle=heading*Math.PI/4;var shape=new RequestShape(-8,-7,-7.5,-6.5,Math.cos(angle),Math.sin(angle),1,32,96);
            assertTrue(shape.score(shape.centerX()+48*shape.directionX(),shape.centerZ()+48*shape.directionZ())<=1024.00001);
            for(int x=-64;x<48;x+=8)for(int z=-64;z<48;z+=8){
                int relation=shape.relation(x,z,x+7,z+7);boolean all=true,any=false;
                for(int px=x;px<x+8;px++)for(int pz=z;pz<z+8;pz++){boolean inside=shape.contains(px,pz);all&=inside;any|=inside;}
                if(relation==1)assertTrue(all);if(relation==-1)assertFalse(any);
            }
        }
        var clipped=new RequestShape(0,0,1,0,1,0,1,32,32);assertFalse(clipped.contains(33,0));assertTrue(clipped.contains(32,0));
    }
    @Test void pauseAndDisableClearMotionWithoutRepeatedRangePublication(){
        var tracker=new MovementPrediction();var world=new Object();var config=new MovementPrediction.Settings(true,2.15,22,.5);
        tracker.sample(world,0,0,false,config);
        for(int i=1;i<=15;i++)tracker.sample(world,i*1.1,0,false,config);
        tracker.sample(world,16.5,0,true,config);assertEquals(0,tracker.snapshot().amount());
        long revision=tracker.snapshot().revision();
        for(int i=0;i<20;i++)tracker.sample(world,16.5,0,true,config);
        assertEquals(revision,tracker.snapshot().revision());
        tracker.sample(world,100,0,false,config);assertEquals(0,tracker.snapshot().speed());
        tracker.sample(world,101.1,0,false,new MovementPrediction.Settings(false,2.15,22,.5));
        assertEquals(0,tracker.snapshot().amount());assertEquals(6,tracker.snapshot().centerX());
    }
}
