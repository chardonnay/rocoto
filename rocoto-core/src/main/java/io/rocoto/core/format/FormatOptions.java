package io.rocoto.core.format;

/**
 * Per-request presentation options.
 *
 * @param title          heading of the HTML page, e.g. the request path
 * @param stylesheet     URL of the stylesheet the HTML page links, or {@code null} for none
 * @param ascii          {@code true}: plain ASCII table borders instead of box-drawing characters
 * @param maxColumnWidth longest cell in the text table before it is shortened
 */
public record FormatOptions(String title, String stylesheet, boolean ascii, int maxColumnWidth) {

    public static final FormatOptions DEFAULT = new FormatOptions("Rocoto", null, false, 60);

    public FormatOptions {
        maxColumnWidth = Math.max(8, maxColumnWidth);
    }
}
