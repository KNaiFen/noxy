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
}
