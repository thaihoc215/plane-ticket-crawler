package com.planecrawler.service;

import com.planecrawler.model.FlightInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlightScraperServiceTest {

    @Test
    void selectCheapestFlights_returnsTopNSortedByPrice() {
        FlightInfo google = new FlightInfo(new BigDecimal("320.00"), "Google", "6h", "SGN", "HAN", "N/A", "N/A");
        FlightInfo vietnamAirlines = new FlightInfo(new BigDecimal("280.00"), "Vietnam Airlines", "5h", "SGN", "HAN", "N/A", "N/A");
        FlightInfo airAsia = new FlightInfo(new BigDecimal("300.00"), "AirAsia", "5h 30m", "SGN", "HAN", "N/A", "N/A");

        List<FlightInfo> cheapest = FlightScraperService.selectCheapestFlights(
                List.of(google, vietnamAirlines, airAsia), 2);

        assertThat(cheapest).hasSize(2);
        assertThat(cheapest.get(0)).isEqualTo(vietnamAirlines);
        assertThat(cheapest.get(1)).isEqualTo(airAsia);
    }

    @Test
    void selectCheapestFlights_withEmptyList_returnsEmpty() {
        List<FlightInfo> result = FlightScraperService.selectCheapestFlights(List.of(), 5);

        assertThat(result).isEmpty();
    }

    @Test
    void selectCheapestFlights_ignoresNullPrices() {
        FlightInfo unknown = new FlightInfo(null, "Unknown", "N/A", "SGN", "HAN", "N/A", "N/A");
        FlightInfo valid = new FlightInfo(new BigDecimal("199.00"), "AirAsia", "5h", "SGN", "HAN", "N/A", "N/A");

        List<FlightInfo> result = FlightScraperService.selectCheapestFlights(List.of(unknown, valid), 5);

        assertThat(result).containsExactly(valid);
    }

    @Test
    void selectCheapestFlights_limitsResults() {
        FlightInfo f1 = new FlightInfo(new BigDecimal("100.00"), "A", "1h", "SGN", "HAN", "N/A", "N/A");
        FlightInfo f2 = new FlightInfo(new BigDecimal("200.00"), "B", "2h", "SGN", "HAN", "N/A", "N/A");
        FlightInfo f3 = new FlightInfo(new BigDecimal("300.00"), "C", "3h", "SGN", "HAN", "N/A", "N/A");

        List<FlightInfo> result = FlightScraperService.selectCheapestFlights(List.of(f3, f1, f2), 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).price()).isEqualTo(new BigDecimal("100.00"));
        assertThat(result.get(1).price()).isEqualTo(new BigDecimal("200.00"));
    }
}
