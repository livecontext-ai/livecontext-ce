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
import com.apimarketplace.auth.web.CreditController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A paid marketplace purchase is charged once per PURCHASE, on the real partial unique index
 * {@code idx_cl_source_id_unique} (real Postgres).
 *
 * <p>Regression context (2026-09-25): the cloud side of a paid acquisition
 * ({@code CeDownloadController}) keyed the {@code MARKETPLACE_PURCHASE} debit on the publication
 * id alone. The ledger's {@code source_id} is unique across EVERY user, so the second buyer of a
 * paid publication hit the index, the consume answered HTTP 500, and the purchase failed: only the
 * first buyer could ever pay. And because that key could not tell a retry from a second purchase,
 * {@code CreditService.replayOf} refused to treat any marketplace purchase as a replay, so a retry
 * of a purchase already paid re-debited and met the same index (500).
 *
 * <p>The key is now {@code marketplace-purchase:<buyer organization>:<publication>}, the pair the
 * publication receipt is unique on. The keys below are spelled literally, not built, so this class
 * pins the format the caller sends (and compiles against the pre-fix code, where it fails).
 */
@SpringBootTest
@DisplayName("Marketplace purchase: one charge per purchase, every buyer billable, a retry idempotent (real Postgres)")
class MarketplacePurchaseLedgerKeyTest extends AuthScratchPostgresSpringTest {

    @Autowired private CreditService creditService;
    @Autowired private CreditController creditController;
    @Autowired private CreditLedgerRepository ledgerRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private BillingCustomerRepository billingCustomerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final String PUBLICATION = "11111111-1111-4111-8111-111111111111";
    private static final BigDecimal WALLET = new BigDecimal("1000");
    private static final int PRICE = 25;

    private User alice;
    private User bob;

    @BeforeEach
    void twoFundedBuyers() {
        ledgerRepository.deleteAll();
        // Absent from the ddl-auto schema (Flyway V3 only), and it is the mechanism under test.
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_cl_source_id_unique "
                + "ON auth.credit_ledger(source_id) WHERE source_id IS NOT NULL");
        subscriptionRepository.deleteAll();
        billingCustomerRepository.deleteAll();
        userRepository.deleteAll();
        planRepository.deleteAll();

        Plan plan = new Plan();
        plan.setCode("PRO");
        plan.setName("Pro");
        plan.setIncludedLlmTokens(1000L);
        plan = planRepository.save(plan);

