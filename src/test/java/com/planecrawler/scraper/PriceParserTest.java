package com.planecrawler.scraper;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceParserTest {

    @Test
    void parse_withCurrencyAndComma_returnsBigDecimal() {
        assertThat(PriceParser.parse("From $1,234")).isEqualByComparingTo(new BigDecimal("1234"));
    }

    @Test
    void parse_withoutNumericValue_throws() {
        assertThatThrownBy(() -> PriceParser.parse("N/A"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to parse price from");
    }
}
