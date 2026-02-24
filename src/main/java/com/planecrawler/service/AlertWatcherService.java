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
            FlightInfo outboundFlight = scraperService.scrape(
                    alert.getOrigin(), alert.getDestination(), alert.getDepartureDate());
            FlightInfo returnFlight = null;
            if (alert.getTripType() == PriceAlert.TripType.ROUND_TRIP && alert.getReturnDate() != null) {
                returnFlight = scraperService.scrape(
                        alert.getDestination(), alert.getOrigin(), alert.getReturnDate());
                alert.setLastCheckedReturnPrice(returnFlight.getPrice());
            }

            alert.setLastCheckedPrice(outboundFlight.getPrice());
            alert.setLastCheckedAt(LocalDateTime.now());
            alertRepository.save(alert);

            boolean outboundMatched = outboundFlight.getPrice().compareTo(alert.getTargetPrice()) <= 0;
            boolean returnMatched = returnFlight != null
                    && alert.getReturnTargetPrice() != null
                    && returnFlight.getPrice().compareTo(alert.getReturnTargetPrice()) <= 0;
            if (outboundMatched || returnMatched) {
                log.info("Price match! Alert id={} – outbound current={} target={} return current={} target={} route={}->{} email={}",
                        alert.getId(), outboundFlight.getPrice(), alert.getTargetPrice(),
                        returnFlight == null ? "-" : returnFlight.getPrice(),
                        alert.getReturnTargetPrice() == null ? "-" : alert.getReturnTargetPrice(),
                        alert.getOrigin(), alert.getDestination(), alert.getUserEmail());
                emailService.sendPriceAlert(alert, outboundFlight, returnFlight, outboundMatched, returnMatched);
            } else {
                log.debug("No match for alert id={}: outbound current={} target={} return current={} target={}",
                        alert.getId(), outboundFlight.getPrice(), alert.getTargetPrice(),
                        returnFlight == null ? "-" : returnFlight.getPrice(),
                        alert.getReturnTargetPrice() == null ? "-" : alert.getReturnTargetPrice());
            }
        } catch (Exception e) {
            log.error("Failed to process alert id={} for route {}->{}: {}",
                    alert.getId(), alert.getOrigin(), alert.getDestination(), e.getMessage(), e);
        }
    }
}
