package io.rocoto.api.stream;

import java.io.IOException;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.rocoto.api.Http;
import io.rocoto.api.RocotoRuntime;
import io.rocoto.api.stream.SubscriptionManager.Subscription;
import io.rocoto.core.json.JsonParser;
import io.rocoto.core.json.JsonWriter;
import io.rocoto.security.CallContext;
import io.rocoto.security.servlet.RocotoPrincipal;
import jakarta.servlet.ServletContext;
import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;

/**
 * WebSocket endpoint {@code /api/v1/ws} with the same subscription model as SSE, multiplexed:
 * <pre>
 * -&gt; {"action":"subscribe","id":"heap","mbean":"java.lang:type=Memory","attributes":["HeapMemoryUsage"],"interval":1000}
 * &lt;- {"id":"heap","event":"value","data":{...}}
 * -&gt; {"action":"unsubscribe","id":"heap"}
 * </pre>
 * The upgrade request passes the auth filter like any other request; the identity travels in the
 * handshake principal. A session without a Rocoto identity is closed immediately.
 */
public class WsEndpoint extends Endpoint {

    public static final String PATH = "/api/v1/ws";

    private static final Logger LOG = Logger.getLogger(WsEndpoint.class.getName());

    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    /** Registers the endpoint if the server provides a WebSocket container. */
    public static void register(ServletContext context) {
        Object container = context.getAttribute("jakarta.websocket.server.ServerContainer");
        if (!(container instanceof ServerContainer serverContainer)) {
            LOG.info("No WebSocket container available - streaming is offered over SSE only");
            return;
        }
        try {
            serverContainer.addEndpoint(ServerEndpointConfig.Builder.create(WsEndpoint.class, PATH).build());
        } catch (DeploymentException | RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot register the WebSocket endpoint - streaming is offered over SSE only", e);
        }
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        Principal principal = session.getUserPrincipal();
        RocotoRuntime runtime = RocotoRuntime.current();
        if (!(principal instanceof RocotoPrincipal rocotoPrincipal) || runtime == null) {
            close(session, CloseCodes.VIOLATED_POLICY, "Authentication required");
            return;
        }
        CallContext ctx = new CallContext(rocotoPrincipal.identity(), "websocket", "stream");
        session.setMaxTextMessageBufferSize(64 * 1024);
        session.addMessageHandler(new MessageHandler.Whole<String>() {
            @Override
            public void onMessage(String message) {
                handle(session, runtime, ctx, message);
            }
        });
    }

    private void handle(Session session, RocotoRuntime runtime, CallContext ctx, String message) {
        String id = null;
        try {
            Map<String, Object> msg = JsonParser.parseObject(message);
            id = msg.get("id") instanceof String s ? s : null;
            String action = String.valueOf(msg.get("action"));
            if (id == null || id.length() > 64) {
                throw new IllegalArgumentException("'id' is required (max. 64 characters)");
            }
            switch (action) {
                case "subscribe" -> {
                    if (subscriptions.containsKey(id)) {
                        throw new IllegalArgumentException("Subscription id already in use");
                    }
                    String subscriptionId = id;
                    Sink sink = new Sink() {
                        @Override
                        public void send(String event, Map<String, Object> data) throws IOException {
                            sendEvent(session, subscriptionId, event, data);
                            boolean credentialsGone = event.equals("error") && Integer.valueOf(401).equals(data.get("status"));
                            if (event.equals("end") || credentialsGone) {
                                // The identity of a WebSocket session comes from its handshake. When it is no
                                // longer valid, or has to be proven again, the whole connection goes - otherwise
                                // the client could simply re-subscribe without authenticating.
                                WsEndpoint.close(session, CloseCodes.VIOLATED_POLICY, "Authenticate again");
                            }
                        }

                        @Override
                        public void close() {
                            subscriptions.remove(subscriptionId);
                        }
                    };
                    subscriptions.put(id, runtime.subscriptions().subscribe(ctx, spec(msg, runtime),
                            runtime.adapter().captureIdentity(), sink));
                }
                case "unsubscribe" -> {
                    Subscription s = subscriptions.remove(id);
                    if (s != null) {
                        s.cancel();
                    }
                }
                default -> throw new IllegalArgumentException("Unknown action '" + action + "'");
            }
        } catch (RuntimeException e) {
            try {
                sendEvent(session, id, "error", Http.error(Http.statusOf(e) == 500 ? 400 : Http.statusOf(e),
                        e.getMessage()));
            } catch (IOException io) {
                close(session, CloseCodes.UNEXPECTED_CONDITION, "send failed");
            }
        }
    }

    private static SubscriptionSpec spec(Map<String, Object> msg, RocotoRuntime runtime) {
        List<String> attributes = new java.util.ArrayList<>();
        if (msg.get("attributes") instanceof List<?> l) {
            l.forEach(a -> attributes.add(String.valueOf(a)));
        }
        long interval = msg.get("interval") instanceof Number n ? n.longValue()
                : Math.max(1000, runtime.subscriptions().minIntervalMillis());
        return new SubscriptionSpec(Http.objectName(msg.get("mbean") instanceof String s ? s : null), attributes,
                interval, !Boolean.FALSE.equals(msg.get("onChange")),
                msg.get("above") instanceof Number a ? a.doubleValue() : null,
                msg.get("below") instanceof Number b ? b.doubleValue() : null,
                Boolean.TRUE.equals(msg.get("notifications")));
    }

    private static void sendEvent(Session session, String id, String event, Map<String, Object> data)
            throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", id);
        envelope.put("event", event);
        envelope.put("data", data);
        synchronized (session) {
            if (!session.isOpen()) {
                throw new IOException("session closed");
            }
            session.getBasicRemote().sendText(JsonWriter.toJson(envelope));
        }
    }

    @Override
    public void onClose(Session session, CloseReason closeReason) {
        cancelAll();
    }

    @Override
    public void onError(Session session, Throwable thr) {
        cancelAll();
    }

    private void cancelAll() {
        for (Subscription s : List.copyOf(subscriptions.values())) {
            s.cancel();
        }
        subscriptions.clear();
    }

    private static void close(Session session, CloseCodes code, String reason) {
        try {
            session.close(new CloseReason(code, reason));
        } catch (IOException e) {
            // already gone
        }
    }
}
