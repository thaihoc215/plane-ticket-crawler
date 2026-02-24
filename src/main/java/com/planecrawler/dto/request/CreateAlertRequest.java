package com.planecrawler.dto.request;

import com.planecrawler.model.PriceAlert;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateAlertRequest(
        @NotBlank String origin,
        @NotBlank String destination,
        PriceAlert.TripType tripType,
        @NotNull LocalDate departureDate,
        LocalDate returnDate,
        @NotNull @Positive BigDecimal targetPrice,
        @Positive BigDecimal returnTargetPrice,
        @Positive BigDecimal roundTripTargetPrice,
        @NotBlank @Email String userEmail
) {}
