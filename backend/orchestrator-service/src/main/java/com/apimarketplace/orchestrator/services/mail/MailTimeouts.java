package com.apimarketplace.orchestrator.services.mail;

/**
 * Socket timeouts for the two workflow mail nodes ({@code EmailInboxNode} over IMAP,
 * {@code SendEmailNode} over SMTP).
 *
 * <p><b>Why this exists at all.</b> Both nodes used to hardcode {@code "10000"} for every
 * timeout Jakarta Mail accepts, which is one number answering two different questions:
 *
 * <ul>
 *   <li><b>connect</b> asks "is this host reachable?". Ten seconds is right: an unreachable
 *       host must fail fast, and waiting longer only holds a worker thread on a lost cause.</li>
 *   <li><b>read</b> asks "how long may the server take to answer a command?". Ten seconds is
 *       WRONG for a mailbox: a {@code SEARCH} followed by a bulk {@code FETCH} over a hundred
 *       messages legitimately takes longer than that on a slow provider, and being slow is not
 *       being broken.</li>
 * </ul>
 *
 * <p>Conflating the two is what made a perfectly authenticated mailbox look broken. Measured on
 * production over 30 days: the same node reads a fast server at a 0.6% failure rate and a slow
 * one at 82.5%, with every failure landing in one of two shapes that are the SAME event seen from
 * two levels. Jakarta Mail reports the raw {@code * BYE ... SocketTimeoutException: Read timed
 * out} when the read is the operation that dies, and Angus Mail's {@code IMAPFolder} reports
 * {@code FolderClosedException("Lost folder connection to server")} when a LATER folder operation
 * discovers the protocol connection is already gone. Neither is an authentication problem, and
 * neither is recoverable by the affected session.
 *
 * <p><b>The read timeout is per socket read, not per node.</b> Raising it does not make a healthy
 * run slower: it only stops one slow round trip from killing a session that would otherwise have
 * completed. The cost it does carry is real and bounded: a read that hangs now occupies its
 * execution worker for the configured ceiling instead of ten seconds. That is why the connect
 * timeout deliberately stays short, and why a node author who wants a hard ceiling on the whole
 * attempt still has {@code nodePolicy.timeoutMs}.
 *
 * <p>auth-service already exposes the same three knobs for its own SMTP sender
 * ({@code SMTP_CONNECT_TIMEOUT_MS} / {@code SMTP_READ_TIMEOUT_MS} / {@code SMTP_WRITE_TIMEOUT_MS}),
 * so the two workflow nodes were the last mail callers in the platform that could not be tuned at
 * all: no property, no environment variable, no credential field, and not even a JVM flag, because
 * {@code Session.getInstance(Properties)} reads the Properties it is handed and never falls back
 * to system properties.
 *
 * <p><b>It differs from auth-service on what an ABSENT property means</b>, and the difference is
 * deliberate. {@code MailTransportSecurityValidator} defaults to 0 and THROWS, so deleting its
 * yml block fails the boot. Here an absent value is coerced instead, because the CE monolith
 * ships no {@code workflow.mail} block and failing its boot would be wrong. Coercing costs one
 * thing: a typo and an absence then look alike, so every substitution is logged at WARN naming
 * the property. See {@link MailTimeoutsConfig}.
 *
 * <p>Values are milliseconds. A non-positive value is refused rather than silently passed to
 * Jakarta Mail, where {@code 0} means "wait forever" - the one setting that turns a slow mailbox
 * into a permanently occupied worker thread.
 */
public record MailTimeouts(
        int imapConnectMs,
        int imapReadMs,
        int smtpConnectMs,
        int smtpReadMs,
        int smtpWriteMs) {

    /** Connect stays at the historical value: an unreachable host must still fail fast. */
    public static final int DEFAULT_CONNECT_MS = 10_000;

    /**
     * A mailbox read may legitimately take a minute on a slow provider. This is the only
     * default that changes behaviour relative to the hardcoded era, and it is the fix.
     */
    public static final int DEFAULT_IMAP_READ_MS = 60_000;

    /** Sending is a single small exchange, so it needs far less headroom than a folder read. */
    public static final int DEFAULT_SMTP_READ_MS = 30_000;
    public static final int DEFAULT_SMTP_WRITE_MS = 30_000;

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(MailTimeouts.class);

    public MailTimeouts {
        imapConnectMs = positiveOr("workflow.mail.imap.connect-timeout-ms", imapConnectMs, DEFAULT_CONNECT_MS);
        imapReadMs = positiveOr("workflow.mail.imap.read-timeout-ms", imapReadMs, DEFAULT_IMAP_READ_MS);
        smtpConnectMs = positiveOr("workflow.mail.smtp.connect-timeout-ms", smtpConnectMs, DEFAULT_CONNECT_MS);
        smtpReadMs = positiveOr("workflow.mail.smtp.read-timeout-ms", smtpReadMs, DEFAULT_SMTP_READ_MS);
        smtpWriteMs = positiveOr("workflow.mail.smtp.write-timeout-ms", smtpWriteMs, DEFAULT_SMTP_WRITE_MS);
    }

    /**
     * The timeouts a node uses when nothing configured them - every test-built
     * {@code ServiceRegistry} and any caller that predates the setting.
     */
    public static MailTimeouts defaults() {
        return new MailTimeouts(DEFAULT_CONNECT_MS, DEFAULT_IMAP_READ_MS,
                DEFAULT_CONNECT_MS, DEFAULT_SMTP_READ_MS, DEFAULT_SMTP_WRITE_MS);
    }

    /**
     * A misconfigured value falls back to the default instead of reaching Jakarta Mail, where
     * {@code 0} and negatives mean "no timeout".
     *
     * <p>The substitution is logged at WARN naming the property, because coercing silently would
     * make a typo, a deleted yml block and a deliberate default indistinguishable at runtime.
     * That is the whole cost of diverging from auth-service, so it is paid in the log.
     */
    private static int positiveOr(String property, int value, int fallback) {
        if (value > 0) return value;
        logger.warn("Mail timeout {} is {}, not a positive number of milliseconds; using {} ms instead",
                property, value, fallback);
        return fallback;
    }
}
