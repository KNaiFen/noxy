package dev.voxydistant.config;

import net.minecraftforge.common.ForgeConfigSpec;
import java.util.*;
import static dev.voxydistant.config.DistantConfig.*;

/** Server-owned settings, including diagnostics and comment language, shared by screen and wire format. */
public final class ServerSettings {
    public record Field(String group, String label, ForgeConfigSpec.ConfigValue<?> value) {
        public String key() { return String.join(".", value.getPath()); }
        public String format(Object input) {
            return input instanceof List<?> list ? String.join(",", list.stream().map(Object::toString).toList()) : input.toString();
        }
        @SuppressWarnings("unchecked")
        public Object parse(String text) {
            Object parsed;
            try {
                if (value instanceof ForgeConfigSpec.BooleanValue) {
                    if (!text.equals("true") && !text.equals("false")) throw new IllegalArgumentException("须为 true 或 false");
                    parsed = Boolean.parseBoolean(text);
                } else if (value instanceof ForgeConfigSpec.IntValue) parsed = Integer.parseInt(text.trim());
                else if (value instanceof ForgeConfigSpec.DoubleValue) {
                    double number = Double.parseDouble(text.trim());
                    if (!Double.isFinite(number)) throw new IllegalArgumentException("须为有限数字");
                    parsed = number;
                } else if (value == SERVER_PRESET) parsed = Preset.valueOf(text);
                else if (value == CONFIG_LANGUAGE) parsed = text.trim();
                else parsed = Arrays.stream(text.split(",", -1)).map(String::trim).toList();
                ForgeConfigSpec.ValueSpec spec = SPEC.getSpec().get(value.getPath());
                if (!spec.test(parsed)) throw new IllegalArgumentException("有效范围 " + hint());
                if (value == BANDS) DistanceBands.parse((List<String>) parsed);
            } catch (IllegalArgumentException ex) { throw new IllegalArgumentException(label + "：" + ex.getMessage(), ex); }
            return parsed;
        }
        public String hint() {
            if (value == IMPORT_THREADS) return "0 自动：逻辑处理器数减 2，限制 1～32；显式 1～256，独占导入线程";
            if (value == IMPORT_MEMORY) return "0 自动：最大堆的 1/8，限制 64～1024 MiB；显式 64～4096 MiB，仅 NBT/快照预算";
            if (value == PLAYER_KBPS) return "0 自动：不加每人固定上限；仍受全服上传及客户端下载上限约束";
            if (value == RECOVERY_SECONDS) return "0 在健康观察期结束后立即恢复全速；其余值为线性恢复秒数";
            if (value == BANDS) return "半径:层级，逗号分隔；最后一档延伸至接收边界";
            if (value == SERVER_PRESET) return "CUSTOM 使用下方自定义参数";
            if (value == CONFIG_LANGUAGE) return "auto 跟随系统语言；zh_cn / en_us 在重启后更新配置注释";
            if (value instanceof ForgeConfigSpec.BooleanValue) return "开启 / 关闭";
            ForgeConfigSpec.ValueSpec spec = SPEC.getSpec().get(value.getPath());
            return spec.getRange().toString();
        }
    }
    public static final List<Field> FIELDS = List.of(
        new Field("基础与分档", "服务端远景", SERVER_ENABLED),
        new Field("基础与分档", "生成缺失区块", SERVER_GENERATE),
        new Field("基础与分档", "最大半径（区块）", SERVER_RADIUS),
        new Field("基础与分档", "距离分档", BANDS),
        new Field("传输", "全服上传（Mbps）", TOTAL_MBPS),
        new Field("传输", "每人上传（KiB/s，0 自动）", PLAYER_KBPS),
        new Field("传输", "Zstd 压缩等级", COMPRESSION_LEVEL),
        new Field("传输", "最大每批列数（1 逐列）", MAX_BATCH_COLUMNS),
        new Field("传输", "失效通知间隔（tick）", DIRTY_TICKS),
        new Field("生成", "负载预设", SERVER_PRESET),
        new Field("生成", "每人并发", PLAYER_CONCURRENCY),
        new Field("生成", "每人待请求（列）", PLAYER_REQUEST_QUEUE),
        new Field("生成", "全服队列（列）", SERVER_QUEUE),
        new Field("生成", "后台刷新并发（列）", BACKGROUND_CONCURRENCY),
        new Field("生成", "后台刷新提交/秒", BACKGROUND_RATE),
        new Field("生成", "单列生成超时（秒）", GENERATION_TIMEOUT),
        new Field("生成", "自定义 · 转换线程", SERVER_THREADS),
        new Field("生成", "自定义 · 全服并发", SERVER_CONCURRENCY),
        new Field("生成", "自定义 · 提交/秒", SERVER_RATE),
        new Field("生成", "自定义 · 快照 ms/tick", SERVER_SNAPSHOT_MS),
        new Field("生成", "自定义 · 转换占空比", SERVER_DUTY),
        new Field("内存", "快照内存（MiB）", SERVER_MEMORY),
        new Field("内存", "每人发送内存（MiB）", PLAYER_SEND_MIB),
        new Field("内存", "全服发送内存（MiB）", TOTAL_SEND_MIB),
        new Field("内存", "缓存内存（MiB，修改时短暂停顿）", SERVER_CACHE_MIB),
        new Field("自动节流", "自动节流", AUTO_THROTTLE),
        new Field("自动节流", "减速 MSPT", SLOW_MS),
        new Field("自动节流", "暂停 MSPT", PAUSE_MS),
        new Field("自动节流", "恢复 MSPT", RESUME_MS),
        new Field("自动节流", "暂停 TPS", PAUSE_TPS),
        new Field("自动节流", "恢复 TPS", RESUME_TPS),
        new Field("自动节流", "恢复健康持续秒数", RESUME_SECONDS),
        new Field("自动节流", "减速连续 tick", SLOW_TICKS),
        new Field("自动节流", "暂停连续 tick", PAUSE_TICKS),
        new Field("自动节流", "恢复至全速秒数", RECOVERY_SECONDS),
        new Field("独占导入", "导入线程（0 自动）", IMPORT_THREADS),
        new Field("独占导入", "导入快照内存（MiB，0 自动）", IMPORT_MEMORY),
        new Field("诊断日志", "诊断日志", DEBUG),
        new Field("诊断日志", "详细传输日志", DEBUG_VERBOSE),
        new Field("诊断日志", "逐项网格日志", DEBUG_MESH),
        new Field("诊断日志", "汇总间隔（秒）", DEBUG_INTERVAL),
        new Field("诊断日志", "配置注释语言", CONFIG_LANGUAGE)
    );
    public record Snapshot(List<String> values) {
        public Snapshot { values = List.copyOf(values); }
        public List<Object> validate() {
            if (values.size() != FIELDS.size()) throw new IllegalArgumentException("服务端配置字段数量不匹配");
            var parsed = new ArrayList<Object>();
            for (int i=0;i<FIELDS.size();i++) parsed.add(FIELDS.get(i).parse(values.get(i)));
            if(number(IMPORT_MEMORY)!=0&&number(IMPORT_MEMORY)<64)throw new IllegalArgumentException("导入快照内存必须为 0 或 64～4096 MiB");
            double resume = number(RESUME_MS), slow = number(SLOW_MS), pause = number(PAUSE_MS);
            if (!(resume < slow && slow < pause) || number(RESUME_TPS) < number(PAUSE_TPS))
                throw new IllegalArgumentException("自动节流：恢复 MSPT < 减速 MSPT < 暂停 MSPT，恢复 TPS >= 暂停 TPS");
            return parsed;
        }
        public String get(ForgeConfigSpec.ConfigValue<?> value) { return values.get(index(value)); }
        public double number(ForgeConfigSpec.ConfigValue<?> value) { return Double.parseDouble(get(value)); }
    }
    public static int index(ForgeConfigSpec.ConfigValue<?> value) {
        for (int i=0;i<FIELDS.size();i++) if (FIELDS.get(i).value() == value) return i;
        throw new IllegalArgumentException("不是服务端配置字段");
    }
    public static Snapshot current() { return new Snapshot(FIELDS.stream().map(f -> f.format(f.value().get())).toList()); }
    private ServerSettings() {}
}