        alice = fundedUser("alice@test.local", plan);
        bob = fundedUser("bob@test.local", plan);
    }

    @Test
    @DisplayName("two different buyers on their own purchase keys are both charged (the caller-side regression lives in CeDownloadControllerTest)")
    void twoBuyersOfTheSamePublicationAreBothCharged() {
        ResponseEntity<CreditConsumeResult> first = purchaseOverHttp(alice, purchaseKey("org-alice"));
        ResponseEntity<CreditConsumeResult> second = purchaseOverHttp(bob, purchaseKey("org-bob"));

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody().creditsUsed()).isEqualByComparingTo(BigDecimal.valueOf(PRICE));
        assertThat(balanceOf(alice)).isEqualByComparingTo(WALLET.subtract(BigDecimal.valueOf(PRICE)));
        assertThat(balanceOf(bob)).isEqualByComparingTo(WALLET.subtract(BigDecimal.valueOf(PRICE)));
        assertThat(purchaseRows()).extracting(CreditLedgerEntry::getSourceId)
                .containsExactlyInAnyOrder(purchaseKey("org-alice"), purchaseKey("org-bob"));
    }

    @Test
    @DisplayName("the same buyer purchasing for a second workspace is a second purchase: charged again, not a 500")
    void sameBuyerSecondWorkspaceIsASecondPurchase() {
        assertThat(purchaseOverHttp(alice, purchaseKey("org-alice")).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<CreditConsumeResult> second = purchaseOverHttp(alice, purchaseKey("org-alice-team"));

        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody().creditsUsed()).isEqualByComparingTo(BigDecimal.valueOf(PRICE));
        assertThat(balanceOf(alice)).isEqualByComparingTo(WALLET.subtract(BigDecimal.valueOf(2L * PRICE)));
        assertThat(purchaseRows()).hasSize(2);
    }

    @Test
    @DisplayName("regression: a retry of the same purchase is charged once and answered as a success, never a 500")
    void retryOfTheSamePurchaseIsChargedOnce() {
        String key = purchaseKey("org-alice");
        assertThat(purchaseOverHttp(alice, key).getStatusCode().value()).isEqualTo(200);

        // Pre-fix: replayOf excluded every marketplace purchase, so this re-debited, the INSERT
        // met idx_cl_source_id_unique and the consume threw (HTTP 500).
        ResponseEntity<CreditConsumeResult> retry = purchaseOverHttp(alice, key);

        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(retry.getBody().success()).isTrue();
        assertThat(retry.getBody().creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balanceOf(alice)).isEqualByComparingTo(WALLET.subtract(BigDecimal.valueOf(PRICE)));
        assertThat(purchaseRows()).hasSize(1);
    }

    @Test
    @DisplayName("regression: two SIMULTANEOUS acquisitions of one purchase (double click) charge exactly once, both answer success")
    void concurrentAcquisitionsOfOnePurchaseChargeOnce() throws Exception {
        String key = purchaseKey("org-alice");
        CountDownLatch start = new CountDownLatch(1);
        Callable<CreditConsumeResult> purchase = () -> {
            start.await();
            return creditService.consumeForMarketplacePurchase(alice.getId(), key, PRICE);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<CreditConsumeResult>> results = new ArrayList<>();
            results.add(pool.submit(purchase));
            results.add(pool.submit(purchase));
            start.countDown();

            // Serialised on the payer's subscription lock: the second sees the first's committed
            // row and answers it as a replay. Pre-fix it re-debited and hit the unique index.
            List<CreditConsumeResult> outcomes = new ArrayList<>();
            for (Future<CreditConsumeResult> f : results) outcomes.add(f.get(30, TimeUnit.SECONDS));

            assertThat(outcomes).allSatisfy(r -> assertThat(r.success()).isTrue());
            assertThat(outcomes).extracting(CreditConsumeResult::creditsUsed)
                    .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .containsExactlyInAnyOrder(BigDecimal.valueOf(PRICE), BigDecimal.ZERO);
        } finally {
            pool.shutdownNow();
        }
        assertThat(balanceOf(alice)).isEqualByComparingTo(WALLET.subtract(BigDecimal.valueOf(PRICE)));
        assertThat(purchaseRows()).hasSize(1);
    }

    @Test
    @DisplayName("a purchase refused for insufficient credits is billable on the same key after a top-up")
    void refusedPurchaseIsBillableAfterTopUp() {
        Subscription sub = subscriptionOf(alice);
        sub.setRemainingCredits(BigDecimal.ZERO);
        sub.setPaygRemainingCredits(BigDecimal.ZERO);
        subscriptionRepository.save(sub);
        String key = purchaseKey("org-alice");

        assertThat(purchaseOverHttp(alice, key).getStatusCode().value()).isEqualTo(402);
        creditService.grantCredits(alice.getId(), WALLET, "PAYG_TOPUP", "topup-alice", "top-up");
        ResponseEntity<CreditConsumeResult> paid = purchaseOverHttp(alice, key);

        assertThat(paid.getStatusCode().value()).isEqualTo(200);
        assertThat(paid.getBody().creditsUsed()).isEqualByComparingTo(BigDecimal.valueOf(PRICE));
        assertThat(purchaseRows()).extracting(CreditLedgerEntry::getSourceId).containsExactly(key);
    }

    private static String purchaseKey(String buyerOrg) {
        return "marketplace-purchase:" + buyerOrg + ":" + PUBLICATION;
    }

    /** The consume exactly as publication-service sends it (CreditConsumptionClient.consumeFixedCredits). */
    private ResponseEntity<CreditConsumeResult> purchaseOverHttp(User buyer, String key) {
        var request = new CreditController.CreditConsumeRequest(
                "MARKETPLACE_PURCHASE", key, null, null, null, null,
                PRICE, null, null, null, null, null, null);
        return creditController.consume(buyer.getId(), request);
    }

    private List<CreditLedgerEntry> purchaseRows() {
        return ledgerRepository.findAll().stream()
                .filter(e -> "MARKETPLACE_PURCHASE".equals(e.getSourceType()))
                .toList();
    }

    private BigDecimal balanceOf(User user) {
        return creditService.getBalance(user.getId());
    }

    private Subscription subscriptionOf(User user) {
        return subscriptionRepository.findActiveByUserId(user.getId()).orElseThrow();
    }

    private User fundedUser(String email, Plan plan) {
        User user = new User();
        user.setEmail(email);
        user.setUsername(email);
        user = userRepository.save(user);
        BillingCustomer customer = billingCustomerRepository.save(new BillingCustomer(user, "internal"));
        LocalDateTime now = LocalDateTime.now();
        Subscription sub = new Subscription();
        sub.setBillingCustomer(customer);
        sub.setPlan(plan);
        sub.setCadence("monthly");
        sub.setStatus("active");
        sub.setProvider("internal");
        sub.setCurrentPeriodStart(now);
        sub.setCurrentPeriodEnd(now.plusMonths(1));
        sub.setCancelAtPeriodEnd(false);
        sub.setRemainingCredits(BigDecimal.ZERO);
        sub.setPaygRemainingCredits(WALLET);
        sub.setAiRemainingCredits(BigDecimal.ZERO);
        subscriptionRepository.save(sub);
        return user;
    }
}
