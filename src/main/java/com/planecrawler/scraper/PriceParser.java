package com.planecrawler.scraper;

import java.math.BigDecimal;

/**
 * Shared utility for parsing price strings extracted from airline websites.
 */
public final class PriceParser {

    private PriceParser() {}

    /**
     * Extracts the first numeric value from a raw price string (e.g. "$1,234" → 1234).
     *
     * @param rawPrice the raw text scraped from the page
     * @return the parsed price as {@link BigDecimal}
     * @throws IllegalStateException if no numeric value is found
     */
    public static BigDecimal parse(String rawPrice) {
        String digitsOnly = rawPrice.replaceAll("[^\\d]", "");
        if (digitsOnly.isEmpty()) {
            throw new IllegalStateException("Unable to parse price from: " + rawPrice);
        }
        return new BigDecimal(digitsOnly);
    }
}
