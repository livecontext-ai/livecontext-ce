package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SplitStep")
class SplitStepTest {

    @Test
    @DisplayName("Should be valid with non-null non-blank stepId")
    void shouldBeValidWithStepId() {
        SplitStep step = new SplitStep("mcp:step1", "mcp:step1");
        assertTrue(step.isValid());
    }

    @Test
    @DisplayName("Should be invalid with null stepId")
    void shouldBeInvalidWithNullStepId() {
        SplitStep step = new SplitStep(null, "target");
        assertFalse(step.isValid());
    }

    @Test
    @DisplayName("Should be invalid with blank stepId")
    void shouldBeInvalidWithBlankStepId() {
        SplitStep step = new SplitStep("  ", "target");
        assertFalse(step.isValid());
    }

    @Test
    @DisplayName("Should store fields correctly")
    void shouldStoreFieldsCorrectly() {
        SplitStep step = new SplitStep("mcp:step1", "mcp:next");
        assertEquals("mcp:step1", step.stepId());
        assertEquals("mcp:next", step.to());
    }
}
