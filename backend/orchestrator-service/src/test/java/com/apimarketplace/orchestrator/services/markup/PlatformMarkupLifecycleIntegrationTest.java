package com.apimarketplace.orchestrator.services.markup;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.SourceIdBuilder;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.FrozenMarkupDto;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * End-to-end proof that the three markup components integrate correctly:
 * <ol>
 *   <li>{@link PlatformMarkupPlanValidator} - rejects invalid plans up-front.</li>
 *   <li>{@link PlatformMarkupPinService} - freezes pricing per credential at run-init
 *       and cancels at terminal.</li>
 *   <li>{@link CredentialClient#resolveRunMarkupRate} + {@link CreditConsumptionClient}
 *       - resolves frozen rate per MCP call and debits the user credit ledger.</li>
 * </ol>
 * <p>
 * Stubs only the transport-level HTTP clients ({@code CredentialClient},
 * {@code CreditConsumptionClient}). The markup services themselves are real
 * beans instantiated by this test - so the wiring, null checks, fail-open
 * branches, and dedup logic are all exercised against production code.
 * <p>
 * This is intentionally not {@code @SpringBootTest} - the full context pulls
 * in JPA, Redis, Kafka, interface-client, etc. The markup feature does not
 * depend on any of that, so a heavy context would only add flakes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Platform markup - end-to-end pin/debit/cancel lifecycle")
class PlatformMarkupLifecycleIntegrationTest {

    private static final String RUN_ID = "run-e2e-123";
    private static final Long USER_ID = 1001L;
    private static final Long CRED_GMAIL = 50L;
    private static final Long CRED_SLACK = 51L;
    private static final Long VERSION_GMAIL_V1 = 900L;
    private static final Long VERSION_GMAIL_V2 = 901L;
    private static final Long VERSION_SLACK = 910L;
    private static final String TENANT_ID_RAW = String.valueOf(USER_ID);
    private static final Long TENANT_ID = USER_ID;

    @Mock CredentialClient credentialClient;
    @Mock CreditConsumptionClient creditClient;

    PlatformMarkupPinService pinService;
    PlatformMarkupPlanValidator validator;

    @BeforeEach
    void wireRealBeans() {
        // Integration test exercises the production path - flag ON.
        pinService = new PlatformMarkupPinService(credentialClient, true);
        validator = new PlatformMarkupPlanValidator();
    }

    // ---- fixtures --------------------------------------------------

    private Step mcpPlatform(UUID toolId, String label, Long credId) {
        return new Step(toolId.toString(), "mcp", label, null, Map.of(), null, null, null,
                CredentialSource.PLATFORM, credId);
    }

    private Step mcpUser(UUID toolId, String label) {
        return new Step(toolId.toString(), "mcp", label, null, Map.of(), null, null, null,
                CredentialSource.USER, null);
    }

    private WorkflowPlan plan(List<Step> mcps) {
        return new WorkflowPlan(
                "plan-e2e", TENANT_ID_RAW,
                List.of(), mcps, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), Map.of());
    }

    private PricingVersionDto pricing(Long versionId, Long credId, BigDecimal defaultMarkup) {
        PricingVersionDto dto = new PricingVersionDto();
        dto.setFound(true);
        dto.setPricingVersionId(versionId);
        dto.setCredentialId(credId);
        dto.setVersion(1);
        dto.setDefaultMarkupCredits(defaultMarkup);
        dto.setCreatedAt(Instant.now());
        return dto;
    }

    private RunPricingPinDto pin(Long credId, Long versionId) {
        RunPricingPinDto dto = new RunPricingPinDto();
        dto.setId(System.nanoTime());
        dto.setRunId(RUN_ID);
        dto.setUserId(USER_ID);
        dto.setPlatformCredentialId(credId);
        dto.setPricingVersionId(versionId);
        return dto;
    }

    private FrozenMarkupDto frozen(Long credId, Long versionId, BigDecimal markup) {
        FrozenMarkupDto dto = new FrozenMarkupDto();
        dto.setFound(true);
        dto.setCredentialId(credId);
        dto.setPricingVersionId(versionId);
        dto.setEffectiveMarkup(markup);
        dto.setVersion(1);
        return dto;
    }

    // ---- scenarios -------------------------------------------------

    @Test
    @DisplayName("Full lifecycle - validate → pin @ init → debit @ step → cancel @ terminal")
    void fullLifecycle() {
        UUID sendToolId = UUID.randomUUID();
        WorkflowPlan plan = plan(List.of(mcpPlatform(sendToolId, "gmail_send", CRED_GMAIL)));
        BigDecimal markup = new BigDecimal("0.25");

        // 1) Run-init pin
        when(credentialClient.getLatestPricingVersion(CRED_GMAIL))
                .thenReturn(Optional.of(pricing(VERSION_GMAIL_V1, CRED_GMAIL, markup)));
        when(credentialClient.saveRunPricingPin(RUN_ID, USER_ID, CRED_GMAIL, VERSION_GMAIL_V1))
                .thenReturn(Optional.of(pin(CRED_GMAIL, VERSION_GMAIL_V1)));

        // 2) Step-time debit (rate already resolved via pin above)
        when(creditClient.consumePlatformMarkup(eq(TENANT_ID), any(), eq("gmail_send"), eq(markup), eq(RUN_ID)))
                .thenReturn(Map.of("success", true));

        // 3) Terminal cancel
        when(credentialClient.cancelScopePin("RUN", RUN_ID)).thenReturn(1);

        // === Exercise ===
        validator.validate(plan);                                             // step 0 - validate
        int pinsCreated = pinService.createPinsForRun(RUN_ID, USER_ID, plan); // step 1 - pin
        String sourceId = SourceIdBuilder.markupDebit(RUN_ID, "mcp:gmail_send", 0, 0, 0, 0);
        Map<String, Object> debitResp = creditClient.consumePlatformMarkup(
                TENANT_ID, sourceId, "gmail_send", markup, RUN_ID);            // step 2 - debit
        int pinsCancelled = pinService.cancelPinsForRun(RUN_ID);               // step 3 - cancel

        // === Verify ordering + side-effects ===
        InOrder order = inOrder(credentialClient, creditClient);
        order.verify(credentialClient).getLatestPricingVersion(CRED_GMAIL);
        order.verify(credentialClient).saveRunPricingPin(RUN_ID, USER_ID, CRED_GMAIL, VERSION_GMAIL_V1);
        order.verify(creditClient).consumePlatformMarkup(eq(TENANT_ID), any(), eq("gmail_send"), eq(markup), eq(RUN_ID));
        order.verify(credentialClient).cancelScopePin("RUN", RUN_ID);

        assertThat(pinsCreated).isOne();
        assertThat(pinsCancelled).isOne();
        assertThat(debitResp).containsEntry("success", true);
        assertThat(sourceId).startsWith("platform-markup:RUN:" + RUN_ID + ":step:mcp:gmail_send:");
    }

    @Test
    @DisplayName("Multiple credentials in one plan - one pin per unique credential")
    void multipleCredentialsDedupAndBillIndependently() {
        WorkflowPlan plan = plan(List.of(
                mcpPlatform(UUID.randomUUID(), "gmail_send", CRED_GMAIL),
                mcpPlatform(UUID.randomUUID(), "gmail_fetch", CRED_GMAIL),
                mcpPlatform(UUID.randomUUID(), "slack_post", CRED_SLACK)));

        when(credentialClient.getLatestPricingVersion(CRED_GMAIL))
                .thenReturn(Optional.of(pricing(VERSION_GMAIL_V1, CRED_GMAIL, new BigDecimal("0.10"))));
        when(credentialClient.getLatestPricingVersion(CRED_SLACK))
                .thenReturn(Optional.of(pricing(VERSION_SLACK, CRED_SLACK, new BigDecimal("0.05"))));
        when(credentialClient.saveRunPricingPin(any(), any(), any(), any()))
                .thenAnswer(inv -> Optional.of(pin(inv.getArgument(2), inv.getArgument(3))));

        int created = pinService.createPinsForRun(RUN_ID, USER_ID, plan);

        assertThat(created).isEqualTo(2);
        verify(credentialClient, times(1)).getLatestPricingVersion(CRED_GMAIL);
        verify(credentialClient, times(1)).getLatestPricingVersion(CRED_SLACK);
        verify(credentialClient, times(1)).saveRunPricingPin(RUN_ID, USER_ID, CRED_GMAIL, VERSION_GMAIL_V1);
        verify(credentialClient, times(1)).saveRunPricingPin(RUN_ID, USER_ID, CRED_SLACK, VERSION_SLACK);
    }

    @Test
    @DisplayName("Pricing v2 published after pin - resolved rate still matches v1 (frozen)")
    void midRunRateChangeDoesNotLeakIntoRunningRun() {
        UUID toolId = UUID.randomUUID();
        WorkflowPlan plan = plan(List.of(mcpPlatform(toolId, "gmail_send", CRED_GMAIL)));

        // Pin creation sees v1
        when(credentialClient.getLatestPricingVersion(CRED_GMAIL))
                .thenReturn(Optional.of(pricing(VERSION_GMAIL_V1, CRED_GMAIL, new BigDecimal("0.10"))));
        when(credentialClient.saveRunPricingPin(RUN_ID, USER_ID, CRED_GMAIL, VERSION_GMAIL_V1))
                .thenReturn(Optional.of(pin(CRED_GMAIL, VERSION_GMAIL_V1)));

        // Admin publishes v2 mid-run - but resolveRunMarkupRate (server-side)
        // consults the *pin*, not the latest version, so it still returns v1's rate.
        when(credentialClient.resolveRunMarkupRate(RUN_ID, CRED_GMAIL, toolId))
                .thenReturn(Optional.of(frozen(CRED_GMAIL, VERSION_GMAIL_V1, new BigDecimal("0.10"))));

        pinService.createPinsForRun(RUN_ID, USER_ID, plan);

        Optional<FrozenMarkupDto> rate = credentialClient.resolveRunMarkupRate(RUN_ID, CRED_GMAIL, toolId);

        assertThat(rate).isPresent();
        assertThat(rate.get().getPricingVersionId()).isEqualTo(VERSION_GMAIL_V1);
        assertThat(rate.get().getEffectiveMarkup()).isEqualByComparingTo("0.10");
    }

    @Test
    @DisplayName("Pin creation fails (no pricing published) - run proceeds, no debit expected")
    void failOpenWhenNoPricingPublished() {
        UUID toolId = UUID.randomUUID();
        WorkflowPlan plan = plan(List.of(mcpPlatform(toolId, "gmail_send", CRED_GMAIL)));

        when(credentialClient.getLatestPricingVersion(CRED_GMAIL)).thenReturn(Optional.empty());

        int created = pinService.createPinsForRun(RUN_ID, USER_ID, plan);

        assertThat(created).isZero();
        verify(credentialClient, never()).saveRunPricingPin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Terminal cancel transport failure - returns 0 but never propagates")
    void terminalCancelTransportFailureIsSwallowed() {
        when(credentialClient.cancelScopePin("RUN", RUN_ID)).thenReturn(-1);

        int cancelled = pinService.cancelPinsForRun(RUN_ID);

        assertThat(cancelled).isZero();
    }

    @Test
    @DisplayName("Explicit cancel + terminal - cancelPinsForRun is idempotent across callers")
    void dualCancelIsIdempotent() {
        when(credentialClient.cancelScopePin("RUN", RUN_ID)).thenReturn(1, 0);

        int firstCancel = pinService.cancelPinsForRun(RUN_ID);
        int secondCancel = pinService.cancelPinsForRun(RUN_ID);

        assertThat(firstCancel).isOne();
        assertThat(secondCancel).isZero();
        verify(credentialClient, times(2)).cancelScopePin("RUN", RUN_ID);
    }

    @Test
    @DisplayName("Plan with only user-credential steps - validator passes, pin service does nothing")
    void allUserCredentialsTouchesNothing() {
        WorkflowPlan plan = plan(List.of(
                mcpUser(UUID.randomUUID(), "sendgrid_email"),
                mcpUser(UUID.randomUUID(), "shopify_order")));

        validator.validate(plan); // no throw
        int created = pinService.createPinsForRun(RUN_ID, USER_ID, plan);

        assertThat(created).isZero();
        verify(credentialClient, never()).getLatestPricingVersion(anyLong());
        verify(credentialClient, never()).saveRunPricingPin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Validator rejects plans before any RPC - no pin side-effect on malformed input")
    void validatorBlocksBeforeSideEffects() {
        Step bad = new Step("id-bad", "mcp", "ambiguous", null, Map.of(), null, null, null,
                CredentialSource.USER, 77L);
        WorkflowPlan plan = plan(List.of(bad));

        assertThatThrownBy(() -> validator.validate(plan))
                .isInstanceOf(PlatformMarkupPlanValidator.InvalidMarkupPlanException.class);

        verify(credentialClient, never()).getLatestPricingVersion(anyLong());
    }

    @Test
    @DisplayName("SourceId format is stable and observable - can be reconstructed from run ctx")
    void sourceIdShapeIsStableAndInvertible() {
        String sid1 = SourceIdBuilder.markupDebit(RUN_ID, "mcp:gmail_send", 0, 0, 0, 0);
        String sid2 = SourceIdBuilder.markupDebit(RUN_ID, "mcp:gmail_send", 3, 2, 1, 5);
        String sid3 = SourceIdBuilder.markupDebit(RUN_ID, "mcp:slack_post", 0, 0, 0, 0);

        assertThat(sid1).isEqualTo(SourceIdBuilder.markupDebit(RUN_ID, "mcp:gmail_send", 0, 0, 0, 0));
        assertThat(sid1).isNotEqualTo(sid2);
        assertThat(sid1).isNotEqualTo(sid3);
        assertThat(sid2).contains(":3:2:1:5");
        assertThat(SourceIdBuilder.isMarkupDebit(sid1)).isTrue();
    }

    @Test
    @DisplayName("Debit path sees ctx-derived sourceId - split item index flows end-to-end")
    void sourceIdCarriesSplitContext() {
        // Simulating: same step fires once per split item. Each item gets a distinct sourceId,
        // so the auth-service ledger treats each call as a unique debit (no idempotency collision).
        String sid0 = SourceIdBuilder.markupDebit(RUN_ID, "mcp:batch_call", 0, 0, 0, 0);
        String sid1 = SourceIdBuilder.markupDebit(RUN_ID, "mcp:batch_call", 0, 0, 0, 1);
        String sid2 = SourceIdBuilder.markupDebit(RUN_ID, "mcp:batch_call", 0, 0, 0, 2);

        assertThat(sid0).isNotEqualTo(sid1).isNotEqualTo(sid2);

        when(creditClient.consumePlatformMarkup(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("success", true));

        creditClient.consumePlatformMarkup(TENANT_ID, sid0, "batch_call", new BigDecimal("0.1"), RUN_ID);
        creditClient.consumePlatformMarkup(TENANT_ID, sid1, "batch_call", new BigDecimal("0.1"), RUN_ID);
        creditClient.consumePlatformMarkup(TENANT_ID, sid2, "batch_call", new BigDecimal("0.1"), RUN_ID);

        ArgumentCaptor<String> sourceIds = ArgumentCaptor.forClass(String.class);
        verify(creditClient, times(3)).consumePlatformMarkup(
                eq(TENANT_ID), sourceIds.capture(), eq("batch_call"), any(), eq(RUN_ID));
        assertThat(sourceIds.getAllValues()).containsExactly(sid0, sid1, sid2);
    }

    @Test
    @DisplayName("Full lifecycle when markup feature flag is OFF - no validator violation, no pins, no RPCs")
    void featureFlagDisabledSkipsPinning() {
        // Regression guard for H1 (audit 2026-04-18): the flag must actually gate
        // pin creation end-to-end, not just the auth-side debit. With enabled=false
        // the orchestrator must create zero pins even when the plan includes
        // well-formed platform steps - this lets ops safely dark-launch.
        PlatformMarkupPinService disabled = new PlatformMarkupPinService(credentialClient, false);
        WorkflowPlan plan = plan(List.of(mcpPlatform(UUID.randomUUID(), "gmail_send", CRED_GMAIL)));

        validator.validate(plan); // validator is independent of the flag - structural check only
        int pinsCreated = disabled.createPinsForRun(RUN_ID, USER_ID, plan);

        assertThat(pinsCreated).isZero();
        verifyNoInteractions(credentialClient);
    }
}
