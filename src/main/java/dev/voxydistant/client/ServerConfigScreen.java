package dev.voxydistant.client;

import dev.voxydistant.config.*;
import dev.voxydistant.network.Protocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;
import java.util.*;

/** Per-screen draft; no remote value is written into this client's config. */
public final class ServerConfigScreen extends Screen {
    private static long nextRequest;
    private final Screen parent;
    private final Connection connection;
    private final List<String> draft = new ArrayList<>();
    private final List<String> groups = ServerSettings.FIELDS.stream().map(ServerSettings.Field::group).distinct().toList();
    private long request, revision;
    private int scroll, group = -1;
    private boolean pending, loaded;
    private String message = "";

    public ServerConfigScreen(Screen parent) {
        super(Component.literal("Voxy Distant · 服务端设置")); this.parent = parent;
        var client = Minecraft.getInstance().getConnection(); connection = client == null ? null : client.getConnection();
    }

    @Override protected void init() {
        if (!loaded && !pending) {
            if (connection == null) { draft.addAll(ServerSettings.current().values()); loaded = true; }
            else if (!Protocol.CHANNEL.isRemotePresent(connection)) message = "服务器未安装兼容的 Voxy Distant";
            else read();
        }
        rebuild();
    }

    private int visibleRows() { return Math.max(1, (height - 123) / 31); }
    private int contentWidth() { return Math.min(460, width - 24); }
    private List<Integer> indices() {
        var indices = new ArrayList<Integer>();
        for (int i = 0; i < ServerSettings.FIELDS.size(); i++)
            if (ServerSettings.FIELDS.get(i).group().equals(groups.get(group))) indices.add(i);
        return indices;
    }

    private void rebuild() {
        clearWidgets(); int span = contentWidth(), left = (width - span) / 2;
        if (group < 0) {
            for (int i = 0; i < groups.size(); i++) {
                int selected = i;
                addRenderableWidget(Button.builder(Component.literal(groups.get(i) + "  ›"), button -> {
                    group = selected; scroll = 0; rebuild();
                }).bounds(left + (i % 2) * (span / 2 + 4), 48 + (i / 2) * 29, span / 2 - 4, 20).build());
            }
        } else if (loaded) {
            var indices = indices(); int labelWidth = span * 3 / 5;
            for (int row = 0; row < visibleRows() && scroll + row < indices.size(); row++) {
                int index = indices.get(scroll + row), y = 48 + row * 31;
                var field = ServerSettings.FIELDS.get(index); AbstractWidget widget;
                if (field.value() instanceof ForgeConfigSpec.BooleanValue || field.value() == DistantConfig.SERVER_PRESET
                        || field.value() == DistantConfig.CONFIG_LANGUAGE) {
                    widget = Button.builder(valueText(field, draft.get(index)), button -> {
                        if (field.value() == DistantConfig.SERVER_PRESET) {
                            var presets = DistantConfig.Preset.values();
                            draft.set(index, presets[(DistantConfig.Preset.valueOf(draft.get(index)).ordinal() + 1) % presets.length].name());
                        } else if (field.value() == DistantConfig.CONFIG_LANGUAGE) {
                            var languages = List.of("auto", "zh_cn", "en_us");
                            draft.set(index, languages.get((languages.indexOf(draft.get(index)) + 1) % languages.size()));
                        } else draft.set(index, Boolean.toString(!Boolean.parseBoolean(draft.get(index))));
                        button.setMessage(valueText(field, draft.get(index)));
                    }).bounds(left + labelWidth + 8, y, span - labelWidth - 8, 20).build();
                } else {
                    var box = new EditBox(font, left + labelWidth + 8, y, span - labelWidth - 8, 20, Component.literal(field.label()));
                    box.setMaxLength(1024); box.setValue(draft.get(index));
                    box.setResponder(text -> draft.set(index, text)); widget = box;
                }
                widget.active = !pending;
                widget.setTooltip(Tooltip.create(Component.literal(field.key() + "\n" + field.hint())));
                addRenderableWidget(widget);
            }
        }
        int buttonWidth = (span - 20) / 3;
        var save = addRenderableWidget(Button.builder(Component.literal("保存并应用"), button -> save())
                .bounds(left, height - 28, buttonWidth, 20).build()); save.active = loaded && !pending;
        var refresh = addRenderableWidget(Button.builder(Component.literal("重新读取"), button -> { read(); rebuild(); })
                .bounds(left + buttonWidth + 10, height - 28, buttonWidth, 20).build());
        refresh.active = connection != null && Protocol.CHANNEL.isRemotePresent(connection) && !pending;
        addRenderableWidget(Button.builder(Component.literal(group < 0 ? "返回" : "返回分类"), button -> {
            if (group < 0) onClose(); else { group = -1; scroll = 0; rebuild(); }
        }).bounds(left + 2 * (buttonWidth + 10), height - 28, buttonWidth, 20).build());
    }

