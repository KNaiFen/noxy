package dev.voxydistant.config;

import net.minecraftforge.common.ForgeConfigSpec;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;

public final class DistantConfig {
    public static final int CURRENT_CONFIG_VERSION = 3;
    private static net.minecraftforge.fml.config.ModConfig registered;
    public enum Preset { MINIMAL, LOW, BALANCED, AGGRESSIVE, FULL, CUSTOM }
    public record Limits(int threads, int concurrency, int perSecond, double snapshotMillis, double dutyCycle) {}
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue DEBUG, DEBUG_VERBOSE, DEBUG_MESH;
    public static final ForgeConfigSpec.IntValue DEBUG_INTERVAL;
    public static final ForgeConfigSpec.ConfigValue<String> CONFIG_LANGUAGE;
    public static final ForgeConfigSpec.IntValue CONFIG_VERSION;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.EnumValue<Preset> PRESET;
    public static final ForgeConfigSpec.IntValue RADIUS, THREADS, CONCURRENCY, PER_SECOND, QUEUE, MEMORY;
    public static final ForgeConfigSpec.DoubleValue SNAPSHOT_MS, DUTY;
    public static final ForgeConfigSpec.BooleanValue RECEIVE, SERVER_ENABLED, SERVER_GENERATE, AUTO_THROTTLE;
    public static final ForgeConfigSpec.IntValue RECEIVE_RADIUS, DOWNLOAD_KBPS, RECEIVE_MIB, INDEX_MIB, REQUEST_WINDOW;
    public static final ForgeConfigSpec.IntValue SERVER_RADIUS, PLAYER_CONCURRENCY, SERVER_QUEUE, SERVER_MEMORY,
            PLAYER_SEND_MIB, TOTAL_SEND_MIB, PLAYER_KBPS, DIRTY_TICKS, SERVER_THREADS, SERVER_CONCURRENCY, SERVER_RATE, SERVER_CACHE_MIB, COMPRESSION_LEVEL, MAX_BATCH_COLUMNS, PLAYER_REQUEST_QUEUE, BACKGROUND_CONCURRENCY, BACKGROUND_RATE;
    public static final ForgeConfigSpec.DoubleValue TOTAL_MBPS, SLOW_MS, PAUSE_MS, RESUME_MS, PAUSE_TPS, RESUME_TPS,
            RESUME_SECONDS, RECOVERY_SECONDS, SERVER_SNAPSHOT_MS, SERVER_DUTY, RECEIVE_DUTY;
    public static final ForgeConfigSpec.IntValue SLOW_TICKS, PAUSE_TICKS, GENERATION_TIMEOUT;
    public static final ForgeConfigSpec.EnumValue<Preset> SERVER_PRESET;
    public static final ForgeConfigSpec.IntValue IMPORT_THREADS, IMPORT_MEMORY;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BANDS;

