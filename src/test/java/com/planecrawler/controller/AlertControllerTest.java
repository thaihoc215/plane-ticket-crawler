package com.planecrawler.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planecrawler.repository.PriceAlertRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AlertControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PriceAlertRepository alertRepository;

    @Test
    void createAlert_returnsCreatedWithAlertId() throws Exception {
        Map<String, Object> request = Map.of(
                "origin", "JFK",
                "destination", "LAX",
                "departureDate", "2026-03-01",
                "targetPrice", 250,
                "userEmail", "test@example.com"
        );

        mockMvc.perform(post("/api/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alertId").exists())
                .andExpect(jsonPath("$.origin").value("JFK"))
                .andExpect(jsonPath("$.destination").value("LAX"))
                .andExpect(jsonPath("$.tripType").value("ONE_WAY"))
                .andExpect(jsonPath("$.userEmail").value("test@example.com"))
                .andExpect(jsonPath("$.message").value("Alert created successfully"));

        assertThat(alertRepository.findByActiveTrue()).isNotEmpty();
    }

    @Test
    void createAlert_withInvalidEmail_returnsBadRequest() throws Exception {
        Map<String, Object> request = Map.of(
                "origin", "JFK",
                "destination", "LAX",
                "departureDate", "2026-03-01",
                "targetPrice", 250,
                "userEmail", "not-an-email"
        );

        mockMvc.perform(post("/api/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAlert_withMissingOrigin_returnsBadRequest() throws Exception {
        Map<String, Object> request = Map.of(
                "destination", "LAX",
                "departureDate", "2026-03-01",
                "targetPrice", 250,
                "userEmail", "test@example.com"
        );

        mockMvc.perform(post("/api/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAlert_withNegativePrice_returnsBadRequest() throws Exception {
        Map<String, Object> request = Map.of(
                "origin", "JFK",
                "destination", "LAX",
                "departureDate", "2026-03-01",
                "targetPrice", -50,
                "userEmail", "test@example.com"
        );

        mockMvc.perform(post("/api/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRoundTripAlert_withMissingReturnFields_returnsBadRequest() throws Exception {
        Map<String, Object> request = Map.of(
                "origin", "JFK",
                "destination", "LAX",
                "tripType", "ROUND_TRIP",
                "departureDate", "2026-03-01",
                "targetPrice", 250,
                "userEmail", "test@example.com"
        );

        mockMvc.perform(post("/api/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRoundTripAlert_withOnlyRoundTripTargetPrice_returnsCreated() throws Exception {
        Map<String, Object> request = Map.of(
                "origin", "SGN",
                "destination", "HAN",
                "tripType", "ROUND_TRIP",
                "departureDate", "2026-03-01",
                "returnDate", "2026-03-10",
                "targetPrice", 300,
                "roundTripTargetPrice", 500,
                "userEmail", "roundtrip@example.com"
        );

        mockMvc.perform(post("/api/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tripType").value("ROUND_TRIP"))
                .andExpect(jsonPath("$.roundTripTargetPrice").value(500));
    }

    @Test
    void listAlerts_withStatusFilter_returnsMatchingAlerts() throws Exception {
        alertRepository.deleteAll();

        var activeAlert = alertRepository.save(new com.planecrawler.model.PriceAlert(
                "JFK", "LAX", com.planecrawler.model.PriceAlert.TripType.ONE_WAY,
                java.time.LocalDate.parse("2026-03-01"), null,
                new java.math.BigDecimal("250"), null, null, "active@example.com"));

        var inactiveAlert = alertRepository.save(new com.planecrawler.model.PriceAlert(
                "SFO", "SEA", com.planecrawler.model.PriceAlert.TripType.ONE_WAY,
                java.time.LocalDate.parse("2026-03-01"), null,
                new java.math.BigDecimal("200"), null, null, "inactive@example.com"));
        inactiveAlert.setActive(false);
        alertRepository.save(inactiveAlert);

        mockMvc.perform(get("/api/alerts").param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userEmail").value("active@example.com"));

        mockMvc.perform(get("/api/alerts").param("status", "inactive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userEmail").value("inactive@example.com"));

        mockMvc.perform(get("/api/alerts").param("status", "all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void deactivateAndDeleteAlert_updatesStatusAndRemovesFromDatabase() throws Exception {
        var alert = alertRepository.save(new com.planecrawler.model.PriceAlert(
                "JFK", "LAX", com.planecrawler.model.PriceAlert.TripType.ONE_WAY,
                java.time.LocalDate.parse("2026-03-01"), null,
                new java.math.BigDecimal("250"), null, null, "ops@example.com"));

        mockMvc.perform(patch("/api/alerts/{id}/deactivate", alert.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Alert deactivated"));

        assertThat(alertRepository.findById(alert.getId())).get().extracting("active").isEqualTo(false);

        mockMvc.perform(delete("/api/alerts/{id}", alert.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Alert deleted"));

        assertThat(alertRepository.existsById(alert.getId())).isFalse();
    }
}
