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
        alertAboveTarget = new PriceAlert("ORD", "MIA", new BigDecimal("200.00"), "user2@example.com");
    }

    @Test
    void checkAlerts_whenPriceBelowTarget_sendsEmail() throws Exception {
        FlightInfo cheapFlight = new FlightInfo(
                new BigDecimal("250.00"), "Delta", "5h 30m", "JFK", "LAX");

        when(alertRepository.findByActiveTrue()).thenReturn(List.of(alertUnderTarget));
        when(scraperService.scrape("JFK", "LAX")).thenReturn(cheapFlight);

        alertWatcherService.checkAlerts();

        verify(emailService, times(1)).sendPriceAlert(alertUnderTarget, cheapFlight);
        verify(alertRepository, times(1)).save(alertUnderTarget);
    }

    @Test
    void checkAlerts_whenPriceEqualsTarget_sendsEmail() throws Exception {
        FlightInfo exactFlight = new FlightInfo(
                new BigDecimal("300.00"), "United", "6h", "JFK", "LAX");

        when(alertRepository.findByActiveTrue()).thenReturn(List.of(alertUnderTarget));
        when(scraperService.scrape("JFK", "LAX")).thenReturn(exactFlight);

        alertWatcherService.checkAlerts();

        verify(emailService, times(1)).sendPriceAlert(alertUnderTarget, exactFlight);
    }

    @Test
    void checkAlerts_whenPriceAboveTarget_doesNotSendEmail() throws Exception {
        FlightInfo expensiveFlight = new FlightInfo(
                new BigDecimal("350.00"), "American", "7h", "ORD", "MIA");

        when(alertRepository.findByActiveTrue()).thenReturn(List.of(alertAboveTarget));
        when(scraperService.scrape("ORD", "MIA")).thenReturn(expensiveFlight);

        alertWatcherService.checkAlerts();

        verify(emailService, never()).sendPriceAlert(any(), any());
    }

    @Test
    void checkAlerts_whenScraperThrows_doesNotPropagateException() throws Exception {
        when(alertRepository.findByActiveTrue()).thenReturn(List.of(alertUnderTarget));
        when(scraperService.scrape(any(), any())).thenThrow(new RuntimeException("Bot detected"));

        // Should not throw; exceptions are caught and logged
        alertWatcherService.checkAlerts();

        verify(emailService, never()).sendPriceAlert(any(), any());
    }

    @Test
    void checkAlerts_withNoActiveAlerts_doesNotScrape() throws Exception {
        when(alertRepository.findByActiveTrue()).thenReturn(List.of());

        alertWatcherService.checkAlerts();

        verifyNoInteractions(scraperService, emailService);
    }
}
