package com.apimarketplace.auth.lifecycle;

import com.apimarketplace.auth.service.CreditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Durable observation and email-step claims for the one personal upgrade campaign. */
@Repository
public class PersonalOfferLifecycleRepository {

    public static final String CAMPAIGN_KEY = "free-credit-upgrade";
    private static final int PAGE_SIZE = 500;
    private final JdbcTemplate jdbc;

    public PersonalOfferLifecycleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Observation(long userId, Instant exhaustedAt) { }
    public record Issued(long userId, long codeId) { }
    public enum Step { INITIAL, REMINDER }

    /** Mirrors CreditAlertScheduler's FREE exhaustion and real-debit definition. */
    public List<Long> exhaustedFreeUsers(long afterUserId) {
        return jdbc.query("""
                SELECT DISTINCT bc.user_id
                FROM auth.subscription s
                JOIN auth.billing_customer bc ON bc.id = s.billing_customer_id
                JOIN auth.plan p ON p.id = s.plan_id
                WHERE bc.user_id > ? AND p.code = 'FREE'
                  AND s.status IN ('trialing', 'active')
                  AND (s.remaining_credits + s.payg_remaining_credits) < ?
                  AND EXISTS (SELECT 1 FROM auth.organization o WHERE o.owner_id = bc.user_id
                              AND o.is_personal = true AND o.deleted_at IS NULL)
                  AND EXISTS (SELECT 1 FROM auth.credit_ledger l WHERE l.user_id = bc.user_id
                              AND l.amount < 0
                              AND l.source_type NOT IN ('PLAN_RESET', 'REWARD_CLAWBACK', 'MANUAL_ADJUSTMENT')
                              AND l.created_at > now() - interval '7 days')
                ORDER BY bc.user_id LIMIT ?
                """, (rs, row) -> rs.getLong(1), afterUserId, CreditService.MIN_USABLE_BALANCE, PAGE_SIZE);
    }

