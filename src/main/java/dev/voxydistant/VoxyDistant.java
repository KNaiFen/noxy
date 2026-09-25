package dev.voxydistant;

import dev.voxydistant.client.ClientEvents;
import dev.voxydistant.config.DistantConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(VoxyDistant.ID)
public final class VoxyDistant {
    public static final String ID = "voxy_distant";
    public VoxyDistant() {
        dev.voxydistant.config.ConfigLanguage.initialize();
        DistantConfig.migrate();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, DistantConfig.SPEC, "voxy_distant.toml");
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus().addListener((net.minecraftforge.fml.event.config.ModConfigEvent.Loading e)->{
            if(e.getConfig().getSpec()==DistantConfig.SPEC){DistantConfig.register(e.getConfig());DistantConfig.validate();if(DebugLog.enabled())DebugLog.log("CONFIG loaded verbose={} interval_seconds={}",DistantConfig.DEBUG_VERBOSE.get(),DistantConfig.DEBUG_INTERVAL.get());}
        });
        dev.voxydistant.network.Protocol.register();
        dev.voxydistant.server.RemoteServer.register();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientEvents.register());
    }
}
