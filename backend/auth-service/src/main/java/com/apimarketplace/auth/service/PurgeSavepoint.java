package com.apimarketplace.auth.service;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;

import java.sql.SQLException;
import java.sql.Savepoint;

/**
 * Runs a best-effort step of a purge inside its own SAVEPOINT on the purge's connection.
 *
 * <p>A try/catch alone is not enough when the step issues SQL: Postgres aborts the whole
 * transaction on the first failing statement, so every purge statement after a swallowed failure
 * would fail too ("current transaction is aborted"). Rolling back to the savepoint undoes only
 * the failed step and leaves the purge able to continue.
 */
final class PurgeSavepoint {

    private PurgeSavepoint() {
    }

    /**
     * Runs {@code step}; if it throws, rolls back to the savepoint first, then rethrows so the
     * caller decides how to report it.
     */
    static void run(EntityManager em, Runnable step) {
        Session session = em.unwrap(Session.class);
        Savepoint[] savepoint = new Savepoint[1];
        session.doWork(conn -> savepoint[0] = conn.setSavepoint());
        try {
            step.run();
        } catch (RuntimeException e) {
            session.doWork(conn -> {
                conn.rollback(savepoint[0]);
                release(conn, savepoint[0]);
            });
            throw e;
        }
        session.doWork(conn -> release(conn, savepoint[0]));
    }

    private static void release(java.sql.Connection conn, Savepoint savepoint) {
        try {
            conn.releaseSavepoint(savepoint);
        } catch (SQLException ignore) {
            // already gone after a rollback, or released with its enclosing savepoint
        }
    }
}
