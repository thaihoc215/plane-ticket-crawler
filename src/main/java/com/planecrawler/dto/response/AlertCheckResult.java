package com.planecrawler.dto.response;

import com.planecrawler.model.FlightInfo;

import java.util.List;

public record AlertCheckResult(
        Long alertId,
        String origin,
        String destination,
        boolean matched,
        List<FlightInfo> matchedOutboundFlights,
        List<FlightInfo> matchedReturnFlights,
        List<FlightInfo> matchedRoundTripFlights,
        String error
) {}