    static {
        var b = new ForgeConfigSpec.Builder();
        CONFIG_VERSION = b.comment(ConfigLanguage.comment("configVersion")).defineInRange("configVersion", CURRENT_CONFIG_VERSION, 0, Integer.MAX_VALUE);
        CONFIG_LANGUAGE = b.comment(ConfigLanguage.comment("configLanguage")).define("configLanguage", "auto", value -> value instanceof String text && List.of("auto", "zh_cn", "en_us").contains(text));
        b.push("client");
        RECEIVE = b.comment(ConfigLanguage.comment("RECEIVE")).define("receiveServerLods", true);
        RECEIVE_RADIUS = b.comment(ConfigLanguage.comment("RECEIVE_RADIUS")).defineInRange("radius", 0, 0, 2048);
        DOWNLOAD_KBPS = b.comment(ConfigLanguage.comment("DOWNLOAD_KBPS")).defineInRange("downloadKiBPerSecond", 0, 0, 131072);
        RECEIVE_MIB = b.comment(ConfigLanguage.comment("RECEIVE_MIB")).defineInRange("receiveMemoryMiB", 128, 4, 1024);
        REQUEST_WINDOW = b.comment(ConfigLanguage.comment("REQUEST_WINDOW")).defineInRange("requestWindowColumns", 256, 1, 1024);
        INDEX_MIB = b.comment(ConfigLanguage.comment("INDEX_MIB")).defineInRange("indexMemoryMiB", 256, 16, 10240);
        RECEIVE_DUTY = b.comment(ConfigLanguage.comment("RECEIVE_DUTY")).defineInRange("processingDutyCycle", 1.0, 0.05, 1.0);
        b.push("localGeneration");
        ENABLED = b.comment(ConfigLanguage.comment("ENABLED")).define("enabled", true);
        RADIUS = b.comment(ConfigLanguage.comment("RADIUS")).defineInRange("radius", 128, 1, 2048);
        PRESET = b.comment(ConfigLanguage.comment("PRESET")).defineEnum("preset", Preset.BALANCED);
        QUEUE = b.comment(ConfigLanguage.comment("QUEUE")).defineInRange("queueColumns", 256, 1, 4096);
        MEMORY = b.comment(ConfigLanguage.comment("MEMORY")).defineInRange("snapshotMemoryMiB", 128, 1, 4096);
        b.push("custom");
        THREADS = b.comment(ConfigLanguage.comment("THREADS")).defineInRange("conversionThreads", 2, 1, 256);
        CONCURRENCY = b.comment(ConfigLanguage.comment("CONCURRENCY")).defineInRange("generationConcurrency", 4, 1, 64);
        PER_SECOND = b.comment(ConfigLanguage.comment("PER_SECOND")).defineInRange("submissionsPerSecond", 8192, 1, 10000);
        SNAPSHOT_MS = b.comment(ConfigLanguage.comment("SNAPSHOT_MS")).defineInRange("snapshotMillisPerTick", 2.0, 0.1, 20.0);
        DUTY = b.comment(ConfigLanguage.comment("DUTY")).defineInRange("conversionDutyCycle", 1.0, 0.05, 1.0);
        b.pop();
        b.pop(); b.pop();
        b.push("server");
        SERVER_ENABLED = b.comment(ConfigLanguage.comment("SERVER_ENABLED")).define("enabled", true);
        SERVER_GENERATE = b.comment(ConfigLanguage.comment("SERVER_GENERATE")).define("generateMissingChunks", true);
        SERVER_RADIUS = b.comment(ConfigLanguage.comment("SERVER_RADIUS")).defineInRange("radius", 256, 1, 2048);
        BANDS = b.comment(ConfigLanguage.comment("BANDS")).defineList("distanceBands", List.of("32:0", "64:1", "96:2"), o -> o instanceof String s && s.matches("\\s*\\d+\\s*:\\s*[0-4]\\s*"));
        TOTAL_MBPS = b.comment(ConfigLanguage.comment("TOTAL_MBPS")).defineInRange("totalBandwidthMbps", 30.0, 0.01, 1000.0);
        PLAYER_KBPS = b.comment(ConfigLanguage.comment("PLAYER_KBPS")).defineInRange("playerBandwidthKiBPerSecond", 0, 0, 131072);
        COMPRESSION_LEVEL = b.comment(ConfigLanguage.comment("COMPRESSION_LEVEL")).defineInRange("compressionLevel", 1, 1, 22);
        MAX_BATCH_COLUMNS = b.comment(ConfigLanguage.comment("MAX_BATCH_COLUMNS")).defineInRange("maxBatchColumns", 16, 1, 128);
        PLAYER_SEND_MIB = b.comment(ConfigLanguage.comment("PLAYER_SEND_MIB")).defineInRange("playerSendMemoryMiB", 32, 1, 1024);
        TOTAL_SEND_MIB = b.comment(ConfigLanguage.comment("TOTAL_SEND_MIB")).defineInRange("totalSendMemoryMiB", 64, 4, 4096);
        DIRTY_TICKS = b.comment(ConfigLanguage.comment("DIRTY_TICKS")).defineInRange("dirtyIntervalTicks", 10, 1, 1200);
        SERVER_CACHE_MIB = b.comment(ConfigLanguage.comment("SERVER_CACHE_MIB")).defineInRange("cacheMemoryMiB", 128, 8, 1024);
        b.push("generation");
        SERVER_PRESET = b.comment(ConfigLanguage.comment("SERVER_PRESET")).defineEnum("preset", Preset.CUSTOM);
        PLAYER_CONCURRENCY = b.comment(ConfigLanguage.comment("PLAYER_CONCURRENCY")).defineInRange("playerConcurrency", 8, 1, 64);
        PLAYER_REQUEST_QUEUE = b.comment(ConfigLanguage.comment("PLAYER_REQUEST_QUEUE")).defineInRange("playerRequestQueueColumns", 256, 1, 4096);
        SERVER_QUEUE = b.comment(ConfigLanguage.comment("SERVER_QUEUE")).defineInRange("queueColumns", 2048, 1, 4096);
        BACKGROUND_CONCURRENCY = b.comment(ConfigLanguage.comment("BACKGROUND_CONCURRENCY")).defineInRange("backgroundConcurrency", 8, 1, 64);
        BACKGROUND_RATE = b.comment(ConfigLanguage.comment("BACKGROUND_RATE")).defineInRange("backgroundSubmissionsPerSecond", 128, 1, 10000);
        SERVER_MEMORY = b.comment(ConfigLanguage.comment("SERVER_MEMORY")).defineInRange("snapshotMemoryMiB", 128, 4, 4096);
        GENERATION_TIMEOUT = b.comment(ConfigLanguage.comment("GENERATION_TIMEOUT")).defineInRange("columnTimeoutSeconds", 60, 5, 600);
        AUTO_THROTTLE = b.comment(ConfigLanguage.comment("AUTO_THROTTLE")).define("automaticThrottle", true);
        SLOW_MS = b.comment(ConfigLanguage.comment("SLOW_MS")).defineInRange("slowAboveMillis", 40.0, 1, 1000);
        PAUSE_MS = b.comment(ConfigLanguage.comment("PAUSE_MS")).defineInRange("pauseAboveMillis", 45.0, 1, 1000);
        RESUME_MS = b.comment(ConfigLanguage.comment("RESUME_MS")).defineInRange("resumeBelowMillis", 35.0, 1, 1000);
        PAUSE_TPS = b.comment(ConfigLanguage.comment("PAUSE_TPS")).defineInRange("pauseBelowTps", 19.0, 1, 20);
        RESUME_TPS = b.comment(ConfigLanguage.comment("RESUME_TPS")).defineInRange("resumeAboveTps", 19.5, 1, 20);
        RESUME_SECONDS = b.comment(ConfigLanguage.comment("RESUME_SECONDS")).defineInRange("healthySeconds", 3.0, 0, 60);
        SLOW_TICKS = b.comment(ConfigLanguage.comment("SLOW_TICKS")).defineInRange("slowTriggerTicks", 20, 1, 1200);
        PAUSE_TICKS = b.comment(ConfigLanguage.comment("PAUSE_TICKS")).defineInRange("pauseTriggerTicks", 20, 1, 1200);
        RECOVERY_SECONDS = b.comment(ConfigLanguage.comment("RECOVERY_SECONDS")).defineInRange("recoverySeconds", 5.0, 0, 60);
        b.push("custom");
        SERVER_THREADS = b.comment(ConfigLanguage.comment("SERVER_THREADS")).defineInRange("conversionThreads", 4, 1, 256);
        SERVER_CONCURRENCY = b.comment(ConfigLanguage.comment("SERVER_CONCURRENCY")).defineInRange("generationConcurrency", 32, 1, 64);
        SERVER_RATE = b.comment(ConfigLanguage.comment("SERVER_RATE")).defineInRange("submissionsPerSecond", 8192, 1, 10000);
        SERVER_SNAPSHOT_MS = b.comment(ConfigLanguage.comment("SERVER_SNAPSHOT_MS")).defineInRange("snapshotMillisPerTick", 2.0, 0.1, 20);
        SERVER_DUTY = b.comment(ConfigLanguage.comment("SERVER_DUTY")).defineInRange("conversionDutyCycle", 1.0, 0.05, 1.0);
        b.pop(); b.pop();
        b.push("import");
        IMPORT_THREADS = b.comment(ConfigLanguage.comment("IMPORT_THREADS")).defineInRange("threads", 0, 0, 256);
        IMPORT_MEMORY = b.comment(ConfigLanguage.comment("IMPORT_MEMORY")).defineInRange("snapshotMemoryMiB", 0, 0, 4096);
        b.pop(); b.pop();
        b.push("debug");
        DEBUG = b.comment(ConfigLanguage.comment("DEBUG")).define("enabled", false);
        DEBUG_VERBOSE = b.comment(ConfigLanguage.comment("DEBUG_VERBOSE")).define("verbose", false);
        DEBUG_MESH = b.comment(ConfigLanguage.comment("DEBUG_MESH")).define("meshDetails", false);
        DEBUG_INTERVAL = b.comment(ConfigLanguage.comment("DEBUG_INTERVAL")).defineInRange("intervalSeconds", 5, 1, 60);
        b.pop();
        SPEC = b.build();
    }

