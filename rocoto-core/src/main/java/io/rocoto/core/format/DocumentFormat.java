package io.rocoto.core.format;

/**
 * Serializes a response tree – {@link java.util.Map}, {@link java.util.List}, {@link String},
 * {@link Number}, {@link Boolean} and {@code null}, as produced by the value converter – into one
 * output format. The tree is already masked and type-converted; formats only decide the syntax.
 */
public interface DocumentFormat {

    /** Short name used in {@code ?format=} and in {@code api.formats}. */
    String id();

    /** Media type including parameters, e.g. {@code text/csv; charset=utf-8}. */
    String contentType();

    void write(Object tree, FormatOptions options, Appendable out);

    default String toString(Object tree, FormatOptions options) {
        StringBuilder sb = new StringBuilder(512);
        write(tree, options, sb);
        return sb.toString();
    }
}
