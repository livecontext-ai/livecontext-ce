package com.apimarketplace.catalog.service.credential;

import java.util.List;

/**
 * The credential templates a CORE WORKFLOW NODE reads, which no {@code catalog.apis} row
 * backs.
 *
 * <p>Every other credential template in {@code catalog.credentials} hangs off an API in the
 * catalog, and both readers below select on that join. These do not: {@code send_email} reads
 * {@code smtp}, {@code email_inbox} reads {@code imap}, and {@code ssh} / {@code sftp} /
 * {@code database} read a template of their own name. Without an explicit allow-list they are
 * dropped by the join, which hides a credential the product genuinely offers.
 *
 * <p><b>Why this is one class and not a constant in each reader.</b> It WAS a constant in each,
 * one of them carrying a {@code Mirrors ...} comment, and they drifted: the user-facing listing
 * and the catalog bundle shipped to self-hosted installs would have disagreed about which
 * credentials exist. A mirror that is only a comment is a mirror nothing enforces. Adding a core
 * node that reads a native credential is now one edit, here.
 *
 * <p>LLM provider keys ({@code llm_*}) are deliberately absent: they are configured on the AI
 * Providers settings page, and listing them here would duplicate that flow.
 */
public final class NativeCoreCredentials {

    private static final List<String> NAMES =
            List.of("smtp", "imap", "ssh", "sftp", "database");

    private NativeCoreCredentials() {
    }

    public static List<String> names() {
        return NAMES;
    }

    /**
     * The names as a SQL {@code IN} list, quotes included, for inlining into a query.
     *
     * <p>Safe to inline because the values are this class's own literals and never caller
     * input. It is built here rather than in each reader so the two cannot inline different
     * sets, which is the whole point of the class.
     */
    public static String sqlInList() {
        return "'" + String.join("','", NAMES) + "'";
    }
}
