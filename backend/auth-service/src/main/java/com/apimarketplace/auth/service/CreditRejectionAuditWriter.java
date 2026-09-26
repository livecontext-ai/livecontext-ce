package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit row for a refused consumption, in a transaction of its own.
 *
 * <p><b>Why it cannot stay in the caller's transaction.</b> The rejection row carries a key derived
 * from the {@code source_id} of the turn that was refused ({@link #rejectionSourceId}), and
 * {@code idx_cl_source_id_unique} is unique on that column alone. An agent that retries a turn it
 * has no credits for therefore writes the same key twice, which is not an edge case: it is what a retry loop does, every time. Inside
 * {@link CreditService#deductCredits}'s transaction the second write raised, PostgreSQL put the
 * transaction in {@code ERROR} state and Spring flagged it rollback-only, and the comment sitting
 * beside the catch ("if the save throws, the caller still receives insufficientCredits") stopped
 * being true: the caller received an {@code UnexpectedRollbackException} thrown at the commit,
 * which no catch inside the method can reach. Production, 2026-09-17: ten refused turns five
 * minutes apart, ten {@code Failed to write rejection audit row} warnings, and twenty HTTP 500s
 * where the answer should have been a clean "insufficient credits".
 *
 * <p>{@code REQUIRES_NEW} suspends the caller's transaction and runs this one on its own
 * connection and its own persistence context, so a duplicate key here damages nothing but itself.
 * The caller keeps its catch and that catch now does what it always claimed to do.
 *
 * <p>The second connection is worth naming: the caller still holds a {@code PESSIMISTIC_WRITE} on
 * the payer's subscription row while this runs, so a cycle of the shape "this session waits on a
 * source_id key held by a third session, which waits on the subscription lock the caller holds"
 * would sit across two transactions and stay invisible to PostgreSQL's deadlock detector. It is
 * not constructible from today's call graph: every writer that can collide on the same
 * {@code source_id} takes the subscription lock before writing, and the two ledger writes that
 * precede that lock (the zero-cost and unlimited branches) return without reaching this class.
 * A future ledger writer that skips the subscription lock would change that.
 *
 * <p>Losing the row on a duplicate is the correct outcome, not a compromise: one rejection already
 * stands for that {@code source_id}, and the analytics this feeds counts refused turns, not
 * refused attempts.
 *
 * <p>Two consequences of the separate transaction that are easy to miss. The row now COMMITS even
 * if the caller later rolls back, which is what we want for an audit of a refusal (the caller
 * returns immediately after it, so there is nothing else to undo) but would be wrong for a row
 * that had to move with the caller's outcome. And the second connection is taken while the caller
 * holds one, so under pool saturation this path needs two where it used to need one.
 */
@Service
public class CreditRejectionAuditWriter {

    /**
     * Suffix of the key a rejection audit row is stored under. A refusal never sits on the
     * {@code source_id} of the turn it refused, because that key belongs to the CHARGE: the turn
     * already ran, and once the wallet is topped up the dead-letter replay has to be able to
     * write the real debit there. Until 2026-09-25 the audit row took the bare key, so the
     * replay hit {@code idx_cl_source_id_unique}, answered HTTP 500 ten times and ended FAILED:
     * work that had already happened became work that could never be billed.
     *
     * <p>The key stays deterministic, so a second refusal of the same turn still collides with
     * the first one and is dropped here, exactly as before (one row per refused turn).
     */
    public static final String REJECTION_KEY_SUFFIX = ":rejected";

    /** Same width as {@code credit_ledger.source_id} (V101). */
    private static final int SOURCE_ID_MAX_LENGTH = 512;

    /**
     * The key the rejection audit row for {@code sourceId} is written under, or {@code null}
     * when the refused call carried no key (null or blank). An oversized key keeps a prefix plus a SHA-256 of
     * the WHOLE id (never the suffix is cut), so it fits the column, stays stable, and two long
     * ids sharing a prefix still get distinct keys.
     */
    public static String rejectionSourceId(String sourceId) {
        // A blank key is no key: ":rejected" would be ONE key shared by every payer's keyless
        // refusal, so all of them after the first would be dropped as duplicates.
        if (sourceId == null || sourceId.isBlank()) return null;
        int room = SOURCE_ID_MAX_LENGTH - REJECTION_KEY_SUFFIX.length();
        if (sourceId.length() <= room) return sourceId + REJECTION_KEY_SUFFIX;
        String hash = sha256Hex(sourceId);
        return sourceId.substring(0, room - hash.length() - 1) + "#" + hash + REJECTION_KEY_SUFFIX;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }

    /** True for a {@code <sourceType>_REJECTED} audit row. */
    public static boolean isRejectionRow(CreditLedgerEntry entry) {
        return entry != null && entry.getSourceType() != null
                && entry.getSourceType().endsWith("_REJECTED");
    }

    private final CreditLedgerRepository ledgerRepository;

    public CreditRejectionAuditWriter(CreditLedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(CreditLedgerEntry rejected) {
        ledgerRepository.save(rejected);
    }
}