    public boolean isExhaustedFree(long userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM auth.subscription s
                JOIN auth.billing_customer bc ON bc.id = s.billing_customer_id
                JOIN auth.plan p ON p.id = s.plan_id
                WHERE bc.user_id = ? AND p.code = 'FREE'
                  AND s.status IN ('trialing', 'active')
                  AND (s.remaining_credits + s.payg_remaining_credits) < ?
                  AND EXISTS (SELECT 1 FROM auth.organization o WHERE o.owner_id = bc.user_id
                              AND o.is_personal = true AND o.deleted_at IS NULL)
                  AND EXISTS (SELECT 1 FROM auth.credit_ledger l WHERE l.user_id = bc.user_id
                              AND l.amount < 0
                              AND l.source_type NOT IN ('PLAN_RESET', 'REWARD_CLAWBACK', 'MANUAL_ADJUSTMENT')
                              AND l.created_at > now() - interval '7 days')
                """, Integer.class, userId, CreditService.MIN_USABLE_BALANCE);
        return count != null && count > 0;
    }

    public boolean hasPositiveGrantSince(long userId, Instant since) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM auth.credit_ledger
                WHERE user_id = ? AND amount > 0 AND created_at > ?
                """, Integer.class, userId, Timestamp.from(since));
        return count != null && count > 0;
    }

    /** Follow-up emails do not require another recent debit after the offer was issued. */
    public boolean isStillFreeAndExhausted(long userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM auth.subscription s
                JOIN auth.billing_customer bc ON bc.id = s.billing_customer_id
                JOIN auth.plan p ON p.id = s.plan_id
                WHERE bc.user_id = ? AND p.code = 'FREE'
                  AND s.status IN ('trialing', 'active')
                  AND (s.remaining_credits + s.payg_remaining_credits) < ?
                """, Integer.class, userId, CreditService.MIN_USABLE_BALANCE);
        return count != null && count > 0;
    }

    public boolean isUserMarketingEligible(long userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM auth.users
                WHERE id = ? AND enabled = true AND email_verified = true
                  AND deactivated_at IS NULL AND marketing_consent = true
                """, Integer.class, userId);
        return count != null && count > 0;
    }

    public void observe(long userId, Instant at) {
        jdbc.update("""
                INSERT INTO auth.personal_offer_lifecycle AS lifecycle
                    (user_id, campaign_key, exhausted_at, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, campaign_key) DO UPDATE
                SET exhausted_at = COALESCE(lifecycle.exhausted_at, EXCLUDED.exhausted_at),
                    updated_at = EXCLUDED.updated_at
                WHERE lifecycle.offer_code_id IS NULL
                """, userId, CAMPAIGN_KEY, Timestamp.from(at), Timestamp.from(at));
    }

    public void resetUnissued(long userId, Instant at) {
        jdbc.update("""
                UPDATE auth.personal_offer_lifecycle SET exhausted_at = NULL, updated_at = ?
                WHERE user_id = ? AND campaign_key = ? AND offer_code_id IS NULL
                """, Timestamp.from(at), userId, CAMPAIGN_KEY);
    }

    public List<Observation> observations(long afterUserId) {
        return jdbc.query("""
                SELECT user_id, exhausted_at FROM auth.personal_offer_lifecycle
                WHERE campaign_key = ? AND user_id > ? AND exhausted_at IS NOT NULL AND offer_code_id IS NULL
                ORDER BY user_id LIMIT ?
                """, (rs, row) -> new Observation(rs.getLong(1), rs.getTimestamp(2).toInstant()),
                CAMPAIGN_KEY, afterUserId, PAGE_SIZE);
    }

    public void attachIssued(long userId, long codeId, Instant at) {
        jdbc.update("""
                UPDATE auth.personal_offer_lifecycle
                SET offer_code_id = ?, updated_at = ?
                WHERE user_id = ? AND campaign_key = ? AND offer_code_id IS NULL
                """, codeId, Timestamp.from(at), userId, CAMPAIGN_KEY);
    }

    public List<Issued> issuedWithPendingEmails(long afterUserId) {
        return jdbc.query("""
                SELECT user_id, offer_code_id FROM auth.personal_offer_lifecycle
                WHERE campaign_key = ? AND user_id > ? AND offer_code_id IS NOT NULL
                  AND (initial_status = 'pending' OR reminder_status = 'pending')
                ORDER BY user_id LIMIT ?
                """, (rs, row) -> new Issued(rs.getLong(1), rs.getLong(2)),
                CAMPAIGN_KEY, afterUserId, PAGE_SIZE);
    }

    public boolean initialAccepted(long userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM auth.personal_offer_lifecycle
                WHERE user_id = ? AND campaign_key = ? AND initial_status = 'accepted'
                """, Integer.class, userId, CAMPAIGN_KEY);
        return count != null && count > 0;
    }

    /** A single UPDATE serializes claims across auth-service pods and restarts. */
    public boolean claim(long userId, Step step, Instant at) {
        String status = step == Step.INITIAL ? "initial_status" : "reminder_status";
        String claimed = step == Step.INITIAL ? "initial_claimed_at" : "reminder_claimed_at";
        return jdbc.update("UPDATE auth.personal_offer_lifecycle SET " + status + " = 'claimed', " +
                        claimed + " = ?, updated_at = ? WHERE user_id = ? AND campaign_key = ? AND " +
                        status + " = 'pending' AND offer_code_id IS NOT NULL",
                Timestamp.from(at), Timestamp.from(at), userId, CAMPAIGN_KEY) == 1;
    }

    /** Complete only the claim held by this worker, never one replaced by a racing pod. */
    public void finish(long userId, Step step, Instant claimedAt, ResendClient.EventResult result, Instant at) {
        String status = step == Step.INITIAL ? "initial_status" : "reminder_status";
        String claimed = step == Step.INITIAL ? "initial_claimed_at" : "reminder_claimed_at";
        String accepted = step == Step.INITIAL ? "initial_accepted_at" : "reminder_accepted_at";
        String target = switch (result) {
            case ACCEPTED -> "accepted";
            case NOT_SENT -> "pending";
            case UNKNOWN -> "unknown";
        };
        jdbc.update("UPDATE auth.personal_offer_lifecycle SET " + status + " = ?, " +
                        accepted + " = CASE WHEN ? = 'accepted' THEN ? ELSE " + accepted + " END, " +
                        "updated_at = ? WHERE user_id = ? AND campaign_key = ? AND " + status +
                        " = 'claimed' AND " + claimed + " = ?",
                target, target, Timestamp.from(at), Timestamp.from(at), userId, CAMPAIGN_KEY,
                Timestamp.from(claimedAt));
    }

    public void suppressPending(long userId, String reason, Instant at) {
        jdbc.update("""
                UPDATE auth.personal_offer_lifecycle
                SET initial_status = CASE WHEN initial_status = 'pending' THEN 'suppressed' ELSE initial_status END,
                    reminder_status = CASE WHEN reminder_status = 'pending' THEN 'suppressed' ELSE reminder_status END,
                    stopped_reason = ?, updated_at = ?
                WHERE user_id = ? AND campaign_key = ?
                """, reason, Timestamp.from(at), userId, CAMPAIGN_KEY);
    }

    public void suppressStep(long userId, Step step, String reason, Instant at) {
        String status = step == Step.INITIAL ? "initial_status" : "reminder_status";
        jdbc.update("UPDATE auth.personal_offer_lifecycle SET " + status +
                        " = 'suppressed', stopped_reason = ?, updated_at = ? " +
                        "WHERE user_id = ? AND campaign_key = ? AND " + status + " = 'pending'",
                reason, Timestamp.from(at), userId, CAMPAIGN_KEY);
    }

    /** A pod death after claiming may have occurred during the HTTP call. Hold for review. */
    public void markStaleClaimsUnknown(Instant olderThan, Instant at) {
        jdbc.update("""
                UPDATE auth.personal_offer_lifecycle
                SET initial_status = CASE WHEN initial_status = 'claimed' AND initial_claimed_at < ?
                                          THEN 'unknown' ELSE initial_status END,
                    reminder_status = CASE WHEN reminder_status = 'claimed' AND reminder_claimed_at < ?
                                           THEN 'unknown' ELSE reminder_status END,
                    updated_at = ?
                WHERE campaign_key = ? AND
                    ((initial_status = 'claimed' AND initial_claimed_at < ?)
                     OR (reminder_status = 'claimed' AND reminder_claimed_at < ?))
                """, Timestamp.from(olderThan), Timestamp.from(olderThan), Timestamp.from(at), CAMPAIGN_KEY,
                Timestamp.from(olderThan), Timestamp.from(olderThan));
    }
}
