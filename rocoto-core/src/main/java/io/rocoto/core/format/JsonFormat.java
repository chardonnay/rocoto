package io.rocoto.core.format;

import io.rocoto.core.json.JsonWriter;

final class JsonFormat implements DocumentFormat {

    @Override
    public String id() {
        return "json";
    }

    @Override
    public String contentType() {
        return "application/json";
    }

    @Override
    public void write(Object tree, FormatOptions options, Appendable out) {
        JsonWriter.write(tree, out);
    }
}
