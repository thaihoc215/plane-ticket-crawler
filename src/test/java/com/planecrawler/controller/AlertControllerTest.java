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
}
