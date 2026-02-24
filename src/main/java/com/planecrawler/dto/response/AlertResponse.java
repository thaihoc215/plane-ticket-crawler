package com.planecrawler.dto.response;

import com.planecrawler.model.PriceAlert;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AlertResponse(
        Long alertId,
        String origin,
        String destination,
        PriceAlert.TripType tripType,
        LocalDate departureDate,
        LocalDate returnDate,
        BigDecimal targetPrice,
        BigDecimal returnTargetPrice,
        BigDecimal roundTripTargetPrice,
        String userEmail,
        boolean active
) {
    public static AlertResponse from(PriceAlert alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getOrigin(),
                alert.getDestination(),
                alert.getTripType(),
                alert.getDepartureDate(),
                alert.getReturnDate(),
                alert.getTargetPrice(),
                alert.getReturnTargetPrice(),
                alert.getRoundTripTargetPrice(),
                alert.getUserEmail(),
                alert.isActive()
        );
    }
}
