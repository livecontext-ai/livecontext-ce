package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PasswordResetToken;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.PasswordResetTokenRepository;
import com.apimarketplace.auth.repository.UserRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Password reset by e-mail, for embedded (self-hosted) auth only.
 *
 * <p>Why this exists: on cloud, Keycloak owns the reset flow. Embedded auth had
 * registration, login and change-password (which needs the CURRENT password) and
 * nothing else, so a self-hoster who forgot their password had no route back
 * into their own install. That is the one gap in the whole notification audit
 * where the absence of an e-mail does not degrade an experience, it removes an
 * access.
 *
 * <p>Three properties this flow has to hold, and each one costs something
 * visible in the code below:
 *
 * <ol>
 *   <li><b>It must not say whether an account exists.</b> {@link #requestReset}
 *       returns void, and the controller answers the same status and the same
 *       body for a known address, an unknown one, one with no local password,
 *       one that is disabled, one that is rate limited, and a send that SMTP
 *       refused. This is why the method cannot usefully return "sent" or "not
 *       sent". The paths are NOT identical (a real address costs two writes and
 *       a mail dispatch), so the e-mail is handed to a pool rather than sent on
 *       the request thread: that is what keeps the difference down to a couple
 *       of local writes instead of an SMTP round trip, which would otherwise
 *       make the response TIME answer what the response body will not. It is a
 *       mitigation, not constant time: this endpoint has the same residual
 *       timing shape as {@code login}, where a known address pays a bcrypt,
 *       though not the same magnitude: bcrypt is hundreds of milliseconds where
 *       the residual here is a commit and three statements.</li>
 *   <li><b>The raw token must exist in exactly one place: the e-mail.</b> Only
 *       its SHA-256 is stored, and it is never logged, not even at DEBUG. A
 *       reset link is a bearer credential for the account.</li>
 *   <li><b>A token must be single-use.</b> That one is absolute: redemption
 *       claims the row with a conditional update, so a second attempt with the
 *       same link is refused even if it arrives at the same instant. Issuing a
 *       new token also burns the previous one, and redeeming burns anything still
 *       pending, so a forwarded or intercepted older e-mail is inert.
 *       <p>"One live token per user" is the weaker of the two and is NOT
 *       guaranteed under concurrency: two genuinely simultaneous requests for one
 *       account each invalidate what they can see and then insert, leaving two
 *       live tokens. Both work, the later one wins the next issuance, and
 *       redeeming either burns the other. V487 records why that is preferred to
 *       a database constraint, which would answer one of the two requesters "a
 *       link is on its way" and send nothing.</li>
 * </ol>
 *
 * <p>Rate limiting is per user, not per IP: the point is to stop an address being
 * mail-bombed through the form, and the attacker chooses the IP but not the
 * victim's address. An unknown address costs a lookup and nothing more, so it
 * needs no counter.
 *
 * <p><b>The CE console fallback is deliberately NOT reused here.</b>
 * {@code app.mail.console-fallback-enabled} defaults to true in CE and
 * {@code EmailVerificationService} uses it to print the verification CODE to the
 * log when delivery fails, which is a real answer to "self-hoster with no
 * relay". It is the wrong answer for this flow: a verification code confirms an
 * address its owner already registered, while a reset link TAKES OVER an
 * existing account. Printing one puts account takeover within reach of anyone
 * who can read the logs, which on a multi-user install is more people than the
 * mailbox owner, and log shipping carries it further still. The operator's route
 * is to fix SMTP, or to set the hash directly; both need the access they already
 * have as operator.
 *
 * <p><b>There is deliberately no "is mail configured on this install" signal.</b>
 * It looks like the obvious kindness to a self-hoster and it cannot be built
 * honestly. {@code spring.mail.host} defaults to {@code localhost} in every
 * edition (auth-service and the CE monolith both), so it is never blank and
 * answers nothing; and treating a local host as unconfigured would refuse the
 * flow to an install running a real relay on localhost, which is a common
 * self-hosted setup, breaking something that works. So the only report of a
 * delivery failure goes to the OPERATOR, as the ERROR log in
 * {@link #requestReset}. The requester cannot be told, because a mail is only
 * ever attempted for an address that exists, and the frontend says plainly that
 * a self-hosted install needs a mail server for this to arrive.
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "embedded")
public class PasswordResetService {

    private static final Logger logger = LoggerFactory.getLogger(PasswordResetService.class);

    /** Long enough to act on from another device, short enough to limit an intercepted mail. */
    static final int TOKEN_TTL_MINUTES = 60;

    /** Requests allowed per user per window, before the form silently stops sending. */
    static final int MAX_REQUESTS_PER_WINDOW = 5;
    static final int RATE_WINDOW_MINUTES = 60;

