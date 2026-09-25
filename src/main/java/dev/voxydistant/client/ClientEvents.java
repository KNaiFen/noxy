package dev.voxydistant.client;

import dev.voxydistant.generation.GenerationController;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModLoadingContext;

public final class ClientEvents {
    public static void register() {
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new DistantScreen(parent)));
        var bus = MinecraftForge.EVENT_BUS;
        bus.addListener(ClientEvents::clientTick);
        bus.addListener(ClientEvents::serverTick);
        bus.addListener(ClientEvents::serverStopping);
        bus.addListener(ClientEvents::screen);
        bus.addListener(ClientEvents::unload);
    }
    private static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) { RemoteClient.tick(); GenerationController.clientTick(); }
    }
    private static void unload(net.minecraftforge.event.level.ChunkEvent.Unload event) {
        if(event.getLevel().isClientSide()&&event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk)RemoteClient.unload(chunk);
    }
    private static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) GenerationController.serverTick(event.getServer());
    }
    private static void serverStopping(ServerStoppingEvent event) { GenerationController.serverStopping(event.getServer()); }
    private static void screen(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof OptionsScreen parent) {
            // Match DH's 20×20 button position exactly.
            var button = Button.builder(Component.literal("VD"), pressed ->
                    net.minecraft.client.Minecraft.getInstance().setScreen(new DistantScreen(parent)))
                    .bounds(parent.width / 2 - 180, parent.height / 6 - 12, 20, 20).build();
            button.setTooltip(Tooltip.create(Component.literal("Voxy Distant 设置")));
            event.addListener(button);
        }
    }
    private ClientEvents() {}
}
