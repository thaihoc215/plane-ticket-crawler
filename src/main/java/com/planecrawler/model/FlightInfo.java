package com.planecrawler.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FlightInfo {

    private BigDecimal price;
    private String airline;
    private String duration;
    private String origin;
    private String destination;
}
