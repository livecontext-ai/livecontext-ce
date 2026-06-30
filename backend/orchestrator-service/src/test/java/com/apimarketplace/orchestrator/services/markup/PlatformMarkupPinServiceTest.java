package com.apimarketplace.orchestrator.services.markup;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.PricingVersionDto;
import com.apimarketplace.credential.client.dto.RunPricingPinDto;
import com.apimarketplace.orchestrator.domain.workflow.CredentialSource;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformMarkupPinService - run-scoped pricing pin lifecycle")
class PlatformMarkupPinServiceTest {

    private static final String RUN_ID = "run-abc";
    private static final Long USER_ID = 42L;
    private static final Long CRED_A = 1001L;
    private static final Long CRED_B = 1002L;
    private static final Long VERSION_A = 700L;
    private static final Long VERSION_B = 701L;

    @Mock CredentialClient credentialClient;

    PlatformMarkupPinService service;

    @BeforeEach
    void setUp() {
        // Default: markup enabled. Flag-off behavior is exercised in featureFlagDisabled().
        service = new PlatformMarkupPinService(credentialClient, true);
    }

    private Step platformStep(String id, Long credId) {
        return new Step(id, "mcp", "step_" + id, null, Map.of(), null, null, null,
                CredentialSource.PLATFORM, credId);
    }

    private Step userStep(String id) {
        return new Step(id, "mcp", "user_" + id, null, Map.of(), null, null, null,
                CredentialSource.USER, null);
    }

    private PricingVersionDto pricingVersion(Long versionId, Long credId, BigDecimal markup) {
        PricingVersionDto dto = new PricingVersionDto();
        dto.setFound(true);
        dto.setPricingVersionId(versionId);
        dto.setCredentialId(credId);
        dto.setVersion(1);
        dto.setDefaultMarkupCredits(markup);
        dto.setCreatedAt(Instant.now());
        return dto;
    }

    private RunPricingPinDto pin(Long credId, Long versionId) {
        RunPricingPinDto dto = new RunPricingPinDto();
        dto.setId(99L);
        dto.setRunId(RUN_ID);
        dto.setUserId(USER_ID);
        dto.setPlatformCredentialId(credId);
        dto.setPricingVersionId(versionId);
        dto.setCancelled(false);
        return dto;
    }

