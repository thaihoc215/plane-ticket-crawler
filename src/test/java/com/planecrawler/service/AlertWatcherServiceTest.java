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

import static org.mockito.ArgumentMatchers.any;
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
                new BigDecimal("250.00"), "Delta", "5h 30m", "JFK", "LAX");

        when(alertRepository.findByActiveTrue()).thenReturn(List.of(alertUnderTarget));
        when(scraperService.scrape(eq("JFK"), eq("LAX"), any())).thenReturn(cheapFlight);

        alertWatcherService.checkAlerts();

        verify(emailService, times(1)).sendPriceAlert(alertUnderTarget, cheapFlight, null, true, false);
        verify(alertRepository, times(1)).save(alertUnderTarget);
    }

    @Test
    void checkAlerts_whenPriceEqualsTarget_sendsEmail() throws Exception {
        FlightInfo exactFlight = new FlightInfo(
                new BigDecimal("300.00"), "United", "6h", "JFK", "LAX");

        when(alertRepository.findByActiveTrue()).thenReturn(List.of(alertUnderTarget));
        when(scraperService.scrape(eq("JFK"), eq("LAX"), any())).thenReturn(exactFlight);

        alertWatcherService.checkAlerts();

        verify(emailService, times(1)).sendPriceAlert(alertUnderTarget, exactFlight, null, true, false);
    }

    @Test
    void checkAlerts_whenPriceAboveTarget_doesNotSendEmail() throws Exception {
        FlightInfo expensiveFlight = new FlightInfo(
                new BigDecimal("350.00"), "American", "7h", "ORD", "MIA");

        when(alertRepository.findByActiveTrue()).thenReturn(List.of(alertAboveTarget));
        when(scraperService.scrape(eq("ORD"), eq("MIA"), any())).thenReturn(expensiveFlight);

        alertWatcherService.checkAlerts();

        verify(emailService, never()).sendPriceAlert(any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void checkAlerts_whenScraperThrows_doesNotPropagateException() throws Exception {
        when(alertRepository.findByActiveTrue()).thenReturn(List.of(alertUnderTarget));
        when(scraperService.scrape(any(), any(), any())).thenThrow(new RuntimeException("Bot detected"));

        // Should not throw; exceptions are caught and logged
        alertWatcherService.checkAlerts();

        verify(emailService, never()).sendPriceAlert(any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void checkAlerts_withNoActiveAlerts_doesNotScrape() throws Exception {
        when(alertRepository.findByActiveTrue()).thenReturn(List.of());

        alertWatcherService.checkAlerts();

        verifyNoInteractions(scraperService, emailService);
    }

    @Test
    void checkAlerts_roundTrip_whenOneLegMatches_sendsEmailWithBothPrices() throws Exception {
        PriceAlert roundTrip = new PriceAlert("SGN", "HAN", new BigDecimal("250.00"), "roundtrip@example.com");
        roundTrip.setTripType(PriceAlert.TripType.ROUND_TRIP);
        roundTrip.setDepartureDate(LocalDate.parse("2026-03-01"));
        roundTrip.setReturnDate(LocalDate.parse("2026-03-10"));
        roundTrip.setReturnTargetPrice(new BigDecimal("150.00"));

        FlightInfo outbound = new FlightInfo(new BigDecimal("240.00"), "Vietnam Airlines", "2h 10m", "SGN", "HAN");
        FlightInfo inbound = new FlightInfo(new BigDecimal("200.00"), "Vietnam Airlines", "2h 5m", "HAN", "SGN");

        when(alertRepository.findByActiveTrue()).thenReturn(List.of(roundTrip));
        when(scraperService.scrape(eq("SGN"), eq("HAN"), any())).thenReturn(outbound);
        when(scraperService.scrape(eq("HAN"), eq("SGN"), any())).thenReturn(inbound);

        alertWatcherService.checkAlerts();

        verify(emailService).sendPriceAlert(roundTrip, outbound, inbound, true, false);
    }
}
