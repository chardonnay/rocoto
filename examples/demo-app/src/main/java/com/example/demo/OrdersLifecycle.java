package com.example.demo;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

/**
 * Registers the MXBean when the application starts and - just as important - unregisters it on
 * shutdown. Without the unregistration a redeploy fails with InstanceAlreadyExistsException and
 * the old class loader leaks.
 */
@WebListener
public class OrdersLifecycle implements ServletContextListener {

    static final String ORDERS_ATTRIBUTE = Orders.class.getName();
    private static final String OBJECT_NAME = "com.example.demo:type=Orders";

    private ObjectName name;
    private ScheduledExecutorService simulator;

    @Override
    public void contextInitialized(ServletContextEvent event) {
        Orders orders = new Orders();
        try {
            name = new ObjectName(OBJECT_NAME);
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            server.registerMBean(orders, name);
        } catch (JMException e) {
            throw new IllegalStateException("Cannot register " + OBJECT_NAME, e);
        }
        event.getServletContext().setAttribute(ORDERS_ATTRIBUTE, orders);

        // Simulated traffic so that counters, the queue and metrics move without any client:
        // orders arrive a little faster than they are processed, the queue grows slowly.
        simulator = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "demo-orders-simulator");
            t.setDaemon(true);
            return t;
        });
        simulator.scheduleAtFixedRate(() -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            orders.submit("customer-" + random.nextInt(1, 50), random.nextLong(500, 25_000));
            if (random.nextInt(10) < 8) {
                orders.processOne();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (simulator != null) {
            simulator.shutdownNow();
        }
        if (name != null) {
            try {
                ManagementFactory.getPlatformMBeanServer().unregisterMBean(name);
            } catch (JMException e) {
                event.getServletContext().log("Cannot unregister " + name, e);
            }
        }
    }
}
