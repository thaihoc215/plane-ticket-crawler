package com.planecrawler.service;

import com.planecrawler.model.FlightInfo;
import com.planecrawler.model.PriceAlert;
import com.planecrawler.repository.PriceAlertRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled service that polls all active price alerts every hour and
 * triggers the scraper + email pipeline when a price match is found.
 *
 * <p>Because spring.threads.virtual.enabled=true is set, each scraping task
 * runs on a Java 21 Virtual Thread, so blocking I/O inside
 * {@link FlightScraperService} does not waste carrier threads.
 */
@Service
@RequiredArgsConstructor
public class AlertWatcherService {

    private static final Logger log = LoggerFactory.getLogger(AlertWatcherService.class);

    private final PriceAlertRepository alertRepository;
    private final FlightScraperService scraperService;
    private final EmailService emailService;

    /**
     * Runs every hour. Fetches all active alerts, scrapes current prices,
     * and notifies users when their target price is met or beaten.
     */
    @Scheduled(fixedRateString = "${alert.watcher.fixed-rate-ms:3600000}")
    public void checkAlerts() {
        List<PriceAlert> alerts = alertRepository.findByActiveTrue();
        log.info("Alert watcher triggered – checking {} active alert(s)", alerts.size());

        for (PriceAlert alert : alerts) {
            processAlert(alert);
        }
    }

    private void processAlert(PriceAlert alert) {
        try {
            FlightInfo flight = scraperService.scrape(alert.getOrigin(), alert.getDestination());

            alert.setLastCheckedPrice(flight.getPrice());
            alert.setLastCheckedAt(LocalDateTime.now());
            alertRepository.save(alert);

            int cmp = flight.getPrice().compareTo(alert.getTargetPrice());
            if (cmp <= 0) {
                log.info("Price match! Alert id={} – current={} target={} route={}->{} email={}",
                        alert.getId(), flight.getPrice(), alert.getTargetPrice(),
                        alert.getOrigin(), alert.getDestination(), alert.getUserEmail());
                emailService.sendPriceAlert(alert, flight);
            } else {
                log.debug("No match for alert id={}: current={} > target={}",
                        alert.getId(), flight.getPrice(), alert.getTargetPrice());
            }
        } catch (Exception e) {
            log.error("Failed to process alert id={} for route {}->{}: {}",
                    alert.getId(), alert.getOrigin(), alert.getDestination(), e.getMessage(), e);
        }
    }
}
