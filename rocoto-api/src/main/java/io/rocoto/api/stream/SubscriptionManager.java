package io.rocoto.api.stream;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.Notification;
import javax.management.NotificationListener;

import io.rocoto.adapters.ServerAdapter.IdentityPropagator;
import io.rocoto.api.ApiException;
import io.rocoto.core.config.Config;
import io.rocoto.core.convert.ValueConverter;
import io.rocoto.core.json.JsonWriter;
import io.rocoto.core.request.Target;
import io.rocoto.security.AccessDeniedException;
import io.rocoto.security.CallContext;
import io.rocoto.security.RequestPipeline;

/**
 * Runs all push subscriptions (SSE and WebSocket) on a small shared scheduler.
 *
 * <p>Security properties: every poll goes through the request pipeline with the subscriber's
 * identity, so a policy or credential change takes effect on a running stream; the number of
 * subscriptions is bounded globally and per user; the polling interval has a lower bound.
 */
public final class SubscriptionManager implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SubscriptionManager.class.getName());
    private static final long HEARTBEAT_MILLIS = 15_000;

    private final RequestPipeline pipeline;
    private final int maxTotal;
    private final int maxPerUser;
    private final long minIntervalMillis;
    private final ScheduledExecutorService scheduler;
    private final Set<Subscription> active = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> perUser = new ConcurrentHashMap<>();

    /** @param stream the {@code stream} section of rocoto.yaml */
    public SubscriptionManager(RequestPipeline pipeline, Config stream) {
        this.pipeline = pipeline;
        this.maxTotal = stream.integer("maxSubscriptions", 200);
        this.maxPerUser = stream.integer("maxSubscriptionsPerUser", 20);
        this.minIntervalMillis = Math.max(100, stream.longValue("minIntervalMillis", 500));
        AtomicInteger counter = new AtomicInteger();
        this.scheduler = Executors.newScheduledThreadPool(Math.max(1, stream.integer("threads", 2)), r -> {
            Thread t = new Thread(r, "rocoto-stream-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public int activeCount() {
        return active.size();
    }

    public long minIntervalMillis() {
        return minIntervalMillis;
    }

    /**
     * Validates the subscription against the policy and starts it.
     *
     * @throws AccessDeniedException if the caller may not read one of the attributes
     * @throws ApiException          if a limit is exceeded
     */
    public Subscription subscribe(CallContext ctx, SubscriptionSpec spec, IdentityPropagator propagator,
            Sink sink) {
        if (spec.attributes().isEmpty() && !spec.notifications()) {
            throw new ApiException(400, "Subscribe to at least one attribute or to notifications");
        }
        if (spec.mbean().isPattern()) {
            throw new ApiException(400, "Subscriptions need a concrete ObjectName");
        }
        // Fail fast with 403/404 instead of opening a stream that only delivers errors.
        for (String attribute : spec.attributes()) {
            pipeline.read(ctx, spec.mbean(), attribute);
        }
        String user = ctx.identity().name();
        AtomicInteger userCount = perUser.computeIfAbsent(user, u -> new AtomicInteger());
        if (active.size() >= maxTotal || userCount.get() >= maxPerUser) {
            throw new ApiException(429, "Too many active subscriptions");
        }
        userCount.incrementAndGet();
        Subscription subscription = new Subscription(ctx, spec, sink);
        active.add(subscription);
        try {
            subscription.start(propagator);
        } catch (RuntimeException e) {
            subscription.cancel();
            throw e;
        }
        return subscription;
    }

    @Override
    public void close() {
        for (Subscription s : active) {
            s.cancel();
        }
        scheduler.shutdownNow();
    }

    /** One running subscription. */
    public final class Subscription {

        private final CallContext ctx;
        private final SubscriptionSpec spec;
        private final Sink sink;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final ValueConverter converter = pipeline.converter().build();
        private volatile ScheduledFuture<?> future;
        private volatile AutoCloseable notificationHandle;
        private String lastPayload;
        private long lastSent = System.currentTimeMillis();

        private Subscription(CallContext ctx, SubscriptionSpec spec, Sink sink) {
            this.ctx = ctx;
            this.spec = spec;
            this.sink = sink;
        }

        private void start(IdentityPropagator propagator) {
            if (spec.notifications()) {
                NotificationListener listener = (notification, handback) -> onNotification(notification);
                notificationHandle = pipeline.listen(ctx, spec.mbean(), listener);
            }
            long interval = Math.max(minIntervalMillis, spec.intervalMillis());
            future = scheduler.scheduleWithFixedDelay(propagator.wrap(this::tick), 0, interval,
                    TimeUnit.MILLISECONDS);
        }

        private void tick() {
            if (cancelled.get()) {
                return;
            }
            try {
                if (!spec.attributes().isEmpty()) {
                    poll();
                }
                if (System.currentTimeMillis() - lastSent > HEARTBEAT_MILLIS) {
                    emit("heartbeat", Map.of("ts", Instant.now().toString()));
                }
            } catch (AccessDeniedException e) {
                fail(403, "Access denied by policy");
            } catch (IOException e) {
                cancel(); // client went away
            } catch (RuntimeException e) {
                fail(500, e.getMessage());
            }
        }

        private void poll() throws IOException {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String attribute : spec.attributes()) {
                values.put(attribute, converter.convert(pipeline.read(ctx, spec.mbean(), attribute)));
            }
            if (!thresholdMet(values)) {
                return;
            }
            String payload = JsonWriter.toJson(values);
            if (spec.onChange() && payload.equals(lastPayload)) {
                return;
            }
            lastPayload = payload;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mbean", spec.mbean().getCanonicalName());
            data.put("ts", Instant.now().toString());
            data.put("values", values);
            emit("value", data);
        }

        private boolean thresholdMet(Map<String, Object> values) {
            if (spec.above() == null && spec.below() == null) {
                return true;
            }
            Object first = values.values().iterator().next();
            if (!(first instanceof Number n)) {
                return false;
            }
            double v = n.doubleValue();
            return (spec.above() == null || v > spec.above()) && (spec.below() == null || v < spec.below());
        }

        private void onNotification(Notification n) {
            if (cancelled.get()) {
                return;
            }
            // Re-check on delivery: permissions may have changed since the listener was registered.
            if (!pipeline.policy().decide(ctx.identity(),
                    Target.read(spec.mbean(), RequestPipeline.NOTIFICATIONS)).allowed()) {
                fail(403, "Access denied by policy");
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mbean", spec.mbean().getCanonicalName());
            data.put("ts", Instant.ofEpochMilli(n.getTimeStamp()).toString());
            data.put("type", n.getType());
            data.put("message", n.getMessage());
            data.put("sequenceNumber", n.getSequenceNumber());
            data.put("userData", converter.convert(n.getUserData()));
            try {
                emit("notification", data);
            } catch (IOException e) {
                cancel();
            }
        }

        private synchronized void emit(String event, Map<String, Object> data) throws IOException {
            sink.send(event, data);
            lastSent = System.currentTimeMillis();
        }

        private void fail(int status, String message) {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("status", status);
                data.put("error", message);
                emit("error", data);
            } catch (IOException e) {
                // nothing more to tell the client
            }
            cancel();
        }

        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            if (future != null) {
                future.cancel(false);
            }
            if (notificationHandle != null) {
                try {
                    notificationHandle.close();
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Removing notification listener failed", e);
                }
            }
            active.remove(this);
            AtomicInteger count = perUser.get(ctx.identity().name());
            if (count != null) {
                count.decrementAndGet();
            }
            sink.close();
        }
    }
}
