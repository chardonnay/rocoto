package io.rocoto.metrics.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigLoader;
import io.rocoto.core.json.JsonParser;
import io.rocoto.metrics.MetricFamily;
import io.rocoto.metrics.MetricRule;

class MetricFormatsTest {

    private static final long NOW = 1_759_000_000_123L;

    private static Map<String, String> labels(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static List<MetricFamily> families() {
        MetricFamily heap = new MetricFamily("jvm_memory_heap_used_bytes", MetricRule.Type.GAUGE, "Used heap");
        heap.addSample(Map.of(), 187904816);
        MetricFamily gc = new MetricFamily("jvm_gc_collections_total", MetricRule.Type.COUNTER, "GCs");
        gc.addSample(labels("gc", "G1 Young Generation"), 42);
        MetricFamily load = new MetricFamily("system_load_average_1m", MetricRule.Type.GAUGE, "Load");
        load.addSample(Map.of(), 1.25);
        MetricFamily odd = new MetricFamily("odd", MetricRule.Type.GAUGE, "Odd");
        odd.addSample(labels("pool", "a,b=c d\nx", "empty", ""), -3);
        odd.addSample(labels("pool", "nan"), Double.NaN);
        return List.of(heap, gc, load, odd);
    }

    @Test
    void influxLineProtocol() {
        String out = new InfluxFormat("ns").toString(families(), NOW);
        assertTrue(out.contains("jvm_memory_heap_used_bytes value=187904816.0 1759000000123000000\n"), out);
        assertTrue(out.contains("jvm_gc_collections_total,gc=G1\\ Young\\ Generation value=42.0 "), out);
        assertTrue(out.contains("system_load_average_1m value=1.25 "), out);
        // escaping keeps every sample on one line; empty tag values and NaN are dropped
        assertTrue(out.contains("odd,pool=a\\,b\\=c\\ d\\ x value=-3.0 "), out);
        assertEquals(4, out.lines().count(), out);
        assertTrue(new InfluxFormat("s").toString(families(), NOW).contains(" 1759000000\n"));
    }

    @Test
    void graphitePlaintext() {
        String out = new GraphiteFormat("prod.app1", false).toString(families(), NOW);
        assertTrue(out.contains("prod.app1.jvm_memory_heap_used_bytes 187904816 1759000000\n"), out);
        assertTrue(out.contains("prod.app1.jvm_gc_collections_total.G1_Young_Generation 42 1759000000\n"), out);
        assertTrue(out.contains("prod.app1.odd.a_b_c_d_x._ -3 1759000000\n"), out);
        assertEquals(4, out.lines().count());

        String tagged = new GraphiteFormat("", true).toString(families(), NOW);
        assertTrue(tagged.contains("jvm_gc_collections_total;gc=G1_Young_Generation 42 1759000000\n"), tagged);
        assertTrue(tagged.contains("odd;pool=a_b_c_d_x -3 "), tagged);
    }

    @Test
    void statsdGaugesAndCounterDeltas() {
        StatsdFormat pull = new StatsdFormat("rocoto", false, false);
        List<String> lines = pull.lines(families());
        assertTrue(lines.contains("rocoto.jvm_gc_collections_total.G1_Young_Generation:42|g"), lines.toString());
        // a negative gauge is reset to 0 first, otherwise StatsD reads it as a decrement
        int zero = lines.indexOf("rocoto.odd.a_b_c_d_x._:0|g");
        assertEquals(zero + 1, lines.indexOf("rocoto.odd.a_b_c_d_x._:-3|g"), lines.toString());

        StatsdFormat push = new StatsdFormat("rocoto", true, true);
        assertFalse(push.lines(families()).stream().anyMatch(l -> l.endsWith("|c|#gc:G1_Young_Generation")),
                "no delta without a baseline");
        MetricFamily gc = new MetricFamily("jvm_gc_collections_total", MetricRule.Type.COUNTER, "GCs");
        gc.addSample(labels("gc", "G1 Young Generation"), 50);
        assertEquals(List.of("rocoto.jvm_gc_collections_total:8|c|#gc:G1_Young_Generation"), push.lines(List.of(gc)));
        MetricFamily reset = new MetricFamily("jvm_gc_collections_total", MetricRule.Type.COUNTER, "GCs");
        reset.addSample(labels("gc", "G1 Young Generation"), 3);
        assertEquals(List.of("rocoto.jvm_gc_collections_total:3|c|#gc:G1_Young_Generation"), push.lines(List.of(reset)),
                "a smaller value is a counter reset");
    }

    @Test
    void otlpJsonForPull() {
        MetricFormats formats = new MetricFormats(ConfigLoader.parse("push:\n  otlp:\n    serviceName: shop"));
        String json = formats.byId("otlp", null).toString(families(), NOW);
        Map<String, Object> parsed = JsonParser.parseObject(json);
        assertTrue(json.contains("\"stringValue\":\"shop\""), json);
        assertTrue(json.contains("\"isMonotonic\":true"));
        assertEquals(1, ((List<?>) parsed.get("resourceMetrics")).size());
    }

    @Test
    void registryAndNegotiation() {
        MetricFormats formats = new MetricFormats(Config.empty());
        for (String id : MetricFormats.IDS) {
            assertEquals(id, formats.byId(id, null).id());
        }
        assertNull(formats.byId("pdf", null));
        assertEquals("openmetrics", formats.negotiate("application/openmetrics-text; version=1.0.0").id());
        assertEquals("prometheus", formats.negotiate("*/*").id());
        assertTrue(formats.byId("graphite", null).toString(families(), NOW).startsWith("rocoto."));
    }
}
