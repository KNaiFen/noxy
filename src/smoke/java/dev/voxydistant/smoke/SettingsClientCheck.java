package dev.voxydistant.smoke;

import com.google.gson.*;
import dev.voxydistant.client.*;
import dev.voxydistant.config.ServerSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import java.lang.reflect.*;

/** Drives actual screen widgets; never calls the server handler directly. */
final class SettingsClientCheck {
    static void command(JsonObject request)throws ReflectiveOperationException {
        var mc=Minecraft.getInstance();String action=request.get("command").getAsString();
        if(action.equals("settings-open")){
            mc.setScreen(new DistantScreen(null));click("本地生成 → 接收");click("接收 → 服务端");return;
        }
        if(action.equals("settings-close")){mc.screen.onClose();mc.setScreen(null);return;}
        if(action.equals("settings-refresh")){click("重新读取");return;}
        if(action.equals("settings-save")){click("保存并应用");return;}
        if(action.equals("settings-set")){
            String key=request.get("key").getAsString(),value=request.get("value").getAsString();
            int index=-1;for(int i=0;i<ServerSettings.FIELDS.size();i++)if(ServerSettings.FIELDS.get(i).key().equals(key))index=i;
            if(index<0)throw new IllegalArgumentException(key);
            var screen=(ServerConfigScreen)mc.screen;var scroll=ServerConfigScreen.class.getDeclaredField("scroll");scroll.setAccessible(true);scroll.setInt(screen,index);
            var rebuild=ServerConfigScreen.class.getDeclaredMethod("rebuild");rebuild.setAccessible(true);rebuild.invoke(screen);
            var widget=(AbstractWidget)screen.children().getFirst();
            if(widget instanceof EditBox box)box.setValue(value);
            else {
                for(int tries=0;tries<6&&!draft(screen).get(index).equals(value);tries++)((Button)widget).onPress();
                if(!draft(screen).get(index).equals(value))throw new IllegalArgumentException("Cannot choose "+value);
            }
            return;
        }
        if(action.equals("settings-stale-reply")){
            var screen=(ServerConfigScreen)mc.screen;var id=ServerConfigScreen.class.getDeclaredField("request");id.setAccessible(true);var pending=ServerConfigScreen.class.getDeclaredField("pending");pending.setAccessible(true);pending.setBoolean(screen,true);
            ServerConfigScreen.result(new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.CLIENTBOUND),new dev.voxydistant.network.Protocol.ConfigResult(id.getLong(screen),99,true,"",ServerSettings.current().values()));
            if(!pending.getBoolean(screen))throw new AssertionError("old connection response accepted");pending.setBoolean(screen,false);return;
        }
        throw new IllegalArgumentException(action);
    }
    @SuppressWarnings("unchecked") private static java.util.List<String> draft(ServerConfigScreen screen)throws ReflectiveOperationException{var f=ServerConfigScreen.class.getDeclaredField("draft");f.setAccessible(true);return (java.util.List<String>)f.get(screen);}
    private static void click(String label){
        var button=Minecraft.getInstance().screen.children().stream().filter(w->w instanceof Button b&&b.getMessage().getString().equals(label)).map(w->(Button)w).findFirst().orElseThrow();
        if(!button.active)throw new IllegalStateException("Button disabled: "+label);button.onPress();
    }
    static void state(JsonObject state){
        if(!(Minecraft.getInstance().screen instanceof ServerConfigScreen screen))return;
        try{
            for(String name:new String[]{"pending","loaded","message","revision"}){var f=ServerConfigScreen.class.getDeclaredField(name);f.setAccessible(true);state.add("settings_"+name,new Gson().toJsonTree(f.get(screen)));}
            var values=new JsonObject();var draft=draft(screen);for(int i=0;i<draft.size();i++)values.addProperty(ServerSettings.FIELDS.get(i).key(),draft.get(i));state.add("settings_values",values);
        }catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}
    }
}
