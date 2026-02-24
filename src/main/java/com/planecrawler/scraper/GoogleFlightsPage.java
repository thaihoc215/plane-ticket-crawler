package com.planecrawler.scraper;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.planecrawler.model.FlightInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Page Object Model for Google Flights.
 * Uses a tiered extraction strategy to survive Google's frequent CSS class changes:
 * <ol>
 *   <li>aria-label based selectors (most stable – accessibility attributes rarely change)</li>
 *   <li>page.evaluate() JavaScript text scanning (ignores CSS classes entirely)</li>
 * </ol>
 */
public class GoogleFlightsPage {

    private static final Logger log = LoggerFactory.getLogger(GoogleFlightsPage.class);

    private static final String BASE_URL =
            "https://www.google.com/travel/flights/search?q=Flights+from+%s+to+%s";

    private static final String FLIGHT_CARD_SELECTOR = "[data-gs]";

    private static final String PRICE_ARIA_SELECTOR =
            "[data-gs] [aria-label*='$'], [data-gs] [aria-label*='USD'], [data-gs] [aria-label*='dollars'],"
            + " [data-gs] [aria-label*='dong'], [data-gs] [aria-label*='VND']";
    private static final String DURATION_ARIA_SELECTOR =
            "[data-gs] [aria-label*='Total duration'], [data-gs] [aria-label*='hr'], [data-gs] [aria-label*='min']";

    private static final Pattern PRICE_PATTERN = Pattern.compile("[\\d,]+");

    private final Page page;

    public GoogleFlightsPage(Page page) {
        this.page = page;
    }

    public void navigate(String origin, String destination) {
        navigate(origin, destination, null);
    }

    public void navigate(String origin, String destination, LocalDate flightDate) {
        String datePart = flightDate == null ? ""
                : "+" + URLEncoder.encode("on " + flightDate, StandardCharsets.UTF_8);
        String url = String.format(BASE_URL, origin, destination) + datePart;
        log.info("Navigating to Google Flights: {}", url);
        page.navigate(url);

        dismissConsentBanner();

        try {
            page.waitForSelector(FLIGHT_CARD_SELECTOR,
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
        } catch (Exception e) {
            log.warn("Flight card selector [data-gs] not found, falling back to networkidle");
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(20_000));
        }
    }

    public FlightInfo extractCheapestFlight(String origin, String destination) {
        // Tier 1: aria-label based extraction
        try {
            return extractViaAriaLabels(origin, destination);
        } catch (Exception e) {
            log.warn("Tier 1 (aria-label) extraction failed: {}", e.getMessage());
        }

        // Tier 2: JavaScript text scanning on [data-gs] cards
        try {
            return extractViaJavaScript(origin, destination);
        } catch (Exception e) {
            log.warn("Tier 2 (JS evaluate) extraction failed: {}", e.getMessage());
        }

        // Tier 3: Full page text regex (last resort)
        return extractViaFullPageText(origin, destination);
    }

    private FlightInfo extractViaAriaLabels(String origin, String destination) {
        Locator priceLocator = page.locator(PRICE_ARIA_SELECTOR);
        if (priceLocator.count() == 0) {
            throw new IllegalStateException("No aria-label price elements found");
        }

        String rawPrice = priceLocator.first().textContent();
        BigDecimal price = parsePrice(rawPrice);

        String airline = "Unknown";
        Locator cards = page.locator(FLIGHT_CARD_SELECTOR);
        if (cards.count() > 0) {
            String cardLabel = cards.first().getAttribute("aria-label");
            if (cardLabel != null && !cardLabel.isBlank()) {
                airline = parseAirlineFromLabel(cardLabel);
            }
        }

        String duration = "N/A";
        Locator durationLocator = page.locator(DURATION_ARIA_SELECTOR);
        if (durationLocator.count() > 0) {
            duration = durationLocator.first().textContent().trim();
        }

        return new FlightInfo(price, airline, duration, origin, destination);
    }

