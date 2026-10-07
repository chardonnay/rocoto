package io.rocoto.core.format;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The available document formats and HTTP content negotiation between them.
 *
 * <p>Selection: an explicit {@code format} name wins; otherwise the {@code Accept} header is
 * evaluated with its quality values; without a usable preference the result is JSON.
 */
public final class DocumentFormats {

    public static final List<String> ALL = List.of("json", "xml", "yaml", "toml", "html", "csv", "text", "table");

    /** Accept media type -> format id. */
    private static final Map<String, String> MEDIA_TYPES = Map.ofEntries(
            Map.entry("application/json", "json"),
            Map.entry("application/xml", "xml"),
            Map.entry("text/xml", "xml"),
            Map.entry("application/yaml", "yaml"),
            Map.entry("application/x-yaml", "yaml"),
            Map.entry("text/yaml", "yaml"),
            Map.entry("application/toml", "toml"),
            Map.entry("text/html", "html"),
            Map.entry("application/xhtml+xml", "html"),
            Map.entry("text/csv", "csv"),
            Map.entry("text/plain", "text"));

    private final Map<String, DocumentFormat> enabled;

    private DocumentFormats(Map<String, DocumentFormat> enabled) {
        this.enabled = enabled;
    }

    public static DocumentFormats all() {
        return of(ALL);
    }

    /**
     * @param ids enabled format ids ({@code api.formats}); JSON is always enabled because errors
     *            fall back to it
     */
    public static DocumentFormats of(Collection<String> ids) {
        Map<String, DocumentFormat> map = new LinkedHashMap<>();
        map.put("json", new JsonFormat());
        for (String raw : ids) {
            String id = raw.trim().toLowerCase(Locale.ROOT);
            DocumentFormat f = switch (id) {
                case "json" -> null;
                case "xml" -> new XmlFormat();
                case "yaml" -> new YamlFormat();
                case "toml" -> new TomlFormat();
                case "html" -> new HtmlFormat();
                case "csv" -> new CsvFormat();
                case "text" -> new TextFormat();
                case "table" -> new TableFormat();
                default -> throw new IllegalArgumentException("Unknown output format '" + raw + "'; supported: " + ALL);
            };
            if (f != null) {
                map.put(id, f);
            }
        }
        return new DocumentFormats(map);
    }

    public DocumentFormat json() {
        return enabled.get("json");
    }

    public List<String> ids() {
        return new ArrayList<>(enabled.keySet());
    }

    /** @return the format, or {@code null} if it is unknown or not enabled */
    public DocumentFormat byId(String id) {
        return id == null ? null : enabled.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Picks the best enabled format for an {@code Accept} header. Media ranges such as
     * {@code text/*} and {@code *}{@code /*} and unknown types select nothing; then JSON is used.
     */
    public DocumentFormat negotiate(String accept) {
        if (accept == null || accept.isBlank()) {
            return json();
        }
        DocumentFormat best = null;
        double bestQ = 0;
        int bestOrder = Integer.MAX_VALUE;
        String[] ranges = accept.split(",");
        for (int i = 0; i < ranges.length && i < 64; i++) {
            String[] parts = ranges[i].split(";");
            String type = parts[0].trim().toLowerCase(Locale.ROOT);
            double q = 1;
            for (int p = 1; p < parts.length; p++) {
                String param = parts[p].trim();
                if (param.startsWith("q=")) {
                    try {
                        q = Double.parseDouble(param.substring(2));
                    } catch (NumberFormatException e) {
                        q = 0;
                    }
                }
            }
            if (type.equals("*/*") || type.equals("application/*")) {
                q = Math.min(q, 0.01); // wildcards prefer JSON
                type = "application/json";
            }
            String id = MEDIA_TYPES.get(type);
            DocumentFormat format = id == null ? null : enabled.get(id);
            if (format != null && q > 0 && (q > bestQ || (q == bestQ && i < bestOrder))) {
                best = format;
                bestQ = q;
                bestOrder = i;
            }
        }
        return best != null ? best : json();
    }
}
