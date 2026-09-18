package com.example.demo;

import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** The "business logic": a queue of orders and a few counters. Thread-safe. */
public final class Orders implements OrdersMXBean {

    private record Pending(String id, String customer, long amountCents) {
    }

    private final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong sequence = new AtomicLong();
    private volatile OrderInfo lastOrder;
    private volatile boolean maintenanceMode;

    /** Orders are always accepted; in maintenance mode they just wait in the queue. */
    public String submit(String customer, long amountCents) {
        String id = "order-" + sequence.incrementAndGet();
        queue.add(new Pending(id, customer, amountCents));
        return id;
    }

    /** Called by the background worker. */
    void processOne() {
        if (!maintenanceMode) {
            process(1);
        }
    }

    private int process(int max) {
        int done = 0;
        Pending next;
        while (done < max && (next = queue.poll()) != null) {
            lastOrder = new OrderInfo(next.id(), next.customer(), next.amountCents(), Instant.now().toString());
            processed.incrementAndGet();
            done++;
        }
        return done;
    }

    // ---- OrdersMXBean --------------------------------------------------------------------

    @Override
    public long getOrdersProcessed() {
        return processed.get();
    }

    @Override
    public int getQueueDepth() {
        return queue.size();
    }

    @Override
    public OrderInfo getLastOrder() {
        return lastOrder;
    }

    @Override
    public String getDbPassword() {
        return "demo-only-not-a-real-secret";
    }

    @Override
    public boolean isMaintenanceMode() {
        return maintenanceMode;
    }

    @Override
    public void setMaintenanceMode(boolean maintenanceMode) {
        this.maintenanceMode = maintenanceMode;
    }

    @Override
    public void resetCounters() {
        processed.set(0);
        lastOrder = null;
    }

    @Override
    public int drainQueue(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("max must not be negative");
        }
        return process(max);
    }
}
