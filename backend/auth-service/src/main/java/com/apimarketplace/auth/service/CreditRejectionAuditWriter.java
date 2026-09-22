package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit row for a refused consumption, in a transaction of its own.
 *
 * <p><b>Why it cannot stay in the caller's transaction.</b> The rejection row carries the
 * {@code source_id} of the turn that was refused, and {@code idx_cl_source_id_unique} is unique on
 * that column alone. An agent that retries a turn it has no credits for therefore writes the same
 * key twice, which is not an edge case: it is what a retry loop does, every time. Inside
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

    private final CreditLedgerRepository ledgerRepository;

    public CreditRejectionAuditWriter(CreditLedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(CreditLedgerEntry rejected) {
        ledgerRepository.save(rejected);
    }
}
