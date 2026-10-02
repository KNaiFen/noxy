package dev.voxydistant.client;

import dev.voxydistant.compat.CoverageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CoverageDirectoryTest {
    @TempDir Path directory;
    @Test void persistedDirectoryCoversHeightWithoutLoadingRenderPages(){
        Path file=directory.resolve("coverage.bin");
        var cache=new CoverageStore(file);cache.remote(-4,40,256);
        for(int y=-4;y<40;y++)cache.received(-1,y,-1,10,2,true);
        cache.received(-2,0,-1,9,0,false);
        cache.received(-1,1,-2,11,1,false);
        cache.saveAfterWorldClosed();
        cache=new CoverageStore(file);cache.remote(-4,40,256);
        var d=cache.directory(-1,-1,false);
        assertEquals(new CoverageStore.Stamp(10,2),d.column(1023));
        assertEquals(5,d.column(1022).level());
        assertEquals(5,d.column(991).level());
        assertEquals(0,cache.usage().pages());
        cache.closeUnconfirmed();
    }
    @Test void unconfirmedWriteCannotReusePersistedDirectory(){
        Path file=directory.resolve("coverage.bin");
        var cache=new CoverageStore(file);cache.remote(0,2,256);
        for(int y=0;y<2;y++)cache.received(0,y,0,10,0,false);
        cache.saveAfterWorldClosed();
        cache=new CoverageStore(file);cache.remote(0,2,256);
        cache.restrict(0,0,0,2,11);
        cache.received(0,0,0,11,0,false);
        cache.closeUnconfirmed();
        var startup=CoverageStore.openDatabase(file.resolveSibling("coverage.bin.rocksdb")).join();while(!CoverageStore.rebuildDirectoriesStep(startup).complete()){}CoverageStore.closeDatabase(file.resolveSibling("coverage.bin.rocksdb"));
        cache=new CoverageStore(file);cache.remote(0,2,256);
        assertEquals(5,cache.directory(0,0,false).column(0).level());
        assertEquals(0,cache.usage().pages());
        cache.closeUnconfirmed();
    }
    @Test void legacyPagesMigrateOneAtATimeWithoutRebuildingRenderCounters()throws Exception{
        Path file=directory.resolve("legacy.bin");var cache=new CoverageStore(file);cache.remote(-4,40,256);
        for(int y=-4;y<40;y++)cache.received(0,y,0,12,3,true);cache.saveAfterWorldClosed();
        try(var options=new org.rocksdb.Options();var db=org.rocksdb.RocksDB.open(options,file.resolveSibling("legacy.bin.rocksdb").toString())){
            for(int py=-1;py<=1;py++)db.delete(java.nio.ByteBuffer.allocate(9).put((byte)4).putLong(CoverageStore.node(4,0,py,0)).array());
        }
        cache=new CoverageStore(file);cache.remote(-4,40,256);assertNull(cache.directory(0,0,false));
        for(int i=0;i<3;i++)assertNull(cache.directory(0,0,true));
        assertEquals(new CoverageStore.Stamp(12,3),cache.directory(0,0,true).column(0));
        assertEquals(3,cache.directoryUsage().migrations());assertEquals(0,cache.usage().pages());cache.closeUnconfirmed();
    }
    @Test void startupMigrationNeedsNoWorldHeightAndSharesDatabaseWithLiveWorld()throws Exception{
        Path file=directory.resolve("startup.bin"),dbPath=file.resolveSibling("startup.bin.rocksdb");
        var cache=new CoverageStore(file);cache.remote(-4,40,256);
        for(int y=-4;y<40;y++)cache.received(-1,y,-1,12,2,true);
        cache.received(-2,0,-1,13,0,false);cache.saveAfterWorldClosed();
        try(var options=new org.rocksdb.Options();var db=org.rocksdb.RocksDB.open(options,dbPath.toString())){
            for(int py=-1;py<=1;py++)db.delete(java.nio.ByteBuffer.allocate(9).put((byte)4).putLong(CoverageStore.node(4,-1,py,-1)).array());
        }
        var database=CoverageStore.openDatabase(dbPath).join();
        assertFalse(CoverageStore.rebuildDirectoriesStep(database).complete());CoverageStore.closeDatabase(dbPath);
        database=CoverageStore.openDatabase(dbPath).join();
        var progress=CoverageStore.rebuildDirectoriesStep(database);while(!progress.complete())progress=CoverageStore.rebuildDirectoriesStep(database);
        assertEquals(3,progress.pages());assertTrue(CoverageStore.directoriesComplete(database));
        cache=new CoverageStore(file);cache.remote(-4,40,256);var d=cache.directory(-1,-1,false);
        assertNotNull(d);assertEquals(new CoverageStore.Stamp(12,2),d.column(1023));assertEquals(5,d.column(1022).level());
        cache.remote(0,2,256);assertEquals(new CoverageStore.Stamp(12,2),cache.directory(-1,-1,false).column(1023));
        assertEquals(0,cache.directoryUsage().migrations());assertEquals(0,cache.usage().pages());
        CoverageStore.closeDatabase(dbPath); // The live world still owns its reference.
        assertEquals(new CoverageStore.Stamp(12,2),cache.directory(-1,-1,false).column(1023));cache.closeUnconfirmed();
        database=CoverageStore.openDatabase(dbPath).join();assertEquals(3,CoverageStore.rebuildDirectoriesStep(database).pages());CoverageStore.closeDatabase(dbPath);
    }
    @Test void clientStartupFindsServerAndSingleplayerCachesWithoutJoiningAWorld()throws Exception{
        var paths=java.util.List.of(directory.resolve(".voxy/saves/example/distant-coverage/world.bin"),directory.resolve("saves/local/voxy/distant-coverage/world.bin"));
        for(Path file:paths){var cache=new CoverageStore(file);cache.remote(0,1,256);cache.received(0,0,0,10,0,false);cache.saveAfterWorldClosed();}
        CacheIndexStartup.start(directory).get(10,java.util.concurrent.TimeUnit.SECONDS);
        for(Path file:paths){var path=file.resolveSibling(file.getFileName()+".rocksdb");var db=CoverageStore.openDatabase(path).join();
            assertTrue(CoverageStore.directoriesComplete(db));CoverageStore.closeDatabase(path);
        }
        CacheIndexStartup.start(directory).get(10,java.util.concurrent.TimeUnit.SECONDS);
        for(Path file:paths){var cache=new CoverageStore(file);cache.remote(0,1,256);assertEquals(new CoverageStore.Stamp(10,0),cache.directory(0,0,false).column(0));cache.closeUnconfirmed();}
    }
    @Test void sixPageReadersDoNotBlockConfirmedLiveSave()throws Exception{
        Path file=directory.resolve("parallel.bin"),dbPath=file.resolveSibling("parallel.bin.rocksdb");
        var cache=new CoverageStore(file);cache.remote(0,1,256);
        for(int i=0;i<6;i++)cache.received(i*32,0,0,7,2,false);cache.saveAfterWorldClosed();
        try(var options=new org.rocksdb.Options();var raw=org.rocksdb.RocksDB.open(options,dbPath.toString())){
            for(int i=0;i<6;i++)raw.delete(java.nio.ByteBuffer.allocate(9).put((byte)4).putLong(CoverageStore.node(4,i,0,0)).array());
        }
        var entered=new java.util.concurrent.CountDownLatch(6);var release=new java.util.concurrent.CountDownLatch(1);
        var decoded=new java.util.concurrent.CountDownLatch(5);var last=new java.util.concurrent.CountDownLatch(1);var order=new java.util.concurrent.atomic.AtomicInteger();
        var readers=new java.util.concurrent.ThreadPoolExecutor(6,6,0,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.LinkedBlockingQueue<>()){
            @Override protected void beforeExecute(Thread t,Runnable r){int lane=order.getAndIncrement();entered.countDown();try{if(lane==5)last.await();else release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
            @Override protected void afterExecute(Runnable r,Throwable e){decoded.countDown();}
        };
        var db=CoverageStore.openDatabase(dbPath).join();
        try{
            var pending=java.util.concurrent.CompletableFuture.supplyAsync(()->CoverageStore.rebuildDirectoriesStep(db,readers));
            try{
                assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
                cache=new CoverageStore(file);cache.remote(0,1,256);cache.received(0,0,0,10,0,true);cache.saveAfterWorldClosed();
            }finally{release.countDown();}
            try{
                assertTrue(decoded.await(10,java.util.concurrent.TimeUnit.SECONDS));
                cache=new CoverageStore(file);cache.remote(0,1,256);cache.received(32,0,0,12,0,true);cache.saveAfterWorldClosed();
            }finally{last.countDown();}
            assertTrue(pending.get(10,java.util.concurrent.TimeUnit.SECONDS).complete());
            cache=new CoverageStore(file);cache.remote(0,1,256);assertEquals(new CoverageStore.Stamp(10,0),cache.directory(0,0,false).column(0));assertEquals(new CoverageStore.Stamp(12,0),cache.directory(1,0,false).column(0));assertEquals(0,cache.usage().pages());cache.closeUnconfirmed();
        }finally{release.countDown();last.countDown();readers.shutdown();assertTrue(readers.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));CoverageStore.closeDatabase(dbPath);}
    }
}
