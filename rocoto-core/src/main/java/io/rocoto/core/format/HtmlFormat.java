package io.rocoto.core.format;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * A complete HTML page: scalar entries as a definition list, the tabular part (see {@link Table})
 * as a {@code <table>}, nested values as nested tables and lists. Every value is HTML-escaped; the
 * page contains no script and no inline style – styling comes from the linked stylesheet, so a
 * strict Content-Security-Policy ({@code style-src 'self'}) suffices.
 */
final class HtmlFormat implements DocumentFormat {

    private static final int MAX_NESTING = 8;

    @Override
    public String id() {
        return "html";
    }

    @Override
    public String contentType() {
        return "text/html; charset=utf-8";
    }

    @Override
    public void write(Object tree, FormatOptions options, Appendable out) {
        Table table = Table.of(tree);
        try {
            out.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                    .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                    .append("<title>").append(escape(options.title())).append(" – Rocoto</title>\n");
            if (options.stylesheet() != null) {
                out.append("<link rel=\"stylesheet\" href=\"").append(escape(options.stylesheet())).append("\">\n");
            }
            out.append("</head>\n<body>\n<header><span class=\"brand\">Rocoto</span></header>\n<main>\n")
                    .append("<h1>").append(escape(options.title())).append("</h1>\n");
            if (!table.meta().isEmpty()) {
                out.append("<dl class=\"meta\">\n");
                for (Map.Entry<String, Object> e : table.meta().entrySet()) {
                    out.append("<dt>").append(escape(e.getKey())).append("</dt><dd>");
                    value(out, e.getValue(), 0);
                    out.append("</dd>\n");
                }
                out.append("</dl>\n");
            }
            out.append("<div class=\"scroll\"><table>\n");
            if (table.caption() != null) {
                out.append("<caption>").append(escape(table.caption())).append("</caption>\n");
            }
            out.append("<thead><tr>");
            for (String c : table.columns()) {
                out.append("<th scope=\"col\">").append(escape(c)).append("</th>");
            }
            out.append("</tr></thead>\n<tbody>\n");
            for (List<Object> row : table.rows()) {
                out.append("<tr>");
                for (Object cell : row) {
                    out.append(cell instanceof Number ? "<td class=\"num\">" : "<td>");
                    value(out, cell, 0);
                    out.append("</td>");
                }
                out.append("</tr>\n");
            }
            if (table.rows().isEmpty()) {
                out.append("<tr><td class=\"empty\" colspan=\"").append(String.valueOf(table.columns().size()))
                        .append("\">no rows</td></tr>\n");
            }
            out.append("</tbody>\n</table></div>\n</main>\n</body>\n</html>\n");
        } catch (IOException e) {
            throw FormatException.wrap(e);
        }
    }

    private static void value(Appendable out, Object v, int depth) throws IOException {
        if (v == null) {
            out.append("<span class=\"null\">null</span>");
        } else if (depth >= MAX_NESTING || Values.isScalar(v)) {
            String text = Values.cell(v);
            if (v instanceof Boolean) {
                out.append("<span class=\"bool\">").append(escape(text)).append("</span>");
            } else {
                out.append(escape(text));
            }
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                out.append("<span class=\"null\">{}</span>");
                return;
            }
            out.append("<table class=\"nested\">");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.append("<tr><th scope=\"row\">").append(escape(String.valueOf(e.getKey()))).append("</th><td>");
                value(out, e.getValue(), depth + 1);
                out.append("</td></tr>");
            }
            out.append("</table>");
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                out.append("<span class=\"null\">[]</span>");
                return;
            }
            out.append("<ul>");
            for (Object item : l) {
                out.append("<li>");
                value(out, item, depth + 1);
                out.append("</li>");
            }
            out.append("</ul>");
        }
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c < 0x20 && c != '\n' && c != '\t' ? '�' : c);
            }
        }
        return sb.toString();
    }
}
