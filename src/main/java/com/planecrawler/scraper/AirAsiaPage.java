package com.planecrawler.scraper;

import com.microsoft.playwright.Page;
import com.planecrawler.model.FlightInfo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Page Object Model for AirAsia booking flow.
 */
public class AirAsiaPage {

    private static final String SEARCH_URL_TEMPLATE =
            "https://www.airasia.com/flights?origin=%s&destination=%s";
    private static final String PRICE_SELECTOR =
            "[data-testid*='price'], [class*='price'], [class*='fare']";
    private static final String AIRLINE_SELECTOR =
            "[data-testid*='airline'], [class*='airline'], [class*='carrier']";
    private static final String DURATION_SELECTOR =
            "[data-testid*='duration'], [class*='duration'], [class*='travel-time']";
    private static final Pattern PRICE_PATTERN = Pattern.compile("[\\d,]+");

    private final Page page;

    public AirAsiaPage(Page page) {
        this.page = page;
    }

    public void navigate(String origin, String destination) {
        navigate(origin, destination, null);
    }

    public void navigate(String origin, String destination, LocalDate flightDate) {
        String url = String.format(
                SEARCH_URL_TEMPLATE,
                URLEncoder.encode(origin, StandardCharsets.UTF_8),
                URLEncoder.encode(destination, StandardCharsets.UTF_8)
        );
        if (flightDate != null) {
            url = url + "&departureDate=" + URLEncoder.encode(flightDate.toString(), StandardCharsets.UTF_8);
        }
        page.navigate(url);
        page.waitForSelector("body", new Page.WaitForSelectorOptions().setTimeout(15_000));
    }

    public FlightInfo extractCheapestFlight(String origin, String destination) {
        String rawPrice = textOrThrow(PRICE_SELECTOR, "price");
        String airline = textOrThrow(AIRLINE_SELECTOR, "airline");
        String duration = textOrThrow(DURATION_SELECTOR, "duration");
        return new FlightInfo(parsePrice(rawPrice), airline.trim(), duration.trim(), origin, destination);
    }

    private String textOrThrow(String selector, String fieldName) {
        var locator = page.locator(selector);
        if (locator.count() == 0) {
            throw new IllegalStateException("AirAsia " + fieldName + " not found");
        }
        return locator.first().textContent();
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