    public static Limits limits() {
        return limits(PRESET.get(), new Limits(THREADS.get(), CONCURRENCY.get(), PER_SECOND.get(), SNAPSHOT_MS.get(), DUTY.get()));
    }
    public static Limits serverLimits() {
        return limits(SERVER_PRESET.get(), new Limits(SERVER_THREADS.get(), SERVER_CONCURRENCY.get(), SERVER_RATE.get(), SERVER_SNAPSHOT_MS.get(), SERVER_DUTY.get()));
    }
    public static dev.voxydistant.server.LoadGovernor.Settings throttleSettings() {
        return new dev.voxydistant.server.LoadGovernor.Settings(AUTO_THROTTLE.get(), SLOW_MS.get(), PAUSE_MS.get(), RESUME_MS.get(),
                PAUSE_TPS.get(), RESUME_TPS.get(), SLOW_TICKS.get(), PAUSE_TICKS.get(), RESUME_SECONDS.get(), RECOVERY_SECONDS.get());
    }
    public static int importThreads() { return IMPORT_THREADS.get()==0?Math.max(1,Math.min(32,Runtime.getRuntime().availableProcessors()-2)):IMPORT_THREADS.get(); }
    public static long importMemory() { return (IMPORT_MEMORY.get()==0?Math.max(64,Math.min(1024,Runtime.getRuntime().maxMemory()/8/1048576)):IMPORT_MEMORY.get())*1048576L; }
    public static void validateReceiveMemory(int sections,int receiveMiB) {
        long bytes=sections*dev.voxydistant.generation.ChunkSnapshot.RESERVED_BYTES;
        long snapshot=Math.min(32L<<20,receiveMiB*1048576L/4);
        if(bytes>32L<<20)throw new IllegalArgumentException("当前维度的完整区块快照超过 32 MiB 接收快照上限");
        if(snapshot<bytes)throw new IllegalArgumentException("当前维度的完整区块快照需要 "+((bytes+1048575)/1048576)+" MiB，接收缓冲至少设置 "+((bytes*4+1048575)/1048576)+" MiB");
    }
    private static Limits limits(Preset preset, Limits custom) {
        int c = Runtime.getRuntime().availableProcessors();
        return switch (preset) {
            case MINIMAL -> new Limits(1, 1, 512, 0.5, 0.5);
            case LOW -> new Limits(1, 2, 1024, 1, 1);
            case BALANCED -> new Limits(Math.max(1, Math.min(4, c / 4)), 4, 2048, 2, 1);
            case AGGRESSIVE -> new Limits(Math.max(1, Math.min(8, c / 2)), 8, 4096, 4, 1);
            case FULL -> new Limits(Math.max(1, c - 1), 16, 8192, 8, 1);
            case CUSTOM -> custom;
        };
    }

