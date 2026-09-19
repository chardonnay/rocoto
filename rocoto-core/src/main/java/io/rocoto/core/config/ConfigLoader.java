package io.rocoto.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Locates and parses {@code rocoto.yaml}.
 *
 * <p>Lookup order (first hit wins):
 * <ol>
 *   <li>system property {@code rocoto.config}</li>
 *   <li>environment variable {@code ROCOTO_CONFIG}</li>
 *   <li>{@code ${jboss.server.config.dir}/rocoto.yaml} (WildFly / JBoss EAP)</li>
 *   <li>{@code ${catalina.base}/conf/rocoto.yaml} (Tomcat)</li>
 *   <li>{@code ${com.sun.aas.instanceRoot}/config/rocoto.yaml} (Payara / GlassFish)</li>
 *   <li>{@code ${jetty.base}/etc/rocoto.yaml} (Jetty)</li>
 *   <li>{@code ${server.config.dir}/rocoto.yaml} (Open Liberty)</li>
 *   <li>{@code /rocoto.yaml} on the class path</li>
 * </ol>
 * Without any file the secure defaults apply.
 */
public final class ConfigLoader {

    private ConfigLoader() {
    }

    public record Loaded(Config config, String source, Path directory) {
    }

    public static Loaded load(ClassLoader classLoader) {
        for (Path candidate : candidates()) {
            if (Files.isRegularFile(candidate)) {
                return new Loaded(parse(candidate), candidate.toString(), candidate.toAbsolutePath().getParent());
            }
        }
        String explicit = explicitLocation();
        if (explicit != null) {
            throw new ConfigException("Configured rocoto config file does not exist: " + explicit);
        }
        try (InputStream in = classLoader.getResourceAsStream("rocoto.yaml")) {
            if (in != null) {
                return new Loaded(parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)),
                        "classpath:rocoto.yaml", serverConfigDirectory());
            }
        } catch (IOException e) {
            throw new ConfigException("Cannot read classpath:rocoto.yaml", e);
        }
        return new Loaded(Config.empty(), "built-in defaults", serverConfigDirectory());
    }

    /**
     * Without a rocoto.yaml, relative paths (the user store) still belong into the server's
     * configuration directory rather than into whatever the working directory happens to be.
     */
    private static Path serverConfigDirectory() {
        for (Path candidate : candidates()) {
            Path dir = candidate.toAbsolutePath().getParent();
            if (dir != null && Files.isDirectory(dir)) {
                return dir;
            }
        }
        return null;
    }

    private static String explicitLocation() {
        String p = System.getProperty("rocoto.config");
        return p != null ? p : System.getenv("ROCOTO_CONFIG");
    }

    private static List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        String explicit = explicitLocation();
        if (explicit != null) {
            out.add(Path.of(explicit));
            return out;
        }
        addIfSet(out, "jboss.server.config.dir", "rocoto.yaml");
        addIfSet(out, "catalina.base", "conf/rocoto.yaml");
        addIfSet(out, "com.sun.aas.instanceRoot", "config/rocoto.yaml");
        addIfSet(out, "jetty.base", "etc/rocoto.yaml");
        addIfSet(out, "server.config.dir", "rocoto.yaml");
        return out;
    }

    private static void addIfSet(List<Path> out, String property, String relative) {
        String base = System.getProperty(property);
        if (base != null) {
            out.add(Path.of(base).resolve(relative));
        }
    }

    public static Config parse(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return toConfig(newYaml().load(reader), file.toString());
        } catch (IOException e) {
            throw new ConfigException("Cannot read " + file, e);
        } catch (RuntimeException e) {
            throw new ConfigException("Invalid YAML in " + file + ": " + e.getMessage(), e);
        }
    }

    public static Config parse(String yaml) {
        try {
            return toConfig(newYaml().load(yaml), "configuration");
        } catch (ConfigException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ConfigException("Invalid YAML: " + e.getMessage(), e);
        }
    }

    /** SafeConstructor only builds maps, lists and scalars – no arbitrary Java types from YAML tags. */
    private static Yaml newYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);
        options.setCodePointLimit(5 * 1024 * 1024);
        return new Yaml(new SafeConstructor(options));
    }

    private static Config toConfig(Object loaded, String source) {
        if (loaded == null) {
            return Config.empty();
        }
        if (!(loaded instanceof Map<?, ?> m)) {
            throw new ConfigException(source + " must contain a YAML mapping at the top level");
        }
        Map<String, Object> root = new LinkedHashMap<>();
        m.forEach((k, v) -> root.put(String.valueOf(k), v));
        return new Config(root);
    }
}
