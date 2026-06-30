package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.CreditService.CreditConsumeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR8 (Q1=b) integration regression - pin the cross-service contract
 * {@code consumeFor*(member) → debits OWNER's wallet}. Audit B 2026-05-12
 * MUST-FIX: this was the single highest-value missing test (called out by
 * audits A + B + C unanimously). Until this pinned the contract, only the
 * pure-resolver {@link PayerResolverContractTest} verified the path.
 *
 * <p>What this exercises that the pure-resolver test does NOT:
 * <ol>
 *   <li>CreditService → resolver → ledger save chain (subscription FOR UPDATE
 *       lock on OWNER, ledger row written under OWNER's userId, executor
 *       breadcrumb in description, MEMBER's subscription never touched).</li>
 *   <li>Marketplace + BYOK allow-list: bypass the helper entirely; always
 *       executor-scoped regardless of redirect.</li>
 *   <li>Length-safe executor-suffix append: 500-char base description does
 *       NOT truncate the breadcrumb (round-2 audit B fix).</li>
 *   <li>Resolver throws / returns null → fall back to executor (hot-path
 *       safety, no NPE leak into ledger).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditService - PR8 Q1=b payer redirect integration")
class CreditServicePayerRedirectIntegrationTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private CreditLedgerRepository ledgerRepository;
    @Mock private ModelPricingService pricingService;
    @Mock private OrganizationMemberRepository memberRepository;

    @Captor private ArgumentCaptor<CreditLedgerEntry> ledgerCaptor;

    private static final Long MEMBER_ID = 42L;
    private static final Long OWNER_ID = 7L;
    private static final BigDecimal OWNER_BALANCE = new BigDecimal("100.0000");
    // Member has enough balance to cover ALLOW-LIST paths (marketplace + BYOK)
    // and fall-back paths (resolver throws). Far below OWNER_BALANCE so a test
    // asserting "owner debited" cannot accidentally pass on a member's wallet.
    private static final BigDecimal MEMBER_BALANCE = new BigDecimal("50.0000");

    private CreditService service;
    private PlanResolutionService resolver;
    private User member;
    private User owner;
    private Organization teamOrg;

    @BeforeEach
    void setUp() {
        service = new CreditService(subscriptionRepository, ledgerRepository, pricingService,
                false, true, false);
        resolver = new PlanResolutionService(subscriptionRepository, memberRepository);
        service.setPlanResolutionService(resolver);

        member = new User("m", "m@test.com", AuthProvider.KEYCLOAK, "kc-m");
        member.setId(MEMBER_ID);
        owner = new User("o", "o@test.com", AuthProvider.KEYCLOAK, "kc-o");
        owner.setId(OWNER_ID);
        teamOrg = new Organization("team", "team", false, owner);
        teamOrg.setId(UUID.randomUUID());

        OrganizationMember mem = new OrganizationMember(teamOrg, member, OrganizationRole.MEMBER, true);
        lenient().when(memberRepository.findByUser_IdAndIsDefaultTrue(MEMBER_ID))
                .thenReturn(Optional.of(mem));

        Subscription ownerSub = new Subscription();
        ownerSub.setId(1L);
        ownerSub.setRemainingCredits(OWNER_BALANCE);
        lenient().when(subscriptionRepository.findActiveByUserIdForUpdate(OWNER_ID))
                .thenReturn(Optional.of(ownerSub));
        lenient().when(subscriptionRepository.findActiveByUserId(OWNER_ID))
                .thenReturn(Optional.of(ownerSub));

        Subscription memberSub = new Subscription();
        memberSub.setId(2L);
        memberSub.setRemainingCredits(MEMBER_BALANCE);
        lenient().when(subscriptionRepository.findActiveByUserIdForUpdate(MEMBER_ID))
                .thenReturn(Optional.of(memberSub));
        lenient().when(subscriptionRepository.findActiveByUserId(MEMBER_ID))
                .thenReturn(Optional.of(memberSub));
    }

    // =====================================================================
    // CORE Q1=b INVARIANT - consumeFor* (member) → OWNER's wallet
    // =====================================================================

    @Test
    @DisplayName("consumeForAgent(member) debits OWNER's subscription; MEMBER's wallet untouched; "
            + "ledger.user_id = OWNER; ledger.executor_user_id = MEMBER; description carries breadcrumb")
    void agentConsumeRedirectsToOwner() {
        BigDecimal cost = new BigDecimal("3.5000");
        when(pricingService.calculateCost("openai", "gpt-4", LlmTokenBreakdown.of(100, 50))).thenReturn(cost);

        CreditConsumeResult result = service.consumeForAgent(
                MEMBER_ID, "src-1", "openai", "gpt-4", 100, 50);

        assertThat(result.success()).isTrue();
        // Owner's subscription was locked-and-debited, NOT member's
        verify(subscriptionRepository).findActiveByUserIdForUpdate(OWNER_ID);
        verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(MEMBER_ID);

        // Ledger row attributed to OWNER + carries executor breadcrumb
        verify(ledgerRepository).save(ledgerCaptor.capture());
        CreditLedgerEntry entry = ledgerCaptor.getValue();
        assertThat(entry.getUserId()).as("ledger.user_id must be OWNER, not executor").isEqualTo(OWNER_ID);
        assertThat(entry.getExecutorUserId())
                .as("PR11 - ledger.executor_user_id must carry MEMBER for quota enforcement")
                .isEqualTo(MEMBER_ID);
        assertThat(entry.getAmount()).isEqualByComparingTo(cost.negate());
        assertThat(entry.getDescription())
                .as("description must carry executor breadcrumb for forensic attribution")
                .contains("[executed by user #" + MEMBER_ID + "]");
    }

    @Test
    @DisplayName("consumeForWorkflowNode(member) redirects to OWNER; idempotency keyed on OWNER")
    void workflowNodeConsumeRedirectsToOwner() {
        when(ledgerRepository.existsBySourceId("src-2")).thenReturn(false);

        CreditConsumeResult result = service.consumeForWorkflowNode(MEMBER_ID, "src-2");

        assertThat(result.success()).isTrue();
        verify(subscriptionRepository).findActiveByUserIdForUpdate(OWNER_ID);
        verify(ledgerRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getUserId()).isEqualTo(OWNER_ID);
        assertThat(ledgerCaptor.getValue().getDescription()).contains("[executed by user #" + MEMBER_ID + "]");
    }

    @Test
    @DisplayName("consumeForWebSearch(member) redirects to OWNER")
    void webSearchConsumeRedirectsToOwner() {
        service.consumeForWebSearch(MEMBER_ID, "ws-1");

        verify(subscriptionRepository).findActiveByUserIdForUpdate(OWNER_ID);
        verify(ledgerRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getUserId()).isEqualTo(OWNER_ID);
        assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("WEB_SEARCH");
        assertThat(ledgerCaptor.getValue().getDescription()).contains("[executed by user #" + MEMBER_ID + "]");
    }

    @Test
    @DisplayName("consumeForImageGeneration(member) redirects to OWNER")
    void imageGenConsumeRedirectsToOwner() {
        when(pricingService.hasPricing("openai", "gpt-image-1-low")).thenReturn(true);
        when(pricingService.calculateUnitCost("openai", "gpt-image-1-low", 2))
                .thenReturn(new BigDecimal("4.0000"));

        service.consumeForImageGeneration(MEMBER_ID, "img-1", "openai", "gpt-image-1-low", 2);

        verify(subscriptionRepository).findActiveByUserIdForUpdate(OWNER_ID);
        verify(ledgerRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getUserId()).isEqualTo(OWNER_ID);
        assertThat(ledgerCaptor.getValue().getDescription()).contains("[executed by user #" + MEMBER_ID + "]");
    }

    @Test
    @DisplayName("consumeForChat(member) redirects to OWNER (allowNegative=true post-flight)")
    void chatConsumeRedirectsToOwner() {
        when(pricingService.calculateCost("anthropic", "claude-3", LlmTokenBreakdown.of(100, 50)))
                .thenReturn(new BigDecimal("2.0000"));

        service.consumeForChat(MEMBER_ID, "conv-1", "anthropic", "claude-3", 100, 50);

        verify(subscriptionRepository).findActiveByUserIdForUpdate(OWNER_ID);
        verify(ledgerRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getUserId()).isEqualTo(OWNER_ID);
        assertThat(ledgerCaptor.getValue().getDescription()).contains("[executed by user #" + MEMBER_ID + "]");
    }

    @Test
    @DisplayName("consumePlatformMarkup(member) redirects to OWNER")
    void platformMarkupRedirectsToOwner() {
        service.consumePlatformMarkup(MEMBER_ID, "pm-1", "gmail", new BigDecimal("0.5000"), "run-1");

        verify(subscriptionRepository).findActiveByUserIdForUpdate(OWNER_ID);
        verify(ledgerRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getUserId()).isEqualTo(OWNER_ID);
        assertThat(ledgerCaptor.getValue().getDescription()).contains("[executed by user #" + MEMBER_ID + "]");
    }

    // =====================================================================
    // ALLOW-LIST PATHS - marketplace + BYOK ALWAYS executor-scoped
    // =====================================================================

    @Test
    @DisplayName("consumeForMarketplacePurchase(member) STAYS on member's wallet - allow-listed bypass")
    void marketplacePurchaseStaysOnMember() {
        service.consumeForMarketplacePurchase(MEMBER_ID, "pub-1", 5);

        verify(subscriptionRepository).findActiveByUserIdForUpdate(MEMBER_ID);
        verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(OWNER_ID);

        verify(ledgerRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getUserId()).isEqualTo(MEMBER_ID);
        assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("MARKETPLACE_PURCHASE");
        assertThat(ledgerCaptor.getValue().getDescription())
                .as("marketplace purchases never carry executor breadcrumb - they ARE the executor's action")
                .doesNotContain("[executed by user");
    }

    @Test
    @DisplayName("consumeForImageGenerationByok(member in TEAM workspace) STAYS on member - "
            + "BYOK passthrough, executor IS payer, balance_after reads MEMBER's wallet")
    void imageByokStaysOnMember() {
        // Setup: OWNER has 100 credits, MEMBER has 50, with the resolver wired to
        // redirect MEMBER→OWNER on every other path. BYOK must escape the redirect.
        service.consumeForImageGenerationByok(MEMBER_ID, "byok-1", "openai", "gpt-image-1", 1);

        // BYOK does NOT lock subscription (zero-amount trace only)
        verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(anyLong());
        // BYOK reads MEMBER's wallet (not OWNER's) via getBalanceForSelf
        verify(subscriptionRepository).findActiveByUserId(MEMBER_ID);
        verify(subscriptionRepository, never()).findActiveByUserId(OWNER_ID);

        verify(ledgerRepository).save(ledgerCaptor.capture());
        CreditLedgerEntry entry = ledgerCaptor.getValue();
        assertThat(entry.getUserId())
                .as("BYOK row's user_id must be MEMBER (not OWNER) - escape from owner-pays redirect")
                .isEqualTo(MEMBER_ID);
        assertThat(entry.getExecutorUserId())
                .as("BYOK: executor IS payer - same column value")
                .isEqualTo(MEMBER_ID);
        assertThat(entry.getSourceType()).isEqualTo("IMAGE_GENERATION_BYOK");
        assertThat(entry.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(entry.getBalanceAfter())
                .as("Invariant: balance_after must read MEMBER's wallet (= "
                        + MEMBER_BALANCE + "), NOT OWNER's (= " + OWNER_BALANCE + "). "
                        + "Regression guard for the pre-fix bug where getBalance(userId) "
                        + "resolved payer → owner balance leaked into the executor's row.")
                .isEqualByComparingTo(MEMBER_BALANCE);
        assertThat(entry.getDescription())
                .as("BYOK has no executor breadcrumb - executor IS payer, suffix is a no-op")
                .doesNotContain("[executed by user");
    }

    // =====================================================================
    // LENGTH-SAFE EXECUTOR BREADCRUMB - round-2 audit B fix
    // =====================================================================

    @Test
    @DisplayName("appendExecutorAudit: long base description truncated to leave room for suffix; "
            + "suffix ALWAYS preserved on redirect rows")
    void appendExecutorAuditLengthSafe() {
        String longBase = "a".repeat(600); // exceeds 500-char column cap on its own
        String result = CreditService.appendExecutorAudit(longBase, 999L, 7L);

        assertThat(result.length()).as("result must fit in VARCHAR(500)").isLessThanOrEqualTo(500);
        assertThat(result)
                .as("suffix must survive even when base alone exceeds the cap")
                .endsWith("[executed by user #999]");
    }

    @Test
    @DisplayName("appendExecutorAudit: when executor == payer (no redirect), passthrough - no suffix appended")
    void appendExecutorAuditNoOpWhenSelf() {
        String base = "Workflow node: run:n:0";
        String result = CreditService.appendExecutorAudit(base, 42L, 42L);

        assertThat(result).isEqualTo(base);
    }

    @Test
    @DisplayName("appendExecutorAudit: null base → bare suffix (trimmed)")
    void appendExecutorAuditNullBase() {
        String result = CreditService.appendExecutorAudit(null, 99L, 1L);
        assertThat(result).isEqualTo("[executed by user #99]");
    }

    // =====================================================================
    // FAIL-SAFE - resolver throws / null → fall back to executor
    // =====================================================================

    // =====================================================================
    // PR11 - MemberQuotaService integration with CreditService
    // =====================================================================

    @Test
    @DisplayName("PR11d-a TOCTOU regression - cap check runs AFTER findActiveByUserIdForUpdate "
            + "(pinned via Mockito InOrder so a refactor reordering the calls would fail this test)")
    void capCheckRunsUnderSubscriptionForUpdateLock() {
        MemberQuotaService quotaSvc = org.mockito.Mockito.mock(MemberQuotaService.class);
        when(quotaSvc.checkCreditsCap(eq(MEMBER_ID), any()))
                .thenReturn(MemberQuotaService.CapDecision.allow());
        service.setMemberQuotaService(quotaSvc);
        when(pricingService.calculateCost("openai", "gpt-4", LlmTokenBreakdown.of(100, 50)))
                .thenReturn(new BigDecimal("3.0000"));

        service.consumeForAgent(MEMBER_ID, "src-order", "openai", "gpt-4", 100, 50);

        // The TOCTOU race was that the cap check used to run in a SEPARATE
        // readOnly tx BEFORE deductCredits opened its write tx. PR11d-a moves
        // the cap check INSIDE deductCredits, AFTER findActiveByUserIdForUpdate
        // takes a PESSIMISTIC_WRITE lock on the payer subscription. The lock
        // is the serialization point that makes parallel consumes race-free.
        // We can't unit-test PG's lock semantics directly, but we CAN pin the
        // CALL ORDER that depends on them - if a future refactor reorders
        // these calls, this test fails and surfaces the regression at audit
        // time instead of at prod-incident time.
        org.mockito.InOrder ordered = org.mockito.Mockito.inOrder(subscriptionRepository, quotaSvc);
        ordered.verify(subscriptionRepository).findActiveByUserIdForUpdate(OWNER_ID);
        ordered.verify(quotaSvc).checkCreditsCap(eq(MEMBER_ID), any());
    }

    @Test
    @DisplayName("PR11d-a TOCTOU regression - cap check runs AFTER findActiveByUserIdForUpdate "
            + "even when the cap REFUSES (refusal path also under lock)")
    void capCheckOrderHoldsOnRefusalPath() {
        MemberQuotaService quotaSvc = org.mockito.Mockito.mock(MemberQuotaService.class);
        when(quotaSvc.checkCreditsCap(eq(MEMBER_ID), any()))
                .thenReturn(MemberQuotaService.CapDecision.exceeded("credits",
                        new BigDecimal("99"), new BigDecimal("100")));
        service.setMemberQuotaService(quotaSvc);
        when(pricingService.calculateCost("openai", "gpt-4", LlmTokenBreakdown.of(100, 50)))
                .thenReturn(new BigDecimal("3.0000"));

        service.consumeForAgent(MEMBER_ID, "src-order-refuse", "openai", "gpt-4", 100, 50);

        // Refusal path: lock taken, cap checked (returns refuse), no save.
        // The lock-acquisition-before-cap-check ordering is the bug-shape
        // contract - preserving it on the refusal path closes the symmetric
        // race where a refusal could race with an allow.
        org.mockito.InOrder ordered = org.mockito.Mockito.inOrder(subscriptionRepository, quotaSvc);
        ordered.verify(subscriptionRepository).findActiveByUserIdForUpdate(OWNER_ID);
        ordered.verify(quotaSvc).checkCreditsCap(eq(MEMBER_ID), any());
    }

    @Test
    @DisplayName("PR11 - quota cap exceeded REFUSES the debit (owner sub locked but NOT debited, no ledger row)")
    void quotaCapBlocksDebit() {
        MemberQuotaService quotaSvc = org.mockito.Mockito.mock(MemberQuotaService.class);
        when(quotaSvc.checkCreditsCap(eq(MEMBER_ID), any())).thenReturn(
                MemberQuotaService.CapDecision.exceeded("credits",
                        new BigDecimal("99.0000"), new BigDecimal("100.0000")));
        service.setMemberQuotaService(quotaSvc);
        when(pricingService.calculateCost("openai", "gpt-4", LlmTokenBreakdown.of(100, 50)))
                .thenReturn(new BigDecimal("5.0000"));

        CreditConsumeResult result = service.consumeForAgent(
                MEMBER_ID, "src-capped", "openai", "gpt-4", 100, 50);

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .as("error must carry the QUOTA_CAP_EXCEEDED prefix so the FE can route to "
                        + "an 'ask admin' message instead of 'top up'")
                .startsWith("QUOTA_CAP_EXCEEDED:credits:");
        // PR11d-a: the owner subscription IS locked (FOR UPDATE) before the cap
        // check runs - this is the serialization point that makes cap-vs-debit
        // race-free. The cap then refuses BEFORE any balance change or ledger
        // row is persisted.
        verify(subscriptionRepository).findActiveByUserIdForUpdate(OWNER_ID);
        verify(subscriptionRepository, never()).save(any());
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("PR11 - quota cap allow → consume proceeds as normal redirect path")
    void quotaCapAllowProceedsToRedirect() {
        MemberQuotaService quotaSvc = org.mockito.Mockito.mock(MemberQuotaService.class);
        when(quotaSvc.checkCreditsCap(eq(MEMBER_ID), any()))
                .thenReturn(MemberQuotaService.CapDecision.allow());
        service.setMemberQuotaService(quotaSvc);
        when(pricingService.calculateCost("openai", "gpt-4", LlmTokenBreakdown.of(100, 50)))
                .thenReturn(new BigDecimal("3.5000"));

        CreditConsumeResult result = service.consumeForAgent(
                MEMBER_ID, "src-allow", "openai", "gpt-4", 100, 50);

        assertThat(result.success()).isTrue();
        verify(subscriptionRepository).findActiveByUserIdForUpdate(OWNER_ID);
        verify(ledgerRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getUserId()).isEqualTo(OWNER_ID);
        assertThat(ledgerCaptor.getValue().getExecutorUserId()).isEqualTo(MEMBER_ID);
    }

    @Test
    @DisplayName("PR11 - MemberQuotaService unwired → no cap enforcement, legacy behaviour")
    void quotaServiceUnwiredFallsBackToLegacy() {
        // No setMemberQuotaService called → field stays null → enforceQuotaCap returns null fast
        when(pricingService.calculateCost("openai", "gpt-4", LlmTokenBreakdown.of(100, 50)))
                .thenReturn(new BigDecimal("3.5000"));

        CreditConsumeResult result = service.consumeForAgent(
                MEMBER_ID, "src-unwired", "openai", "gpt-4", 100, 50);

        assertThat(result.success()).isTrue();
        verify(subscriptionRepository).findActiveByUserIdForUpdate(OWNER_ID);
    }

    @Test
    @DisplayName("Resolver throws → fall back to executor (no NPE leak, no ledger row attributed to null)")
    void resolverThrowsFallsBackToExecutor() {
        when(memberRepository.findByUser_IdAndIsDefaultTrue(MEMBER_ID))
                .thenThrow(new RuntimeException("DB hiccup"));
        when(pricingService.calculateCost("openai", "gpt-4", LlmTokenBreakdown.of(1, 1))).thenReturn(BigDecimal.ONE);

        // Must not throw - credit consumption is best-effort fail-safe
        CreditConsumeResult result = service.consumeForAgent(MEMBER_ID, "src-x", "openai", "gpt-4", 1, 1);

        assertThat(result.success()).isTrue();
        // Fallback to executor billing
        verify(subscriptionRepository).findActiveByUserIdForUpdate(MEMBER_ID);
        verify(ledgerRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getUserId()).isEqualTo(MEMBER_ID);
    }
}