    public static void validate() {
        if(IMPORT_MEMORY.get()!=0&&IMPORT_MEMORY.get()<64)throw new IllegalArgumentException("导入快照内存必须为 0 或 64～4096 MiB");
        DistanceBands.parse(BANDS.get());
        if (!(RESUME_MS.get() < SLOW_MS.get() && SLOW_MS.get() < PAUSE_MS.get()) || RESUME_TPS.get() < PAUSE_TPS.get())
            throw new IllegalArgumentException("恢复 MSPT < 减速 MSPT < 暂停 MSPT，恢复 TPS >= 暂停 TPS");
    }
    public static void register(net.minecraftforge.fml.config.ModConfig config){
        registered=config;
        // FileWatcher reloads by clearing the live config on another thread. A new session can
        // then cache defaults. File edits use our explicit reload; screen edits already set values.
        com.electronwill.nightconfig.core.file.FileWatcher.defaultInstance().removeWatch(((CommentedFileConfig)config.getConfigData()).getNioPath());
    }
    public static void reload(){
        var data=(CommentedFileConfig)registered.getConfigData();data.load();SPEC.afterReload();validate();
    }

    public static ServerSettings.Snapshot readServerFile() {
        var live=(CommentedFileConfig)registered.getConfigData();
        try(var file=CommentedFileConfig.of(live.getNioPath())) {
            file.load();
            var values=new java.util.ArrayList<String>();
            for(var field:ServerSettings.FIELDS) values.add(field.format(file.getOrElse(field.value().getPath(),field.value()::getDefault)));
            var snapshot=new ServerSettings.Snapshot(values);snapshot.validate();return snapshot;
        }
    }

    public static void reloadDebug() {
        var live=(CommentedFileConfig)registered.getConfigData();
        try(var file=CommentedFileConfig.of(live.getNioPath())){
            file.load();var debug=file.<com.electronwill.nightconfig.core.CommentedConfig>get("debug");
            if(debug!=null){
                for(var value:java.util.List.of(DEBUG,DEBUG_VERBOSE,DEBUG_MESH,DEBUG_INTERVAL)){
                    var spec=SPEC.getSpec().<ForgeConfigSpec.ValueSpec>get(value.getPath());
                    if(!spec.test(file.get(value.getPath())))throw new IllegalArgumentException("诊断配置无效："+String.join(".",value.getPath()));
                }
                live.valueMap().put("debug",debug);SPEC.afterReload();
            }
        }
    }

