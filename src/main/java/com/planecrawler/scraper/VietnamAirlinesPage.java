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
 * Page Object Model for Vietnam Airlines booking flow.
 */
public class VietnamAirlinesPage {

    private static final Logger log = LoggerFactory.getLogger(VietnamAirlinesPage.class);

    private static final String BOOKING_URL = "https://www.vietnamairlines.com/vn/en/book-a-trip/booking";
    private static final Pattern PRICE_PATTERN = Pattern.compile("[\\d,]+");

    private final Page page;

    public VietnamAirlinesPage(Page page) {
        this.page = page;
    }

    public void navigate(String origin, String destination) {
        navigate(origin, destination, null);
    }

    public void navigate(String origin, String destination, LocalDate flightDate) {
        String departDate = flightDate != null
                ? flightDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                : LocalDate.now().plusDays(30).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        String url = BOOKING_URL
                + "?tripType=1"
                + "&departCity=" + origin
                + "&arriveCity=" + destination
                + "&departDate=" + departDate
                + "&adultQty=1&childQty=0&infantQty=0";

        log.info("Navigating to Vietnam Airlines: {}", url);
        page.navigate(url);

        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(30_000));
        } catch (Exception e) {
            log.warn("Vietnam Airlines page did not reach networkidle within timeout");
        }

        try {
            page.waitForSelector("[class*='fare'], [class*='price'], [class*='flight-result'], [class*='avail']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
        } catch (Exception e) {
            log.debug("Vietnam Airlines fare selectors not found after wait");
        }
    }

    public FlightInfo extractCheapestFlight(String origin, String destination) {
        try {
            return extractViaSelectors(origin, destination);
        } catch (Exception e) {
            log.warn("Vietnam Airlines selector extraction failed: {}", e.getMessage());
        }

        return extractViaJavaScript(origin, destination);
    }

    private FlightInfo extractViaSelectors(String origin, String destination) {
        String priceSelector = "[class*='fare-amount'], [class*='price-amount'], [class*='total-fare'], [class*='lowestPrice']";
        Locator priceLocator = page.locator(priceSelector);
        if (priceLocator.count() == 0) {
            throw new IllegalStateException("Vietnam Airlines price elements not found via selectors");
        }

        String rawPrice = priceLocator.first().textContent();
        BigDecimal price = parsePrice(rawPrice);
        return new FlightInfo(price, "Vietnam Airlines", "N/A", origin, destination);
    }

    @SuppressWarnings("unchecked")
    private FlightInfo extractViaJavaScript(String origin, String destination) {
        Object result = page.evaluate(
                "() => {\n" +
                "  const text = document.body.innerText;\n" +
                "  const vndMatch = text.match(/(\\d{1,3}(?:,\\d{3})+)\\s*(?:VND|đ)/i)\n" +
                "    || text.match(/(?:VND|đ)\\s*(\\d{1,3}(?:,\\d{3})+)/i)\n" +
                "    || text.match(/[₫đ](\\d[\\d,]*)/);\n" +
                "  const usdMatch = text.match(/\\$(\\d[\\d,]*)/)\n" +
                "    || text.match(/USD\\s*(\\d[\\d,]*)/);\n" +
                "  const match = vndMatch || usdMatch;\n" +
                "  return { price: match ? match[1].replace(/,/g, '') : null };\n" +
                "}"
        );

        if (result == null) {
            throw new IllegalStateException("Vietnam Airlines JS extraction returned null");
        }

        Map<String, Object> map = (Map<String, Object>) result;
        Object priceVal = map.get("price");
        if (priceVal == null) {
            throw new IllegalStateException("Vietnam Airlines price not found on page");
        }

        BigDecimal price = new BigDecimal(priceVal.toString());
        return new FlightInfo(price, "Vietnam Airlines", "N/A", origin, destination);
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
