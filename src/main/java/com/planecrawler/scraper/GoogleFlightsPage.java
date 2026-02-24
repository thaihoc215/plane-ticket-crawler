package com.planecrawler.scraper;

import com.microsoft.playwright.Page;
import com.planecrawler.model.FlightInfo;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Page Object Model for Google Flights.
 * Isolates all selectors and page interaction logic from the scraping service.
 */
public class GoogleFlightsPage {

    // Google Flights URL template
    private static final String BASE_URL =
            "https://www.google.com/travel/flights/search?q=Flights+from+%s+to+%s";

    // CSS selectors (Google Flights)
    private static final String PRICE_SELECTOR   = "[data-gs] .YMlIz.FpEdX span";
    private static final String AIRLINE_SELECTOR = "[data-gs] .sSHqwe.tPgKwe.ogfYpf span";
    private static final String DURATION_SELECTOR= "[data-gs] .gvkrdb.AdWm1c.tPgKwe.ogfYpf";

    private static final Pattern PRICE_PATTERN = Pattern.compile("[\\d,]+");

    private final Page page;

    public GoogleFlightsPage(Page page) {
        this.page = page;
    }

    /**
     * Navigates to the Google Flights search page for the given route.
     */
    public void navigate(String origin, String destination) {
        String url = String.format(BASE_URL, origin, destination);
        page.navigate(url);
        // Wait for flight results to load
        page.waitForSelector(PRICE_SELECTOR, new Page.WaitForSelectorOptions().setTimeout(15_000));
    }

    /**
     * Extracts the cheapest flight info from the first search result.
     *
     * @return a {@link FlightInfo} populated with price, airline, and duration
     */
    public FlightInfo extractCheapestFlight(String origin, String destination) {
        String rawPrice   = page.locator(PRICE_SELECTOR).first().textContent();
        String airline    = page.locator(AIRLINE_SELECTOR).first().textContent();
        String duration   = page.locator(DURATION_SELECTOR).first().textContent();

        BigDecimal price  = parsePrice(rawPrice);
        return new FlightInfo(price, airline.trim(), duration.trim(), origin, destination);
    }

    private static BigDecimal parsePrice(String rawPrice) {
        String cleaned = rawPrice.replaceAll(",", "");
        Matcher m = PRICE_PATTERN.matcher(cleaned);
        if (m.find()) {
            return new BigDecimal(m.group());
        }
        throw new IllegalStateException("Unable to parse price from: " + rawPrice);
    }
}
