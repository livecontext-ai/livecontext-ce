package com.apimarketplace.orchestrator.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DAGValidationException.
 */
@DisplayName("DAGValidationException")
class DAGValidationExceptionTest {

    @Nested
    @DisplayName("constructor with trigger IDs and shared nodes")
    class ConstructorWithDetailsTests {

        @Test
        @DisplayName("Should build message with trigger IDs and shared nodes")
        void shouldBuildMessageWithDetails() {
            Set<String> sharedNodes = Set.of("mcp:step1", "mcp:step2");

            DAGValidationException exception = new DAGValidationException(
                "trigger:webhook_1", "trigger:webhook_2", sharedNodes
            );

            assertThat(exception.getMessage()).contains("trigger:webhook_1");
            assertThat(exception.getMessage()).contains("trigger:webhook_2");
            assertThat(exception.getMessage()).contains("2 node(s)");
            assertThat(exception.getMessage()).contains("independent DAG");
        }

        @Test
        @DisplayName("Should expose trigger1Id")
        void shouldExposeTrigger1Id() {
            DAGValidationException exception = new DAGValidationException(
                "trigger:a", "trigger:b", Set.of("node1")
            );

            assertThat(exception.getTrigger1Id()).isEqualTo("trigger:a");
        }

        @Test
        @DisplayName("Should expose trigger2Id")
        void shouldExposeTrigger2Id() {
            DAGValidationException exception = new DAGValidationException(
                "trigger:a", "trigger:b", Set.of("node1")
            );

            assertThat(exception.getTrigger2Id()).isEqualTo("trigger:b");
        }

        @Test
        @DisplayName("Should expose shared nodes")
        void shouldExposeSharedNodes() {
            Set<String> sharedNodes = Set.of("mcp:shared_step");

            DAGValidationException exception = new DAGValidationException(
                "trigger:a", "trigger:b", sharedNodes
            );

            assertThat(exception.getSharedNodes()).containsExactly("mcp:shared_step");
        }
    }

    @Nested
    @DisplayName("constructor with message only")
    class ConstructorWithMessageTests {

        @Test
        @DisplayName("Should set message and null trigger IDs")
        void shouldSetMessageAndNullIds() {
            DAGValidationException exception = new DAGValidationException("Custom error message");

            assertThat(exception.getMessage()).isEqualTo("Custom error message");
            assertThat(exception.getTrigger1Id()).isNull();
            assertThat(exception.getTrigger2Id()).isNull();
            assertThat(exception.getSharedNodes()).isEmpty();
        }
    }

    @Test
    @DisplayName("Should be a RuntimeException")
    void shouldBeRuntimeException() {
        DAGValidationException exception = new DAGValidationException("test");
        assertThat(exception).isInstanceOf(RuntimeException.class);
    }
}
