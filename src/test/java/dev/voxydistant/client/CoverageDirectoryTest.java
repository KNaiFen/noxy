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
}
