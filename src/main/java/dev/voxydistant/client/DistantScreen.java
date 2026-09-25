package dev.voxydistant.client;

import dev.voxydistant.config.DistantConfig;
import dev.voxydistant.generation.GenerationController;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A local draft shared by the category pages; server settings have their own draft and permission flow. */
public final class DistantScreen extends Screen {
    private record Setting(String label, String hint, ForgeConfigSpec.ConfigValue<?> value) {}

    private static final List<Setting> LOCAL = List.of(
            new Setting("本地生成", "在原版视距外生成真实区块供 Voxy 显示。", DistantConfig.ENABLED),
            new Setting("生成半径（区块）", "本地生成的最大半径。", DistantConfig.RADIUS),
            new Setting("负载预设", "CUSTOM 使用下面的自定义参数。", DistantConfig.PRESET),
            new Setting("待处理列上限", "本地生成队列的列数上限。", DistantConfig.QUEUE),
            new Setting("快照内存 MiB", "本地生成快照预算。", DistantConfig.MEMORY),
            new Setting("转换线程（自定义）", "仅在 CUSTOM 预设下生效。", DistantConfig.THREADS),
            new Setting("生成并发（自定义）", "仅在 CUSTOM 预设下生效。", DistantConfig.CONCURRENCY),
            new Setting("提交/秒（自定义）", "仅在 CUSTOM 预设下生效。", DistantConfig.PER_SECOND),
            new Setting("快照 ms/tick（自定义）", "仅在 CUSTOM 预设下生效。", DistantConfig.SNAPSHOT_MS),
            new Setting("转换占空比（自定义）", "仅在 CUSTOM 预设下生效。", DistantConfig.DUTY));
    private static final List<Setting> RECEIVE = List.of(
            new Setting("接收服务端远景", "接收兼容服务器发送的 LOD 列。", DistantConfig.RECEIVE),
            new Setting("接收半径（区块）", "0 自动跟随 Voxy 渲染距离，仍受服务器上限约束。", DistantConfig.RECEIVE_RADIUS),
            new Setting("下载 KiB/s", "0 自动使用服务器允许的速度，仍受全服和单人上限约束。", DistantConfig.DOWNLOAD_KBPS),
            new Setting("接收缓冲 MiB", "修改后退出并重新连接服务器生效。", DistantConfig.RECEIVE_MIB),
            new Setting("索引缓存 MiB", "客户端覆盖索引的内存预算。", DistantConfig.INDEX_MIB),
            new Setting("处理占空比", "远景接收应用线程的工作占比。", DistantConfig.RECEIVE_DUTY),
            new Setting("请求窗口（列）", "最多允许多少列处于未完成请求状态。", DistantConfig.REQUEST_WINDOW));
    private static final List<Setting> DEBUG = List.of(
            new Setting("诊断日志", "本机日志写入 logs/latest.log；服务器日志在“服务端设置”中单独开启。", DistantConfig.DEBUG),
            new Setting("详细传输日志", "记录传输、逐列、回执及慢数据库操作；排查结束后建议关闭。", DistantConfig.DEBUG_VERBOSE),
            new Setting("逐项网格日志", "独立的网格决策日志，网络排查时通常保持关闭。", DistantConfig.DEBUG_MESH),
            new Setting("汇总间隔（秒）", "诊断吞吐、队列、数据库和计时的汇总间隔。", DistantConfig.DEBUG_INTERVAL),
            new Setting("配置注释语言", "auto 跟随游戏或系统语言；重启后用于配置文件注释。", DistantConfig.CONFIG_LANGUAGE));
    private static final List<List<Setting>> PAGES = List.of(LOCAL, RECEIVE, DEBUG);
    private static final String[] TITLES = {"本地生成", "服务端接收", "诊断日志"};
    private final Screen parent;
    private final Map<ForgeConfigSpec.ConfigValue<?>, String> draft = new LinkedHashMap<>();
    private int page = -1;
    private int scroll;
    private String error = "";

    public DistantScreen(Screen parent) {
        super(Component.literal("Voxy Distant 设置"));
        this.parent = parent;
        for (var settings : PAGES) for (var setting : settings) draft.put(setting.value(), setting.value().get().toString());
    }

    @Override protected void init() { rebuild(); }

    private int contentWidth() { return Math.min(460, width - 24); }
    private int visibleRows() { return Math.max(1, (height - 130) / 29); }

    private void rebuild() {
        clearWidgets();
        int span = contentWidth(), left = (width - span) / 2;
        if (page < 0) {
            for (int i = 0; i < TITLES.length; i++) {
                int selected = i;
                addRenderableWidget(Button.builder(Component.literal(TITLES[i] + "  ›"), button -> {
                    page = selected; scroll = 0; error = ""; rebuild();
                }).bounds(left + (i % 2) * (span / 2 + 4), 48 + (i / 2) * 32, span / 2 - 4, 24).build());
            }
            addRenderableWidget(Button.builder(Component.literal("服务端设置  ›"), button -> minecraft.setScreen(new ServerConfigScreen(this)))
                    .bounds(left + span / 2 + 4, 80, span / 2 - 4, 24).build());
        } else {
            var settings = PAGES.get(page);
            int labelWidth = span * 3 / 5;
            for (int row = 0; row < visibleRows() && scroll + row < settings.size(); row++) {
                var setting = settings.get(scroll + row);
                int x = left + labelWidth + 8, y = 48 + row * 29, controlWidth = span - labelWidth - 8;
                if (setting.value() instanceof ForgeConfigSpec.BooleanValue || setting.value() == DistantConfig.PRESET
                        || setting.value() == DistantConfig.CONFIG_LANGUAGE) {
                    var button = Button.builder(valueText(setting.value()), pressed -> {
                        cycle(setting.value()); pressed.setMessage(valueText(setting.value()));
                    }).bounds(x, y, controlWidth, 20).build();
                    button.setTooltip(Tooltip.create(Component.literal(setting.hint())));
                    addRenderableWidget(button);
                } else {
                    var box = new EditBox(font, x, y, controlWidth, 20, Component.literal(setting.label()));
                    box.setMaxLength(32); box.setValue(draft.get(setting.value()));
                    box.setResponder(text -> draft.put(setting.value(), text));
                    box.setTooltip(Tooltip.create(Component.literal(setting.hint())));
                    addRenderableWidget(box);
                }
            }
        }
        int buttonWidth = (span - 16) / 3;
        addRenderableWidget(Button.builder(Component.literal("保存"), button -> save()).bounds(left, height - 28, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal(page < 0 ? "关闭" : "返回分类"), button -> {
            if (page < 0) onClose(); else { page = -1; scroll = 0; error = ""; rebuild(); }
        }).bounds(left + buttonWidth + 8, height - 28, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose())
                .bounds(left + 2 * (buttonWidth + 8), height - 28, buttonWidth, 20).build());
    }

