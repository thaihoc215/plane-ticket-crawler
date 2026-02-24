package com.planecrawler.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "price_alerts")
@Getter
@Setter
@NoArgsConstructor
public class PriceAlert {

    public enum TripType {
        ONE_WAY,
        ROUND_TRIP
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String origin;

    @Column(nullable = false)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripType tripType = TripType.ONE_WAY;

    @Column(nullable = false)
    private LocalDate departureDate;

    @Column
    private LocalDate returnDate;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal targetPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal returnTargetPrice;

    @Column(nullable = false)
    private String userEmail;

    @Column(precision = 10, scale = 2)
    private BigDecimal lastCheckedPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal lastCheckedReturnPrice;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column
    private LocalDateTime lastCheckedAt;

    public PriceAlert(String origin, String destination, BigDecimal targetPrice, String userEmail) {
        this.origin = origin;
        this.destination = destination;
        this.targetPrice = targetPrice;
        this.userEmail = userEmail;
    }

    public PriceAlert(String origin, String destination, TripType tripType, LocalDate departureDate, LocalDate returnDate,
                      BigDecimal targetPrice, BigDecimal returnTargetPrice, String userEmail) {
        this.origin = origin;
        this.destination = destination;
        this.tripType = tripType;
        this.departureDate = departureDate;
        this.returnDate = returnDate;
        this.targetPrice = targetPrice;
        this.returnTargetPrice = returnTargetPrice;
        this.userEmail = userEmail;
    }
}
