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
}
