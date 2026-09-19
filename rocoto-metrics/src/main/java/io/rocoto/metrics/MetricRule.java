package io.rocoto.metrics;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;

/**
 * Maps one MBean attribute to a metric.
 *
 * @param mbean     ObjectName or pattern
 * @param attribute attribute to read
 * @param path      key inside a composite value (e.g. {@code used} of {@code HeapMemoryUsage}); if
 *                  {@code null} and the value is composite, every numeric key becomes a sample with a
 *                  {@code key} label
 * @param labels    label templates; {@code ${prop}} is replaced by the ObjectName key property,
 *                  {@code ${prop|basename}} by the part of it after the last {@code /}
 * @param scale     multiplier, e.g. {@code 0.001} to turn milliseconds into seconds
 */
public record MetricRule(ObjectName mbean, String attribute, String path, String name, Type type, String help,
        Map<String, String> labels, double scale) {

    public enum Type { GAUGE, COUNTER }

    static MetricRule fromConfig(Config c, String where) {
        String mbean = required(c, "mbean", where);
        String name = required(c, "name", where);
        if (!name.matches("[a-zA-Z_:][a-zA-Z0-9_:]*")) {
            throw new ConfigException(where + ": invalid metric name '" + name + "'");
        }
        Type type;
        try {
            type = Type.valueOf(c.string("type", "gauge").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(where + ": type must be gauge or counter");
        }
        if (type == Type.COUNTER && !name.endsWith("_total")) {
            throw new ConfigException(where + ": counter names must end with _total (" + name + ")");
        }
        Map<String, String> labels = new LinkedHashMap<>();
        c.sub("labels").asMap().forEach((k, v) -> {
            if (!k.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                throw new ConfigException(where + ": invalid label name '" + k + "'");
            }
            labels.put(k, String.valueOf(v));
        });
        Object scale = c.get("scale");
        try {
            return new MetricRule(new ObjectName(mbean), required(c, "attribute", where), c.string("path", null),
                    name, type, c.string("help", name), labels,
                    scale instanceof Number n ? n.doubleValue() : 1.0);
        } catch (MalformedObjectNameException e) {
            throw new ConfigException(where + ": invalid ObjectName '" + mbean + "'");
        }
    }

    private static String required(Config c, String key, String where) {
        String v = c.string(key, null);
        if (v == null) {
            throw new ConfigException(where + ": '" + key + "' is required");
        }
        return v;
    }
}
