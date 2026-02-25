package com.planecrawler.scraper;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class GoogleFlightsPageTest {

    @Test
    void buildTfsParam_roundTrip_matchesExpectedEncoding() {
        String tfs = GoogleFlightsPage.buildTfsParam(
                "SGN", "BKK",
                LocalDate.of(2026, 12, 10),
                LocalDate.of(2026, 12, 15));

        String expected = "CBwQAhoeEgoyMDI2LTEyLTEwagcIARIDU0dOcgcIARIDQktLGh4SCjIwMjYtMTItMTVqBwgBEgNCS0tyBwgBEgNTR05AAUgBcAGCAQsI____________AZgBAQ";
        assertEquals(expected, tfs);
    }

    @Test
    void buildTfsParam_oneWay_hasSingleSegmentAndTripTypeOne() {
        String tfs = GoogleFlightsPage.buildTfsParam(
                "SGN", "BKK",
                LocalDate.of(2026, 12, 10),
                null);

        // One-way: field 2 = 1 → base64 starts with CBwQAR (vs CBwQAh for round-trip)
        assertTrue(tfs.startsWith("CBwQAR"), "One-way tfs should have trip type 1");
        // Should contain only the SGN→BKK segment, not BKK→SGN
        assertFalse(tfs.contains(
                GoogleFlightsPage.buildTfsParam("SGN", "BKK",
                        LocalDate.of(2026, 12, 10),
                        LocalDate.of(2026, 12, 15)).substring(40, 60)),
                "One-way tfs should not contain return segment");
    }
}
