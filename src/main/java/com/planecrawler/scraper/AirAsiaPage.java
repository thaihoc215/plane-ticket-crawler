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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Page Object Model for AirAsia booking flow.
 * Note: AirAsia uses Cloudflare protection, so this scraper is best-effort.
 */
public class AirAsiaPage {

    private static final Logger log = LoggerFactory.getLogger(AirAsiaPage.class);

    private static final String SEARCH_URL = "https://www.airasia.com/flights/search";
    private static final Pattern PRICE_PATTERN = Pattern.compile("[\\d,]+");

    private final Page page;

    public AirAsiaPage(Page page) {
        this.page = page;
    }

    public void navigate(String origin, String destination) {
        navigate(origin, destination, null);
    }

    public void navigate(String origin, String destination, LocalDate flightDate) {
        StringBuilder url = new StringBuilder(SEARCH_URL);
        url.append("?origin=").append(origin);
        url.append("&destination=").append(destination);
        url.append("&pax=1");
        if (flightDate != null) {
            url.append("&departDate=").append(flightDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        }

        log.info("Navigating to AirAsia: {}", url);
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
        try {
            return extractViaSelectors(origin, destination);
        } catch (Exception e) {
            log.warn("AirAsia selector extraction failed: {}", e.getMessage());
        }

        return extractViaJavaScript(origin, destination);
    }

    private FlightInfo extractViaSelectors(String origin, String destination) {
        String priceSelector = "[data-testid*='fare-amount'], [data-testid*='price'], [class*='fare-amount'], [class*='total-price']";
        Locator priceLocator = page.locator(priceSelector);
        if (priceLocator.count() == 0) {
            throw new IllegalStateException("AirAsia price elements not found via selectors");
        }

        String rawPrice = priceLocator.first().textContent();
        BigDecimal price = parsePrice(rawPrice);
        return new FlightInfo(price, "AirAsia", "N/A", origin, destination);
    }

    @SuppressWarnings("unchecked")
    private FlightInfo extractViaJavaScript(String origin, String destination) {
        Object result = page.evaluate(
                "() => {\n" +
                "  const text = document.body.innerText;\n" +
                "  const currencyMatch = text.match(/(\\d{1,3}(?:,\\d{3})+)\\s*(?:VND|THB|MYR|đ)/i)\n" +
                "    || text.match(/(?:VND|THB|MYR|đ)\\s*(\\d{1,3}(?:,\\d{3})+)/i)\n" +
                "    || text.match(/[₫đ](\\d[\\d,]*)/);\n" +
                "  const usdMatch = text.match(/\\$(\\d[\\d,]*)/)\n" +
                "    || text.match(/USD\\s*(\\d[\\d,]*)/);\n" +
                "  const match = currencyMatch || usdMatch;\n" +
                "  return { price: match ? match[1].replace(/,/g, '') : null };\n" +
                "}"
        );

        if (result == null) {
            throw new IllegalStateException("AirAsia JS extraction returned null");
        }

        Map<String, Object> map = (Map<String, Object>) result;
        Object priceVal = map.get("price");
        if (priceVal == null) {
            throw new IllegalStateException("AirAsia price not found on page");
        }

        BigDecimal price = new BigDecimal(priceVal.toString());
        return new FlightInfo(price, "AirAsia", "N/A", origin, destination);
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
