package com.apimarketplace.auth.service;

import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tells the person who pays that their credits are running low, then that they
 * ran out: at most ONE alert per level (LOW, EXHAUSTED) per credit cycle.
 *
 * <p><b>A scan, not a hook in the debit path.</b> Credits are debited by a dozen
 * paths (agent turns, workflow nodes, generations, reservations settling later),
 * and a threshold check threaded through each would be one more thing every new
 * path has to remember. A scan every few minutes sees the balance they all
 * produce, and cannot slow a single debit down.
 *
 * <p><b>Only accounts that SPENT credits (a debit) in the last 7 days.</b> The first scan
 * after a deploy would otherwise alert every dormant account that happens to sit
 * under the threshold, all at once, about a balance nobody is spending.
 *
 * <p>The cycle key is the subscription's period start plus its monthly credit
 * cycle index, so a yearly plan (re-granted monthly, V498) alerts once per month,
 * not once per year. Never runs with unlimited credits (self-hosted default).
 */
@Component
public class CreditAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(CreditAlertScheduler.class);

    static final String LEVEL_LOW = "LOW";
    static final String LEVEL_EXHAUSTED = "EXHAUSTED";
    private static final int BATCH = 500;

    /**
     * A subscription that spent recently, with what is needed to decide its level: the balance,
     * what its cycle granted, and which levels were already alerted this cycle. The payer's
     * personal workspace comes with it, because that is where the alert lands.
     */
    record Candidate(long subscriptionId, long userId, BigDecimal balance, String cycleKey, String organizationId,
                     long cycleGrant, boolean lowSent, boolean exhaustedSent) {

        /**
         * The level to alert now, or null: EXHAUSTED below {@link CreditService#MIN_USABLE_BALANCE},
         * the floor at which workflows stop (a balance of 0.44 refuses every node, and a refused
         * debit never takes it to zero), LOW under {@code ratio} of the grant.
         *
         * <p>Known limits, all outside this bar:
         * <ul>
         *   <li>The balance read here is the TOTAL (monthly + PAYG), while on the FREE plan a chat
         *       turn on a model not open to the free tier reads the PAYG bucket alone, so a Free
         *       account can be refused those turns before this fires.</li>
         *   <li>The AI allowance a free-tier turn may also draw is not counted. V512 set
         *       {@code plan.included_ai_credits} to NULL on FREE; if a plan grants one again,
         *       EXHAUSTED can fire while those turns still run.</li>
         *   <li>A turn on a cheap model that costs under one credit can still run after this fired.</li>
         *   <li>The orchestrator's per-run budget mirror reserves 2 credits for an agent node, so
         *       between 1 and 2 credits a workflow can stop when it reaches an agent node, before
         *       this fires.</li>
         *   <li>EXHAUSTED is sent once per cycle. A brief dip under one credit that the person
         *       then tops up uses it: running out again later in the same cycle sends nothing,
         *       and LOW is not sent either for the rest of the cycle. Re-arming it on a top-up is a
         *       follow-up.</li>
         *   <li>Only accounts with a real debit in the last 7 days are scanned, and a refused debit
         *       writes an amount-0 row, not a debit. An account that runs out is caught by the next
         *       scan (15 minutes), but one left under the floor for more than 7 days after its last
         *       successful debit is no longer a candidate.</li>
         * </ul>
         * EXHAUSTED is checked first, so on a grant whose LOW threshold is itself under one credit
         * (a grant under 5 at the default 0.2 ratio) LOW is never sent, only EXHAUSTED.
         */
        String levelToAlert(BigDecimal ratio) {
            if (exhaustedSent) return null;
            if (balance.compareTo(CreditService.MIN_USABLE_BALANCE) < 0) return LEVEL_EXHAUSTED;
            if (lowSent || cycleGrant <= 0) return null;
            return balance.compareTo(BigDecimal.valueOf(cycleGrant).multiply(ratio)) <= 0 ? LEVEL_LOW : null;
        }
    }

    private final JdbcTemplate jdbc;
    private final boolean unlimited;
    private final boolean enabled;
    private final BigDecimal lowRatio;

    @Autowired(required = false)
    private NotificationClient notificationClient;

    /** Product analytics (PostHog). Optional: a null field emits nothing. */
    @Autowired(required = false)
    private com.apimarketplace.auth.analytics.AuthAnalyticsEmitter analytics;

    public CreditAlertScheduler(JdbcTemplate jdbc,
                                @Value("${credit.unlimited:false}") boolean unlimited,
                                @Value("${notifications.credit-alerts.enabled:true}") boolean enabled,
                                @Value("${notifications.credit-alerts.low-ratio:0.2}") BigDecimal lowRatio) {
        this.jdbc = jdbc;
        this.unlimited = unlimited;
        this.enabled = enabled;
        this.lowRatio = lowRatio;
    }

    @Scheduled(initialDelayString = "${notifications.credit-alerts.initial-delay-ms:120000}",
            fixedDelayString = "${notifications.credit-alerts.interval-ms:900000}")
    @SchedulerLock(name = "credit-alert-scan", lockAtMostFor = "PT10M")
    public void scan() {
        if (unlimited || !enabled || notificationClient == null) return;
        try {
            // Keyset paging over EVERY recently-spending subscription: the level is decided in Java
            // (see CreditAttributionService.cycleGrantCredits), so a page may alert nobody, and a fixed first page would starve
            // every subscription behind it.
            long after = 0L;
            List<Candidate> page;
            do {
                page = page(after);
                for (Candidate c : page) {
                    after = c.subscriptionId();
                    String level = c.levelToAlert(lowRatio);
                    if (level == null) continue;
                    try {
                        alert(c, level);
                    } catch (RuntimeException ex) {
                        // One bad row must not starve every subscription behind it, scan after scan.
                        log.warn("Credit alert for subscription {} failed: {}", c.subscriptionId(), ex.getMessage());
                    }
                }
            } while (page.size() == BATCH);
        } catch (RuntimeException ex) {
            log.warn("Credit alert scan failed: {}", ex.getMessage());
        }
    }

    /** Every alertable candidate, all pages (for tests and callers that want the decision only). */
    List<Candidate> candidates() {
        List<Candidate> out = new java.util.ArrayList<>();
        long after = 0L;
        List<Candidate> page;
        do {
            page = page(after);
            for (Candidate c : page) {
                if (c.levelToAlert(lowRatio) != null) out.add(c);
                after = c.subscriptionId();
            }
        } while (page.size() == BATCH);
        return out;
    }

    List<Candidate> page(long afterSubscriptionId) {
        String cycleKey = "to_char(s.current_period_start, 'YYYYMMDDHH24MISS') || ':' || s.credit_cycle_index";
        String balance = "(s.remaining_credits + s.payg_remaining_credits)";
        // The personal workspace is resolved IN the query: an account without one is not a
        // candidate at all, instead of a row every page has to step over.
        return jdbc.query(
                "SELECT s.id AS sub_id, bc.user_id, " + balance + " AS balance, " + cycleKey + " AS cycle_key, "
                        + "       po.id::text AS org_id, p.code AS plan_code, s.provider AS provider, "
                        + "       p.included_llm_tokens AS included_llm, "
                        + "       COALESCE(s.credit_quantity, 0) AS credit_quantity, "
                        + "       EXISTS (SELECT 1 FROM auth.credit_alert_sent a WHERE a.subscription_id = s.id "
                        + "               AND a.cycle_key = " + cycleKey + " AND a.level = 'LOW') AS low_sent, "
                        + "       EXISTS (SELECT 1 FROM auth.credit_alert_sent a WHERE a.subscription_id = s.id "
                        + "               AND a.cycle_key = " + cycleKey + " AND a.level = 'EXHAUSTED') AS exhausted_sent "
                        + "FROM auth.subscription s "
                        + "JOIN auth.billing_customer bc ON bc.id = s.billing_customer_id "
                        + "JOIN auth.plan p ON p.id = s.plan_id "
                        + "JOIN LATERAL (SELECT o.id FROM auth.organization o "
                        + "      WHERE o.owner_id = bc.user_id AND o.is_personal = true AND o.deleted_at IS NULL "
                        + "      ORDER BY o.created_at LIMIT 1) po ON true "
                        + "WHERE s.status IN ('trialing', 'active') AND s.id > ? "
                        // Spent in the last week: a real consumption DEBIT. Not a grant or a top-up
                        // (positive), and not the negative rows that are bookkeeping rather than use:
                        // the renewal reset, a reward clawback, an admin adjustment. A dormant
                        // account is never alerted about a balance nobody is spending.
                        + "  AND EXISTS (SELECT 1 FROM auth.credit_ledger l "
                        + "        WHERE l.user_id = bc.user_id AND l.amount < 0 "
                        + "          AND l.source_type NOT IN ('PLAN_RESET', 'REWARD_CLAWBACK', 'MANUAL_ADJUSTMENT') "
                        + "          AND l.created_at > now() - interval '7 days') "
                        + "ORDER BY s.id LIMIT ?",
                (rs, i) -> new Candidate(rs.getLong("sub_id"), rs.getLong("user_id"),
                        rs.getBigDecimal("balance"), rs.getString("cycle_key"), rs.getString("org_id"),
                        CreditAttributionService.cycleGrantCredits(rs.getString("provider"), rs.getString("plan_code"),
                                rs.getInt("credit_quantity"), rs.getObject("included_llm", Long.class)),
                        rs.getBoolean("low_sent"), rs.getBoolean("exhausted_sent")),
                afterSubscriptionId, BATCH);
    }

    void alert(Candidate c, String level) {
        NotificationEmitRequest req = new NotificationEmitRequest();
        req.setTenantId(String.valueOf(c.userId()));
        req.setCategory(LEVEL_EXHAUSTED.equals(level) ? "CREDIT_EXHAUSTED" : "CREDIT_LOW");
        req.setSeverity(LEVEL_EXHAUSTED.equals(level) ? "error" : "warning");
        req.setSubjectType("BILLING");
        // One stable subject per account, so the bell groups every credit alert of a person together.
        req.setSubjectId(UUID.nameUUIDFromBytes(("billing-" + c.userId()).getBytes(StandardCharsets.UTF_8)));
        req.setSourceId("credit-" + level.toLowerCase() + ":" + c.subscriptionId() + ":" + c.cycleKey());
        // The wallet belongs to the person, so the alert lands in their personal workspace.
        req.setOrganizationId(c.organizationId());
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", LEVEL_EXHAUSTED.equals(level) ? "exhausted" : "low");
        payload.put("subjectName", "Credits");
        payload.put("remainingCredits", c.balance().max(BigDecimal.ZERO).setScale(0, RoundingMode.DOWN).toPlainString());
        req.setPayload(payload);
        req.setOccurredAt(Instant.now());

        if (!notificationClient.emit(req)) {
            // Not recorded as sent: the next scan tries again.
            return;
        }
        int written = jdbc.update("INSERT INTO auth.credit_alert_sent (subscription_id, cycle_key, level) VALUES (?, ?, ?) "
                + "ON CONFLICT DO NOTHING", c.subscriptionId(), c.cycleKey(), level);
        // Counted once per RECORDED alert: a scan that lost the insert race is not a new alert.
        // distinct_id is the payer (billing_customer.user_id), the person the alert is addressed to.
        if (written > 0 && analytics != null) {
            analytics.creditAlertSent(c.userId(), c.organizationId(), level);
        }
    }
}
