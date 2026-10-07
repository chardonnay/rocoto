package io.rocoto.core.format;

/**
 * Per-request presentation options.
 *
 * @param title          heading of the HTML page, e.g. the request path
 * @param stylesheet     URL of the stylesheet the HTML page links, or {@code null} for none
 * @param ascii          {@code true}: plain ASCII table borders instead of box-drawing characters
 * @param maxColumnWidth longest cell in the text table before it is shortened
 * @param status         HTTP status of the response; 400 and above is shown as an error in HTML
 */
public record FormatOptions(String title, String stylesheet, boolean ascii, int maxColumnWidth, int status) {

    public static final FormatOptions DEFAULT = new FormatOptions("Rocoto", null, false, 60);

    public FormatOptions {
        maxColumnWidth = Math.max(8, maxColumnWidth);
    }

    public FormatOptions(String title, String stylesheet, boolean ascii, int maxColumnWidth) {
        this(title, stylesheet, ascii, maxColumnWidth, 200);
    }

    public FormatOptions withStatus(int newStatus) {
        return new FormatOptions(title, stylesheet, ascii, maxColumnWidth, newStatus);
    }
}
