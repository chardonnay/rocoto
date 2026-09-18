package com.example.demo;

/** Mapped to a JMX {@code CompositeData} with the items {@code id}, {@code customer}, {@code amountCents} and {@code processedAt}. */
public record OrderInfo(String id, String customer, long amountCents, String processedAt) {
}
