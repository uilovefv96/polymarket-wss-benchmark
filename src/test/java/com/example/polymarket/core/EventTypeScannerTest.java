package com.example.polymarket.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EventTypeScannerTest {
    @Test
    void scansObjectWithoutParsingJson() {
        assertThat(EventTypeScanner.scan("{\"asset_id\":\"x\",\"event_type\" : \"book\",\"x\":1}"))
                .isEqualTo("book");
        assertThat(EventTypeScanner.scan("{\"x\":1}")).isEqualTo("unknown");
        assertThat(EventTypeScanner.scan("broken \"event_type\":" )).isEqualTo("unknown");
    }

    @Test
    void classifiesPureBookTopLevelArrayAsBook() {
        String payload = "[ {\"event_type\":\"book\",\"bids\":[]}, {\"event_type\":\"book\",\"asks\":[]} ]";
        assertThat(EventTypeScanner.scan(payload)).isEqualTo("book");
    }

    @Test
    void classifiesMixedTopLevelArrayAsMixed() {
        String payload = "[{\"event_type\":\"book\"},{\"event_type\":\"price_change\"}]";
        assertThat(EventTypeScanner.scan(payload)).isEqualTo("mixed");
    }

    @Test
    void objectContentDoesNotConfuseTopLevelElementBoundaries() {
        String payload = "[{\"event_type\":\"book\",\"nested\":{\"x\":1,\"y\":2}},{\"event_type\":\"book\"}]";
        assertThat(EventTypeScanner.scan(payload)).isEqualTo("book");
    }
}
