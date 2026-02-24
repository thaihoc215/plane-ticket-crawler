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
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
        PriceAlert.TripType tripType = request.tripType() == null ? PriceAlert.TripType.ONE_WAY : request.tripType();
        validateTripTypeSpecificFields(request, tripType);

        PriceAlert alert = new PriceAlert(
                request.origin(),
                request.destination(),
                tripType,
                request.departureDate(),
                request.returnDate(),
                request.targetPrice(),
                request.returnTargetPrice(),
                request.userEmail()
        );
        PriceAlert saved = alertRepository.save(alert);
        log.info("Created {} price alert id={} for route {}->{} at target price {}{}",
                saved.getTripType(), saved.getId(), saved.getOrigin(), saved.getDestination(),
                saved.getTargetPrice(),
                saved.getReturnTargetPrice() == null ? "" : " / return target " + saved.getReturnTargetPrice());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Alert created successfully");
        response.put("alertId", saved.getId());
        response.put("origin", saved.getOrigin());
        response.put("destination", saved.getDestination());
        response.put("tripType", saved.getTripType());
        response.put("departureDate", saved.getDepartureDate());
        response.put("returnDate", saved.getReturnDate());
        response.put("targetPrice", saved.getTargetPrice());
        response.put("returnTargetPrice", saved.getReturnTargetPrice());
        response.put("userEmail", saved.getUserEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private static void validateTripTypeSpecificFields(AlertRequest request, PriceAlert.TripType tripType) {
        if (request.departureDate() == null) {
            throw new IllegalArgumentException("departureDate is required");
        }
        if (tripType == PriceAlert.TripType.ROUND_TRIP) {
            if (request.returnDate() == null) {
                throw new IllegalArgumentException("returnDate is required for ROUND_TRIP");
            }
            if (request.returnTargetPrice() == null) {
                throw new IllegalArgumentException("returnTargetPrice is required for ROUND_TRIP");
            }
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    /**
     * Request body for creating an alert.
     */
    public record AlertRequest(
            @NotBlank String origin,
            @NotBlank String destination,
            PriceAlert.TripType tripType,
            @NotNull LocalDate departureDate,
            LocalDate returnDate,
            @NotNull @Positive BigDecimal targetPrice,
            @Positive BigDecimal returnTargetPrice,
            @NotBlank @Email String userEmail
    ) {}
}