    /**
     * 32 bytes, the same size PasswordAuthService uses for a refresh token.
     *
     * <p>Package-private so a test can pin it. It is the one number in this class
     * that decides whether the whole feature is safe, and it was the only one
     * with no test: deleting the {@code nextBytes} call below, so that every user
     * of every install receives the same all-zero token, left the entire suite
     * green.
     */
    static final int TOKEN_BYTES = 32;

    /**
     * ONE message for unknown, spent and expired, and for losing the race to a
     * concurrent redemption.
     *
     * <p>Which of those it was is not the requester's business and is not
     * actionable by them, and telling them apart is an oracle: "not valid" for an
     * unknown token versus "already used" for a real one confirms that a token
     * was genuinely issued, which confirms the address has an account. The four
     * cases share this constant so they cannot drift apart by editing one.
     */
    public static final String INVALID_TOKEN_MESSAGE =
            "This reset link is not valid, or has already been used. Please request a new one.";

    /** {@code created_ip} is VARCHAR(45): an IPv6 address, and not one byte more. */
    private static final int MAX_IP_LENGTH = 45;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordAuthService passwordAuthService;
    private final PasswordResetMailer mailer;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetService(UserRepository userRepository,
                                PasswordResetTokenRepository tokenRepository,
                                PasswordAuthService passwordAuthService,
                                PasswordResetMailer mailer) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordAuthService = passwordAuthService;
        this.mailer = mailer;
    }

    /**
     * Issues a reset token for {@code email} and sends it, or does nothing at all.
     *
     * <p>Deliberately void: see property (1) in the class docs. The caller must
     * not be able to distinguish "sent" from "no such account", so there is
     * nothing safe to return.
     */
    @Transactional
    public void requestReset(String email, String requestIp) {
        if (email == null || email.isBlank()) return;

        Optional<User> found = userRepository.findByEmail(email.trim().toLowerCase());
        if (found.isEmpty()) {
            // No row, no token, no mail, and the caller cannot tell.
            logger.debug("Password reset requested for an unknown address");
            return;
        }
        User user = found.get();

        // A user with no local password (OAuth-only account) has nothing to reset.
        // Same silence: telling them would disclose how the account signs in.
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            logger.debug("Password reset requested for user {} which has no local password", user.getId());
            return;
        }

        // A disabled account must not be handed back by e-mail. Login refuses it
        // anyway, so resetting its hash would only mean a stranger who guessed a
        // suspended address gets to rewrite its password for free.
        if (!user.isEnabled()) {
            logger.debug("Password reset requested for disabled user {}", user.getId());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        long recent = tokenRepository.countByUserSince(user.getId(), now.minusMinutes(RATE_WINDOW_MINUTES));
        if (recent >= MAX_REQUESTS_PER_WINDOW) {
            // Silent on purpose: an error here would confirm the address exists.
            logger.warn("Password reset rate limit hit for user {} ({} in the last {} min)",
                    user.getId(), recent, RATE_WINDOW_MINUTES);
            return;
        }

        // One live token per user: the previous e-mail stops working now.
        tokenRepository.invalidateLiveTokens(user.getId(), now);

        byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        PasswordResetToken row = new PasswordResetToken();
        row.setUserId(user.getId());
        row.setTokenHash(hashToken(rawToken));
        row.setCreatedAt(now);
        row.setExpiresAt(now.plusMinutes(TOKEN_TTL_MINUTES));
        // Truncated, not trusted: this comes from X-Forwarded-For, so its length
        // is the caller's choice. Unbounded, an over-long value fails the INSERT,
        // rolls the whole request back, and leaves the user told a mail is on its
        // way with no token behind it.
        row.setCreatedIp(truncate(requestIp, MAX_IP_LENGTH));
        tokenRepository.save(row);

        // The only place the raw token leaves this method, and it leaves on
        // another thread AFTER this transaction commits: see the timing note in
        // the class docs, and dispatchOnCommit below. The failure is the
        // OPERATOR's to see, and the dispatcher logs it at ERROR; an install with
        // no working mail server cannot be detected up front without lying, so
        // that line is what tells a self-hoster their SMTP settings, not their
        // password, are the problem.
        dispatchOnCommit(user, rawToken);
        logger.info("Password reset token issued for user {}", user.getId());
    }

    /**
     * Sends only if the transaction that issued the token actually commits.
     *
     * <p>Sending inline would be wrong in a way that is easy to miss. Ordering is
     * not the problem: a commit takes microseconds while delivery takes far
     * longer, so nothing could arrive before the row is visible. ATOMICITY is the
     * problem. If the commit then fails, on a deadlock, a lost connection, or an
     * outer transaction already marked rollback-only, the pool thread has already
     * sent a link whose row never existed. The person receives a reset e-mail
     * whose link answers "not valid", while the log says a token was issued: the
     * two most confusing halves of the flow at once.
     *
     * <p>Falls back to sending immediately when there is no transaction to wait
     * for, so a caller outside one (a test, or a future non-transactional entry
     * point) still gets a mail rather than silence.
     */
    private void dispatchOnCommit(User user, String rawToken) {
        Runnable dispatch = () -> mailer.dispatchResetEmail(
                user.getEmail(), displayName(user), rawToken, TOKEN_TTL_MINUTES, user.getId());

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch.run();
            }
        });
    }

    /**
     * Redeems a token and sets the new password.
     *
     * @return the user id whose password was changed, for the audit line
     * @throws InvalidTokenException if the token is unknown, already used or expired
     * @throws IllegalArgumentException if the new password fails the shared strength rule
     */
    @Transactional
    public Long resetPassword(String rawToken, String newPassword) {
        // The password is checked BEFORE the token is even looked up, and the
        // order is the point twice over.
        //
        // It keeps the "a typo does not cost the link" property, which is why it
        // was already ahead of the claim. It also closes a smaller thing: with
        // the lookup first, a VALID token plus a short password answered "must be
        // at least 8 characters" while an invalid token answered the uniform
        // refusal, so the endpoint told a caller whether a token was genuine
        // without consuming it. Irrelevant at 256 bits of entropy, but the
        // controller's javadoc claims the two 400s only ever describe the
        // requester's own input, and now that is true.
        passwordAuthService.validateNewPassword(newPassword);

        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidTokenException(INVALID_TOKEN_MESSAGE);
        }

        PasswordResetToken row = tokenRepository.findByTokenHash(hashToken(rawToken))
                .orElseThrow(() -> new InvalidTokenException(INVALID_TOKEN_MESSAGE));

        LocalDateTime now = LocalDateTime.now();
        if (!row.isRedeemable(now)) {
            throw new InvalidTokenException(INVALID_TOKEN_MESSAGE);
        }

        // The token is CLAIMED with a conditional update, which is what
        //    makes it single-use. Read-then-write would not be: two requests
        //    arriving together both read usedAt as null under READ COMMITTED and
        //    both proceed. Here the second UPDATE blocks on the first one's row
        //    lock, re-evaluates "used_at IS NULL" against the committed row, and
        //    matches nothing, so it loses the race and says so.
        if (tokenRepository.markUsed(row.getId(), now) == 0) {
            logger.warn("Password reset token for user {} was redeemed twice; the second attempt lost "
                    + "the race and was refused", row.getUserId());
            throw new InvalidTokenException(INVALID_TOKEN_MESSAGE);
        }

        // Only now the write, plus the revoke-every-session consequence that
        // comes with it.
        try {
            passwordAuthService.resetPasswordTo(row.getUserId(), newPassword);
        } catch (IllegalArgumentException e) {
            // The account is gone. The password rule was already checked above,
            // so the only way here is a missing row, and the controller returns
            // an IllegalArgumentException's message verbatim: unhandled, a
            // stranger holding an old link would read "User not found" and learn
            // the account was deleted. The FK added in V487 makes this
            // unreachable by cascading the tokens away with the user; this is the
            // belt to that braces, because the message is the leak, not the row.
            // Note what this does NOT do: resetPasswordTo is @Transactional and
            // joins this transaction, so its exception has already marked the
            // whole thing rollback-only. The markUsed claim above is therefore
            // rolled back and the token stays live until it expires. The outcome
            // is still safe (every later attempt is refused the same way, because
            // the account is still gone or still suspended), but "redeeming burns
            // anything still pending" is not true on this path, and pretending
            // otherwise here would be the kind of comment this change has had to
            // correct three times.
            logger.warn("Password reset token for user {} was redeemed but the account is gone or "
                    + "suspended; the claim rolls back with the transaction", row.getUserId());
            throw new InvalidTokenException(INVALID_TOKEN_MESSAGE);
        }

        // Anything else still pending for this user dies with it.
        tokenRepository.invalidateLiveTokens(row.getUserId(), now);

        logger.info("Password reset completed for user {}", row.getUserId());
        return row.getUserId();
    }

    /** Drops rows whose expiry, and therefore whose audit value, has long passed. */
    @Scheduled(fixedRate = 86_400_000)
    @SchedulerLock(name = "password_reset_token_cleanup", lockAtMostFor = "PT10M")
    @Transactional
    public void cleanupExpiredTokens() {
        int deleted = tokenRepository.deleteExpiredBefore(LocalDateTime.now().minusDays(7));
        if (deleted > 0) {
            logger.info("Cleaned up {} expired password reset tokens", deleted);
        }
    }

    private static String displayName(User user) {
        String first = user.getFirstName();
        if (first != null && !first.isBlank()) return first;
        String email = user.getEmail();
        if (email == null) return "there";
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    /**
     * THE hash, borrowed rather than re-implemented.
     *
     * <p>A second copy of a security primitive is two things to keep in step, and
     * the previous copy here had already drifted in construction (a manual hex
     * loop against {@code HexFormat}) while its comment claimed they matched.
     */
    private static String hashToken(String rawToken) {
        return PasswordAuthService.hashToken(rawToken);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /** Unknown, spent or expired token. Carries a message safe to show a stranger. */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
