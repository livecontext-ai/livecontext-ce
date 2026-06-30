package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConsumerTracker")
class ConsumerTrackerTest {

    private ConsumerTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ConsumerTracker();
    }

    @Nested
    @DisplayName("registerTemplateDependency()")
    class RegisterTemplateDependencyTests {

        @Test
        @DisplayName("Should register template dependency")
        void shouldRegisterDependency() {
            tracker.registerTemplateDependency("mcp:step1", "mcp:step2");

            assertTrue(tracker.hasPendingConsumers("mcp:step1"));
        }

        @Test
        @DisplayName("Should ignore null source step ID")
        void shouldIgnoreNullSource() {
            tracker.registerTemplateDependency(null, "mcp:step2");
            // No exception thrown
        }

        @Test
        @DisplayName("Should ignore null consumer step ID")
        void shouldIgnoreNullConsumer() {
            tracker.registerTemplateDependency("mcp:step1", null);
            // No exception thrown
        }

        @Test
        @DisplayName("Should ignore self-reference")
        void shouldIgnoreSelfReference() {
            tracker.registerTemplateDependency("mcp:step1", "mcp:step1");
            assertFalse(tracker.hasPendingConsumers("mcp:step1"));
        }
    }

    @Nested
    @DisplayName("hasPendingConsumers()")
    class HasPendingConsumersTests {

        @Test
        @DisplayName("Should return false when no consumers registered")
        void shouldReturnFalseWhenEmpty() {
            assertFalse(tracker.hasPendingConsumers("mcp:step1"));
        }

        @Test
        @DisplayName("Should return true after registering consumer")
        void shouldReturnTrueAfterRegistering() {
            tracker.registerTemplateDependency("mcp:source", "mcp:consumer");
            assertTrue(tracker.hasPendingConsumers("mcp:source"));
        }

        @Test
        @DisplayName("Should return false after removing consumer")
        void shouldReturnFalseAfterRemoving() {
            tracker.registerTemplateDependency("mcp:source", "mcp:consumer");
            tracker.releaseConsumer("mcp:source", "mcp:consumer");
            assertFalse(tracker.hasPendingConsumers("mcp:source"));
        }
    }

    @Nested
    @DisplayName("releaseConsumer()")
    class ReleaseConsumerTests {

        @Test
        @DisplayName("Should release specific consumer from source")
        void shouldReleaseSpecificConsumer() {
            tracker.registerTemplateDependency("mcp:source", "mcp:consumer1");
            tracker.registerTemplateDependency("mcp:source", "mcp:consumer2");

            tracker.releaseConsumer("mcp:source", "mcp:consumer1");

            assertTrue(tracker.hasPendingConsumers("mcp:source"));
        }

        @Test
        @DisplayName("Should handle null source")
        void shouldHandleNullSource() {
            assertDoesNotThrow(() -> tracker.releaseConsumer(null, "mcp:consumer"));
        }

        @Test
        @DisplayName("Should handle null consumer")
        void shouldHandleNullConsumer() {
            assertDoesNotThrow(() -> tracker.releaseConsumer("mcp:source", null));
        }

        @Test
        @DisplayName("Should handle non-existent source")
        void shouldHandleNonExistentSource() {
            assertDoesNotThrow(() -> tracker.releaseConsumer("mcp:nonexistent", "mcp:consumer"));
        }
    }

    @Nested
    @DisplayName("removeConsumerTracking()")
    class RemoveConsumerTrackingTests {

        @Test
        @DisplayName("Should remove all tracking for a step")
        void shouldRemoveAllTracking() {
            tracker.registerTemplateDependency("mcp:source", "mcp:consumer1");
            tracker.registerTemplateDependency("mcp:source", "mcp:consumer2");

            tracker.removeConsumerTracking("mcp:source");

            assertFalse(tracker.hasPendingConsumers("mcp:source"));
        }

        @Test
        @DisplayName("Should handle non-existent step")
        void shouldHandleNonExistent() {
            assertDoesNotThrow(() -> tracker.removeConsumerTracking("mcp:nonexistent"));
        }
    }

    @Nested
    @DisplayName("clear()")
    class ClearTests {

        @Test
        @DisplayName("Should clear all tracking data")
        void shouldClearAll() {
            tracker.registerTemplateDependency("mcp:source1", "mcp:consumer1");
            tracker.registerTemplateDependency("mcp:source2", "mcp:consumer2");

            tracker.clear();

            assertFalse(tracker.hasPendingConsumers("mcp:source1"));
            assertFalse(tracker.hasPendingConsumers("mcp:source2"));
        }
    }

    @Nested
    @DisplayName("Multiple consumers")
    class MultipleConsumersTests {

        @Test
        @DisplayName("Should track multiple consumers for same source")
        void shouldTrackMultiple() {
            tracker.registerTemplateDependency("mcp:source", "mcp:consumer1");
            tracker.registerTemplateDependency("mcp:source", "mcp:consumer2");
            tracker.registerTemplateDependency("mcp:source", "mcp:consumer3");

            assertTrue(tracker.hasPendingConsumers("mcp:source"));

            tracker.releaseConsumer("mcp:source", "mcp:consumer1");
            assertTrue(tracker.hasPendingConsumers("mcp:source"));

            tracker.releaseConsumer("mcp:source", "mcp:consumer2");
            assertTrue(tracker.hasPendingConsumers("mcp:source"));

            tracker.releaseConsumer("mcp:source", "mcp:consumer3");
            assertFalse(tracker.hasPendingConsumers("mcp:source"));
        }

        @Test
        @DisplayName("Should track multiple sources for same consumer")
        void shouldTrackMultipleSources() {
            tracker.registerTemplateDependency("mcp:source1", "mcp:consumer");
            tracker.registerTemplateDependency("mcp:source2", "mcp:consumer");

            assertTrue(tracker.hasPendingConsumers("mcp:source1"));
            assertTrue(tracker.hasPendingConsumers("mcp:source2"));
        }
    }
}
