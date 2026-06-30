package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskRecurrenceEntity;
import com.apimarketplace.agent.dto.UpdateRecurrenceRequest;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskRecurrenceRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Update + delete paths of {@link AgentTaskRecurrenceService}, which had ZERO
 * coverage before the 2026-06-23 audit ({@code AgentTaskRecurrenceServiceTest}
 * covers only create / fireOnce / list). Both paths go through the scope-aware
 * finder (needs an active org on the thread) and the creator-only
 * {@code canModify} guard.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskRecurrenceService - update / delete")
class AgentTaskRecurrenceServiceUpdateDeleteTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-1";
    private static final UUID ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock private AgentTaskRecurrenceRepository recurrenceRepository;
    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentRepository agentRepository;

    private AgentTaskRecurrenceService service;

    @BeforeEach
    void setUp() {
        service = new AgentTaskRecurrenceService(recurrenceRepository, taskRepository, agentRepository);
        lenient().when(recurrenceRepository.save(any(AgentTaskRecurrenceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /** A recurrence owned by user-1, with a known cron + a stale nextFireAt. */
    private AgentTaskRecurrenceEntity owned() {
        AgentTaskRecurrenceEntity r = new AgentTaskRecurrenceEntity();
        r.setId(ID);
        r.setTenantId(TENANT);
        r.setOrganizationId(ORG);
        r.setCreatedByUserId("user-1");
        r.setCreatedByAgentId(UUID.randomUUID());
        r.setTitle("Old title");
        r.setInstructions("Old instructions");
        r.setPriority("normal");
        r.setCronExpression("0 * * * * *");
        r.setTimezone("UTC");
        r.setEnabled(true);
        r.setNextFireAt(Instant.now().minusSeconds(3600));
        return r;
    }

    private void inScope(AgentTaskRecurrenceEntity r) {
        when(recurrenceRepository.findByIdAndOrganizationIdStrict(ID, ORG)).thenReturn(Optional.of(r));
    }

    private AgentTaskRecurrenceEntity update(UpdateRecurrenceRequest req, String callingUser) {
        AtomicReference<AgentTaskRecurrenceEntity> out = new AtomicReference<>();
        TenantResolver.runWithOrgScope(ORG, () -> out.set(service.update(TENANT, ID, null, callingUser, req)));
        return out.get();
    }

    // ── update ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("applies only the non-null fields and leaves the rest (and nextFireAt) untouched")
    void updateSelectiveFields() {
        AgentTaskRecurrenceEntity r = owned();
        Instant originalFire = r.getNextFireAt();
        inScope(r);

        AgentTaskRecurrenceEntity result = update(
                new UpdateRecurrenceRequest(false, null, "New title", null, "high"), "user-1");

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getTitle()).isEqualTo("New title");
        assertThat(result.getPriority()).isEqualTo("high");
        assertThat(result.getInstructions()).isEqualTo("Old instructions"); // untouched (null in request)
        assertThat(result.getCronExpression()).isEqualTo("0 * * * * *");     // untouched
        assertThat(result.getNextFireAt()).isEqualTo(originalFire);          // only recomputed on a cron change
        verify(recurrenceRepository).save(r);
    }

    @Test
    @DisplayName("a new cron is parsed, stored, and recomputes nextFireAt into the future")
    void updateRecomputesNextFireOnNewCron() {
        AgentTaskRecurrenceEntity r = owned();
        Instant stale = r.getNextFireAt();
        inScope(r);

        AgentTaskRecurrenceEntity result = update(
                new UpdateRecurrenceRequest(null, "0 0 * * * *", null, null, null), "user-1");

        assertThat(result.getCronExpression()).isEqualTo("0 0 * * * *");
        assertThat(result.getNextFireAt()).isAfter(stale).isAfter(Instant.now().minusSeconds(1));
    }

    @Test
    @DisplayName("a blank cron is a no-op (cron + nextFireAt unchanged)")
    void updateBlankCronIsNoOp() {
        AgentTaskRecurrenceEntity r = owned();
        Instant originalFire = r.getNextFireAt();
        inScope(r);

        AgentTaskRecurrenceEntity result = update(
                new UpdateRecurrenceRequest(null, "   ", null, null, null), "user-1");

        assertThat(result.getCronExpression()).isEqualTo("0 * * * * *");
        assertThat(result.getNextFireAt()).isEqualTo(originalFire);
    }

    @Test
    @DisplayName("a title longer than 500 chars is truncated to 500")
    void updateTitleTruncatedTo500() {
        AgentTaskRecurrenceEntity r = owned();
        inScope(r);

        AgentTaskRecurrenceEntity result = update(
                new UpdateRecurrenceRequest(null, null, "x".repeat(600), null, null), "user-1");

        assertThat(result.getTitle()).hasSize(500);
    }

    @Test
    @DisplayName("a missing recurrence -> IllegalArgumentException, nothing saved")
    void updateNotFound() {
        when(recurrenceRepository.findByIdAndOrganizationIdStrict(ID, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> update(new UpdateRecurrenceRequest(false, null, null, null, null), "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recurrence not found");
        verify(recurrenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("a non-creator caller -> IllegalStateException, nothing saved")
    void updateNotCreator() {
        AgentTaskRecurrenceEntity r = owned(); // owned by user-1
        inScope(r);

        assertThatThrownBy(() -> update(new UpdateRecurrenceRequest(false, null, null, null, null), "intruder"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only the creator may update");
        verify(recurrenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("an invalid cron -> IllegalArgumentException, nothing saved")
    void updateInvalidCron() {
        AgentTaskRecurrenceEntity r = owned();
        inScope(r);

        assertThatThrownBy(() -> update(new UpdateRecurrenceRequest(null, "not a cron", null, null, null), "user-1"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(recurrenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("an invalid priority -> IllegalArgumentException, nothing saved")
    void updateInvalidPriority() {
        AgentTaskRecurrenceEntity r = owned();
        inScope(r);

        assertThatThrownBy(() -> update(new UpdateRecurrenceRequest(null, null, null, null, "bogus"), "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid priority");
        verify(recurrenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("the creating AGENT may update even when the calling user id does not match (canModify agentId branch)")
    void updateByCreatorAgent() {
        AgentTaskRecurrenceEntity r = owned(); // createdByUserId='user-1', createdByAgentId=<random>
        UUID creatorAgent = r.getCreatedByAgentId();
        inScope(r);

        // Call as the creator AGENT with a non-matching user id: the agentId clause of
        // canModify grants access (the userId clause would not).
        AtomicReference<AgentTaskRecurrenceEntity> out = new AtomicReference<>();
        TenantResolver.runWithOrgScope(ORG, () -> out.set(service.update(
                TENANT, ID, creatorAgent, "someone-else",
                new UpdateRecurrenceRequest(false, null, null, null, null))));

        assertThat(out.get().isEnabled()).isFalse();
        verify(recurrenceRepository).save(r);
    }

    // ── delete ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the creator can delete the recurrence")
    void deleteHappy() {
        AgentTaskRecurrenceEntity r = owned();
        inScope(r);

        TenantResolver.runWithOrgScope(ORG, () -> service.delete(TENANT, ID, null, "user-1"));

        verify(recurrenceRepository).delete(r);
    }

    @Test
    @DisplayName("delete of a missing recurrence -> IllegalArgumentException, nothing deleted")
    void deleteNotFound() {
        when(recurrenceRepository.findByIdAndOrganizationIdStrict(ID, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> TenantResolver.runWithOrgScope(ORG,
                () -> service.delete(TENANT, ID, null, "user-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recurrence not found");
        verify(recurrenceRepository, never()).delete(any());
    }

    @Test
    @DisplayName("delete by a non-creator -> IllegalStateException, nothing deleted")
    void deleteNotCreator() {
        AgentTaskRecurrenceEntity r = owned();
        inScope(r);

        assertThatThrownBy(() -> TenantResolver.runWithOrgScope(ORG,
                () -> service.delete(TENANT, ID, null, "intruder")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only the creator may delete");
        verify(recurrenceRepository, never()).delete(any());
    }
}
