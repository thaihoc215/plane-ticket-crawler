package com.planecrawler.dto.response;

import com.planecrawler.model.FlightInfo;

import java.math.BigDecimal;
import java.util.List;

public record AlertCheckResult(
        Long alertId,
        String origin,
        String destination,
        boolean matched,
        BigDecimal cheapestOutboundPrice,
        BigDecimal targetPrice,
        BigDecimal cheapestReturnPrice,
        BigDecimal returnTargetPrice,
        BigDecimal cheapestRoundTripPrice,
        BigDecimal roundTripTargetPrice,
        List<FlightInfo> matchedOutboundFlights,
        List<FlightInfo> matchedReturnFlights,
        List<FlightInfo> matchedRoundTripFlights,
        String error
) {}
