package com.planecrawler.service;

import com.microsoft.playwright.*;
import com.planecrawler.model.FlightInfo;
import com.planecrawler.scraper.AirAsiaPage;
import com.planecrawler.scraper.GoogleFlightsPage;
import com.planecrawler.scraper.UserAgentRotator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scrapes flight prices using Playwright (Chromium headless browser).
 *
 * <p>Resilience features:
 * <ul>
 *   <li>@Retryable – retries up to 3 times with exponential back-off if bot-detection occurs.</li>
 *   <li>try-with-resources – guarantees the Playwright, Browser, BrowserContext, and Page are
 *       closed even if an exception is thrown, preventing memory leaks.</li>
 *   <li>Virtual-thread parallelism – each airline source is scraped concurrently on its
 *       own virtual thread with a dedicated Playwright/Browser instance.</li>
 *   <li>Screenshot-on-failure – captures debug screenshots when a scraper fails.</li>
 *   <li>Random sleep intervals – mimic human reading/browsing speed.</li>
 *   <li>User-Agent rotation – each run picks a different UA string.</li>
 * </ul>
 */
@Service
public class FlightScraperService {

    private static final Logger log = LoggerFactory.getLogger(FlightScraperService.class);

    private static final int MAX_FLIGHTS_PER_SOURCE = 5;
    private static final int MAX_TOTAL_FLIGHTS = 5;
    private static final int MIN_SLEEP_MS = 2_000;
    private static final int MAX_SLEEP_MS = 5_000;
    private static final String SCREENSHOT_DIR = System.getProperty("java.io.tmpdir") + "/plane-crawler-debug/";
    private static final String WEBDRIVER_MASK = "Object.defineProperty(navigator, 'webdriver', {get: () => undefined})";

