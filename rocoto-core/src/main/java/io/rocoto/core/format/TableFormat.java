package io.rocoto.core.format;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Aligned text table with box-drawing (or ASCII) borders; scalar entries are printed above it. */
final class TableFormat implements DocumentFormat {

    @Override
    public String id() {
        return "table";
    }

    @Override
    public String contentType() {
        return "text/plain; charset=utf-8";
    }

    @Override
    public void write(Object tree, FormatOptions options, Appendable out) {
        Table table = Table.of(tree);
        try {
            for (Map.Entry<String, Object> e : table.meta().entrySet()) {
                out.append(Values.oneLine(e.getKey())).append(": ").append(Values.oneLine(Values.cell(e.getValue())))
                        .append('\n');
            }
            if (!table.meta().isEmpty()) {
                out.append('\n');
            }
            render(table, options, out);
        } catch (IOException e) {
            throw FormatException.wrap(e);
        }
    }

    private static void render(Table table, FormatOptions options, Appendable out) throws IOException {
        boolean ascii = options.ascii();
        int max = options.maxColumnWidth();
        List<String> header = new ArrayList<>();
        table.columns().forEach(c -> header.add(fit(Values.oneLine(c), max, ascii)));
        List<List<String>> cells = new ArrayList<>();
        boolean[] numeric = new boolean[header.size()];
        java.util.Arrays.fill(numeric, true);
        for (List<Object> row : table.rows()) {
            List<String> line = new ArrayList<>(row.size());
            for (int i = 0; i < row.size(); i++) {
                Object v = row.get(i);
                if (v != null && !(v instanceof Number)) {
                    numeric[i] = false;
                }
                line.add(fit(Values.oneLine(Values.cell(v)), max, ascii));
            }
            cells.add(line);
        }
        int[] width = new int[header.size()];
        for (int i = 0; i < width.length; i++) {
            width[i] = length(header.get(i));
            for (List<String> line : cells) {
                width[i] = Math.max(width[i], length(line.get(i)));
            }
        }
        String h = ascii ? "-" : "─";
        String v = ascii ? "|" : "│";
        border(out, width, h, ascii ? "+" : "┌", ascii ? "+" : "┬", ascii ? "+" : "┐");
        row(out, header, width, v, new boolean[width.length]);
        border(out, width, h, ascii ? "+" : "├", ascii ? "+" : "┼", ascii ? "+" : "┤");
        for (List<String> line : cells) {
            row(out, line, width, v, numeric);
        }
        border(out, width, h, ascii ? "+" : "└", ascii ? "+" : "┴", ascii ? "+" : "┘");
        if (cells.isEmpty()) {
            out.append("(no rows)\n");
        }
    }

    private static void border(Appendable out, int[] width, String h, String left, String mid, String right)
            throws IOException {
        out.append(left);
        for (int i = 0; i < width.length; i++) {
            out.append(h.repeat(width[i] + 2)).append(i == width.length - 1 ? right : mid);
        }
        out.append('\n');
    }

    private static void row(Appendable out, List<String> cells, int[] width, String v, boolean[] rightAlign)
            throws IOException {
        out.append(v);
        for (int i = 0; i < width.length; i++) {
            String cell = cells.get(i);
            String pad = " ".repeat(width[i] - length(cell));
            out.append(' ').append(rightAlign[i] ? pad + cell : cell + pad).append(' ').append(v);
        }
        out.append('\n');
    }

    private static int length(String s) {
        return s.codePointCount(0, s.length());
    }

    private static String fit(String s, int max, boolean ascii) {
        if (length(s) <= max) {
            return s;
        }
        String ellipsis = ascii ? "..." : "…";
        int keep = max - length(ellipsis);
        return s.substring(0, s.offsetByCodePoints(0, keep)) + ellipsis;
    }
}
