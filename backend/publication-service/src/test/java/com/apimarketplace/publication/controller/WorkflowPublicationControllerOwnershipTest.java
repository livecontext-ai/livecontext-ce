package com.apimarketplace.publication.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The marketplace "already owned" rule ({@code computeOwnedByMe}) - applications are
 * workspace-scoped like every other resource, so ownership is keyed to the caller's
 * ACTIVE workspace, NOT mere membership of the owner org:
 * <ul>
 *   <li>ORG-owned → owned only when {@code owner_id == active organization}.</li>
 *   <li>USER-owned → owned when {@code owner_id == caller}.</li>
 *   <li>legacy (no owner scope) → falls back to {@code publisher_id == caller}.</li>
 *   <li>anonymous (no user id) → never owned.</li>
 * </ul>
 */
@DisplayName("WorkflowPublicationController.computeOwnedByMe")
class WorkflowPublicationControllerOwnershipTest {

    @Test
    @DisplayName("ORG-owned: owned only when owner_id == the caller's ACTIVE org")
    void orgOwnedKeyedToActiveOrg() {
        // Active workspace IS the owner org → owned.
        assertThat(WorkflowPublicationController.computeOwnedByMe("ORG", "org-A", "pub-7", "5", "org-A")).isTrue();
        // Member of the owner org but a DIFFERENT active workspace → NOT owned (must acquire a clone).
        assertThat(WorkflowPublicationController.computeOwnedByMe("ORG", "org-A", "pub-7", "5", "org-B")).isFalse();
        // No active org context → not owned.
        assertThat(WorkflowPublicationController.computeOwnedByMe("ORG", "org-A", "pub-7", "5", null)).isFalse();
        assertThat(WorkflowPublicationController.computeOwnedByMe("ORG", "org-A", "pub-7", "5", "")).isFalse();
    }

    @Test
    @DisplayName("USER-owned: owned when owner_id == caller, and the USER arm does NOT fall back to publisher_id")
    void userOwnedKeyedToCaller() {
        assertThat(WorkflowPublicationController.computeOwnedByMe("USER", "5", "5", "5", "org-A")).isTrue();
        assertThat(WorkflowPublicationController.computeOwnedByMe("USER", "9", "9", "5", "org-A")).isFalse();
        // owner_id mismatch must be false even when publisher_id == caller (no publisher fallback on the USER arm).
        assertThat(WorkflowPublicationController.computeOwnedByMe("USER", "9", "5", "5", "org-A")).isFalse();
    }

    @Test
    @DisplayName("legacy rows without owner scope fall back to publisher_id")
    void legacyFallsBackToPublisher() {
        assertThat(WorkflowPublicationController.computeOwnedByMe(null, null, "5", "5", "org-A")).isTrue();
        assertThat(WorkflowPublicationController.computeOwnedByMe(null, null, "7", "5", "org-A")).isFalse();
        // Blank owner_id is also legacy (parity with isCallerInOwnerScope#hasAssignedOwnerScope).
        assertThat(WorkflowPublicationController.computeOwnedByMe("ORG", "", "5", "5", "org-A")).isTrue();
        assertThat(WorkflowPublicationController.computeOwnedByMe("ORG", "", "7", "5", "org-A")).isFalse();
    }

    @Test
    @DisplayName("an unknown owner_type falls back to publisher_id (defensive default arm)")
    void unknownOwnerTypeFallsBackToPublisher() {
        assertThat(WorkflowPublicationController.computeOwnedByMe("TEAM", "org-A", "5", "5", "org-A")).isTrue();
        assertThat(WorkflowPublicationController.computeOwnedByMe("TEAM", "org-A", "7", "5", "org-A")).isFalse();
    }

    @Test
    @DisplayName("anonymous caller (no user id) never owns anything")
    void anonymousNeverOwns() {
        assertThat(WorkflowPublicationController.computeOwnedByMe("ORG", "org-A", "pub-7", null, "org-A")).isFalse();
        assertThat(WorkflowPublicationController.computeOwnedByMe("USER", "5", "5", "", "org-A")).isFalse();
    }
}
