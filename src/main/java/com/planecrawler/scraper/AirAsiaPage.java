package com.planecrawler.scraper;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.planecrawler.model.FlightInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Page Object Model for AirAsia booking flow.
 * Note: AirAsia uses Cloudflare protection, so this scraper is best-effort.
 */
public class AirAsiaPage {

    private static final Logger log = LoggerFactory.getLogger(AirAsiaPage.class);

    private static final String SEARCH_URL = "https://www.airasia.com/flights/search/";
    private static final DateTimeFormatter AIRASIA_DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private final Page page;

    public AirAsiaPage(Page page) {
        this.page = page;
    }

    public void navigate(String origin, String destination) {
        navigate(origin, destination, null);
    }

    public void navigate(String origin, String destination, LocalDate flightDate) {
        navigate(origin, destination, flightDate, null);
    }

    public void navigate(String origin, String destination, LocalDate departDate, LocalDate returnDate) {
        StringBuilder url = new StringBuilder(SEARCH_URL);
        url.append("?origin=").append(origin);
        url.append("&destination=").append(destination);
        if (departDate != null) {
            url.append("&departDate=").append(departDate.format(AIRASIA_DATE_FORMAT));
        }
        if (returnDate != null) {
            url.append("&tripType=R");
            url.append("&returnDate=").append(returnDate.format(AIRASIA_DATE_FORMAT));
        } else {
            url.append("&tripType=O");
        }
        url.append("&adult=1&child=0&infant=0");
        url.append("&locale=vi-vn");
        url.append("&currency=VND");
        url.append("&ule=true");
        url.append("&cabinClass=economy");
        url.append("&uce=true");
        url.append("&ancillaryAbTest=false");
        url.append("&isOC=false&isDC=true");
        url.append("&promoCode=");
        url.append("&type=paired");
        url.append("&airlineProfile=all");
        url.append("&upsellWidget=true");
        url.append("&upsellPremiumFlatbedWidget=true");

        log.info("Navigating to AirAsia{}: {}", returnDate != null ? " (round-trip)" : "", url);
        page.navigate(url.toString());

        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(30_000));
        } catch (Exception e) {
            log.warn("AirAsia page did not reach networkidle within timeout");
        }

        try {
            page.waitForSelector("[data-testid*='fare'], [data-testid*='price'], [class*='fare'], [class*='flight-card']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
        } catch (Exception e) {
            log.debug("AirAsia fare selectors not found after wait");
        }
    }

    public FlightInfo extractCheapestFlight(String origin, String destination) {
        List<FlightInfo> flights = extractFlights(origin, destination, 1);
        if (flights.isEmpty()) {
            throw new IllegalStateException("No flight results found on AirAsia");
        }
        return flights.get(0);
    }

    public List<FlightInfo> extractFlights(String origin, String destination, int limit) {
        // Tier 1: CSS selector extraction for multiple fare elements
        try {
            List<FlightInfo> results = extractMultipleViaSelectors(origin, destination, limit);
            if (!results.isEmpty()) {
                return results;
            }
        } catch (Exception e) {
            log.warn("AirAsia multi-selector extraction failed: {}", e.getMessage());
        }

        // Tier 2: JavaScript multi-price extraction
        try {
            List<FlightInfo> results = extractMultipleViaJavaScript(origin, destination, limit);
            if (!results.isEmpty()) {
                return results;
            }
        } catch (Exception e) {
            log.warn("AirAsia multi-JS extraction failed: {}", e.getMessage());
        }

        return List.of();
    }

    private List<FlightInfo> extractMultipleViaSelectors(String origin, String destination, int limit) {
        String priceSelector = "[data-testid*='fare-amount'], [data-testid*='price'], [class*='fare-amount'], [class*='total-price']";
        Locator priceLocator = page.locator(priceSelector);
        int count = Math.min(priceLocator.count(), limit);
        if (count == 0) {
            throw new IllegalStateException("AirAsia price elements not found via selectors");
        }

        List<FlightInfo> flights = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            try {
                String rawPrice = priceLocator.nth(i).textContent();
                BigDecimal price = PriceParser.parse(rawPrice);
                flights.add(new FlightInfo(price, "AirAsia", "N/A", origin, destination, "N/A", "N/A"));
            } catch (Exception e) {
                log.debug("Failed to parse AirAsia price element {}: {}", i, e.getMessage());
            }
        }
        return flights;
    }

    @SuppressWarnings("unchecked")
    private List<FlightInfo> extractMultipleViaJavaScript(String origin, String destination, int limit) {
        Object result = page.evaluate(
                "(limit) => {\n" +
                "  const text = document.body.innerText;\n" +
                "  const regex = /(\\d{1,3}(?:,\\d{3})+)\\s*(?:VND|THB|MYR|đ)/gi;\n" +
                "  const prices = [];\n" +
                "  let match;\n" +
                "  while ((match = regex.exec(text)) !== null && prices.length < limit) {\n" +
                "    prices.push(match[1].replace(/,/g, ''));\n" +
                "  }\n" +
                "  if (!prices.length) {\n" +
                "    const usdRegex = /\\$(\\d[\\d,]*)/g;\n" +
                "    while ((match = usdRegex.exec(text)) !== null && prices.length < limit) {\n" +
                "      prices.push(match[1].replace(/,/g, ''));\n" +
                "    }\n" +
                "  }\n" +
                "  return prices;\n" +
                "}",
                limit
        );

        if (result == null) {
            return List.of();
        }

        List<String> priceStrings = (List<String>) result;
        List<FlightInfo> flights = new ArrayList<>();
        for (String priceStr : priceStrings) {
            flights.add(new FlightInfo(new BigDecimal(priceStr), "AirAsia", "N/A", origin, destination, "N/A", "N/A"));
        }
        return flights;
    }

}
