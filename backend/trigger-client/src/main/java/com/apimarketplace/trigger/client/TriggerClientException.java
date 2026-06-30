package com.apimarketplace.trigger.client;

/**
 * Distinguishes "missing resource" (404 - caller may safely fall through to recreate)
 * from "transient failure" (5xx / network - caller MUST NOT take destructive action).
 *
 * <p>Background: most {@link TriggerClient} methods catch every exception and return
 * {@code null} for backward compat. That conflates "row doesn't exist" with "service down",
 * which caused a prod incident on 2026-04-29 when {@code ScheduleSyncService} ran
 * {@code cleanupDuplicateRows} against a phantom keepId returned silently from a 404,
 * deleting the only working row.
 *
 * <p>Use this exception only on the critical paths that drive destructive cleanup
 * (e.g. standalone schedule update). Other methods keep returning {@code null} -
 * their callers don't act destructively on the result.
 */
public class TriggerClientException extends RuntimeException {

    public enum Kind { NOT_FOUND, CLIENT_ERROR, SERVER_ERROR, TRANSPORT }

    private final Kind kind;

    public TriggerClientException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() { return kind; }

    public boolean isNotFound() { return kind == Kind.NOT_FOUND; }

    public boolean isTransient() {
        return kind == Kind.SERVER_ERROR || kind == Kind.TRANSPORT;
    }
}
