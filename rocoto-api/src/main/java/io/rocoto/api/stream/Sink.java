package io.rocoto.api.stream;

import java.io.IOException;
import java.util.Map;

/** Where subscription events go: an SSE response or a WebSocket session. */
public interface Sink {

    /** @param event {@code value}, {@code notification}, {@code error} or {@code heartbeat} */
    void send(String event, Map<String, Object> data) throws IOException;

    void close();
}
