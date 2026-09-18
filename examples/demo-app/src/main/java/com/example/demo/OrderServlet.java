package com.example.demo;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** {@code POST /demo-app/orders?customer=alice&amountCents=1999} queues one order by hand. */
@WebServlet("/orders")
public class OrderServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Orders orders = (Orders) getServletContext().getAttribute(OrdersLifecycle.ORDERS_ATTRIBUTE);
        String customer = request.getParameter("customer") == null ? "anonymous" : request.getParameter("customer");
        long amount;
        try {
            amount = Long.parseLong(request.getParameter("amountCents") == null ? "1000"
                    : request.getParameter("amountCents"));
        } catch (NumberFormatException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "amountCents must be a number");
            return;
        }
        String id = orders.submit(customer, amount);
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        response.getWriter().println(id);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Orders orders = (Orders) getServletContext().getAttribute(OrdersLifecycle.ORDERS_ATTRIBUTE);
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().println("processed=" + orders.getOrdersProcessed() + " queued=" + orders.getQueueDepth());
    }
}
