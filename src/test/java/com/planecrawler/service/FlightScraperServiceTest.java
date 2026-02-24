package com.planecrawler.service;

import com.planecrawler.model.FlightInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlightScraperServiceTest {

    @Test
    void selectBestPrice_returnsLowestPriceFlight() {
        FlightInfo google = new FlightInfo(new BigDecimal("320.00"), "Google", "6h", "SGN", "HAN");
        FlightInfo vietnamAirlines = new FlightInfo(new BigDecimal("280.00"), "Vietnam Airlines", "5h", "SGN", "HAN");
        FlightInfo airAsia = new FlightInfo(new BigDecimal("300.00"), "AirAsia", "5h 30m", "SGN", "HAN");

        FlightInfo best = FlightScraperService.selectBestPrice(List.of(google, vietnamAirlines, airAsia));

        assertThat(best).isEqualTo(vietnamAirlines);
    }

    @Test
    void selectBestPrice_withEmptyList_throwsException() {
        assertThatThrownBy(() -> FlightScraperService.selectBestPrice(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No flight results available");
    }

    @Test
    void selectBestPrice_ignoresNullPrices() {
        FlightInfo unknown = new FlightInfo(null, "Unknown", "N/A", "SGN", "HAN");
        FlightInfo valid = new FlightInfo(new BigDecimal("199.00"), "AirAsia", "5h", "SGN", "HAN");

        FlightInfo best = FlightScraperService.selectBestPrice(List.of(unknown, valid));

        assertThat(best).isEqualTo(valid);
    }
}