    /** Save server settings, diagnostics and comment language before exposing them to running workers. */
    public static void saveServer(ServerSettings.Snapshot snapshot) {
        var parsed=snapshot.validate();var live=(CommentedFileConfig)registered.getConfigData();
        var path=live.getNioPath();var temporary=path.resolveSibling(path.getFileName()+".remote.tmp");
        var section=com.electronwill.nightconfig.core.CommentedConfig.inMemory();
        var debug=com.electronwill.nightconfig.core.CommentedConfig.inMemory();
        try {
            try(var output=CommentedFileConfig.builder(temporary,com.electronwill.nightconfig.toml.TomlFormat.instance()).sync().build()) {
                try(var disk=CommentedFileConfig.of(path)){disk.load();output.putAll(disk);output.putAllComments(disk);}
                // Stage the sections without mutating the live config between individual fields.
                for(int i=0;i<ServerSettings.FIELDS.size();i++) {
                    var field=ServerSettings.FIELDS.get(i);var key=field.value().getPath();
                    if (field.value()==CONFIG_LANGUAGE) {
                        output.set(key,parsed.get(i));output.setComment(key,live.getComment(key));continue;
                    }
                    var target=key.getFirst().equals("debug")?debug:section;
                    target.set(key.subList(1,key.size()),parsed.get(i));
                    target.setComment(key.subList(1,key.size()),live.getComment(key));
                }
                output.set("server",section);output.set("debug",debug);output.save();
            }
            Files.move(temporary,path,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            // Forge's live wrapper autosaves every set(). Publish all staged values
            // without intermediate file writes, then invalidate cached ConfigValues.
            live.valueMap().put("server",section);live.valueMap().put("debug",debug);
            live.valueMap().put("configLanguage",snapshot.get(CONFIG_LANGUAGE));SPEC.afterReload();
        } catch(IOException ex) { throw new UncheckedIOException("保存服务端配置失败",ex); }
    }

    public static void migrate() {
        var root = FMLPaths.CONFIGDIR.get();
        var old = root.resolve("voxy_distant-client.toml");
        var target = root.resolve("voxy_distant.toml");
        try {
            if (Files.exists(old) && !Files.exists(target)) {
                Files.copy(old, root.resolve("voxy_distant-client.toml.bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                try (var source = CommentedFileConfig.of(old); var output = CommentedFileConfig.of(target)) {
                    source.load();
                    for (var entry : source.entrySet()) output.set("client.localGeneration." + entry.getKey(), entry.getValue());
                    output.save();
                }
            }
            upgrade(target);
        } catch (IOException e) { throw new UncheckedIOException("迁移 Voxy Distant 配置失败", e); }
    }

    static void upgrade(java.nio.file.Path target) throws IOException {
        if (!Files.exists(target)) return;
        try (var source = CommentedFileConfig.of(target)) {
            source.load();
            Object stored = source.get("configVersion");
            int version = stored instanceof Number number ? number.intValue() : 0;
            if (version >= CURRENT_CONFIG_VERSION) return;
            var backup = target.resolveSibling(target.getFileName() + ".v" + version + "." + System.currentTimeMillis() + ".bak");
            Files.copy(target, backup);
            var temporary = target.resolveSibling(target.getFileName() + ".upgrade.tmp");
            try (var output = CommentedFileConfig.builder(temporary, com.electronwill.nightconfig.toml.TomlFormat.instance()).sync().build()) {
                output.putAll(source);
                // Forge fills new defaults, updates translated comments and validates old values.
                // Unsupported/invalid old values remain available in the untouched backup.
                SPEC.correct(output, (action, path, previous, replacement) -> {
                    if (previous != null) com.mojang.logging.LogUtils.getLogger().warn("Voxy Distant config correction {}: {} -> {}; original in {}", String.join(".",path), previous, replacement, backup);
                });
                output.set("configVersion", CURRENT_CONFIG_VERSION);
                output.save();
            }
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            com.mojang.logging.LogUtils.getLogger().info("Voxy Distant config upgraded {} -> {}; backup={}", version, CURRENT_CONFIG_VERSION, backup);
        }
    }

    private DistantConfig() {}
}
