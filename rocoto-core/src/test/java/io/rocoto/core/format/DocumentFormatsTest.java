package io.rocoto.core.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import io.rocoto.core.json.JsonParser;

class DocumentFormatsTest {

    private static final DocumentFormats FORMATS = DocumentFormats.all();

    /** A tree with every awkward thing a Rocoto response can contain. */
    private static Map<String, Object> sample() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("used", 187904816L);
        usage.put("max", "9223372036854775807"); // large long, already a string after conversion
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("HeapMemoryUsage", usage);
        values.put("Verbose", false);
        values.put("Ratio", 0.5);
        values.put("NotANumber", "NaN");
        values.put("Name", "a \"quoted\" <b>bold</b> & 'single'\nsecond line\u0001");
        values.put("Numeric string", "42");
        values.put("Missing", null);
        values.put("Tags", List.of("a", "b"));
        values.put("Empty", List.of());
        values.put("Ünicode ☕", "grüße");
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("mbean", "java.lang:type=Memory");
        tree.put("values", values);
        return tree;
    }

    private static String render(String id, Object tree) {
        return FORMATS.byId(id).toString(tree, FormatOptions.DEFAULT);
    }

    @Test
    void jsonIsUnchanged() {
        String json = render("json", sample());
        assertEquals(io.rocoto.core.json.JsonWriter.toJson(sample()), json);
        assertEquals(sample().keySet(), ((Map<?, ?>) JsonParser.parse(json)).keySet());
    }

    @Test
    void xmlIsWellFormedAndKeepsValues() throws Exception {
        String xml = render("xml", sample());
        assertFalse(xml.contains("<!DOCTYPE"));
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Element root = doc.getDocumentElement();
        assertEquals("response", root.getTagName());
        assertEquals("java.lang:type=Memory", root.getElementsByTagName("mbean").item(0).getTextContent());
        Element used = (Element) root.getElementsByTagName("used").item(0);
        assertEquals("187904816", used.getTextContent());
        assertEquals("number", used.getAttribute("type"));
        Element name = (Element) root.getElementsByTagName("Name").item(0);
        assertTrue(name.getTextContent().startsWith("a \"quoted\" <b>bold</b> & 'single'\nsecond line"));
        assertEquals("true", ((Element) root.getElementsByTagName("Missing").item(0)).getAttribute("nil"));
        // keys that are no XML names become <entry key="...">
        assertTrue(xml.contains("<entry key=\"Numeric string\">42</entry>"), xml);
        assertTrue(xml.contains("<entry key=\"Ünicode ☕\">grüße</entry>"), xml);
        assertEquals(2, ((Element) root.getElementsByTagName("Tags").item(0)).getElementsByTagName("item").getLength());
    }

    @Test
    void yamlRoundTripsTypes() {
        String yaml = render("yaml", sample());
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        Map<?, ?> values = (Map<?, ?>) ((Map<?, ?>) parsed).get("values");
        assertEquals("42", values.get("Numeric string"), "numeric strings stay strings");
        assertEquals("9223372036854775807", ((Map<?, ?>) values.get("HeapMemoryUsage")).get("max"));
        assertEquals(Boolean.FALSE, values.get("Verbose"));
        assertEquals(0.5, values.get("Ratio"));
        assertNull(values.get("Missing"));
        assertTrue(values.containsKey("Missing"));
        assertEquals(List.of("a", "b"), values.get("Tags"));
        assertEquals("grüße", values.get("Ünicode ☕"));
    }

    @Test
    void tomlIsValidAndOmitsNull() {
        String toml = render("toml", sample());
        TomlParseResult r = Toml.parse(toml);
        assertFalse(r.hasErrors(), () -> r.errors() + "\n" + toml);
        assertEquals("java.lang:type=Memory", r.getString("mbean"));
        assertEquals(187904816L, r.getLong("values.HeapMemoryUsage.used"));
        assertEquals("42", r.getString(List.of("values", "Numeric string")));
        assertFalse(r.getBoolean("values.Verbose"));
        assertEquals(0.5, r.getDouble("values.Ratio"));
        assertFalse(r.contains("values.Missing"), "TOML has no null");
        assertEquals(2, r.getArray("values.Tags").size());
    }

    @Test
    void tomlArraysOfTablesAndNonMapRoots() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("kind", "threadpools");
        tree.put("items", List.of(Map.of("name", "http-nio-8080", "busyThreads", 3L),
                Map.of("name", "ajp", "busyThreads", 0L, "extra", Map.of("x", 1L))));
        TomlParseResult r = Toml.parse(render("toml", tree));
        assertFalse(r.hasErrors(), () -> r.errors().toString());
        assertEquals(2, r.getArray("items").size());
        assertEquals(1L, r.getArray("items").getTable(1).getLong("extra.x"));

        TomlParseResult wrapped = Toml.parse(render("toml", List.of(1L, "two")));
        assertFalse(wrapped.hasErrors(), () -> wrapped.errors().toString());
        assertEquals(2, wrapped.getArray("value").size());
    }

    @Test
    void htmlEscapesEverything() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("mbean", "x:type=<script>alert(1)</script>");
        tree.put("values", Map.of("evil\"><img src=x onerror=alert(1)>", "</td><script>alert(2)</script>"));
        String html = FORMATS.byId("html").toString(tree,
                new FormatOptions("/api/v1/read\"><script>", "/rocoto/api/v1/style.css", false, 60));
        assertFalse(html.contains("<script"), html);
        assertFalse(html.contains("<img"), html);
        assertFalse(html.contains("style="), "no inline styles");
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("<link rel=\"stylesheet\" href=\"/rocoto/api/v1/style.css\">"));
    }

    @Test
    void csvNeutralisesFormulasAndQuotes() {
        List<Object> items = List.of(
                mapOf("name", "=HYPERLINK(\"http://evil\",\"x\")", "value", -5L),
                mapOf("name", "a,b", "value", 1L),
                mapOf("name", "+1", "value", 2L),
                mapOf("name", "@SUM(A1)", "value", 3L));
        String csv = render("csv", Map.of("items", items));
        String[] lines = csv.split("\r\n");
        assertEquals("name,value", lines[0]);
        assertEquals("\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\",-5", lines[1]);
        assertEquals("\"a,b\",1", lines[2]);
        assertEquals("'+1,2", lines[3]);
        assertEquals("'@SUM(A1),3", lines[4]);
    }

    @Test
    void textIsOneLinePerValue() {
        String text = render("text", sample());
        assertTrue(text.contains("mbean: java.lang:type=Memory\n"));
        assertTrue(text.contains("values.HeapMemoryUsage.used: 187904816\n"));
        assertTrue(text.contains("values[\"Numeric string\"]: 42\n"), text);
        assertTrue(text.contains("values.Tags[1]: b\n"));
        assertTrue(text.contains("values.Empty: []\n"));
        assertTrue(text.contains("values.Missing: null\n"));
        assertTrue(text.contains("second line�"), "control characters cannot break lines");
        assertEquals(text.lines().count(), Values.flatten(sample()).size());
    }

    @Test
    void tablesForTheTypicalResponseShapes() {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("kind", "threadpools");
        resources.put("server", "tomcat");
        resources.put("items", List.of(
                mapOf("name", "http-nio-8080", "busyThreads", 3L, "maxThreads", 200L),
                mapOf("name", "ajp", "busyThreads", 12L)));
        String table = render("table", resources);
        assertEquals("""
                kind: threadpools
                server: tomcat

                ┌───────────────┬─────────────┬────────────┐
                │ name          │ busyThreads │ maxThreads │
                ├───────────────┼─────────────┼────────────┤
                │ http-nio-8080 │           3 │        200 │
                │ ajp           │          12 │            │
                └───────────────┴─────────────┴────────────┘
                """, table);
        String ascii = FORMATS.byId("table").toString(resources, new FormatOptions("t", null, true, 60));
        assertTrue(ascii.contains("+---------------+"), ascii);
        assertTrue(ascii.chars().allMatch(c -> c < 128));

        // pattern read: map of maps -> one row per MBean
        Table pattern = Table.of(Map.of("pattern", "java.lang:type=MemoryPool,*",
                "values", Map.of("java.lang:type=MemoryPool,name=Eden", Map.of("Name", "Eden"))));
        assertEquals(List.of("name", "Name"), pattern.columns());
        // search result: list of strings -> one column
        Table search = Table.of(Map.of("pattern", "*:*", "mbeans", List.of("a:b=c")));
        assertEquals(List.of("mbeans"), search.columns());
        // empty result list: still a valid table with one column
        Table empty = Table.of(Map.of("kind", "threadpools", "items", List.of()));
        assertEquals(List.of("items"), empty.columns());
        assertTrue(render("table", Map.of("kind", "x", "items", List.of())).contains("(no rows)"));
        // multi-attribute read: nested values become their own rows
        Table multi = Table.of(Map.of("mbean", "m", "values", Map.of("Heap", Map.of("used", 1L), "Verbose", true)));
        assertTrue(multi.rows().stream().anyMatch(r -> r.get(0).equals("Heap.used")), multi.toString());
        // everything else: flattened name/value pairs
        Table server = Table.of(Map.of("rocoto", Map.of("version", "1"), "caller", Map.of("name", "ada")));
        assertTrue(server.isKeyValue());
        assertEquals(2, server.rows().size());
    }

    @Test
    void tableShortensLongCells() {
        String table = FORMATS.byId("table").toString(Map.of("a", "x".repeat(200)), new FormatOptions("t", null, false, 20));
        assertTrue(table.contains("x".repeat(19) + "…"), table);
        assertFalse(table.contains("x".repeat(20)));
    }

    @Test
    void contentNegotiation() {
        assertEquals("json", FORMATS.negotiate(null).id());
        assertEquals("json", FORMATS.negotiate("*/*").id());
        assertEquals("json", FORMATS.negotiate("image/png").id());
        assertEquals("xml", FORMATS.negotiate("application/xml").id());
        assertEquals("yaml", FORMATS.negotiate("application/yaml, application/json;q=0.5").id());
        assertEquals("json", FORMATS.negotiate("application/yaml;q=0.4, application/json").id());
        // what a browser sends for navigation
        assertEquals("html", FORMATS.negotiate(
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,*/*;q=0.8").id());
        assertEquals("csv", FORMATS.negotiate("text/csv").id());
        assertEquals("text", FORMATS.negotiate("text/plain").id());

        DocumentFormats restricted = DocumentFormats.of(List.of("xml"));
        assertEquals(List.of("json", "xml"), restricted.ids());
        assertNull(restricted.byId("html"));
        assertEquals("json", restricted.negotiate("text/html").id());
        assertThrows(IllegalArgumentException.class, () -> DocumentFormats.of(List.of("pdf")));
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unused")
    private static List<Object> list(Object... v) {
        return Arrays.asList(v);
    }
}
