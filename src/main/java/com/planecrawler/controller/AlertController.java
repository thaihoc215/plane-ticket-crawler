package com.planecrawler.controller;

import com.planecrawler.model.PriceAlert;
import com.planecrawler.repository.PriceAlertRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private static final Logger log = LoggerFactory.getLogger(AlertController.class);

    private final PriceAlertRepository alertRepository;

    /**
     * Creates a new price alert. The user supplies their travel details,
     * a threshold price, and their email address.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createAlert(@Valid @RequestBody AlertRequest request) {
        PriceAlert alert = new PriceAlert(
                request.origin(),
                request.destination(),
                request.targetPrice(),
                request.userEmail()
        );
        PriceAlert saved = alertRepository.save(alert);
        log.info("Created price alert id={} for route {}->{} at target price {}",
                saved.getId(), saved.getOrigin(), saved.getDestination(), saved.getTargetPrice());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Map.of(
                        "message", "Alert created successfully",
                        "alertId", saved.getId(),
                        "origin", saved.getOrigin(),
                        "destination", saved.getDestination(),
                        "targetPrice", saved.getTargetPrice(),
                        "userEmail", saved.getUserEmail()
                ));
    }

    /**
     * Request body for creating an alert.
     */
    public record AlertRequest(
            @NotBlank String origin,
            @NotBlank String destination,
            @NotNull @Positive BigDecimal targetPrice,
            @NotBlank @Email String userEmail
    ) {}
}
