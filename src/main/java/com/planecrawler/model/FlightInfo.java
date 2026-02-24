package com.planecrawler.model;

import java.math.BigDecimal;

/**
 * Immutable value object representing a scraped flight result.
 */
public record FlightInfo(
        BigDecimal price,
        String airline,
        String duration,
        String origin,
        String destination,
        String departureTime,
        String arrivalTime
) {}
