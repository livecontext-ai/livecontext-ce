package com.apimarketplace.auth.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BillingEvent Domain Model Tests")
class BillingEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create with default constructor")
        void shouldCreateWithDefaultConstructor() {
            BillingEvent event = new BillingEvent();

            assertThat(event.getId()).isNull();
            assertThat(event.getProvider()).isNull();
            assertThat(event.getEventId()).isNull();
        }

        @Test
        @DisplayName("should create with parameterized constructor")
        void shouldCreateWithParameterizedConstructor() {
            JsonNode payload = objectMapper.createObjectNode().put("key", "value");

            BillingEvent event = new BillingEvent("stripe", "evt_123", "customer.subscription.updated", payload);

            assertThat(event.getProvider()).isEqualTo("stripe");
            assertThat(event.getEventId()).isEqualTo("evt_123");
            assertThat(event.getType()).isEqualTo("customer.subscription.updated");
            assertThat(event.getPayload()).isEqualTo(payload);
        }
    }

    @Nested
    @DisplayName("equals()")
    class EqualsTests {

        @Test
        @DisplayName("should be equal to itself")
        void shouldBeEqualToItself() {
            BillingEvent event = new BillingEvent();
            event.setId(1L);
            event.setEventId("evt_123");

            assertThat(event).isEqualTo(event);
        }

        @Test
        @DisplayName("should be equal when same id and eventId")
        void shouldBeEqualWhenSameIdAndEventId() {
            BillingEvent event1 = new BillingEvent();
            event1.setId(1L);
            event1.setEventId("evt_123");

            BillingEvent event2 = new BillingEvent();
            event2.setId(1L);
            event2.setEventId("evt_123");

            assertThat(event1).isEqualTo(event2);
        }

        @Test
        @DisplayName("should not be equal when different eventId")
        void shouldNotBeEqualWhenDifferentEventId() {
            BillingEvent event1 = new BillingEvent();
            event1.setId(1L);
            event1.setEventId("evt_123");

            BillingEvent event2 = new BillingEvent();
            event2.setId(1L);
            event2.setEventId("evt_456");

            assertThat(event1).isNotEqualTo(event2);
        }

        @Test
        @DisplayName("should not be equal to null")
        void shouldNotBeEqualToNull() {
            BillingEvent event = new BillingEvent();
            event.setId(1L);

            assertThat(event).isNotEqualTo(null);
        }
    }

    @Nested
    @DisplayName("hashCode()")
    class HashCodeTests {

        @Test
        @DisplayName("should have same hashcode for equal objects")
        void shouldHaveSameHashcodeForEqualObjects() {
            BillingEvent event1 = new BillingEvent();
            event1.setId(1L);
            event1.setEventId("evt_123");

            BillingEvent event2 = new BillingEvent();
            event2.setId(1L);
            event2.setEventId("evt_123");

            assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should include relevant fields")
        void shouldIncludeRelevantFields() {
            BillingEvent event = new BillingEvent();
            event.setId(1L);
            event.setProvider("stripe");
            event.setEventId("evt_123");
            event.setType("customer.subscription.updated");

            String result = event.toString();

            assertThat(result).contains("id=1");
            assertThat(result).contains("provider='stripe'");
            assertThat(result).contains("eventId='evt_123'");
            assertThat(result).contains("type='customer.subscription.updated'");
        }
    }
}
