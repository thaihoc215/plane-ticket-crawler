package com.planecrawler.scheduler;

import com.planecrawler.service.AlertWatcherService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduling trigger for the alert watcher pipeline.
 * Delegates all business logic to {@link AlertWatcherService}.
 */
@Component
@RequiredArgsConstructor
public class AlertScheduler {

    private final AlertWatcherService alertWatcherService;

    @Scheduled(fixedRateString = "${alert.watcher.fixed-rate-ms:3600000}")
    public void checkAlerts() {
        alertWatcherService.checkAlerts();
    }
}
