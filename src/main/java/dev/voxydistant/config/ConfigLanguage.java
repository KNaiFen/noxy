package dev.voxydistant.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Properties;

/** Select comments before Forge creates/corrects the config; never loads client classes. */
public final class ConfigLanguage {
    private static Properties comments = read("en_us");

    public static void initialize() {
        comments = read(detect(FMLPaths.CONFIGDIR.get().resolve("voxy_distant.toml"),
                FMLPaths.GAMEDIR.get().resolve("options.txt"), FMLLoader.getDist().isClient()));
    }

    static String detect(java.nio.file.Path config, java.nio.file.Path options, boolean client) {
        String language = "auto";
        if (Files.exists(config)) {
            try (var file = CommentedFileConfig.of(config)) {
                file.load();
                Object configured=file.get("configLanguage");
                if(configured instanceof String text && java.util.List.of("auto","zh_cn","en_us").contains(text))language=text;
            }
        }
        try {
            if (language.equals("auto")) {
                language = Locale.getDefault().getLanguage();
                if (client && Files.exists(options)) {
                    try (var lines = Files.lines(options, StandardCharsets.UTF_8)) {
                        language = lines.filter(line -> line.startsWith("lang:")).map(line -> line.substring(5)).findFirst().orElse(language);
                    }
                }
            }
            return language;
        } catch (IOException e) { throw new UncheckedIOException("Cannot detect Voxy Distant config language", e); }
    }

    private static Properties read(String language) {
        var result = new Properties();
        try {
            String resource = "/assets/voxy_distant/config/" + (language.startsWith("zh") ? "zh_cn" : "en_us") + ".properties";
            try (var reader = new InputStreamReader(ConfigLanguage.class.getResourceAsStream(resource), StandardCharsets.UTF_8)) {
                result.load(reader);
            }
            return result;
        } catch (IOException e) { throw new UncheckedIOException("Cannot load Voxy Distant config language", e); }
    }

    static String comment(String key) {
        return java.util.Objects.requireNonNull(comments.getProperty(key), "Missing config comment: " + key);
    }
    private ConfigLanguage() {}
}