    @SuppressWarnings("unchecked")
    private FlightInfo extractViaJavaScript(String origin, String destination) {
        Object result = page.evaluate(
                "() => {\n" +
                "  const cards = document.querySelectorAll('[data-gs]');\n" +
                "  if (!cards.length) return null;\n" +
                "  const card = cards[0];\n" +
                "  const text = card.innerText;\n" +
                "  /* Match prices: $123, ₫1,234,000, VND 1,234,000, 1,234,000 VND */\n" +
                "  const dollarMatch = text.match(/\\$(\\d[\\d,]*)/);\n" +
                "  const dongMatch = text.match(/[₫đ](\\d[\\d,]*)/) || text.match(/(\\d{1,3}(?:,\\d{3})+)\\s*(?:VND|đ)/i) || text.match(/VND\\s*(\\d[\\d,]*)/);\n" +
                "  const priceMatch = dollarMatch || dongMatch;\n" +
                "  const durationMatch = text.match(/(\\d+\\s*hr?\\s*\\d*\\s*min?)/);\n" +
                "  const lines = text.split('\\n').filter(l => l.trim());\n" +
                "  const airline = lines.find(l =>\n" +
                "    !l.match(/^\\d/) && !l.match(/^[\\$₫đ]/) &&\n" +
                "    !l.match(/hr|min|stop|nonstop/i) &&\n" +
                "    !l.match(/^[A-Z]{3}\\s/) &&\n" +
                "    l.length > 2 && l.length < 40\n" +
                "  ) || 'Unknown';\n" +
                "  return {\n" +
                "    price: priceMatch ? priceMatch[1].replace(/,/g, '') : null,\n" +
                "    duration: durationMatch ? durationMatch[0] : 'N/A',\n" +
                "    airline: airline.trim()\n" +
                "  };\n" +
                "}"
        );

        if (result == null) {
            throw new IllegalStateException("No [data-gs] cards found via JS evaluation");
        }

        Map<String, Object> map = (Map<String, Object>) result;
        Object priceVal = map.get("price");
        if (priceVal == null) {
            throw new IllegalStateException("No price pattern found in flight card text");
        }

        BigDecimal price = new BigDecimal(priceVal.toString());
        String airline = map.getOrDefault("airline", "Unknown").toString();
        String duration = map.getOrDefault("duration", "N/A").toString();

        return new FlightInfo(price, airline, duration, origin, destination);
    }

    @SuppressWarnings("unchecked")
    private FlightInfo extractViaFullPageText(String origin, String destination) {
        Object result = page.evaluate(
                "() => {\n" +
                "  const text = document.body.innerText;\n" +
                "  const dollarMatch = text.match(/\\$(\\d[\\d,]*)/);\n" +
                "  const dongMatch = text.match(/[₫đ](\\d[\\d,]*)/) || text.match(/(\\d{1,3}(?:,\\d{3})+)\\s*(?:VND|đ)/i) || text.match(/VND\\s*(\\d[\\d,]*)/);\n" +
                "  const priceMatch = dollarMatch || dongMatch;\n" +
                "  const durationMatch = text.match(/(\\d+\\s*hr?\\s*\\d*\\s*min?)/);\n" +
                "  return {\n" +
                "    price: priceMatch ? priceMatch[1].replace(/,/g, '') : null,\n" +
                "    duration: durationMatch ? durationMatch[0] : 'N/A'\n" +
                "  };\n" +
                "}"
        );

        if (result == null) {
            throw new IllegalStateException("Full-page text extraction returned null");
        }

        Map<String, Object> map = (Map<String, Object>) result;
        Object priceVal = map.get("price");
        if (priceVal == null) {
            throw new IllegalStateException("No price pattern found anywhere on Google Flights page");
        }

        BigDecimal price = new BigDecimal(priceVal.toString());
        String duration = map.getOrDefault("duration", "N/A").toString();

        return new FlightInfo(price, "Unknown", duration, origin, destination);
    }

    private void dismissConsentBanner() {
        try {
            Locator consentButton = page.locator(
                    "button:has-text('Accept all'), button:has-text('I agree'), " +
                    "button:has-text('Accept'), button[aria-label*='Accept']");
            if (consentButton.count() > 0) {
                consentButton.first().click(new Locator.ClickOptions().setTimeout(3_000));
                log.debug("Dismissed cookie consent banner");
            }
        } catch (Exception ignored) {
        }
    }

    private static String parseAirlineFromLabel(String label) {
        Pattern p = Pattern.compile("(?:with|by|on)\\s+([A-Za-z][A-Za-z .&'-]+?)(?:\\.|,|\\s+Total|\\s+Duration|$)");
        Matcher m = p.matcher(label);
        if (m.find()) {
            return m.group(1).trim();
        }
        String[] parts = label.split("[.,]");
        if (parts.length > 0 && parts[0].length() < 50) {
            return parts[0].trim();
        }
        return "Unknown";
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
