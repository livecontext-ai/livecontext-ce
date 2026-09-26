package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.CreditService;
import com.apimarketplace.auth.service.CreditService.CreditConsumeResult;
import com.apimarketplace.auth.service.LlmTokenBreakdown;
import com.apimarketplace.auth.web.CreditController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A consumption refused for insufficient balance can still be billed once the wallet is topped
 * up, and a replay of a consumption already billed is an idempotent success (real Postgres).
 *
 * <p>Production, 2026-09-25: a COMPACTION_SUMMARY call for user 121 cost 347.6 credits against a
 * balance of 245. auth-service refused it and wrote a {@code COMPACTION_SUMMARY_REJECTED} audit
 * row on the SAME {@code source_id} as the charge, and the consumption went to the dead-letter
 * queue. Every retry then tried to INSERT the charge on that key, hit
 * {@code idx_cl_source_id_unique}, and answered HTTP 500, ten times, until the entry ended FAILED:
 * the LLM work had happened and could never be charged. The same shape made the consume endpoint
 * answer 500 to a plain replay of a turn already charged.
 *
 * <p>Only a real unique index shows this. With a mocked ledger the INSERT always succeeds, which
 * is exactly how {@code legacyRejectedRowDoesNotShortCircuitRealDebit} stayed green for months
 * while production could not do what it asserts.
 *
 * <p>Runs on the CI {@code postgres} service ({@link AuthScratchPostgresSpringTest}), not on
 * Testcontainers: the CI runners have no Docker socket, so a Docker-gated class is skipped there
 * and this proof would be executed by nothing.
 */
@SpringBootTest
@DisplayName("A refused consumption is billable after a top-up, and a replay is idempotent (real Postgres)")
class RejectedConsumptionReplayTest extends AuthScratchPostgresSpringTest {

    @Autowired private CreditService creditService;
    @Autowired private CreditController creditController;
    @Autowired private CreditLedgerRepository ledgerRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private BillingCustomerRepository billingCustomerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** Enough tokens that the default rates put the call well beyond an empty wallet. */
    private static final LlmTokenBreakdown USAGE = LlmTokenBreakdown.of(40_000, 4_000);
    private static final BigDecimal TOP_UP = new BigDecimal("1000000");

    private User user;

    @BeforeEach
    void emptyWallet() {
        ledgerRepository.deleteAll();
        // Absent from the ddl-auto schema (it exists only in Flyway V3), and it is the whole
        // point of this class: without it every assertion below passes on the pre-fix code.
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_cl_source_id_unique "
                + "ON auth.credit_ledger(source_id) WHERE source_id IS NOT NULL");
        subscriptionRepository.deleteAll();
        billingCustomerRepository.deleteAll();
        userRepository.deleteAll();
        planRepository.deleteAll();

        Plan free = new Plan();
        free.setCode("FREE");
        free.setName("Free");
        free.setIncludedLlmTokens(1000L);
        free = planRepository.save(free);

        user = new User();
        user.setEmail("replay@test.local");
        user.setUsername("replay@test.local");
        user = userRepository.save(user);

        BillingCustomer customer = billingCustomerRepository.save(new BillingCustomer(user, "internal"));

