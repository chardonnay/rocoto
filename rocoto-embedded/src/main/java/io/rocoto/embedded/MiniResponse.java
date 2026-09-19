package io.rocoto.embedded;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The supported part of {@code HttpServletResponse}. The body is buffered and sent with a
 * {@code Content-Length} when the request ends; an explicit flush (Server-Sent Events) commits the
 * response and switches to streaming.
 */
final class MiniResponse {

    private final Exchange exchange;
    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
    private int status = 200;
    private String contentType;
    private String characterEncoding;
    private boolean committed;
    private boolean finished;
    private PrintWriter writer;
    private ServletOutputStream stream;
    HttpServletResponse proxy;

    MiniResponse(Exchange exchange) {
        this.exchange = exchange;
    }

    // ---- status and headers -----------------------------------------------------------------------

    public void setStatus(int code) {
        if (!committed) {
            status = code;
        }
    }

    public int getStatus() {
        return status;
    }

    public void sendError(int code) throws IOException {
        sendError(code, null);
    }

    public void sendError(int code, String message) throws IOException {
        if (committed) {
            throw new IllegalStateException("response is already committed");
        }
        resetBuffer();
        status = code;
        setContentType("application/json");
        setCharacterEncoding("UTF-8");
        String text = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        getWriter().write("{\"status\":" + code + ",\"error\":\"" + text + "\"}");
    }

    public void setHeader(String name, String value) {
        if (committed) {
            return;
        }
        if ("Content-Type".equalsIgnoreCase(name)) {
            setContentType(value);
            return;
        }
        List<String> values = new ArrayList<>(1);
        values.add(value);
        headers.put(name, values);
    }

    public void addHeader(String name, String value) {
        if (committed) {
            return;
        }
        if ("Content-Type".equalsIgnoreCase(name)) {
            setContentType(value);
            return;
        }
        headers.computeIfAbsent(name, k -> new ArrayList<>(1)).add(value);
    }

    public void setIntHeader(String name, int value) {
        setHeader(name, Integer.toString(value));
    }

    public void addIntHeader(String name, int value) {
        addHeader(name, Integer.toString(value));
    }

    public void setDateHeader(String name, long date) {
        setHeader(name, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.Instant.ofEpochMilli(date).atZone(java.time.ZoneOffset.UTC)));
    }

    public boolean containsHeader(String name) {
        return headers.containsKey(name);
    }

    public String getHeader(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public Collection<String> getHeaders(String name) {
        List<String> values = headers.get(name);
        return values == null ? List.of() : List.copyOf(values);
    }

    public Collection<String> getHeaderNames() {
        return List.copyOf(headers.keySet());
    }

    public void setContentType(String type) {
        if (committed || type == null) {
            return;
        }
        int semicolon = type.indexOf(';');
        if (semicolon >= 0) {
            for (String part : type.substring(semicolon + 1).split(";")) {
                String p = part.trim();
                if (p.regionMatches(true, 0, "charset=", 0, 8) && writer == null) {
                    characterEncoding = p.substring(8).replace("\"", "").trim();
                }
            }
            contentType = type.substring(0, semicolon).trim();
        } else {
            contentType = type.trim();
        }
    }

    public String getContentType() {
        if (contentType == null) {
            return null;
        }
        return characterEncoding == null ? contentType : contentType + ";charset=" + characterEncoding;
    }

    public void setCharacterEncoding(String encoding) {
        if (!committed && writer == null) {
            characterEncoding = encoding;
        }
    }

    public String getCharacterEncoding() {
        return characterEncoding == null ? "ISO-8859-1" : characterEncoding;
    }

    public void setContentLength(int length) {
        // computed from the buffer
    }

    public void setContentLengthLong(long length) {
        // computed from the buffer
    }

    public void setLocale(Locale locale) {
        // not used
    }

    public Locale getLocale() {
        return Locale.getDefault();
    }

    // ---- body ---------------------------------------------------------------------------------------

    public PrintWriter getWriter() {
        if (stream != null) {
            throw new IllegalStateException("getOutputStream() has already been called");
        }
        if (writer == null) {
            Charset charset = characterEncoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(characterEncoding);
            writer = new PrintWriter(new OutputStreamWriter(new Body(), charset), false);
        }
        return writer;
    }

    public ServletOutputStream getOutputStream() {
        if (writer != null) {
            throw new IllegalStateException("getWriter() has already been called");
        }
        if (stream == null) {
            Body body = new Body();
            stream = new ServletOutputStream() {
                @Override
                public void write(int b) throws IOException {
                    body.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    body.write(b, off, len);
                }

                @Override
                public void flush() throws IOException {
                    body.flush();
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    throw new UnsupportedOperationException("non-blocking I/O is not supported by the embedded Rocoto runtime");
                }
            };
        }
        return stream;
    }

    public boolean isCommitted() {
        return committed;
    }

    public void resetBuffer() {
        if (committed) {
            throw new IllegalStateException("response is already committed");
        }
        if (writer != null) {
            writer.flush();     // drains the encoder into the buffer, which is cleared next
        }
        buffer.reset();
    }

    public void reset() {
        resetBuffer();
        headers.clear();
        status = 200;
        contentType = null;
    }

    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        } else {
            commit(-1);
            drain();
        }
    }

    public int getBufferSize() {
        return Integer.MAX_VALUE;
    }

    public void setBufferSize(int size) {
        // the buffer grows as needed
    }

    /** Called by the engine when the request (or the async cycle) is over. */
    synchronized void finish() {
        if (finished) {
            return;
        }
        finished = true;
        try {
            if (writer != null) {
                flushing = false;
                writer.flush();
            }
            if (!committed) {
                commit(buffer.size());
            }
            drain();
        } catch (IOException e) {
            // the client is gone
        } finally {
            exchange.end();
        }
    }

    private boolean flushing = true;

    private void commit(long contentLength) throws IOException {
        if (committed) {
            return;
        }
        committed = true;
        Map<String, List<String>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        out.putAll(headers);
        String type = getContentType();
        if (type != null) {
            out.put("Content-Type", List.of(type));
        }
        exchange.begin(status, out, contentLength);
    }

    private void drain() throws IOException {
        if (buffer.size() > 0) {
            byte[] bytes = buffer.toByteArray();
            buffer.reset();
            exchange.write(bytes, 0, bytes.length);
        }
    }

    /** Buffers until the first explicit flush, streams afterwards. */
    private final class Body extends OutputStream {

        @Override
        public void write(int b) {
            buffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            synchronized (MiniResponse.this) {
                if (!flushing) {
                    return;         // final flush of finish(): keep the buffer for Content-Length
                }
                if (finished) {
                    throw new IOException("response is complete");
                }
                commit(-1);
                drain();
            }
        }
    }
}
