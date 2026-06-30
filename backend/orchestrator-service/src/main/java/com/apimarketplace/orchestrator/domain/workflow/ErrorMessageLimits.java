package com.apimarketplace.orchestrator.domain.workflow;

/**
 * Centralized cap for diagnostic text persisted to the DB - the
 * {@code workflow_step_data.error_message} column and the {@code metadata}
 * JSONB {@code statusMessage} / {@code errorMessage} keys.
 *
 * <p>V186 widened {@code error_message} from VARCHAR(1000) to TEXT, removing
 * the silent-drop bug where a 4 KB Google HTML 404 page collapsed the INSERT.
 * TEXT is unbounded (1 GB ceiling), so we add this application-level cap
 * to keep the highest-volume orchestrator table healthy:
 * <ul>
 *   <li>The full failure payload is already persisted to S3 by
 *       {@code StepPayloadService}, so the DB column is a "preview" - clicking
 *       a failed node in the UI fetches the full body from storage.</li>
 *   <li>Postgres TOAST detoasting penalises every read of large rows, and
 *       Hibernate over-fetch patterns (cf. OOM incident 2026-05-07,
 *       {@code findLatestPerAliasLightweight}) scale linearly with row size.</li>
 * </ul>
 *
 * <p>Single source of truth: called from {@link StepExecutionResult}'s compact
 * constructor (covers {@code result.message()} on every step outcome) and
 * from {@code StepMetadataBuilder} for the {@code Exception.getMessage()}
 * path (which bypasses the record).
 *
 * <p>Fast-path: O(1) length check, no allocation when the message is null
 * or within budget. Slow-path runs only on truncation.
 */
public final class ErrorMessageLimits {

    /**
     * Hard cap, in characters. 16 384 chars covers:
     * <ul>
     *   <li>HTML 4xx/5xx error pages (Google, Cloudflare, Nginx - ~4-6 KB)</li>
     *   <li>Multi-cause Java stacktraces (~2-10 KB)</li>
     *   <li>Verbose JSON validation errors (~1-5 KB)</li>
     * </ul>
     * For ASCII content this is exactly 16 KB on disk; for multibyte UTF-8 up
     * to 64 KB worst case - both stay below the Postgres TOAST 8 KB inline
     * threshold's hot reach for the leading content fragment.
     */
    public static final int MAX_LENGTH = 16_384;

    private ErrorMessageLimits() {}

    /**
     * Truncate a diagnostic message to {@link #MAX_LENGTH} characters when it
     * exceeds the cap, appending a {@code …[truncated, was N chars]} suffix
     * so the original length stays auditable.
     *
     * <p>Optimised hot path:
     * <ul>
     *   <li>{@code null} → returns {@code null} (no work).</li>
     *   <li>{@code length() <= MAX_LENGTH} → returns the input reference
     *       unchanged (no allocation, no scan).</li>
     *   <li>Otherwise → single {@link StringBuilder} allocation pre-sized to
     *       {@code MAX_LENGTH} (no growth), one substring copy, one concat.
     *       Total work is bounded by {@code MAX_LENGTH}.</li>
     * </ul>
     *
     * @param message original message; may be {@code null}
     * @return original reference when within budget, otherwise a truncated copy
     */
    public static String truncate(String message) {
        if (message == null) return null;
        int len = message.length();
        if (len <= MAX_LENGTH) return message;

        // Reserve room for the suffix so the final string stays <= MAX_LENGTH.
        // Suffix length depends on the digit count of `len`; max realistic
        // length is "…[truncated, was 2147483647 chars]" = 34 chars (cap = Integer.MAX_VALUE).
        // We compute it exactly to avoid wasting bytes.
        String suffix = "…[truncated, was " + len + " chars]";
        int prefix = MAX_LENGTH - suffix.length();
        if (prefix < 0) prefix = 0; // pathological MAX_LENGTH < 34, never in practice

        return new StringBuilder(MAX_LENGTH)
                .append(message, 0, prefix)
                .append(suffix)
                .toString();
    }
}
