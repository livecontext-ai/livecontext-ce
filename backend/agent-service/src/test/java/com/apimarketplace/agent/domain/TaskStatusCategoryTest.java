package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the canonical category ↔ historical-key mapping the agent state machine
 * relies on. A drift here silently re-routes the worker/reviewer loop, so every
 * value and classification is asserted explicitly.
 */
class TaskStatusCategoryTest {

    @Test
    @DisplayName("each category maps to its historical default status key")
    void defaultStatusKeys() {
        assertEquals("pending", TaskStatusCategory.PENDING.defaultStatusKey());
        assertEquals("in_progress", TaskStatusCategory.IN_PROGRESS.defaultStatusKey());
        assertEquals("in_review", TaskStatusCategory.IN_REVIEW.defaultStatusKey());
        assertEquals("completed", TaskStatusCategory.DONE.defaultStatusKey());
        assertEquals("failed", TaskStatusCategory.FAILED.defaultStatusKey());
        assertEquals("cancelled", TaskStatusCategory.CANCELLED.defaultStatusKey());
        assertEquals("deleted", TaskStatusCategory.DELETED.defaultStatusKey());
    }

    @Test
    @DisplayName("active = pending/in_progress/in_review only")
    void activeClassification() {
        assertTrue(TaskStatusCategory.PENDING.isActive());
        assertTrue(TaskStatusCategory.IN_PROGRESS.isActive());
        assertTrue(TaskStatusCategory.IN_REVIEW.isActive());
        assertFalse(TaskStatusCategory.DONE.isActive());
        assertFalse(TaskStatusCategory.FAILED.isActive());
        assertFalse(TaskStatusCategory.CANCELLED.isActive());
        assertFalse(TaskStatusCategory.DELETED.isActive());
    }

    @Test
    @DisplayName("terminal = done/failed/cancelled only (deleted is NOT terminal)")
    void terminalClassification() {
        assertTrue(TaskStatusCategory.DONE.isTerminal());
        assertTrue(TaskStatusCategory.FAILED.isTerminal());
        assertTrue(TaskStatusCategory.CANCELLED.isTerminal());
        assertFalse(TaskStatusCategory.DELETED.isTerminal());
        assertFalse(TaskStatusCategory.PENDING.isTerminal());
        assertFalse(TaskStatusCategory.IN_PROGRESS.isTerminal());
        assertFalse(TaskStatusCategory.IN_REVIEW.isTerminal());
    }

    @Test
    @DisplayName("fromWire parses case-insensitively and rejects unknown tokens")
    void fromWire() {
        assertEquals(TaskStatusCategory.IN_REVIEW, TaskStatusCategory.fromWire("in_review").orElseThrow());
        assertEquals(TaskStatusCategory.DONE, TaskStatusCategory.fromWire("  DONE ").orElseThrow());
        assertTrue(TaskStatusCategory.fromWire("nope").isEmpty());
        assertTrue(TaskStatusCategory.fromWire(null).isEmpty());
    }

    @Test
    @DisplayName("ofDefaultKey classifies a historical literal, empty for custom keys")
    void ofDefaultKey() {
        assertEquals(TaskStatusCategory.DONE, TaskStatusCategory.ofDefaultKey("completed").orElseThrow());
        assertEquals(TaskStatusCategory.PENDING, TaskStatusCategory.ofDefaultKey("pending").orElseThrow());
        assertTrue(TaskStatusCategory.ofDefaultKey("qa_review").isEmpty());
        assertTrue(TaskStatusCategory.ofDefaultKey(null).isEmpty());
    }
}