    private Component valueText(ServerSettings.Field field, String value) {
        return Component.literal(field.value() == DistantConfig.SERVER_PRESET || field.value() == DistantConfig.CONFIG_LANGUAGE
                ? value : value.equals("true") ? "开启" : "关闭");
    }
    private void read() { pending = true; message = "正在读取服务器配置…"; request = ++nextRequest;
        Protocol.CHANNEL.sendToServer(new Protocol.ConfigRequest(request, false, 0, List.of())); }
    private void save() {
        var snapshot = new ServerSettings.Snapshot(draft);
        try { snapshot.validate(); } catch (IllegalArgumentException ex) { message = ex.getMessage(); return; }
        if (connection == null) {
            try { DistantConfig.saveServer(snapshot); message = "本机服务端配置已保存"; }
            catch (RuntimeException ex) { com.mojang.logging.LogUtils.getLogger().error("Saving local server settings failed", ex); message = "保存失败：" + ex.getMessage(); }
            return;
        }
        pending = true; message = "正在保存并应用；修改缓存时请等待任务收尾…"; request = ++nextRequest; rebuild();
        Protocol.CHANNEL.sendToServer(new Protocol.ConfigRequest(request, true, revision, snapshot.values()));
    }
    public static void result(Connection source, Protocol.ConfigResult result) {
        var mc = Minecraft.getInstance();
        if (!(mc.screen instanceof ServerConfigScreen screen) || screen.connection != source || screen.request != result.id() || !screen.pending) return;
        screen.pending = false;
        if (result.success()) {
            screen.revision = result.revision();
            var snapshot = new ServerSettings.Snapshot(result.values()); snapshot.validate();
            screen.draft.clear(); screen.draft.addAll(snapshot.values()); screen.loaded = true; screen.message = "服务器配置已同步";
        } else {
            screen.message = result.message();
            if (result.values().size() != ServerSettings.FIELDS.size()) screen.loaded = false;
        }
        screen.rebuild();
    }
    @Override public void tick() {
        if (connection != null && (minecraft.getConnection() == null || minecraft.getConnection().getConnection() != connection)) {
            draft.clear(); loaded = false; pending = false; onClose();
        }
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (group >= 0 && y >= 45 && y < height - 65) {
            int next = Math.max(0, Math.min(indices().size() - visibleRows(), scroll - (int) Math.signum(delta)));
            if (next != scroll) { scroll = next; rebuild(); return true; }
        }
        return super.mouseScrolled(x, y, delta);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics); int span = contentWidth(), left = (width - span) / 2;
        graphics.drawCenteredString(font, group < 0 ? title : Component.literal("服务端设置 · " + groups.get(group)), width / 2, 10, 0xFFFFFF);
        graphics.drawString(font, connection == null ? "本机配置 · 进入世界后使用服务器配置" : "当前服务器 · OP 等级 ≥2 · 保存后在线应用", left, 28, 0xAAAAAA);
        if (loaded && group >= 0) {
            var indices = indices();
            for (int row = 0; row < visibleRows() && scroll + row < indices.size(); row++) {
                var field = ServerSettings.FIELDS.get(indices.get(scroll + row)); int y = 48 + row * 31;
                graphics.drawString(font, font.plainSubstrByWidth(field.label(), span * 3 / 5 - 8), left, y + 6, 0xFFFFFF);
                if (mouseX >= left && mouseX < left + span * 3 / 5 && mouseY >= y && mouseY < y + 20)
                    graphics.renderTooltip(font, font.split(Component.literal(field.key() + " · " + field.hint()), Math.min(320, width - 24)), mouseX, mouseY);
            }
            graphics.drawString(font, "滚轮浏览 · " + (scroll + 1) + "–" + Math.min(scroll + visibleRows(), indices.size()) + " / " + indices.size(), left, height - 66, 0xAAAAAA);
        }
        RemoteClient.requestCacheStats();
        graphics.drawString(font, font.plainSubstrByWidth(RemoteClient.cacheStatus(), span), left, height - 52, 0xDDDDDD);
        graphics.drawString(font, font.plainSubstrByWidth(message, span), left, height - 39, 0xFFE090);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { draft.clear(); pending = false; minecraft.setScreen(parent); }
}
