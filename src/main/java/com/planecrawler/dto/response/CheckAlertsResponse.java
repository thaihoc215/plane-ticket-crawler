package com.planecrawler.dto.response;

import java.util.List;

public record CheckAlertsResponse(
        String message,
        int alertsChecked,
        int alertsMatched,
        List<AlertCheckResult> results
) {}
