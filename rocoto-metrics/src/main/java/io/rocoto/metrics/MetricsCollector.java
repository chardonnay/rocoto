package io.rocoto.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import io.rocoto.core.convert.ValueConverter;
import io.rocoto.core.jmx.MBeanAccessException;
import io.rocoto.security.AccessDeniedException;
import io.rocoto.security.CallContext;
import io.rocoto.security.RequestPipeline;

/**
 * Turns mapping rules into metric samples. Every value is read through the request pipeline with
 * the identity of the scraper, so a metrics token only ever sees what its roles and MBean scope
 * allow. Denied, masked, missing and non-numeric values are skipped silently.
 */
public final class MetricsCollector {

    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");

    private final List<MetricRule> rules;

    public MetricsCollector(List<MetricRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<MetricFamily> collect(CallContext ctx, RequestPipeline pipeline) {
        Map<String, MetricFamily> families = new LinkedHashMap<>();
        for (MetricRule rule : rules) {
            for (ObjectName name : pipeline.search(ctx, rule.mbean())) {
                Object value;
                try {
                    value = pipeline.read(ctx, name, rule.attribute());
                } catch (AccessDeniedException | MBeanAccessException e) {
                    continue;
                }
                if (ValueConverter.MASK.equals(value)) {
                    continue;
                }
                MetricFamily family = families.computeIfAbsent(rule.name(),
                        n -> new MetricFamily(n, rule.type(), rule.help()));
                addSamples(family, rule, name, value);
            }
        }
        return new ArrayList<>(families.values());
    }

    private static void addSamples(MetricFamily family, MetricRule rule, ObjectName name, Object value) {
        Map<String, String> labels = labels(rule, name);
        if (value instanceof CompositeData cd) {
            if (rule.path() != null) {
                if (cd.containsKey(rule.path())) {
                    add(family, labels, cd.get(rule.path()), rule.scale());
                }
                return;
            }
            for (String key : cd.getCompositeType().keySet()) {
                Map<String, String> withKey = new LinkedHashMap<>(labels);
                withKey.put("key", key);
                add(family, withKey, cd.get(key), rule.scale());
            }
            return;
        }
        add(family, labels, value, rule.scale());
    }

    private static void add(MetricFamily family, Map<String, String> labels, Object value, double scale) {
        Double number = value instanceof Number n ? Double.valueOf(n.doubleValue())
                : value instanceof Boolean b ? Double.valueOf(b ? 1 : 0) : null;
        if (number != null && !number.isNaN()) {
            family.add(labels, number * scale);
        }
    }

    private static Map<String, String> labels(MetricRule rule, ObjectName name) {
        Map<String, String> labels = new LinkedHashMap<>();
        rule.labels().forEach((label, template) -> {
            Matcher m = TEMPLATE.matcher(template);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String prop = name.getKeyProperty(m.group(1));
                if (prop != null && prop.startsWith("\"")) {
                    prop = ObjectName.unquote(prop);
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(prop == null ? "" : prop));
            }
            m.appendTail(sb);
            labels.put(label, sb.toString());
        });
        return labels;
    }
}
