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
            if (options.status() >= 400) {
                Object message = tree instanceof Map<?, ?> m ? m.get("error") : null;
                out.append("<div class=\"alert\" role=\"alert\"><strong>").append(String.valueOf(options.status()));
                String reason = reason(options.status());
                if (reason != null) {
                    out.append(" ").append(reason);
                }
                out.append("</strong>");
                if (message != null) {
                    out.append("<span>").append(escape(String.valueOf(message))).append("</span>");
                }
                out.append("</div>\n");
                if (isPlainError(tree)) {
                    // The banner says it all; a table repeating status and message adds nothing.
                    out.append("</main>\n</body>\n</html>\n");
                    return;
                }
            }
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

    /** The standard error body {@code {"status": ..., "error": ...}} and nothing else. */
    private static boolean isPlainError(Object tree) {
        return tree instanceof Map<?, ?> m && m.size() == 2 && m.containsKey("status") && m.containsKey("error");
    }

    private static String reason(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 413 -> "Content Too Large";
            case 415 -> "Unsupported Media Type";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> null;
        };
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
        } else if (Values.isScalarList(v)) {
            // Plain values (thread ids, role names, ...) as one compact, wrapping line.
            out.append("<span class=\"list\">").append(escape(Values.cell(v))).append("</span>");
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