    @PostConstruct
    void logScreenshotDirectory() {
        log.info("Debug screenshots will be saved to: {}", SCREENSHOT_DIR);
    }

    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 5_000, multiplier = 2)
    )
    public List<FlightInfo> scrape(String origin, String destination) throws Exception {
        return scrape(origin, destination, null);
    }

    /**
     * Scrapes up to {@value MAX_TOTAL_FLIGHTS} cheapest flights from Google Flights and AirAsia.
     * Vietnam Airlines is temporarily disabled.
     */
    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 5_000, multiplier = 2)
    )
    public List<FlightInfo> scrape(String origin, String destination, LocalDate flightDate) throws Exception {
        log.info("Scraping flights from {} to {} on {}", origin, destination, flightDate);

        // Vietnam Airlines and AirAsia temporarily disabled
        String[] sources = {"Google Flights"};
        List<Future<List<FlightInfo>>> futures;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = List.of(
                    executor.submit(() -> scrapeMultipleFromSource("Google Flights", origin, destination, flightDate))
            );
        } // executor.close() waits for all tasks to complete

        List<FlightInfo> allResults = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                allResults.addAll(futures.get(i).get());
            } catch (ExecutionException e) {
                log.warn("{} scrape failed for {}->{}: {}", sources[i], origin, destination,
                        e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("{} scrape interrupted for {}->{}", sources[i], origin, destination);
            }
        }

        if (allResults.isEmpty()) {
            throw new IllegalStateException("All flight sources failed for route " + origin + "->" + destination);
        }

        List<FlightInfo> cheapest = selectCheapestFlights(allResults, MAX_TOTAL_FLIGHTS);
        log.info("Found {} flights for {}->{}, top {} selected (cheapest: {} from {})",
                allResults.size(), origin, destination, cheapest.size(),
                cheapest.get(0).price(), cheapest.get(0).airline());
        return cheapest;
    }

    /**
     * Scrapes round-trip flights. The returned prices are total round-trip prices
     * (not per-leg), as reported by Google Flights and AirAsia.
     */
    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 5_000, multiplier = 2)
    )
    public List<FlightInfo> scrapeRoundTrip(String origin, String destination,
                                             LocalDate departDate, LocalDate returnDate) throws Exception {
        log.info("Scraping round-trip flights from {} to {} depart={} return={}",
                origin, destination, departDate, returnDate);

        // Vietnam Airlines and AirAsia temporarily disabled
        String[] sources = {"Google Flights"};
        List<Future<List<FlightInfo>>> futures;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = List.of(
                    executor.submit(() -> scrapeRoundTripFromSource("Google Flights", origin, destination, departDate, returnDate))
            );
        }

        List<FlightInfo> allResults = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                allResults.addAll(futures.get(i).get());
            } catch (ExecutionException e) {
                log.warn("{} round-trip scrape failed for {}->{}: {}", sources[i], origin, destination,
                        e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("{} round-trip scrape interrupted for {}->{}", sources[i], origin, destination);
            }
        }

        if (allResults.isEmpty()) {
            throw new IllegalStateException("All flight sources failed for round-trip route " + origin + "->" + destination);
        }

        List<FlightInfo> cheapest = selectCheapestFlights(allResults, MAX_TOTAL_FLIGHTS);
        log.info("Found {} round-trip flights for {}->{}, top {} selected (cheapest: {} from {})",
                allResults.size(), origin, destination, cheapest.size(),
                cheapest.get(0).price(), cheapest.get(0).airline());
        return cheapest;
    }

    /**
     * Runs a multi-flight scrape in its own Playwright/Browser lifecycle.
     */
    private List<FlightInfo> scrapeMultipleFromSource(String source, String origin, String destination,
                                                      LocalDate flightDate) throws Exception {
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

                try (BrowserContext context = browser.newContext(contextOptions)) {
                    try (Page page = context.newPage()) {
                        page.addInitScript(WEBDRIVER_MASK);
                        return switch (source) {
                            case "Google Flights" -> scrapeFlightsFromGoogle(page, origin, destination, flightDate);
                            case "AirAsia" -> scrapeFlightsFromAirAsia(page, origin, destination, flightDate);
                            default -> throw new IllegalArgumentException("Unknown source: " + source);
                        };
                    }
                }
            }
        }
    }

    static List<FlightInfo> selectCheapestFlights(List<FlightInfo> flights, int limit) {
        return flights.stream()
                .filter(flight -> flight != null && flight.price() != null)
                .sorted(Comparator.comparing(FlightInfo::price))
                .limit(limit)
                .toList();
    }

    private List<FlightInfo> scrapeRoundTripFromSource(String source, String origin, String destination,
                                                        LocalDate departDate, LocalDate returnDate) throws Exception {
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

                try (BrowserContext context = browser.newContext(contextOptions)) {
                    try (Page page = context.newPage()) {
                        page.addInitScript(WEBDRIVER_MASK);
                        return switch (source) {
                            case "Google Flights" -> scrapeRoundTripFromGoogle(page, origin, destination, departDate, returnDate);
                            case "AirAsia" -> scrapeRoundTripFromAirAsia(page, origin, destination, departDate, returnDate);
                            default -> throw new IllegalArgumentException("Unknown source: " + source);
                        };
                    }
                }
            }
        }
    }

    private List<FlightInfo> scrapeRoundTripFromGoogle(Page page, String origin, String destination,
                                                        LocalDate departDate, LocalDate returnDate) throws Exception {
        try {
            GoogleFlightsPage flightsPage = new GoogleFlightsPage(page);
            flightsPage.navigate(origin, destination, departDate, returnDate);
            sleepRandom();
            List<FlightInfo> results = flightsPage.extractFlights(origin, destination, MAX_FLIGHTS_PER_SOURCE);
            results.forEach(f -> logScrapeResult("Google Flights (round-trip)", f));
            return results;
        } catch (Exception e) {
            captureDebugScreenshot(page, "GoogleFlights_RoundTrip");
            throw e;
        }
    }

    private List<FlightInfo> scrapeRoundTripFromAirAsia(Page page, String origin, String destination,
                                                         LocalDate departDate, LocalDate returnDate) throws Exception {
        try {
            AirAsiaPage flightsPage = new AirAsiaPage(page);
            flightsPage.navigate(origin, destination, departDate, returnDate);
            sleepRandom();
            List<FlightInfo> results = flightsPage.extractFlights(origin, destination, MAX_FLIGHTS_PER_SOURCE);
            results.forEach(f -> logScrapeResult("AirAsia (round-trip)", f));
            return results;
        } catch (Exception e) {
            captureDebugScreenshot(page, "AirAsia_RoundTrip");
            throw e;
        }
    }

    private List<FlightInfo> scrapeFlightsFromGoogle(Page page, String origin, String destination, LocalDate flightDate) throws Exception {
        try {
            GoogleFlightsPage flightsPage = new GoogleFlightsPage(page);
            flightsPage.navigate(origin, destination, flightDate);
            sleepRandom();
            List<FlightInfo> results = flightsPage.extractFlights(origin, destination, MAX_FLIGHTS_PER_SOURCE);
            results.forEach(f -> logScrapeResult("Google Flights", f));
            return results;
        } catch (Exception e) {
            captureDebugScreenshot(page, "GoogleFlights");
            throw e;
        }
    }

    private List<FlightInfo> scrapeFlightsFromAirAsia(Page page, String origin, String destination, LocalDate flightDate) throws Exception {
        try {
            AirAsiaPage flightsPage = new AirAsiaPage(page);
            flightsPage.navigate(origin, destination, flightDate);
            sleepRandom();
            List<FlightInfo> results = flightsPage.extractFlights(origin, destination, MAX_FLIGHTS_PER_SOURCE);
            results.forEach(f -> logScrapeResult("AirAsia", f));
            return results;
        } catch (Exception e) {
            captureDebugScreenshot(page, "AirAsia");
            throw e;
        }
    }

    private void captureDebugScreenshot(Page page, String source) {
        try {
            Path dir = Path.of(SCREENSHOT_DIR);
            Files.createDirectories(dir);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = source + "_" + timestamp + ".png";
            Path screenshotPath = dir.resolve(filename);
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotPath)
                    .setFullPage(true));
            log.info("Debug screenshot saved: {}", screenshotPath);
        } catch (Exception screenshotError) {
            log.warn("Failed to capture debug screenshot for {}: {}", source, screenshotError.getMessage());
        }
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
