package io.rocoto.core.format;

import java.io.IOException;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;

/**
 * YAML 1.1 in block style via SnakeYAML. Strings that would read as numbers, booleans or null are
 * quoted, so the types survive a round trip.
 */
final class YamlFormat implements DocumentFormat {

    private static final ThreadLocal<Yaml> YAML = ThreadLocal.withInitial(() -> {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        options.setSplitLines(false);
        options.setAllowUnicode(true);
        return new Yaml(new Representer(options), options);
    });

    @Override
    public String id() {
        return "yaml";
    }

    @Override
    public String contentType() {
        return "application/yaml; charset=utf-8";
    }

    @Override
    public void write(Object tree, FormatOptions options, Appendable out) {
        try {
            out.append(YAML.get().dump(tree));
        } catch (IOException e) {
            throw FormatException.wrap(e);
        }
    }
}
