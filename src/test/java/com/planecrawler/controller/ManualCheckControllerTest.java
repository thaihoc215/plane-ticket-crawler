package com.planecrawler.controller;

import com.planecrawler.dto.response.AlertCheckResult;
import com.planecrawler.model.FlightInfo;
import com.planecrawler.service.AlertWatcherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ManualCheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertWatcherService alertWatcherService;

    @Test
    void manualCheck_returnsOkWithResults() throws Exception {
        var matchedFlight = new FlightInfo(
                new BigDecimal("200"), "Delta", "5h 30m", "JFK", "LAX", "08:00", "13:30");
        var matchedResult = new AlertCheckResult(
                1L, "JFK", "LAX", true,
                List.of(matchedFlight), List.of(), List.of(), null);
        var noMatchResult = new AlertCheckResult(
                2L, "SGN", "HAN", false,
                List.of(), List.of(), List.of(), null);

        when(alertWatcherService.checkAlerts()).thenReturn(List.of(matchedResult, noMatchResult));

        mockMvc.perform(post("/api/alerts/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Manual alert check completed"))
                .andExpect(jsonPath("$.alertsChecked").value(2))
                .andExpect(jsonPath("$.alertsMatched").value(1))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].alertId").value(1))
                .andExpect(jsonPath("$.results[0].matched").value(true))
                .andExpect(jsonPath("$.results[0].matchedOutboundFlights[0].price").value(200))
                .andExpect(jsonPath("$.results[0].matchedOutboundFlights[0].airline").value("Delta"))
                .andExpect(jsonPath("$.results[1].alertId").value(2))
                .andExpect(jsonPath("$.results[1].matched").value(false));
    }

    @Test
    void manualCheck_withNoActiveAlerts_returnsEmptyResults() throws Exception {
        when(alertWatcherService.checkAlerts()).thenReturn(List.of());

        mockMvc.perform(post("/api/alerts/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsChecked").value(0))
                .andExpect(jsonPath("$.alertsMatched").value(0))
                .andExpect(jsonPath("$.results.length()").value(0));
    }
}
