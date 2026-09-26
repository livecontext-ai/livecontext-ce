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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A refused turn, retried, on a real database.
 *
 * <p>This is the only shape that witnesses the defect. The rejected-consumption audit row is keyed
 * on the turn it refuses (the {@code source_id} itself until 2026-09-25, a deterministic
 * {@code <source_id>:rejected} since), and {@code idx_cl_source_id_unique} is unique on
 * that column across the whole ledger, so the second refusal of the same turn writes a key that
 * already exists. Written inside the consume transaction, as it was, that violation aborted the
 * transaction and flagged it rollback-only; the catch beside the save returned
 * {@code insufficientCredits} and was then overruled by an {@code UnexpectedRollbackException}
 * thrown at the commit, outside the method, where no catch of its own could reach it. Production,
 * 2026-09-17: ten refused turns five minutes apart, ten "Failed to write rejection audit row"
 * warnings, and twenty HTTP 500s for users whose only problem was being out of credits.
 *
 * <p>A mocked ledger cannot express any of that. There is no transaction to abort, no commit to
 * fail, and a stubbed {@code save} that throws produces the same observable result before and
 * after the fix. What has to be exercised is the real proxy, a real transaction and a real unique
 * index, which is what this test does.
 */
@SpringBootTest
@DisplayName("A retried refusal does not become a server error (real Postgres)")
class CreditRejectionAuditIsolationTest extends AuthScratchPostgresSpringTest {

    @Autowired private CreditService creditService;
    @Autowired private CreditLedgerRepository ledgerRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private BillingCustomerRepository billingCustomerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** Enough tokens that the default rates put the turn well beyond an empty wallet. */
    private static final LlmTokenBreakdown USAGE = LlmTokenBreakdown.of(40_000, 4_000);

    private User user;

    @BeforeEach
    void emptyWallet() {
        ledgerRepository.deleteAll();
        // The constraint this whole test is about is NOT in the schema these tests run on.
        // CreditLedgerEntry declares no indexes, and this profile builds the schema from the
        // entities (ddl-auto: create-drop), so idx_cl_source_id_unique, which exists only in the
        // Flyway migration V3 that production runs, is simply absent. Without it a duplicate
        // source_id inserts happily and the test passes while proving nothing, which is exactly
        // what the first version of it did: it asserted one audit row and found two.
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
        user.setEmail("broke@test.local");
        user.setUsername("broke@test.local");
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
    @DisplayName("the same turn refused twice answers insufficient credits both times, and never throws")
    void aRetriedRefusalStillAnswersInsufficientCredits() {
        String sourceId = "exec-retried-on-an-empty-wallet";

        CreditConsumeResult first = creditService.consumeForAgent(
                user.getId(), sourceId, "deepseek", "deepseek-chat", USAGE, "AGENT_EXECUTION");
        assertThat(first.success()).isFalse();
        assertThat(first.error()).contains("Insufficient credits");

        // The retry. Pre-fix this threw UnexpectedRollbackException out of consumeForAgent's
        // commit, and the caller, which had asked a billing question and been refused, got a 500.
        CreditConsumeResult second = creditService.consumeForAgent(
                user.getId(), sourceId, "deepseek", "deepseek-chat", USAGE, "AGENT_EXECUTION");
        assertThat(second.success())
                .as("a refusal is the answer, not an error")
                .isFalse();
        assertThat(second.error()).contains("Insufficient credits");

        // The audit row survives exactly once, which is the intended outcome of losing the
        // duplicate: one refusal stands for that source_id, and the analytics behind these rows
        // count refused turns, not refused attempts.
        List<CreditLedgerEntry> rejected = ledgerRepository.findAll().stream()
                .filter(e -> "AGENT_EXECUTION_REJECTED".equals(e.getSourceType()))
                .toList();
        assertThat(rejected).hasSize(1);
        // Under its own key: the bare one is reserved for the charge a replay writes after a
        // top-up (see RejectedConsumptionReplayTest).
        assertThat(rejected.get(0).getSourceId()).isEqualTo(sourceId + ":rejected");
        assertThat(rejected.get(0).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a third and fourth attempt keep answering, so the audit transaction stays isolated")
    void furtherAttemptsKeepAnswering() {
        String sourceId = "exec-hammered";

        // A retry loop is what produced the production incident, so exercise one rather than a
        // single repeat: every attempt after the first writes a key that already exists.
        assertThatCode(() -> {
            for (int i = 0; i < 4; i++) {
                CreditConsumeResult result = creditService.consumeForAgent(
                        user.getId(), sourceId, "deepseek", "deepseek-chat", USAGE, "AGENT_EXECUTION");
                assertThat(result.success()).isFalse();
            }
        }).doesNotThrowAnyException();

        assertThat(ledgerRepository.findAll()).hasSize(1);
    }
}
