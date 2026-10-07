package io.rocoto.core.format;

import java.io.IOException;
import java.util.List;

/**
 * RFC 4180 CSV with a header line. Scalar entries next to the table (see {@link Table}) are not
 * part of the CSV. Cells that a spreadsheet would treat as a formula are prefixed with {@code '}.
 */
final class CsvFormat implements DocumentFormat {

    @Override
    public String id() {
        return "csv";
    }

    @Override
    public String contentType() {
        return "text/csv; charset=utf-8; header=present";
    }

    @Override
    public void write(Object tree, FormatOptions options, Appendable out) {
        Table table = Table.of(tree);
        try {
            line(out, List.copyOf(table.columns()));
            for (List<Object> row : table.rows()) {
                line(out, row);
            }
        } catch (IOException e) {
            throw FormatException.wrap(e);
        }
    }

    private static void line(Appendable out, List<?> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            Object v = cells.get(i);
            out.append(escape(v instanceof Number || v instanceof Boolean ? String.valueOf(v) : guard(Values.cell(v))));
        }
        out.append("\r\n");
    }

    /** CSV/formula injection: =, +, -, @, tab and CR start a formula in common spreadsheets. */
    static String guard(String s) {
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            return "'" + s;
        }
        return s;
    }

    private static String escape(String s) {
        boolean quote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0
                || (!s.isEmpty() && (Character.isWhitespace(s.charAt(0))
                        || Character.isWhitespace(s.charAt(s.length() - 1))));
        return quote ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
