package com.apimarketplace.orchestrator.services.completion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StepCompletionResult")
class StepCompletionResultTest {

    @Nested
    @DisplayName("persisted()")
    class PersistedFactoryTests {

        @Test
        @DisplayName("Should create result with persisted=true")
        void shouldCreatePersistedResult() {
            Map<String, Object> statusCounts = Map.of("success", 1);
            Map<String, Object> eventData = Map.of("type", "step_executed");

            StepCompletionResult result = StepCompletionResult.persisted(statusCounts, eventData);

            assertTrue(result.persisted());
            assertFalse(result.isDuplicate());
            assertEquals(statusCounts, result.statusCounts());
            assertEquals(eventData, result.eventData());
        }
    }

    @Nested
    @DisplayName("duplicate()")
    class DuplicateFactoryTests {

        @Test
        @DisplayName("Should create result with persisted=false")
        void shouldCreateDuplicateResult() {
            Map<String, Object> statusCounts = Map.of("success", 5);
            Map<String, Object> eventData = Map.of("type", "step_executed");

            StepCompletionResult result = StepCompletionResult.duplicate(statusCounts, eventData);

            assertFalse(result.persisted());
            assertTrue(result.isDuplicate());
            assertEquals(statusCounts, result.statusCounts());
        }
    }

    @Nested
    @DisplayName("isDuplicate()")
    class IsDuplicateTests {

        @Test
        @DisplayName("Should return true when not persisted")
        void shouldReturnTrueWhenNotPersisted() {
            StepCompletionResult result = new StepCompletionResult(false, null, null, false, null);
            assertTrue(result.isDuplicate());
        }

        @Test
        @DisplayName("Should return false when persisted")
        void shouldReturnFalseWhenPersisted() {
            StepCompletionResult result = new StepCompletionResult(true, null, null, false, null);
            assertFalse(result.isDuplicate());
        }
    }

    @Nested
    @DisplayName("persistedPayloadLost()")
    class PayloadLostFactoryTests {

        @Test
        @DisplayName("Payload-lost result is persisted (a FAILED row landed) and carries the loss message")
        void payloadLostIsPersistedAndCarriesMessage() {
            StepCompletionResult result = StepCompletionResult.persistedPayloadLost(
                    Map.of("failed", 1), Map.of("type", "step_executed"),
                    "[storage] Output payload lost: storage write failed after retries");

            assertTrue(result.persisted(), "a payload-lost row DID land (as FAILED)");
            assertTrue(result.payloadLost());
            assertEquals("[storage] Output payload lost: storage write failed after retries",
                    result.payloadLostMessage());
        }

        @Test
        @DisplayName("persisted()/duplicate() factories report payloadLost=false with null message")
        void otherFactoriesReportNoPayloadLoss() {
            assertFalse(StepCompletionResult.persisted(Map.of(), Map.of()).payloadLost());
            assertNull(StepCompletionResult.persisted(Map.of(), Map.of()).payloadLostMessage());
            assertFalse(StepCompletionResult.duplicate(Map.of(), Map.of()).payloadLost());
        }
    }

    @Nested
    @DisplayName("Null fields")
    class NullFieldTests {

        @Test
        @DisplayName("Should allow null statusCounts")
        void shouldAllowNullStatusCounts() {
            StepCompletionResult result = StepCompletionResult.persisted(null, null);
            assertNull(result.statusCounts());
        }

        @Test
        @DisplayName("Should allow null eventData")
        void shouldAllowNullEventData() {
            StepCompletionResult result = StepCompletionResult.persisted(null, null);
            assertNull(result.eventData());
        }
    }
}
