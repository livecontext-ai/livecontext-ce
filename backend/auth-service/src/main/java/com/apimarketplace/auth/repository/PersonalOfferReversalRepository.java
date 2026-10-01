package com.apimarketplace.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** A charge stays pending until invoice lookup and the transactional clawback succeed. */
@Repository
public class PersonalOfferReversalRepository {
    private final JdbcTemplate jdbc;
    public PersonalOfferReversalRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Task(String chargeId, String reason) { }

    public void enqueue(String chargeId, String reason) {
        jdbc.update("""
                INSERT INTO auth.personal_offer_reversal_task(charge_id,reason) VALUES (?,?)
                ON CONFLICT (charge_id) DO NOTHING
                """, chargeId, reason);
    }

    public List<Task> due(Instant now) {
        return jdbc.query("""
                SELECT charge_id,reason FROM auth.personal_offer_reversal_task
                WHERE status='PENDING' AND next_attempt_at <= ?
                ORDER BY next_attempt_at,created_at,charge_id LIMIT 100
                """, (rs, row) -> new Task(rs.getString(1), rs.getString(2)), Timestamp.from(now));
    }

    public Optional<Task> pending(String chargeId) {
        return jdbc.query("SELECT charge_id,reason FROM auth.personal_offer_reversal_task WHERE charge_id=? AND status='PENDING'",
                (rs, row) -> new Task(rs.getString(1), rs.getString(2)), chargeId).stream().findFirst();
    }

    public void complete(Task task, String invoiceId) {
        jdbc.update("""
                UPDATE auth.personal_offer_reversal_task SET status=?,invoice_id=?,resolved_at=now()
                WHERE charge_id=? AND status='PENDING'
                """, invoiceId == null ? "NOT_APPLICABLE" : "RESOLVED", invoiceId, task.chargeId());
    }

    public void defer(Task task, Instant next) {
        jdbc.update("""
                UPDATE auth.personal_offer_reversal_task SET next_attempt_at=?,attempts=attempts+1
                WHERE charge_id=? AND status='PENDING'
                """, Timestamp.from(next), task.chargeId());
    }
}
