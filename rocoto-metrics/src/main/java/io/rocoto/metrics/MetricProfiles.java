package io.rocoto.metrics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;
import io.rocoto.core.config.ConfigLoader;

/**
 * Loads metric mapping profiles. Bundled profiles: {@code jvm}, {@code tomcat}, {@code wildfly},
 * {@code payara}, {@code jetty}, {@code liberty}. {@code auto} resolves to the profile of the detected server adapter. Anything
 * else is treated as the path of a custom profile file.
 */
public final class MetricProfiles {

    private static final List<String> BUNDLED = List.of("jvm", "tomcat", "wildfly", "payara", "jetty", "liberty");

    private MetricProfiles() {
    }

    /**
     * @param metrics   the {@code metrics} section of rocoto.yaml
     * @param adapterId id of the active server adapter
     * @param configDir base directory for relative custom profile paths
     */
    public static List<MetricRule> load(Config metrics, String adapterId, Path configDir) {
        List<String> profiles = metrics.has("profiles") ? metrics.strings("profiles") : List.of("jvm", "auto");
        List<MetricRule> rules = new ArrayList<>();
        for (String profile : profiles) {
            String resolved = profile.equals("auto") ? adapterId : profile;
            if (BUNDLED.contains(resolved)) {
                rules.addAll(parse(bundled(resolved), "profile " + resolved));
            } else if (!profile.equals("auto")) {
                Path file = Path.of(profile);
                if (!file.isAbsolute() && configDir != null) {
                    file = configDir.resolve(file);
                }
                rules.addAll(parse(ConfigLoader.parse(file), file.toString()));
            }
        }
        // Rules can also be written inline: metrics.rules
        rules.addAll(parse(metrics, "metrics"));
        return rules;
    }

    static List<MetricRule> parse(Config profile, String source) {
        List<MetricRule> rules = new ArrayList<>();
        int i = 0;
        for (Config rc : profile.subList("rules")) {
            rules.add(MetricRule.fromConfig(rc, source + ".rules[" + i++ + "]"));
        }
        return rules;
    }

    private static Config bundled(String name) {
        String resource = "io/rocoto/metrics/profiles/" + name + ".yaml";
        try (InputStream in = MetricProfiles.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new ConfigException("Bundled metrics profile missing: " + resource);
            }
            return ConfigLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConfigException("Cannot read " + resource, e);
        }
    }
}
