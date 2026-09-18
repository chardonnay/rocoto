package com.example.demo;

/**
 * Management interface of the demo application. The {@code MXBean} suffix makes the JMX runtime
 * map {@link OrderInfo} (a record) to {@code CompositeData}, so any JMX client - including
 * Rocoto - can read it without having the class.
 */
public interface OrdersMXBean {

    /** Monotonic counter - a good candidate for a Prometheus counter. */
    long getOrdersProcessed();

    /** Orders waiting to be processed - a gauge. */
    int getQueueDepth();

    /** The most recently processed order, or {@code null} before the first one. */
    OrderInfo getLastOrder();

    /** Only here to demonstrate masking: Rocoto replaces the value by {@code ********}. */
    String getDbPassword();

    boolean isMaintenanceMode();

    /** While enabled the background worker pauses: new orders keep arriving and the queue grows. */
    void setMaintenanceMode(boolean maintenanceMode);

    void resetCounters();

    /**
     * Processes up to {@code max} queued orders immediately.
     *
     * @return the number of orders that were processed
     */
    int drainQueue(int max);
}
