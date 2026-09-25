package dev.voxydistant.data;

import java.nio.ByteBuffer;
import java.util.List;

/** Length-delimited original column encodings, compressed as one frame. */
public final class BatchCodec {
    public static final int MAX_COLUMNS=128;
    public static ColumnCodec.Encoded encode(List<byte[]> columns,int compressionLevel) {
        ColumnCodec.bounded(columns.size(),1,MAX_COLUMNS);
        int length=4;for(byte[] column:columns)length=Math.addExact(length,4+column.length);
        ColumnCodec.bounded(length,1,ColumnCodec.MAX_BYTES);
        var buffer=ByteBuffer.allocate(length);buffer.putInt(columns.size());
        for(byte[] column:columns)buffer.putInt(column.length).put(column);
        return ColumnCodec.compress(buffer.array(),compressionLevel);
    }
    private BatchCodec() {}
}
