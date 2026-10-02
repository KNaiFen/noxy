package dev.voxydistant.smoke.mixin;

import dev.voxydistant.data.*;
import dev.voxydistant.smoke.LodMetrics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=ColumnCodec.class,remap=false)
public class CodecMetricsMixin {
    private static final ThreadLocal<Boolean> ENCODING=ThreadLocal.withInitial(()->false),DECODING=ThreadLocal.withInitial(()->false);
    @Inject(method="encode(Ldev/voxydistant/data/LodColumn;II)Ldev/voxydistant/data/ColumnCodec$Encoded;",at=@At("HEAD"))
    private static void encodeStart(LodColumn column,int mask,int compression,CallbackInfoReturnable<ColumnCodec.Encoded> ci){ENCODING.set(true);LodMetrics.begin(0);}
    @Inject(method="encode(Ldev/voxydistant/data/LodColumn;II)Ldev/voxydistant/data/ColumnCodec$Encoded;",at=@At("RETURN"))
    private static void encodeEnd(LodColumn column,int mask,int compression,CallbackInfoReturnable<ColumnCodec.Encoded> ci){var e=ci.getReturnValue();LodMetrics.end(0,mask==31?5:Integer.numberOfTrailingZeros(mask),e.bytes().length,e.rawLength());ENCODING.set(false);}
    @Inject(method="decodeLevels",at=@At("HEAD"))
    private static void decodeStart(ColumnCodec.Encoded encoded,int mask,CallbackInfoReturnable<LodColumn> ci){DECODING.set(true);LodMetrics.begin(1);}
    @Inject(method="decodeLevels",at=@At("RETURN"))
    private static void decodeEnd(ColumnCodec.Encoded encoded,int mask,CallbackInfoReturnable<LodColumn> ci){var c=ci.getReturnValue();LodMetrics.end(1,c.mask()==31?5:c.minimumLevel(),encoded.bytes().length,encoded.rawLength());DECODING.set(false);}
    @Inject(method="encodeRaw",at=@At("HEAD"))
    private static void rawEncodeStart(LodColumn c,int mask,CallbackInfoReturnable<byte[]> ci){if(!ENCODING.get())LodMetrics.begin(0);}
    @Inject(method="encodeRaw",at=@At("RETURN"))
    private static void rawEncodeEnd(LodColumn c,int mask,CallbackInfoReturnable<byte[]> ci){if(!ENCODING.get())LodMetrics.end(0,Integer.numberOfTrailingZeros(mask),0,ci.getReturnValue().length);}
    @Inject(method="decodeRaw",at=@At("HEAD"))
    private static void rawDecodeStart(byte[] raw,int mask,CallbackInfoReturnable<LodColumn> ci){if(!DECODING.get())LodMetrics.begin(1);}
    @Inject(method="decodeRaw",at=@At("RETURN"))
    private static void rawDecodeEnd(byte[] raw,int mask,CallbackInfoReturnable<LodColumn> ci){if(!DECODING.get())LodMetrics.end(1,ci.getReturnValue().minimumLevel(),0,raw.length);}
    @Inject(method="uncompress",at=@At("HEAD"))
    private static void uncompressStart(ColumnCodec.Encoded e,CallbackInfoReturnable<byte[]> ci){if(!DECODING.get())LodMetrics.begin(1);}
    @Inject(method="uncompress",at=@At("RETURN"))
    private static void uncompressEnd(ColumnCodec.Encoded e,CallbackInfoReturnable<byte[]> ci){if(!DECODING.get())LodMetrics.end(1,-1,e.bytes().length,e.rawLength());}
}
