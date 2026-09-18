package io.rocoto.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** All samples of one metric name. */
public final class MetricFamily {

    public record Sample(Map<String, String> labels, double value) {
    }

    private final String name;
    private final MetricRule.Type type;
    private final String help;
    private final List<Sample> samples = new ArrayList<>();

    public MetricFamily(String name, MetricRule.Type type, String help) {
        this.name = name;
        this.type = type;
        this.help = help;
    }

    public String name() {
        return name;
    }

    public MetricRule.Type type() {
        return type;
    }

    public String help() {
        return help;
    }

    public List<Sample> samples() {
        return samples;
    }

    /** Adds a sample unless the same label set is already present. */
    public void addSample(Map<String, String> labels, double value) {
        add(labels, value);
    }

    void add(Map<String, String> labels, double value) {
        for (Sample s : samples) {
            if (s.labels().equals(labels)) {
                return; // same series from an overlapping rule: first one wins
            }
        }
        samples.add(new Sample(labels, value));
    }
}