        LocalDateTime now = LocalDateTime.now();
        Subscription sub = new Subscription();
        sub.setBillingCustomer(customer);
        sub.setPlan(free);
        sub.setCadence("monthly");
        sub.setStatus("active");
        sub.setProvider("internal");
        sub.setCurrentPeriodStart(now);
        sub.setCurrentPeriodEnd(now.plusMonths(1));
        sub.setCancelAtPeriodEnd(false);
        sub.setRemainingCredits(BigDecimal.ZERO);
        sub.setPaygRemainingCredits(BigDecimal.ZERO);
        sub.setAiRemainingCredits(BigDecimal.ZERO);
        subscriptionRepository.save(sub);
    }

    @Test
    @DisplayName("refused, then topped up, then replayed: charged exactly once, and the refusal stays audited")
    void refusedConsumptionIsChargedOnceAfterTopUp() {
        String sourceId = "exec-compaction-refused";

        CreditConsumeResult refused = compaction(sourceId);
        assertThat(refused.success()).isFalse();
        assertThat(refused.error()).contains("Insufficient credits");

        creditService.grantCredits(user.getId(), TOP_UP, "PAYG_TOPUP", "topup-1", "top-up");

        // The dead-letter replay. Pre-fix: DataIntegrityViolationException on
        // idx_cl_source_id_unique, i.e. HTTP 500, and the charge was never recorded.
        CreditConsumeResult replay = compaction(sourceId);
        assertThat(replay.success()).isTrue();
        assertThat(replay.creditsUsed()).isPositive();

        // Charged once: one debit row on the bare key, and the wallet moved by exactly that row
        // (the stored amount is at the column's scale 4, creditsUsed is the unrounded cost).
        CreditLedgerEntry charge = onlyRow("COMPACTION_SUMMARY");
        assertThat(charge.getSourceId()).isEqualTo(sourceId);
        assertThat(charge.getAmount()).isNegative();
        assertThat(creditService.getBalance(user.getId()))
                .isEqualByComparingTo(TOP_UP.add(charge.getAmount()));

        // The refusal is still on record, under its own key, amount 0.
        CreditLedgerEntry audit = onlyRow("COMPACTION_SUMMARY_REJECTED");
        assertThat(audit.getSourceId()).isEqualTo(sourceId + ":rejected");
        assertThat(audit.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a replay of a consumption already charged is an idempotent success, never a second debit or an error")
    void replayOfAnAlreadyChargedSourceIdIsIdempotent() {
        String sourceId = "exec-charged-once";
        creditService.grantCredits(user.getId(), TOP_UP, "PAYG_TOPUP", "topup-2", "top-up");

        CreditConsumeResult first = compaction(sourceId);
        assertThat(first.success()).isTrue();
        BigDecimal afterFirst = creditService.getBalance(user.getId());

        // Pre-fix: consumeForAgent has no guard of its own, so this re-debited the wallet, then
        // the INSERT hit the unique index and the whole call answered 500.
        CreditConsumeResult replay = compaction(sourceId);
        assertThat(replay.success()).isTrue();
        assertThat(replay.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(creditService.getBalance(user.getId())).isEqualByComparingTo(afterFirst);
        assertThat(ledgerRepository.findAll().stream()
                .filter(e -> "COMPACTION_SUMMARY".equals(e.getSourceType()))).hasSize(1);
    }

    @Test
    @DisplayName("a replay of a charged consumption still succeeds after the wallet has run dry since")
    void replayAfterTheWalletRanDryIsStillASuccess() {
        String sourceId = "exec-charged-then-dry";
        creditService.grantCredits(user.getId(), TOP_UP, "PAYG_TOPUP", "topup-3", "top-up");
        assertThat(compaction(sourceId).success()).isTrue();

        Subscription sub = subscriptionRepository.findAll().get(0);
        sub.setPaygRemainingCredits(BigDecimal.ZERO);
        subscriptionRepository.save(sub);

        // The turn was paid for; being broke now is no reason to refuse its replay, nor to write
        // a rejection row for a charge that exists.
        CreditConsumeResult replay = compaction(sourceId);
        assertThat(replay.success()).isTrue();
        assertThat(ledgerRepository.findAll().stream()
                .filter(e -> e.getSourceType().endsWith("_REJECTED"))).isEmpty();
    }

    @Test
    @DisplayName("a legacy rejection row on the bare key (written before the fix) is moved aside and the charge lands")
    void legacyRejectionRowOnTheBareKeyNoLongerBlocksTheCharge() {
        // The two production dead-letter entries: their audit row already sits on the bare key.
        String sourceId = "d80a9a15-legacy";
        ledgerRepository.save(legacyRejection("COMPACTION_SUMMARY_REJECTED", sourceId));
        creditService.grantCredits(user.getId(), TOP_UP, "PAYG_TOPUP", "topup-4", "top-up");

        CreditConsumeResult replay = compaction(sourceId);

        assertThat(replay.success()).isTrue();
        assertThat(onlyRow("COMPACTION_SUMMARY").getSourceId()).isEqualTo(sourceId);
        assertThat(onlyRow("COMPACTION_SUMMARY_REJECTED").getSourceId()).isEqualTo(sourceId + ":rejected");
    }

    @Test
    @DisplayName("a legacy rejection AND a newer rejection of the same turn: both kept, on distinct keys, and the charge lands")
    void legacyAndNewRejectionBothSurviveTheCharge() {
        String sourceId = "exec-refused-twice-across-the-deploy";
        ledgerRepository.save(legacyRejection("COMPACTION_SUMMARY_REJECTED", sourceId));
        // Refused again after the deploy: the new-shape row takes the canonical rejection key.
        assertThat(compaction(sourceId).success()).isFalse();
        creditService.grantCredits(user.getId(), TOP_UP, "PAYG_TOPUP", "topup-5", "top-up");

        assertThat(compaction(sourceId).success()).isTrue();

        List<String> rejectionKeys = ledgerRepository.findAll().stream()
                .filter(e -> "COMPACTION_SUMMARY_REJECTED".equals(e.getSourceType()))
                .map(CreditLedgerEntry::getSourceId)
                .sorted()
                .toList();
        assertThat(rejectionKeys).containsExactly(
                sourceId + ":legacy:rejected", sourceId + ":rejected");
        assertThat(onlyRow("COMPACTION_SUMMARY").getSourceId()).isEqualTo(sourceId);
    }

    @Test
    @DisplayName("a chat turn with a legacy CHAT_CONVERSATION_REJECTED row is charged, not a 500 (the April dead-letter shape)")
    void legacyChatRejectionNoLongerBlocksTheChatCharge() {
        String conversationId = "conv-refused-in-april";
        ledgerRepository.save(legacyRejection("CHAT_CONVERSATION_REJECTED", conversationId));

        CreditConsumeResult result = creditService.consumeForChat(
                user.getId(), conversationId, "deepseek", "deepseek-chat", USAGE);

        assertThat(result.success()).isTrue();
        assertThat(onlyRow("CHAT_CONVERSATION").getSourceId()).isEqualTo(conversationId);
    }

    @Test
    @DisplayName("a workflow node whose legacy rejection sits on its key is charged, not waved through as already paid")
    void legacyWorkflowNodeRejectionIsNotMistakenForACharge() {
        String sourceId = "run-1:node-a:0:0:0:0";
        ledgerRepository.save(legacyRejection("WORKFLOW_NODE_REJECTED", sourceId));
        creditService.grantCredits(user.getId(), TOP_UP, "PAYG_TOPUP", "topup-6", "top-up");

        CreditConsumeResult result = creditService.consumeForWorkflowNode(user.getId(), sourceId);

        // Pre-fix the existsBySourceId guard saw the refusal and answered "already charged".
        assertThat(result.success()).isTrue();
        assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(onlyRow("WORKFLOW_NODE").getSourceId()).isEqualTo(sourceId);
    }

    @Test
    @DisplayName("a platform markup whose legacy rejection sits on its key is charged, not skipped and not a 500")
    void legacyPlatformMarkupRejectionIsChargedOnReplay() {
        String sourceId = "platform-markup:RUN:run-7:step:s1:n1";
        ledgerRepository.save(legacyRejection("PLATFORM_MARKUP_REJECTED", sourceId));
        creditService.grantCredits(user.getId(), TOP_UP, "PAYG_TOPUP", "topup-7", "top-up");

        CreditConsumeResult result = creditService.consumePlatformMarkup(
                user.getId(), sourceId, "some-tool", new BigDecimal("2"), "run-7");

        assertThat(result.success()).isTrue();
        assertThat(onlyRow("PLATFORM_MARKUP").getAmount()).isEqualByComparingTo(new BigDecimal("-2"));
        assertThat(onlyRow("PLATFORM_MARKUP_REJECTED").getSourceId()).isEqualTo(sourceId + ":rejected");

        // And the replay of that charge is now a no-op.
        assertThat(creditService.consumePlatformMarkup(
                user.getId(), sourceId, "some-tool", new BigDecimal("2"), "run-7").success()).isTrue();
        assertThat(onlyRow("PLATFORM_MARKUP").getAmount()).isEqualByComparingTo(new BigDecimal("-2"));
    }

    /** A consume exactly as it arrives over HTTP, where a keyless caller's key is "". */
    private CreditConsumeResult keylessAgentTurnOverHttp() {
        var request = new CreditController.CreditConsumeRequest(
                "AGENT_EXECUTION", "", "deepseek", "deepseek-chat", 40_000, 4_000,
                null, null, null, null, null, null, null);
        return creditController.consume(user.getId(), request).getBody();
    }

    @Test
    @DisplayName("regression: two KEYLESS charges (the client sends \"\") are both billed, neither is a 500 nor answered as paid")
    void keylessChargesAreEachBilled() {
        creditService.grantCredits(user.getId(), TOP_UP, "PAYG_TOPUP", "topup-keyless", "top-up");

        CreditConsumeResult first = keylessAgentTurnOverHttp();
        // Pre-fix: stored under "", which idx_cl_source_id_unique still covers, so this one hit the
        // index (500) or, through the replay gate, was answered as the first one's replay.
        CreditConsumeResult second = keylessAgentTurnOverHttp();

        assertThat(first.success()).isTrue();
        assertThat(second.success()).isTrue();
        assertThat(second.creditsUsed()).isPositive();
        List<CreditLedgerEntry> charges = ledgerRepository.findAll().stream()
                .filter(e -> "AGENT_EXECUTION".equals(e.getSourceType())).toList();
        assertThat(charges).hasSize(2).allSatisfy(e -> assertThat(e.getSourceId()).isNull());
    }

    @Test
    @DisplayName("regression: two KEYLESS refusals are both audited, not collapsed onto one shared \":rejected\" key")
    void keylessRefusalsAreEachAudited() {
        // Empty wallet: both refused (402, body returned).
        assertThat(keylessAgentTurnOverHttp().success()).isFalse();
        assertThat(keylessAgentTurnOverHttp().success()).isFalse();

        // Pre-fix both audit rows took ":rejected", ONE key for every payer's keyless refusal, so
        // the second was dropped as a duplicate (and every other payer's after it too).
        List<CreditLedgerEntry> refusals = ledgerRepository.findAll().stream()
                .filter(e -> "AGENT_EXECUTION_REJECTED".equals(e.getSourceType())).toList();
        assertThat(refusals).hasSize(2).allSatisfy(e -> assertThat(e.getSourceId()).isNull());
    }

    private CreditConsumeResult compaction(String sourceId) {
        return creditService.consumeForAgent(
                user.getId(), sourceId, "deepseek", "deepseek-chat", USAGE, "COMPACTION_SUMMARY");
    }

    private CreditLedgerEntry onlyRow(String sourceType) {
        List<CreditLedgerEntry> rows = ledgerRepository.findAll().stream()
                .filter(e -> sourceType.equals(e.getSourceType()))
                .toList();
        assertThat(rows).as("rows of type %s", sourceType).hasSize(1);
        return rows.get(0);
    }

    /** A rejection row exactly as the pre-fix code wrote it: amount 0, on the bare key. */
    private CreditLedgerEntry legacyRejection(String sourceType, String sourceId) {
        CreditLedgerEntry row = new CreditLedgerEntry();
        row.setUserId(user.getId());
        row.setExecutorUserId(user.getId());
        row.setAmount(BigDecimal.ZERO);
        row.setBalanceAfter(BigDecimal.ZERO);
        row.setSourceType(sourceType);
        row.setSourceId(sourceId);
        row.setDescription("REJECTED: attempted 347.635246 credits, balance 245.0096");
        return row;
    }
}
