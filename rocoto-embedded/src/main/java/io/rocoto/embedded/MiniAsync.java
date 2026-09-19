package io.rocoto.embedded;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/** The supported part of {@code AsyncContext}: no timeouts, no dispatches. */
final class MiniAsync {

    private final MiniRequest request;
    private final MiniResponse response;
    private final List<AsyncListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean completed = new AtomicBoolean();
    final AsyncContext proxy;

    MiniAsync(MiniRequest request, MiniResponse response, Exchange exchange) {
        this.request = request;
        this.response = response;
        this.proxy = Proxies.create(AsyncContext.class, this);
        exchange.onClosed(this::complete);
    }

    boolean completed() {
        return completed.get();
    }

    public ServletRequest getRequest() {
        return request.proxy;
    }

    public ServletResponse getResponse() {
        return response.proxy;
    }

    public boolean hasOriginalRequestAndResponse() {
        return true;
    }

    public void setTimeout(long timeout) {
        // streams end through Rocoto's own stream.maxDurationSeconds
    }

    public long getTimeout() {
        return 0;
    }

    public void addListener(AsyncListener listener) {
        listeners.add(listener);
    }

    public void start(Runnable run) {
        Thread thread = new Thread(run, "rocoto-embedded-async");
        thread.setDaemon(true);
        thread.start();
    }

    public void complete() {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        response.finish();
        AsyncEvent event = new AsyncEvent(proxy, request.proxy, response.proxy);
        for (AsyncListener listener : listeners) {
            try {
                listener.onComplete(event);
            } catch (Exception e) {
                // a listener must not keep the others from running
            }
        }
    }
}
