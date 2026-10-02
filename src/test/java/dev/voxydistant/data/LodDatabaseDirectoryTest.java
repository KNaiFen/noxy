package dev.voxydistant.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LodDatabaseDirectoryTest {
    @TempDir Path directory;
    private static byte[] key(int x,int z){return LodDatabase.key("minecraft:overworld",(x&0xffffffffL)|((long)z<<32),1);}
    private static byte[] body(long version){
        byte[] raw=ByteBuffer.allocate(25).putInt(1).putInt(0).putInt(0).putInt(-4).putLong(version).put((byte)31).array();
        return LodDatabase.pack(ColumnCodec.compress(raw,1));
    }
    @Test void metadataSurvivesRestartAndRejectsStaleWrites(){
        try(var db=new LodDatabase(directory,8L<<20)){
            assertTrue(db.storeColumn(key(-1,-1),body(10),10,31));
            byte[] invalid=LodDatabase.key("minecraft:overworld",-1L,2);
            db.invalidationsAndMissing(Map.of(invalid,ByteBuffer.allocate(8).putLong(11).array()),List.of());
            assertFalse(db.metadata(key(-1,-1)).current());
            assertFalse(db.storeColumn(key(-1,-1),body(10),10,31));
            db.importColumn(key(-1,-1),body(12),12,31);
            // A metadata hit must not inspect the terrain body, even if it is unreadable.
            db.put(key(-1,-1),new byte[]{99});
            assertEquals(12,db.metadata(key(-1,-1)).version());
            assertEquals(1,db.cacheStats().columns());
        }
        try(var db=new LodDatabase(directory,8L<<20)){
            assertEquals(1,db.cacheStats().columns());
            assertEquals(12,db.metadata(key(-1,-1)).version());
        }
    }
    @Test void legacyRegionAndCountingResumeWithoutLosingConcurrentInserts()throws Exception{
        RocksDB.loadLibrary();
        try(var options=new Options().setCreateIfMissing(true);var db=RocksDB.open(options,directory.toString())){
            for(int i=0;i<1300;i++)db.put(key(i,0),body(7));
        }
        try(var db=new LodDatabase(directory,8L<<20)){
            assertEquals(-1,db.cacheStats().columns());
            assertFalse(db.countCachedColumnsStep());
            db.storeColumn(key(-2,0),body(8),8,31); // Ahead of the cursor in RocksDB byte order.
            db.storeColumn(key(2,-1),body(8),8,31);
            assertFalse(db.prepareRegion("minecraft:overworld",0,0));
        }
        try(var db=new LodDatabase(directory,8L<<20)){
            while(!db.countCachedColumnsStep()){}
            assertEquals(1302,db.cacheStats().columns());
            for(int i=0;i<15;i++)db.prepareRegion("minecraft:overworld",0,0);
            assertTrue(db.prepareRegion("minecraft:overworld",0,0));
            var region=db.region("minecraft:overworld",0,0);
            assertEquals(7,region[0].version());assertEquals(31,region[0].mask());
            assertEquals(0,region[32].mask());
        }
    }
    @Test void startupIndexResumesAndSkipsTerrainAfterCompletion()throws Exception{
        RocksDB.loadLibrary();
        try(var options=new Options().setCreateIfMissing(true);var db=RocksDB.open(options,directory.toString())){
            for(int i=0;i<130;i++)db.put(key(i,0),body(7));
            db.put(LodDatabase.key("minecraft:the_nether",-1L,1),body(9));
        }
        try(var db=new LodDatabase(directory,8L<<20)){
            assertFalse(db.rebuildIndexStep().complete());
            db.storeColumn(key(0,-1),body(8),8,31); // A new key after the current cursor.
            db.storeColumn(LodDatabase.key("minecraft:aaa",0,1),body(6),6,31); // Behind the persisted cursor.
            db.storeColumn(key(0,0),body(8),8,31);
        }
        try(var db=new LodDatabase(directory,8L<<20)){
            var step=db.rebuildIndexStep();while(!step.complete())step=db.rebuildIndexStep();
            assertEquals(133,step.columns());assertEquals(133,db.cacheStats().columns());
            assertTrue(db.indexComplete());assertTrue(db.prepareRegion("minecraft:overworld",0,0));
            assertEquals(8,db.region("minecraft:overworld",0,0)[0].version());
            assertEquals(0,db.region("minecraft:overworld",100,100)[0].mask());
            assertEquals(9,db.metadata(LodDatabase.key("minecraft:the_nether",-1L,1)).version());
            db.put(key(0,0),new byte[]{99}); // Completed startup must not parse terrain again.
        }
        try(var db=new LodDatabase(directory,8L<<20)){
            assertTrue(db.indexComplete());assertEquals(133,db.rebuildIndexStep().columns());
            assertEquals(8,db.metadata(key(0,0)).version());
            db.storeColumn(key(-1,-1),body(10),10,31);assertEquals(134,db.cacheStats().columns());
            assertEquals(10,db.region("minecraft:overworld",-1,-1)[1023].version());
        }
    }
    @Test void sixReadersAllowLiveWritesAndKeepAccurateCount()throws Exception{
        RocksDB.loadLibrary();
        try(var options=new Options().setCreateIfMissing(true);var raw=RocksDB.open(options,directory.toString())){
            for(int i=0;i<600;i++)raw.put(key(i*2,0),body(7));
        }
        var entered=new java.util.concurrent.CountDownLatch(6);var release=new java.util.concurrent.CountDownLatch(1);
        var decoded=new java.util.concurrent.CountDownLatch(5);var last=new java.util.concurrent.CountDownLatch(1);var order=new java.util.concurrent.atomic.AtomicInteger();
        var readers=new java.util.concurrent.ThreadPoolExecutor(6,6,0,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.LinkedBlockingQueue<>()){
            @Override protected void beforeExecute(Thread t,Runnable r){int lane=order.getAndIncrement();entered.countDown();try{if(lane==5)last.await();else release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
            @Override protected void afterExecute(Runnable r,Throwable e){decoded.countDown();}
        };
        try(var db=new LodDatabase(directory,8L<<20)){
            var pending=java.util.concurrent.CompletableFuture.supplyAsync(()->db.rebuildIndexStep(readers));
            try{
                assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS),"six tasks must run concurrently");
                db.storeColumn(key(0,0),body(10),10,31);
                db.storeColumn(key(1,0),body(9),9,31); // In the selected key range, absent from its snapshot.
                db.storeColumn(LodDatabase.key("minecraft:aaa",0,1),body(8),8,31);
                db.invalidationsAndMissing(Map.of(LodDatabase.key("minecraft:overworld",2L,2),ByteBuffer.allocate(8).putLong(11).array()),List.of());
            }finally{release.countDown();}
            try{
                assertTrue(decoded.await(10,java.util.concurrent.TimeUnit.SECONDS));
                db.storeColumn(key(0,0),body(12),12,31); // Readers have parsed old snapshots; commit must retain this update.
                db.invalidationsAndMissing(Map.of(LodDatabase.key("minecraft:overworld",2L,2),ByteBuffer.allocate(8).putLong(13).array()),List.of());
            }finally{last.countDown();}
            assertFalse(pending.get(10,java.util.concurrent.TimeUnit.SECONDS).complete());
            while(!db.rebuildIndexStep(readers).complete()){}
            assertEquals(602,db.cacheStats().columns());assertEquals(12,db.metadata(key(0,0)).version());assertEquals(13,db.metadata(key(2,0)).invalid());assertFalse(db.metadata(key(2,0)).current());
        }finally{release.countDown();last.countDown();readers.shutdown();assertTrue(readers.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
    }
}
