package com.planecrawler.service;

import com.planecrawler.model.FlightInfo;
import com.planecrawler.model.PriceAlert;
import com.planecrawler.repository.PriceAlertRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled service that polls all active price alerts every hour and
 * triggers the scraper + email pipeline when a price match is found.
 *
 * <p>The {@code @Scheduled} method runs on a virtual thread
 * (via {@code spring.threads.virtual.enabled=true}), but alerts are
 * processed sequentially. Parallelism happens inside
 * {@link FlightScraperService}, where each airline source is scraped
 * concurrently on its own virtual thread.
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
            List<FlightInfo> outboundFlights = scraperService.scrape(
                    alert.getOrigin(), alert.getDestination(), alert.getDepartureDate());
            if (outboundFlights.isEmpty()) {
                throw new IllegalStateException("No outbound flights found for route " + alert.getOrigin() + "->" + alert.getDestination());
            }

            List<FlightInfo> returnFlights = List.of();
            List<FlightInfo> roundTripFlights = List.of();

            if (alert.getTripType() == PriceAlert.TripType.ROUND_TRIP && alert.getReturnDate() != null) {
                if (alert.getRoundTripTargetPrice() != null) {
                    roundTripFlights = scraperService.scrapeRoundTrip(
                            alert.getOrigin(), alert.getDestination(),
                            alert.getDepartureDate(), alert.getReturnDate());
                }
                if (alert.getReturnTargetPrice() != null) {
                    returnFlights = scraperService.scrape(
                            alert.getDestination(), alert.getOrigin(), alert.getReturnDate());
                }
            }

            BigDecimal cheapestOutbound = outboundFlights.get(0).price();
            BigDecimal cheapestReturn = returnFlights.isEmpty() ? null : returnFlights.get(0).price();
            BigDecimal cheapestRoundTrip = roundTripFlights.isEmpty() ? null : roundTripFlights.get(0).price();
            alert.setLastCheckedPrice(cheapestOutbound);
            if (cheapestReturn != null) {
                alert.setLastCheckedReturnPrice(cheapestReturn);
            }
            alert.setLastCheckedAt(LocalDateTime.now());
            alertRepository.save(alert);

            List<FlightInfo> matchedOutbound = filterByTargetPrice(outboundFlights, alert.getTargetPrice());
            List<FlightInfo> matchedReturn = alert.getReturnTargetPrice() != null
                    ? filterByTargetPrice(returnFlights, alert.getReturnTargetPrice())
                    : List.of();

            boolean roundTripMatched = false;
            BigDecimal roundTripPrice = null;
            List<FlightInfo> matchedRoundTrip = List.of();
            if (alert.getRoundTripTargetPrice() != null && cheapestRoundTrip != null) {
                roundTripPrice = cheapestRoundTrip;
                roundTripMatched = roundTripPrice.compareTo(alert.getRoundTripTargetPrice()) <= 0;
                if (roundTripMatched) {
                    matchedRoundTrip = filterByTargetPrice(roundTripFlights, alert.getRoundTripTargetPrice());
                }
            }

            if (!matchedOutbound.isEmpty() || !matchedReturn.isEmpty() || roundTripMatched) {
                log.info("Price match! Alert id={} – {} outbound, {} return under target, roundTrip={} ({}<={}), route={}->{} email={}",
                        alert.getId(), matchedOutbound.size(), matchedReturn.size(),
                        roundTripMatched, roundTripPrice, alert.getRoundTripTargetPrice(),
                        alert.getOrigin(), alert.getDestination(), alert.getUserEmail());
                emailService.sendPriceAlert(alert, matchedOutbound, matchedReturn, roundTripMatched, roundTripPrice, matchedRoundTrip);
            } else {
                log.debug("No match for alert id={}: cheapest outbound={} target={}, cheapest return={} target={}, roundTrip={} target={}",
                        alert.getId(), cheapestOutbound, alert.getTargetPrice(),
                        cheapestReturn == null ? "-" : cheapestReturn,
                        alert.getReturnTargetPrice() == null ? "-" : alert.getReturnTargetPrice(),
                        roundTripPrice == null ? "-" : roundTripPrice,
                        alert.getRoundTripTargetPrice() == null ? "-" : alert.getRoundTripTargetPrice());
            }
        } catch (Exception e) {
            log.error("Failed to process alert id={} for route {}->{}: {}",
                    alert.getId(), alert.getOrigin(), alert.getDestination(), e.getMessage(), e);
        }
    }

    private List<FlightInfo> filterByTargetPrice(List<FlightInfo> flights, BigDecimal targetPrice) {
        return flights.stream()
                .filter(f -> f.price().compareTo(targetPrice) <= 0)
                .toList();
    }
}