    private WorkflowPlan planWithSteps(List<Step> steps) {
        return new WorkflowPlan(
                "plan-1", "tenant-1",
                List.of(), steps, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), Map.of());
    }

    @Test
    @DisplayName("No platform steps - returns 0 pins and touches no credentialClient")
    void noPlatformStepsCreatesZeroPins() {
        WorkflowPlan plan = planWithSteps(List.of(userStep("s1"), userStep("s2")));

        int created = service.createPinsForRun(RUN_ID, USER_ID, plan);

        assertThat(created).isZero();
        verifyNoInteractions(credentialClient);
    }

    @Test
    @DisplayName("Single platform step - fetches latest pricing and persists exactly one pin")
    void singlePlatformStepCreatesOnePin() {
        WorkflowPlan plan = planWithSteps(List.of(platformStep("s1", CRED_A)));
        when(credentialClient.getLatestPricingVersion(CRED_A))
                .thenReturn(Optional.of(pricingVersion(VERSION_A, CRED_A, new BigDecimal("0.5"))));
        when(credentialClient.saveRunPricingPin(eq(RUN_ID), eq(USER_ID), eq(CRED_A), eq(VERSION_A)))
                .thenReturn(Optional.of(pin(CRED_A, VERSION_A)));

        int created = service.createPinsForRun(RUN_ID, USER_ID, plan);

        assertThat(created).isOne();
        verify(credentialClient).getLatestPricingVersion(CRED_A);
        verify(credentialClient).saveRunPricingPin(RUN_ID, USER_ID, CRED_A, VERSION_A);
    }

    @Test
    @DisplayName("Multiple steps sharing a credential - deduped to one pin per credential")
    void dedupsMultipleStepsSharingCredential() {
        WorkflowPlan plan = planWithSteps(List.of(
                platformStep("s1", CRED_A),
                platformStep("s2", CRED_A),
                platformStep("s3", CRED_A)));
        when(credentialClient.getLatestPricingVersion(CRED_A))
                .thenReturn(Optional.of(pricingVersion(VERSION_A, CRED_A, new BigDecimal("0.1"))));
        when(credentialClient.saveRunPricingPin(any(), any(), any(), any()))
                .thenReturn(Optional.of(pin(CRED_A, VERSION_A)));

        int created = service.createPinsForRun(RUN_ID, USER_ID, plan);

        assertThat(created).isOne();
        verify(credentialClient, times(1)).getLatestPricingVersion(CRED_A);
        verify(credentialClient, times(1)).saveRunPricingPin(RUN_ID, USER_ID, CRED_A, VERSION_A);
    }

    @Test
    @DisplayName("Distinct credentials - one pin per credential")
    void distinctCredentialsEachPinned() {
        WorkflowPlan plan = planWithSteps(List.of(
                platformStep("s1", CRED_A),
                platformStep("s2", CRED_B)));
        when(credentialClient.getLatestPricingVersion(CRED_A))
                .thenReturn(Optional.of(pricingVersion(VERSION_A, CRED_A, new BigDecimal("0.1"))));
        when(credentialClient.getLatestPricingVersion(CRED_B))
                .thenReturn(Optional.of(pricingVersion(VERSION_B, CRED_B, new BigDecimal("0.2"))));
        when(credentialClient.saveRunPricingPin(any(), any(), eq(CRED_A), eq(VERSION_A)))
                .thenReturn(Optional.of(pin(CRED_A, VERSION_A)));
        when(credentialClient.saveRunPricingPin(any(), any(), eq(CRED_B), eq(VERSION_B)))
                .thenReturn(Optional.of(pin(CRED_B, VERSION_B)));

        int created = service.createPinsForRun(RUN_ID, USER_ID, plan);

        assertThat(created).isEqualTo(2);
        ArgumentCaptor<Long> credCaptor = ArgumentCaptor.forClass(Long.class);
        verify(credentialClient, times(2)).getLatestPricingVersion(credCaptor.capture());
        Set<Long> capturedIds = credCaptor.getAllValues().stream().collect(Collectors.toSet());
        assertThat(capturedIds).containsExactlyInAnyOrder(CRED_A, CRED_B);
    }

    @Test
    @DisplayName("Missing pricing version (Optional.empty) - skip pin, fail-open, no save RPC")
    void missingPricingVersionSkipsPin() {
        WorkflowPlan plan = planWithSteps(List.of(platformStep("s1", CRED_A)));
        when(credentialClient.getLatestPricingVersion(CRED_A)).thenReturn(Optional.empty());

        int created = service.createPinsForRun(RUN_ID, USER_ID, plan);

        assertThat(created).isZero();
        verify(credentialClient).getLatestPricingVersion(CRED_A);
        verify(credentialClient, never()).saveRunPricingPin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Save RPC failure (Optional.empty) - pin not counted, fail-open")
    void saveRpcFailureReturnsZero() {
        WorkflowPlan plan = planWithSteps(List.of(platformStep("s1", CRED_A)));
        when(credentialClient.getLatestPricingVersion(CRED_A))
                .thenReturn(Optional.of(pricingVersion(VERSION_A, CRED_A, new BigDecimal("0.1"))));
        when(credentialClient.saveRunPricingPin(any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        int created = service.createPinsForRun(RUN_ID, USER_ID, plan);

        assertThat(created).isZero();
        verify(credentialClient).saveRunPricingPin(RUN_ID, USER_ID, CRED_A, VERSION_A);
    }

    @Test
    @DisplayName("Null / blank runId - returns 0 without touching credentialClient")
    void nullOrBlankRunIdShortCircuits() {
        WorkflowPlan plan = planWithSteps(List.of(platformStep("s1", CRED_A)));

        assertThat(service.createPinsForRun(null, USER_ID, plan)).isZero();
        assertThat(service.createPinsForRun("", USER_ID, plan)).isZero();
        assertThat(service.createPinsForRun("   ", USER_ID, plan)).isZero();
        verifyNoInteractions(credentialClient);
    }

    @Test
    @DisplayName("Null userId or null plan - returns 0 without touching credentialClient")
    void nullUserIdOrPlanShortCircuits() {
        WorkflowPlan plan = planWithSteps(List.of(platformStep("s1", CRED_A)));

        assertThat(service.createPinsForRun(RUN_ID, null, plan)).isZero();
        assertThat(service.createPinsForRun(RUN_ID, USER_ID, null)).isZero();
        verifyNoInteractions(credentialClient);
    }

    @Test
    @DisplayName("Partial failure across credentials - successful ones still counted, others skipped")
    void partialFailureCountsOnlySuccessful() {
        WorkflowPlan plan = planWithSteps(List.of(
                platformStep("s1", CRED_A),
                platformStep("s2", CRED_B)));
        when(credentialClient.getLatestPricingVersion(CRED_A))
                .thenReturn(Optional.of(pricingVersion(VERSION_A, CRED_A, new BigDecimal("0.1"))));
        when(credentialClient.getLatestPricingVersion(CRED_B)).thenReturn(Optional.empty());
        when(credentialClient.saveRunPricingPin(any(), any(), eq(CRED_A), eq(VERSION_A)))
                .thenReturn(Optional.of(pin(CRED_A, VERSION_A)));

        int created = service.createPinsForRun(RUN_ID, USER_ID, plan);

        assertThat(created).isOne();
        verify(credentialClient, never()).saveRunPricingPin(eq(RUN_ID), eq(USER_ID), eq(CRED_B), anyLong());
    }

    @Test
    @DisplayName("cancelPinsForRun - happy path returns transport-reported count")
    void cancelPinsHappyPath() {
        when(credentialClient.cancelScopePin("RUN", RUN_ID)).thenReturn(3);

        int cancelled = service.cancelPinsForRun(RUN_ID);

        assertThat(cancelled).isEqualTo(3);
        verify(credentialClient).cancelScopePin("RUN", RUN_ID);
    }

    @Test
    @DisplayName("cancelPinsForRun - zero-count is a valid response (no pins held), not a failure")
    void cancelPinsZeroCountIsOk() {
        when(credentialClient.cancelScopePin("RUN", RUN_ID)).thenReturn(0);

        int cancelled = service.cancelPinsForRun(RUN_ID);

        assertThat(cancelled).isZero();
        verify(credentialClient).cancelScopePin("RUN", RUN_ID);
    }

    @Test
    @DisplayName("cancelPinsForRun - negative transport signal is swallowed (returns 0, never throws)")
    void cancelPinsTransportFailureReturnsZero() {
        when(credentialClient.cancelScopePin("RUN", RUN_ID)).thenReturn(-1);

        int cancelled = service.cancelPinsForRun(RUN_ID);

        assertThat(cancelled).isZero();
    }

    @Test
    @DisplayName("cancelPinsForRun - null or blank runId short-circuits without RPC")
    void cancelPinsNullOrBlankRunIdShortCircuits() {
        assertThat(service.cancelPinsForRun(null)).isZero();
        assertThat(service.cancelPinsForRun("")).isZero();
        assertThat(service.cancelPinsForRun("   ")).isZero();
        verifyNoInteractions(credentialClient);
    }

    @Test
    @DisplayName("Plan with empty mcps list - returns 0, no RPCs")
    void emptyMcpsListReturnsZero() {
        WorkflowPlan plan = planWithSteps(List.of());

        int created = service.createPinsForRun(RUN_ID, USER_ID, plan);

        assertThat(created).isZero();
        verifyNoInteractions(credentialClient);
    }

    @Test
    @DisplayName("Plan with null mcps list - returns 0, no RPCs")
    void nullMcpsListReturnsZero() {
        WorkflowPlan plan = new WorkflowPlan(
                "plan-1", "tenant-1",
                List.of(), null, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), Map.of());

        int created = service.createPinsForRun(RUN_ID, USER_ID, plan);

        assertThat(created).isZero();
        verifyNoInteractions(credentialClient);
    }

    @Test
    @DisplayName("Feature flag disabled - no RPCs, no pins, even when plan has platform-sourced MCP steps")
    void featureFlagDisabledSkipsAllPinning() {
        // Regression guard for H1 (audit 2026-04-18): flag existed but was
        // never consulted, so dark-launch was impossible. Now a disabled deploy
        // creates zero pin rows regardless of plan content.
        PlatformMarkupPinService disabled = new PlatformMarkupPinService(credentialClient, false);
        WorkflowPlan plan = planWithSteps(List.of(
                platformStep("s1", CRED_A),
                platformStep("s2", CRED_B)));

        int created = disabled.createPinsForRun(RUN_ID, USER_ID, plan);

        assertThat(created).isZero();
        verifyNoInteractions(credentialClient);
    }
}
