package com.planecrawler.service;

import com.planecrawler.dto.response.AlertCheckResult;
import com.planecrawler.model.FlightInfo;
import com.planecrawler.model.PriceAlert;
import com.planecrawler.repository.PriceAlertRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Service that polls all active price alerts and triggers the scraper + email
 * pipeline when a price match is found.
 *
 * <p>Processing runs on a virtual thread (via {@code spring.threads.virtual.enabled=true})
 * but alerts are processed sequentially. Parallelism happens inside
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
     * Fetches all active alerts, scrapes current prices,
     * and notifies users when their target price is met or beaten.
     * Triggered by {@link com.planecrawler.scheduler.AlertScheduler} on a schedule,
     * and also callable directly for manual checks.
     */
    public List<AlertCheckResult> checkAlerts() {
        List<PriceAlert> alerts = alertRepository.findByActiveTrue();
        log.info("Alert watcher triggered – checking {} active alert(s)", alerts.size());

        List<AlertCheckResult> results = new ArrayList<>();
        for (PriceAlert alert : alerts) {
            results.add(processAlert(alert));
        }
        return results;
    }

    private AlertCheckResult processAlert(PriceAlert alert) {
        try {
            List<FlightInfo> outboundFlights = List.of();
            List<FlightInfo> returnFlights = List.of();
            List<FlightInfo> roundTripFlights = List.of();

            if (alert.getRoundTripTargetPrice() != null) {
                // Mode 3: roundTripTargetPrice set → combined round-trip search only
                log.info("Alert id={}: round-trip mode, {}->{} depart={} return={}",
                        alert.getId(), alert.getOrigin(), alert.getDestination(),
                        alert.getDepartureDate(), alert.getReturnDate());
                roundTripFlights = scraperService.scrapeRoundTrip(
                        alert.getOrigin(), alert.getDestination(),
                        alert.getDepartureDate(), alert.getReturnDate());
                if (roundTripFlights.isEmpty()) {
                    throw new IllegalStateException(
                            "No round-trip flights found for " + alert.getOrigin() + "->" + alert.getDestination());
                }

            } else if (alert.getReturnTargetPrice() != null) {
                // Mode 2: targetPrice + returnTargetPrice → two separate one-way searches
                log.info("Alert id={}: separate legs mode, {}->{} depart={}, return={}",
                        alert.getId(), alert.getOrigin(), alert.getDestination(),
                        alert.getDepartureDate(), alert.getReturnDate());
                outboundFlights = scraperService.scrape(
                        alert.getOrigin(), alert.getDestination(), alert.getDepartureDate());
                returnFlights = scraperService.scrape(
                        alert.getDestination(), alert.getOrigin(), alert.getReturnDate());
                if (outboundFlights.isEmpty() && returnFlights.isEmpty()) {
                    throw new IllegalStateException(
                            "No flights found for either leg of " + alert.getOrigin() + "<->" + alert.getDestination());
                }

            } else {
                // Mode 1: targetPrice only → one-way outbound search only
                log.info("Alert id={}: one-way mode, {}->{} on {}",
                        alert.getId(), alert.getOrigin(), alert.getDestination(), alert.getDepartureDate());
                outboundFlights = scraperService.scrape(
                        alert.getOrigin(), alert.getDestination(), alert.getDepartureDate());
                if (outboundFlights.isEmpty()) {
                    throw new IllegalStateException(
                            "No outbound flights found for " + alert.getOrigin() + "->" + alert.getDestination());
                }
            }

            BigDecimal cheapestOutbound = outboundFlights.isEmpty() ? null : outboundFlights.get(0).price();
            BigDecimal cheapestReturn = returnFlights.isEmpty() ? null : returnFlights.get(0).price();
            BigDecimal cheapestRoundTrip = roundTripFlights.isEmpty() ? null : roundTripFlights.get(0).price();

            if (cheapestOutbound != null) {
                alert.setLastCheckedPrice(cheapestOutbound);
            }
            if (cheapestReturn != null) {
                alert.setLastCheckedReturnPrice(cheapestReturn);
            }
            alert.setLastCheckedAt(LocalDateTime.now());
            alertRepository.save(alert);

            List<FlightInfo> matchedOutbound = alert.getTargetPrice() != null
                    ? filterByTargetPrice(outboundFlights, alert.getTargetPrice())
                    : List.of();
            List<FlightInfo> matchedReturn = alert.getReturnTargetPrice() != null
                    ? filterByTargetPrice(returnFlights, alert.getReturnTargetPrice())
                    : List.of();
            List<FlightInfo> matchedRoundTrip = alert.getRoundTripTargetPrice() != null
                    ? filterByTargetPrice(roundTripFlights, alert.getRoundTripTargetPrice())
                    : List.of();

            boolean matched = !matchedOutbound.isEmpty() || !matchedReturn.isEmpty() || !matchedRoundTrip.isEmpty();

            if (matched) {
                log.info("Price match! Alert id={} route={}->{} email={} – outbound={}/{}, return={}/{}, roundTrip={}/{}",
                        alert.getId(), alert.getOrigin(), alert.getDestination(), alert.getUserEmail(),
                        matchedOutbound.size(), outboundFlights.size(),
                        matchedReturn.size(), returnFlights.size(),
                        matchedRoundTrip.size(), roundTripFlights.size());
                emailService.sendPriceAlert(alert, matchedOutbound, matchedReturn, !matchedRoundTrip.isEmpty(), cheapestRoundTrip, matchedRoundTrip);
            } else {
                log.debug("No match for alert id={}: outbound={} target={}, return={} target={}, roundTrip={} target={}",
                        alert.getId(),
                        cheapestOutbound == null ? "-" : cheapestOutbound, alert.getTargetPrice(),
                        cheapestReturn == null ? "-" : cheapestReturn,
                        alert.getReturnTargetPrice() == null ? "-" : alert.getReturnTargetPrice(),
                        cheapestRoundTrip == null ? "-" : cheapestRoundTrip,
                        alert.getRoundTripTargetPrice() == null ? "-" : alert.getRoundTripTargetPrice());
            }

            return new AlertCheckResult(alert.getId(), alert.getOrigin(), alert.getDestination(),
                    matched,
                    cheapestOutbound, alert.getTargetPrice(),
                    cheapestReturn, alert.getReturnTargetPrice(),
                    cheapestRoundTrip, alert.getRoundTripTargetPrice(),
                    matchedOutbound, matchedReturn, matchedRoundTrip, null);
        } catch (Exception e) {
            log.error("Failed to process alert id={} for route {}->{}: {}",
                    alert.getId(), alert.getOrigin(), alert.getDestination(), e.getMessage(), e);
            return new AlertCheckResult(alert.getId(), alert.getOrigin(), alert.getDestination(),
                    false, null, alert.getTargetPrice(), null, alert.getReturnTargetPrice(),
                    null, alert.getRoundTripTargetPrice(),
                    List.of(), List.of(), List.of(), e.getMessage());
        }
    }

    private List<FlightInfo> filterByTargetPrice(List<FlightInfo> flights, BigDecimal targetPrice) {
        return flights.stream()
                .filter(f -> f.price().compareTo(targetPrice) <= 0)
                .toList();
    }
}
