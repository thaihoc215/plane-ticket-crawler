package com.planecrawler.service;

import com.microsoft.playwright.*;
import com.planecrawler.model.FlightInfo;
import com.planecrawler.scraper.GoogleFlightsPage;
import com.planecrawler.scraper.UserAgentRotator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

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
        log.info("Scraping flights from {} to {}", origin, destination);

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

                    GoogleFlightsPage flightsPage = new GoogleFlightsPage(page);
                    flightsPage.navigate(origin, destination);

                    // Random delay to mimic human reading the page
                    sleepRandom();

                    FlightInfo info = flightsPage.extractCheapestFlight(origin, destination);
                    log.info("Scraped flight: {} {} {} (price: {})",
                            info.getAirline(), info.getDuration(), info.getOrigin() + "->" + info.getDestination(),
                            info.getPrice());
                    return info;
                }
            }
        }
    }

    private static void sleepRandom() throws InterruptedException {
        long delay = ThreadLocalRandom.current().nextLong(MIN_SLEEP_MS, MAX_SLEEP_MS);
        Thread.sleep(delay);
    }
}