    private Component valueText(ForgeConfigSpec.ConfigValue<?> value) {
        String text = draft.get(value);
        if (value instanceof ForgeConfigSpec.BooleanValue) text = Boolean.parseBoolean(text) ? "开启" : "关闭";
        return Component.literal(text);
    }

    private void cycle(ForgeConfigSpec.ConfigValue<?> value) {
        String current = draft.get(value);
        if (value instanceof ForgeConfigSpec.BooleanValue) draft.put(value, Boolean.toString(!Boolean.parseBoolean(current)));
        else if (value == DistantConfig.PRESET) {
            var choices = DistantConfig.Preset.values();
            draft.put(value, choices[(DistantConfig.Preset.valueOf(current).ordinal() + 1) % choices.length].name());
        } else {
            var choices = List.of("auto", "zh_cn", "en_us");
            draft.put(value, choices.get((choices.indexOf(current) + 1) % choices.size()));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void save() {
        var parsed = new LinkedHashMap<ForgeConfigSpec.ConfigValue<?>, Object>();
        for (var settings : PAGES) for (var setting : settings) {
            var value = setting.value(); String input = draft.get(value);
            Object result;
            try {
                if (value instanceof ForgeConfigSpec.IntValue) result = Integer.parseInt(input.trim());
                else if (value instanceof ForgeConfigSpec.DoubleValue) result = Double.parseDouble(input.trim());
                else if (value == DistantConfig.PRESET) result = DistantConfig.Preset.valueOf(input);
                else if (value instanceof ForgeConfigSpec.BooleanValue) result = Boolean.parseBoolean(input);
                else result = input;
            } catch (IllegalArgumentException ex) { error = setting.label() + "：请输入有效数值"; return; }
            var spec = DistantConfig.SPEC.getSpec().<ForgeConfigSpec.ValueSpec>get(value.getPath());
            if (!spec.test(result)) { error = setting.label() + "：有效范围 " + spec.getRange(); return; }
            parsed.put(value, result);
        }
        parsed.forEach((value, result) -> ((ForgeConfigSpec.ConfigValue) value).set(result));
        DistantConfig.SPEC.save();
        onClose();
    }

    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (page >= 0 && y >= 45 && y < height - 58) {
            int next = Math.max(0, Math.min(PAGES.get(page).size() - visibleRows(), scroll - (int) Math.signum(delta)));
            if (next != scroll) { scroll = next; rebuild(); return true; }
        }
        return super.mouseScrolled(x, y, delta);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int span = contentWidth(), left = (width - span) / 2;
        graphics.drawCenteredString(font, page < 0 ? title : Component.literal("Voxy Distant · " + TITLES[page]), width / 2, 12, 0xFFFFFF);
        if (page >= 0) {
            var settings = PAGES.get(page);
            for (int row = 0; row < visibleRows() && scroll + row < settings.size(); row++) {
                var setting = settings.get(scroll + row);
                int y = 48 + row * 29;
                graphics.drawString(font, font.plainSubstrByWidth(setting.label(), span * 3 / 5 - 8), left, y + 6, 0xDDDDDD);
                if (mouseX >= left && mouseX < left + span * 3 / 5 && mouseY >= y && mouseY < y + 20)
                    graphics.renderTooltip(font, font.split(Component.literal(setting.label() + " · " + setting.hint()), Math.min(320, width - 24)), mouseX, mouseY);
            }
            graphics.drawString(font, "滚轮浏览 · " + (scroll + 1) + "–" + Math.min(scroll + visibleRows(), settings.size()) + " / " + settings.size(), left, height - 55, 0xAAAAAA);
        } else {
            var state = GenerationController.status();
            if (height >= 205) {
                graphics.drawString(font, String.format(Locale.ROOT, "生成 %d · 转换 %d · 已完成 %d · %.1f 列/秒", state.generating(), state.converting(), state.completed(), state.perSecond()), left, 113, 0xDDDDDD);
                RemoteClient.requestCacheStats();
                graphics.drawString(font, font.plainSubstrByWidth(RemoteClient.receiveSpeed(), span), left, 126, 0xDDDDDD);
                graphics.drawString(font, font.plainSubstrByWidth(RemoteClient.cacheStatus(), span), left, 139, 0xDDDDDD);
            }
        }
        if (!error.isEmpty()) graphics.drawCenteredString(font, font.plainSubstrByWidth(error, span), width / 2, height - 43, 0xFF7777);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
