package com.planecrawler.service;

import com.planecrawler.model.FlightInfo;
import com.planecrawler.model.PriceAlert;
import com.planecrawler.repository.PriceAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertWatcherServiceTest {

    @Mock
    private PriceAlertRepository alertRepository;

    @Mock
    private FlightScraperService scraperService;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private AlertWatcherService alertWatcherService;

    private PriceAlert alertUnderTarget;
    private PriceAlert alertAboveTarget;

    @BeforeEach
    void setUp() {
        alertUnderTarget = new PriceAlert("JFK", "LAX", new BigDecimal("300.00"), "user1@example.com");
        alertUnderTarget.setDepartureDate(LocalDate.parse("2026-03-01"));
        alertAboveTarget = new PriceAlert("ORD", "MIA", new BigDecimal("200.00"), "user2@example.com");
        alertAboveTarget.setDepartureDate(LocalDate.parse("2026-03-01"));
    }

    @Test
    void checkAlerts_whenPriceBelowTarget_sendsEmail() throws Exception {
        FlightInfo cheapFlight = new FlightInfo(
                new BigDecimal("250.00"), "Delta", "5h 30m", "JFK", "LAX", "N/A", "N/A");

        when(alertRepository.findByActiveTrue()).thenReturn(List.of(alertUnderTarget));
        when(scraperService.scrape(eq("JFK"), eq("LAX"), any())).thenReturn(List.of(cheapFlight));

        alertWatcherService.checkAlerts();

        verify(emailService, times(1)).sendPriceAlert(eq(alertUnderTarget), eq(List.of(cheapFlight)), eq(List.of()), eq(false), isNull(), eq(List.of()));
        verify(alertRepository, times(1)).save(alertUnderTarget);
    }

    @Test
    void checkAlerts_whenPriceEqualsTarget_sendsEmail() throws Exception {
        FlightInfo exactFlight = new FlightInfo(
                new BigDecimal("300.00"), "United", "6h", "JFK", "LAX", "N/A", "N/A");

        when(alertRepository.findByActiveTrue()).thenReturn(List.of(alertUnderTarget));
        when(scraperService.scrape(eq("JFK"), eq("LAX"), any())).thenReturn(List.of(exactFlight));

        alertWatcherService.checkAlerts();

        verify(emailService, times(1)).sendPriceAlert(eq(alertUnderTarget), eq(List.of(exactFlight)), eq(List.of()), eq(false), isNull(), eq(List.of()));
    }

    @Test
    void checkAlerts_whenPriceAboveTarget_doesNotSendEmail() throws Exception {
        FlightInfo expensiveFlight = new FlightInfo(
                new BigDecimal("350.00"), "American", "7h", "ORD", "MIA", "N/A", "N/A");

        when(alertRepository.findByActiveTrue()).thenReturn(List.of(alertAboveTarget));
        when(scraperService.scrape(eq("ORD"), eq("MIA"), any())).thenReturn(List.of(expensiveFlight));

        alertWatcherService.checkAlerts();

        verify(emailService, never()).sendPriceAlert(any(), anyList(), anyList(), anyBoolean(), any(), anyList());
    }

    @Test
    void checkAlerts_whenScraperThrows_doesNotPropagateException() throws Exception {
        when(alertRepository.findByActiveTrue()).thenReturn(List.of(alertUnderTarget));
        when(scraperService.scrape(any(), any(), any())).thenThrow(new RuntimeException("Bot detected"));

        // Should not throw; exceptions are caught and logged
        alertWatcherService.checkAlerts();

        verify(emailService, never()).sendPriceAlert(any(), anyList(), anyList(), anyBoolean(), any(), anyList());
    }

    @Test
    void checkAlerts_withNoActiveAlerts_doesNotScrape() throws Exception {
        when(alertRepository.findByActiveTrue()).thenReturn(List.of());

        alertWatcherService.checkAlerts();

        verifyNoInteractions(scraperService, emailService);
    }

    @Test
    void checkAlerts_sendsOnlyFlightsUnderTarget() throws Exception {
        FlightInfo cheap = new FlightInfo(new BigDecimal("150.00"), "AirAsia", "5h", "JFK", "LAX", "N/A", "N/A");
        FlightInfo mid = new FlightInfo(new BigDecimal("290.00"), "Google", "6h", "JFK", "LAX", "N/A", "N/A");
        FlightInfo expensive = new FlightInfo(new BigDecimal("350.00"), "United", "7h", "JFK", "LAX", "N/A", "N/A");

        when(alertRepository.findByActiveTrue()).thenReturn(List.of(alertUnderTarget));
        when(scraperService.scrape(eq("JFK"), eq("LAX"), any())).thenReturn(List.of(cheap, mid, expensive));

        alertWatcherService.checkAlerts();

        // Only cheap and mid are <= 300 target
        verify(emailService).sendPriceAlert(eq(alertUnderTarget), eq(List.of(cheap, mid)), eq(List.of()), eq(false), isNull(), eq(List.of()));
    }

    @Test
    void checkAlerts_roundTrip_sendsMatchedFlightsForBothLegs() throws Exception {
        PriceAlert roundTrip = new PriceAlert("SGN", "HAN", new BigDecimal("250.00"), "roundtrip@example.com");
        roundTrip.setTripType(PriceAlert.TripType.ROUND_TRIP);
        roundTrip.setDepartureDate(LocalDate.parse("2026-03-01"));
        roundTrip.setReturnDate(LocalDate.parse("2026-03-10"));
        roundTrip.setReturnTargetPrice(new BigDecimal("150.00"));

        FlightInfo outbound = new FlightInfo(new BigDecimal("240.00"), "Vietnam Airlines", "2h 10m", "SGN", "HAN", "N/A", "N/A");
        FlightInfo inbound = new FlightInfo(new BigDecimal("200.00"), "Vietnam Airlines", "2h 5m", "HAN", "SGN", "N/A", "N/A");

        when(alertRepository.findByActiveTrue()).thenReturn(List.of(roundTrip));
        when(scraperService.scrape(eq("SGN"), eq("HAN"), any())).thenReturn(List.of(outbound));
        when(scraperService.scrape(eq("HAN"), eq("SGN"), any())).thenReturn(List.of(inbound));

        alertWatcherService.checkAlerts();

        // Outbound 240 <= 250 target, but return 200 > 150 target so return list is empty.
        // No roundTripTargetPrice set, so scrapeRoundTrip is NOT called
        verify(scraperService, never()).scrapeRoundTrip(any(), any(), any(), any());
        verify(emailService).sendPriceAlert(eq(roundTrip), eq(List.of(outbound)), eq(List.of()), eq(false), isNull(), eq(List.of()));
    }

    @Test
    void checkAlerts_roundTrip_scrapeRoundTripAboveTarget_outboundStillMatches() throws Exception {
        PriceAlert roundTrip = new PriceAlert("SGN", "HAN", new BigDecimal("500.00"), "rt@example.com");
        roundTrip.setTripType(PriceAlert.TripType.ROUND_TRIP);
        roundTrip.setDepartureDate(LocalDate.parse("2026-03-01"));
        roundTrip.setReturnDate(LocalDate.parse("2026-03-10"));
        roundTrip.setReturnTargetPrice(new BigDecimal("100.00"));
        roundTrip.setRoundTripTargetPrice(new BigDecimal("600.00"));

        FlightInfo outbound = new FlightInfo(new BigDecimal("400.00"), "VietJet", "2h", "SGN", "HAN", "N/A", "N/A");
        FlightInfo inbound = new FlightInfo(new BigDecimal("250.00"), "VietJet", "2h", "HAN", "SGN", "N/A", "N/A");
        // Native round-trip search returns 650 (above 600 target)
        FlightInfo roundTripFlight = new FlightInfo(new BigDecimal("650.00"), "VietJet", "2h", "SGN", "HAN", "N/A", "N/A");

        when(alertRepository.findByActiveTrue()).thenReturn(List.of(roundTrip));
        when(scraperService.scrape(eq("SGN"), eq("HAN"), any())).thenReturn(List.of(outbound));
        when(scraperService.scrape(eq("HAN"), eq("SGN"), any())).thenReturn(List.of(inbound));
        when(scraperService.scrapeRoundTrip(eq("SGN"), eq("HAN"), any(), any())).thenReturn(List.of(roundTripFlight));

        alertWatcherService.checkAlerts();

        // Outbound 400 <= 500 (matches), return 250 > 100 (no match), roundTrip 650 > 600 (no match)
        // Still sends because outbound matched
        verify(scraperService).scrapeRoundTrip(eq("SGN"), eq("HAN"), any(), any());
        verify(emailService).sendPriceAlert(eq(roundTrip), eq(List.of(outbound)), eq(List.of()), eq(false), eq(new BigDecimal("650.00")), eq(List.of()));
    }

    @Test
    void checkAlerts_roundTrip_nativeRoundTripPriceMatchesTarget() throws Exception {
        PriceAlert roundTrip = new PriceAlert("SGN", "HAN", new BigDecimal("100.00"), "rt2@example.com");
        roundTrip.setTripType(PriceAlert.TripType.ROUND_TRIP);
        roundTrip.setDepartureDate(LocalDate.parse("2026-03-01"));
        roundTrip.setReturnDate(LocalDate.parse("2026-03-10"));
        roundTrip.setReturnTargetPrice(new BigDecimal("100.00"));
        roundTrip.setRoundTripTargetPrice(new BigDecimal("500.00"));

        FlightInfo outbound = new FlightInfo(new BigDecimal("300.00"), "VietJet", "2h", "SGN", "HAN", "N/A", "N/A");
        FlightInfo inbound = new FlightInfo(new BigDecimal("150.00"), "VietJet", "2h", "HAN", "SGN", "N/A", "N/A");
        // Native round-trip search returns 450 (under 500 target — matches!)
        FlightInfo roundTripFlight = new FlightInfo(new BigDecimal("450.00"), "VietJet", "2h", "SGN", "HAN", "N/A", "N/A");

        when(alertRepository.findByActiveTrue()).thenReturn(List.of(roundTrip));
        when(scraperService.scrape(eq("SGN"), eq("HAN"), any())).thenReturn(List.of(outbound));
        when(scraperService.scrape(eq("HAN"), eq("SGN"), any())).thenReturn(List.of(inbound));
        when(scraperService.scrapeRoundTrip(eq("SGN"), eq("HAN"), any(), any())).thenReturn(List.of(roundTripFlight));

        alertWatcherService.checkAlerts();

        // No individual leg matches, but native round-trip price 450 <= 500 target triggers the alert
        verify(scraperService).scrapeRoundTrip(eq("SGN"), eq("HAN"), any(), any());
        verify(emailService).sendPriceAlert(eq(roundTrip), eq(List.of()), eq(List.of()), eq(true), eq(new BigDecimal("450.00")), eq(List.of(roundTripFlight)));
    }
}
