package com.planecrawler.service;

import com.microsoft.playwright.*;
import com.planecrawler.model.FlightInfo;
import com.planecrawler.scraper.AirAsiaPage;
import com.planecrawler.scraper.GoogleFlightsPage;
import com.planecrawler.scraper.VietnamAirlinesPage;
import com.planecrawler.scraper.UserAgentRotator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scrapes flight prices using Playwright (Chromium headless browser).
 *
 * <p>Resilience features:
 * <ul>
 *   <li>@Retryable – retries up to 3 times with exponential back-off if bot-detection occurs.</li>
 *   <li>try-with-resources – guarantees the Playwright, Browser, BrowserContext, and Page are
 *       closed even if an exception is thrown, preventing memory leaks.</li>
 *   <li>Random sleep intervals – mimic human reading/browsing speed.</li>
 *   <li>User-Agent rotation – each run picks a different UA string.</li>
 * </ul>
 */
@Service
public class FlightScraperService {

    private static final Logger log = LoggerFactory.getLogger(FlightScraperService.class);

    private static final int MIN_SLEEP_MS = 2_000;
    private static final int MAX_SLEEP_MS = 5_000;

    /**
     * Scrapes the cheapest available flight for the given route.
     *
     * @param origin      IATA airport code or city name (e.g. "JFK")
     * @param destination IATA airport code or city name (e.g. "LAX")
     * @return {@link FlightInfo} with price, airline, and duration
     * @throws Exception if scraping fails after all retry attempts
     */
    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 5_000, multiplier = 2)
    )
    public FlightInfo scrape(String origin, String destination) throws Exception {
        return scrape(origin, destination, null);
    }

    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 5_000, multiplier = 2)
    )
    public FlightInfo scrape(String origin, String destination, LocalDate flightDate) throws Exception {
        log.info("Scraping flights from {} to {} on {}", origin, destination, flightDate);

        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(java.util.List.of(
                            "--no-sandbox",
                            "--disable-blink-features=AutomationControlled"
                    ));

            try (Browser browser = playwright.chromium().launch(launchOptions)) {
                Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                        .setUserAgent(UserAgentRotator.random())
                        .setViewportSize(1280, 800)
                        .setLocale("en-US");

                try (BrowserContext context = browser.newContext(contextOptions);
                     Page page = context.newPage()) {

                    // Mask the webdriver flag to reduce bot-detection
                    page.addInitScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");
                    List<FlightInfo> results = new ArrayList<>();

                    try {
                        results.add(scrapeFromGoogleFlights(page, origin, destination, flightDate));
                    } catch (Exception googleError) {
                        log.warn("Google Flights scrape failed for {}->{}: {}", origin, destination, googleError.getMessage());
                    }

                    try {
                        results.add(scrapeFromVietnamAirlines(page, origin, destination, flightDate));
                    } catch (Exception vaError) {
                        log.warn("Vietnam Airlines scrape failed for {}->{}: {}", origin, destination, vaError.getMessage());
                    }

                    try {
                        results.add(scrapeFromAirAsia(page, origin, destination, flightDate));
                    } catch (Exception airAsiaError) {
                        log.warn("AirAsia scrape failed for {}->{}: {}", origin, destination, airAsiaError.getMessage());
                    }

                    if (results.isEmpty()) {
                        throw new IllegalStateException("All flight sources failed for route " + origin + "->" + destination);
                    }

                    FlightInfo best = selectBestPrice(results);
                    log.info("Best price selected for {}->{}: {} ({}, {})",
                            origin, destination, best.price(), best.airline(), best.duration());
                    return best;
                }
            }
        }
    }

    static FlightInfo selectBestPrice(List<FlightInfo> flights) {
        return flights.stream()
                .filter(flight -> flight != null && flight.price() != null)
                .min(Comparator.comparing(FlightInfo::price))
                .orElseThrow(() -> new IllegalArgumentException("No flight results available"));
    }

    private FlightInfo scrapeFromGoogleFlights(Page page, String origin, String destination, LocalDate flightDate) throws Exception {
        GoogleFlightsPage flightsPage = new GoogleFlightsPage(page);
        flightsPage.navigate(origin, destination, flightDate);
        sleepRandom();
        return logScrapeResult("Google Flights", flightsPage.extractCheapestFlight(origin, destination));
    }

    private FlightInfo scrapeFromVietnamAirlines(Page page, String origin, String destination, LocalDate flightDate) throws Exception {
        VietnamAirlinesPage flightsPage = new VietnamAirlinesPage(page);
        flightsPage.navigate(origin, destination, flightDate);
        sleepRandom();
        return logScrapeResult("Vietnam Airlines", flightsPage.extractCheapestFlight(origin, destination));
    }

    private FlightInfo scrapeFromAirAsia(Page page, String origin, String destination, LocalDate flightDate) throws Exception {
        AirAsiaPage flightsPage = new AirAsiaPage(page);
        flightsPage.navigate(origin, destination, flightDate);
        sleepRandom();
        return logScrapeResult("AirAsia", flightsPage.extractCheapestFlight(origin, destination));
    }

    private FlightInfo logScrapeResult(String source, FlightInfo info) {
        log.info("Scraped flight from {}: {} {} {} (price: {})",
                source, info.airline(), info.duration(), info.origin() + "->" + info.destination(),
                info.price());
        return info;
    }

    private static void sleepRandom() throws InterruptedException {
        long delay = ThreadLocalRandom.current().nextLong(MIN_SLEEP_MS, MAX_SLEEP_MS);
        Thread.sleep(delay);
    }
}
