package io.rocoto.tools;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

/**
 * Edits {@code rocoto-users.yaml}. The file is rewritten atomically with owner-only permissions;
 * a running Rocoto picks the change up within a few seconds. Comments in the file are not kept.
 */
final class UserFile {

    private final Path file;

    UserFile(Path file) {
        this.file = file;
    }

    void putUser(String name, String passwordHash, List<String> roles) throws IOException {
        Map<String, Object> root = load();
        List<Map<String, Object>> users = section(root, "users");
        users.removeIf(u -> name.equals(u.get("name")));
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("name", name);
        user.put("password", passwordHash);
        user.put("roles", roles);
        users.add(user);
        save(root);
    }

    void removeUser(String name) throws IOException {
        Map<String, Object> root = load();
        if (!section(root, "users").removeIf(u -> name.equals(u.get("name")))) {
            throw new IllegalArgumentException("no such user: " + name);
        }
        save(root);
    }

    void putToken(String id, String hash, List<String> roles, Instant expires, List<String> mbeans)
            throws IOException {
        Map<String, Object> root = load();
        List<Map<String, Object>> tokens = section(root, "tokens");
        tokens.removeIf(t -> id.equals(t.get("id")));
        Map<String, Object> token = new LinkedHashMap<>();
        token.put("id", id);
        token.put("hash", hash);
        token.put("roles", roles);
        if (expires != null) {
            token.put("expires", expires.toString());
        }
        if (!mbeans.isEmpty()) {
            token.put("mbeans", mbeans);
        }
        tokens.add(token);
        save(root);
    }

    void removeToken(String id) throws IOException {
        Map<String, Object> root = load();
        if (!section(root, "tokens").removeIf(t -> id.equals(t.get("id")))) {
            throw new IllegalArgumentException("no such token: " + id);
        }
        save(root);
    }

    private Map<String, Object> load() throws IOException {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            Map<String, Object> root = new LinkedHashMap<>();
            if (loaded instanceof Map<?, ?> m) {
                m.forEach((k, v) -> root.put(String.valueOf(k), v));
            }
            return root;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> section(Map<String, Object> root, String key) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (root.get(key) instanceof List<?> existing) {
            for (Object o : existing) {
                if (o instanceof Map<?, ?> m) {
                    list.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
        }
        root.put(key, list);
        return list;
    }

    private void save(Map<String, Object> root) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        String yaml = "# Rocoto built-in credentials. Managed with rocoto-tools; contains only hashes.\n"
                + new Yaml(new Representer(options), options).dump(root);
        Path absolute = file.toAbsolutePath();
        Path tmp = Files.createTempFile(absolute.getParent(), ".rocoto-users", ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                // non-POSIX file system
            }
            Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
            Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
