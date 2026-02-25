package com.planecrawler.scraper;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.planecrawler.model.FlightInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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

    private static final String SEARCH_URL =
            "https://www.google.com/travel/flights/search";

    private static final String FALLBACK_URL =
            SEARCH_URL + "?q=Flights+from+%s+to+%s";

    /** Flight search options for round-trip queries. */
    private static final String TFU_ROUND_TRIP = "EgoIAhABGAAgAigL";

    private static final String FLIGHT_CARD_SELECTOR = "[data-gs]";

    private static final String PRICE_ARIA_SELECTOR =
            "[data-gs] [aria-label*='$'], [data-gs] [aria-label*='USD'], [data-gs] [aria-label*='dollars'],"
            + " [data-gs] [aria-label*='dong'], [data-gs] [aria-label*='VND']";
    private static final String DURATION_ARIA_SELECTOR =
            "[data-gs] [aria-label*='Total duration'], [data-gs] [aria-label*='hr'], [data-gs] [aria-label*='min']";

    private final Page page;

    public GoogleFlightsPage(Page page) {
        this.page = page;
    }

    public void navigate(String origin, String destination) {
        navigate(origin, destination, null);
    }

    public void navigate(String origin, String destination, LocalDate flightDate) {
        navigate(origin, destination, flightDate, null);
    }

    public void navigate(String origin, String destination, LocalDate departDate, LocalDate returnDate) {
        String url;
        if (departDate != null && returnDate != null) {
            // Round-trip: use tfs protobuf encoding (matches Google Flights internal format)
            String tfs = buildTfsParam(origin, destination, departDate, returnDate);
            url = SEARCH_URL + "?tfs=" + tfs + "&tfu=" + TFU_ROUND_TRIP;
        } else if (departDate != null) {
            // One-way with date: use natural-language q= format
            url = String.format(FALLBACK_URL, origin, destination) + "+on+" + departDate;
        } else {
            url = String.format(FALLBACK_URL, origin, destination);
        }
        log.info("Navigating to Google Flights{}: {}", returnDate != null ? " (round-trip)" : "", url);
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
        List<FlightInfo> flights = extractFlights(origin, destination, 1);
        if (flights.isEmpty()) {
            throw new IllegalStateException("No flight results found on Google Flights");
        }
        return flights.get(0);
    }

    public List<FlightInfo> extractFlights(String origin, String destination, int limit) {
        // Tier 1: JavaScript multi-card extraction (most reliable for multiple results)
        try {
            List<FlightInfo> results = extractMultipleViaJavaScript(origin, destination, limit);
            if (!results.isEmpty()) {
                return results;
            }
        } catch (Exception e) {
            log.warn("Multi-card JS extraction failed: {}", e.getMessage());
        }

        // Tier 2: aria-label single extraction fallback
        try {
            return List.of(extractViaAriaLabels(origin, destination));
        } catch (Exception e) {
            log.warn("Tier 2 (aria-label) extraction failed: {}", e.getMessage());
        }

        // Tier 3: Full page text regex (last resort)
        try {
            return List.of(extractViaFullPageText(origin, destination));
        } catch (Exception e) {
            log.warn("Tier 3 (full-page text) extraction failed: {}", e.getMessage());
        }

        return List.of();
    }

    private FlightInfo extractViaAriaLabels(String origin, String destination) {
        Locator priceLocator = page.locator(PRICE_ARIA_SELECTOR);
        if (priceLocator.count() == 0) {
            throw new IllegalStateException("No aria-label price elements found");
        }

        String rawPrice = priceLocator.first().textContent();
        BigDecimal price = PriceParser.parse(rawPrice);

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

        return new FlightInfo(price, airline, duration, origin, destination, "N/A", "N/A");
    }

    @SuppressWarnings("unchecked")
    private List<FlightInfo> extractMultipleViaJavaScript(String origin, String destination, int limit) {
        // Query flight cards directly via accessibility attributes.
        // Each flight card is a [role="link"] element whose aria-label contains "Total duration",
        // and it encodes every field we need in one string:
        //   "From 2841000 Vietnamese dong round trip total. Nonstop flight with Vietjet.
        //    Leaves ... at 5:35 PM on ... and arrives at ... at 7:05 PM on ...
        //    Total duration 1 hr 30 min. Select flight"
        Object result = page.evaluate(
                "(limit) => {\n" +
                "  const cards = document.querySelectorAll('[role=\"link\"][aria-label*=\"Total duration\"]');\n" +
                "  if (!cards.length) return [];\n" +
                "  const flights = [];\n" +
                "  for (const card of cards) {\n" +
                "    if (flights.length >= limit) break;\n" +
                "    const label = card.getAttribute('aria-label') || '';\n" +
                "    // Price: 'From 2841000 Vietnamese dong' or 'From $250'\n" +
                "    const priceMatch = label.match(/From ([\\d,]+)\\s*(?:Vietnamese dong|US dollar|dollar)/i)\n" +
                "                    || label.match(/From \\$([\\d,.]+)/);\n" +
                "    if (!priceMatch) continue;\n" +
                "    const price = priceMatch[1].replace(/,/g, '');\n" +
                "    // Airline: 'flight with <Airline>.'\n" +
                "    const airlineMatch = label.match(/flight with ([^.]+)\\./);\n" +
                "    const airline = airlineMatch ? airlineMatch[1].trim() : 'Unknown';\n" +
                "    // Times: all HH:MM AM/PM occurrences (first = departure, second = arrival)\n" +
                "    const timeRe = /(\\d{1,2}:\\d{2}\\s*(?:AM|PM))/gi;\n" +
                "    const times = [];\n" +
                "    let m;\n" +
                "    while ((m = timeRe.exec(label)) !== null) times.push(m[1].trim());\n" +
                "    const departureTime = times[0] || 'N/A';\n" +
                "    const arrivalTime = times[1] || 'N/A';\n" +
                "    // Duration: 'Total duration X hr Y min'\n" +
                "    const durationMatch = label.match(/Total duration ([^.]+)/);\n" +
                "    const duration = durationMatch ? durationMatch[1].trim() : 'N/A';\n" +
                "    flights.push({ price, airline, departureTime, arrivalTime, duration });\n" +
                "  }\n" +
                "  return flights;\n" +
                "}",
                limit
        );

        if (result == null) {
            return List.of();
        }

        List<Map<String, Object>> items = (List<Map<String, Object>>) result;
        List<FlightInfo> flights = new ArrayList<>();
        for (Map<String, Object> map : items) {
            Object priceVal = map.get("price");
            if (priceVal == null) continue;
            log.info("FLIGHT CARD LABEL: {}", map.get("_label"));
            BigDecimal price = new BigDecimal(priceVal.toString());
            String airline = map.getOrDefault("airline", "Unknown").toString();
            String duration = map.getOrDefault("duration", "N/A").toString();
            String departureTime = map.getOrDefault("departureTime", "N/A").toString();
            String arrivalTime = map.getOrDefault("arrivalTime", "N/A").toString();
            flights.add(new FlightInfo(price, airline, duration, origin, destination, departureTime, arrivalTime));
        }
        return flights;
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

        return new FlightInfo(price, "Unknown", duration, origin, destination, "N/A", "N/A");
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
            log.warn("Failed to load cookie consent banner");
        }
    }

    // ---- Protobuf-based tfs URL builder (matches Google Flights internal format) ----

    /**
     * Builds the {@code tfs} query parameter that Google Flights uses for flight searches.
     * The parameter is a URL-safe Base64-encoded Protocol Buffer containing flight segments.
     */
    static String buildTfsParam(String origin, String destination,
                                LocalDate departDate, LocalDate returnDate) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeTag(buf, 1, 0);
        writeVarint(buf, 28);
        writeTag(buf, 2, 0);
        writeVarint(buf, returnDate != null ? 2 : 1);

        writeSegment(buf, departDate, origin, destination);
        if (returnDate != null) {
            writeSegment(buf, returnDate, destination, origin);
        }

        // adults = 1
        writeTag(buf, 8, 0);
        writeVarint(buf, 1);
        // cabin class: 1 = economy
        writeTag(buf, 9, 0);
        writeVarint(buf, 1);
        // stops: any
        writeTag(buf, 14, 0);
        writeVarint(buf, 1);
        // price filter: no limit (uint64 max)
        writeTag(buf, 16, 2);
        byte[] priceFilter = buildNoMaxPrice();
        writeVarint(buf, priceFilter.length);
        writeRawBytes(buf, priceFilter);
        // sort: best flights
        writeTag(buf, 19, 0);
        writeVarint(buf, 1);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf.toByteArray());
    }

    private static void writeSegment(ByteArrayOutputStream outer, LocalDate date,
                                     String origin, String destination) {
        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        byte[] dateBytes = date.toString().getBytes(StandardCharsets.UTF_8);
        writeTag(seg, 2, 2);
        writeVarint(seg, dateBytes.length);
        writeRawBytes(seg, dateBytes);

        byte[] originBytes = buildAirport(origin);
        writeTag(seg, 13, 2);
        writeVarint(seg, originBytes.length);
        writeRawBytes(seg, originBytes);

        byte[] destBytes = buildAirport(destination);
        writeTag(seg, 14, 2);
        writeVarint(seg, destBytes.length);
        writeRawBytes(seg, destBytes);

        byte[] segBytes = seg.toByteArray();
        writeTag(outer, 3, 2);
        writeVarint(outer, segBytes.length);
        writeRawBytes(outer, segBytes);
    }

    private static byte[] buildAirport(String code) {
        ByteArrayOutputStream ap = new ByteArrayOutputStream();
        writeTag(ap, 1, 0);
        writeVarint(ap, 1);
        writeTag(ap, 2, 2);
        byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
        writeVarint(ap, codeBytes.length);
        writeRawBytes(ap, codeBytes);
        return ap.toByteArray();
    }

    private static byte[] buildNoMaxPrice() {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        writeTag(f, 1, 0);
        // uint64 max encoded as varint (0xFFFFFFFFFFFFFFFF)
        f.write(new byte[]{
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x01
        }, 0, 10);
        return f.toByteArray();
    }

    private static void writeTag(ByteArrayOutputStream buf, int fieldNumber, int wireType) {
        writeVarint(buf, (fieldNumber << 3) | wireType);
    }

    private static void writeVarint(ByteArrayOutputStream buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.write(value);
    }

    private static void writeRawBytes(ByteArrayOutputStream buf, byte[] data) {
        buf.write(data, 0, data.length);
    }

    // ---- End tfs URL builder ----

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

}
