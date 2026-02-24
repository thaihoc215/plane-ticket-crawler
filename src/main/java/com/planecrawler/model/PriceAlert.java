package com.planecrawler.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "price_alerts")
@Getter
@Setter
@NoArgsConstructor
public class PriceAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String origin;

    @NotBlank
    @Column(nullable = false)
    private String destination;

    @NotNull
    @Positive
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal targetPrice;

    @NotBlank
    @Email
    @Column(nullable = false)
    private String userEmail;

    @Column(precision = 10, scale = 2)
    private BigDecimal lastCheckedPrice;

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
}
