package com.planecrawler.controller;

import com.planecrawler.dto.request.CreateAlertRequest;
import com.planecrawler.dto.response.AlertResponse;
import com.planecrawler.dto.response.ApiMessageResponse;
import com.planecrawler.dto.response.CreateAlertResponse;
import com.planecrawler.exception.AlertNotFoundException;
import com.planecrawler.exception.BadRequestException;
import com.planecrawler.model.PriceAlert;
import com.planecrawler.repository.PriceAlertRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public ResponseEntity<CreateAlertResponse> createAlert(@Valid @RequestBody CreateAlertRequest request) {
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
                request.roundTripTargetPrice(),
                request.userEmail()
        );
        PriceAlert saved = alertRepository.save(alert);
        log.info("Created {} price alert id={} for route {}->{} at target price {}{}",
                saved.getTripType(), saved.getId(), saved.getOrigin(), saved.getDestination(),
                saved.getTargetPrice(),
                saved.getReturnTargetPrice() == null ? "" : " / return target " + saved.getReturnTargetPrice());

        return ResponseEntity.status(HttpStatus.CREATED).body(CreateAlertResponse.from(saved));
    }

    @GetMapping
    public ResponseEntity<List<AlertResponse>> listAlerts(@RequestParam(defaultValue = "active") String status) {
        List<AlertResponse> response = getAlertsByStatus(status).stream()
                .map(AlertResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<ApiMessageResponse> deactivateAlert(@PathVariable Long id) {
        PriceAlert alert = alertRepository.findById(id)
                .orElseThrow(() -> new AlertNotFoundException(id));
        alert.setActive(false);
        alertRepository.save(alert);
        return ResponseEntity.ok(new ApiMessageResponse("Alert deactivated", id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiMessageResponse> deleteAlert(@PathVariable Long id) {
        if (!alertRepository.existsById(id)) {
            throw new AlertNotFoundException(id);
        }
        alertRepository.deleteById(id);
        return ResponseEntity.ok(new ApiMessageResponse("Alert deleted", id));
    }

    private List<PriceAlert> getAlertsByStatus(String status) {
        return switch (status.toLowerCase()) {
            case "active" -> alertRepository.findByActive(true);
            case "inactive" -> alertRepository.findByActive(false);
            case "all" -> alertRepository.findAll();
            default -> throw new BadRequestException(
                    "Invalid status filter: " + status + ". Valid values are: active, inactive, all");
        };
    }

    private static void validateTripTypeSpecificFields(CreateAlertRequest request, PriceAlert.TripType tripType) {
        if (tripType == PriceAlert.TripType.ROUND_TRIP) {
            if (request.returnDate() == null) {
                throw new BadRequestException("returnDate is required for ROUND_TRIP");
            }
            if (request.returnTargetPrice() == null && request.roundTripTargetPrice() == null) {
                throw new BadRequestException("At least one of returnTargetPrice or roundTripTargetPrice is required for ROUND_TRIP");
            }
        }
    }
}
