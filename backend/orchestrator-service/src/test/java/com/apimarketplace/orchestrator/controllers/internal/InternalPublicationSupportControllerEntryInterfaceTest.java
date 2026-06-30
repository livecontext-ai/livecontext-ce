package com.apimarketplace.orchestrator.controllers.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InternalPublicationSupportController#entryInterfaceIdFromPlan} - the lean
 * entry-interface extraction added to the acquired-workflows payload so a cloud-acquired app's
 * My-Purchases card can fall back to rendering the acquirer's LOCAL clone interface (A1).
 */
@DisplayName("InternalPublicationSupportController.entryInterfaceIdFromPlan")
class InternalPublicationSupportControllerEntryInterfaceTest {

    private static Map<String, Object> iface(String id, boolean entry) {
        return Map.of("id", id, "label", id, "isEntryInterface", entry);
    }

    private static Map<String, Object> plan(List<Map<String, Object>> interfaces) {
        return Map.of("interfaces", interfaces);
    }

    @Test
    @DisplayName("Picks the interface flagged isEntryInterface, not just the first")
    void picksEntryFlagged() {
        Map<String, Object> p = plan(List.of(iface("a", false), iface("entry", true), iface("c", false)));
        assertThat(InternalPublicationSupportController.entryInterfaceIdFromPlan(p)).isEqualTo("entry");
    }

    @Test
    @DisplayName("Falls back to the first interface when none is flagged as entry")
    void fallsBackToFirst() {
        Map<String, Object> p = plan(List.of(iface("first", false), iface("second", false)));
        assertThat(InternalPublicationSupportController.entryInterfaceIdFromPlan(p)).isEqualTo("first");
    }

    @Test
    @DisplayName("Null when the plan is null, has no interfaces, or the entry interface has no id")
    void nullCases() {
        assertThat(InternalPublicationSupportController.entryInterfaceIdFromPlan(null)).isNull();
        assertThat(InternalPublicationSupportController.entryInterfaceIdFromPlan(plan(List.of()))).isNull();
        assertThat(InternalPublicationSupportController.entryInterfaceIdFromPlan(
                plan(List.of(Map.of("label", "no-id", "isEntryInterface", true))))).isNull();
    }
}
