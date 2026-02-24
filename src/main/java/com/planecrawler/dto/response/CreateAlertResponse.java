package com.planecrawler.dto.response;

import com.planecrawler.model.PriceAlert;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateAlertResponse(
        String message,
        Long alertId,
        String origin,
        String destination,
        PriceAlert.TripType tripType,
        LocalDate departureDate,
        LocalDate returnDate,
        BigDecimal targetPrice,
        BigDecimal returnTargetPrice,
        BigDecimal roundTripTargetPrice,
        String userEmail
) {
    public static CreateAlertResponse from(PriceAlert alert) {
        return new CreateAlertResponse(
                "Alert created successfully",
                alert.getId(),
                alert.getOrigin(),
                alert.getDestination(),
                alert.getTripType(),
                alert.getDepartureDate(),
                alert.getReturnDate(),
                alert.getTargetPrice(),
                alert.getReturnTargetPrice(),
                alert.getRoundTripTargetPrice(),
                alert.getUserEmail()
        );
    }
}
