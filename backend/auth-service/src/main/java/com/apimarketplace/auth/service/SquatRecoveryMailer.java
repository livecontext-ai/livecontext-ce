package com.apimarketplace.auth.service;

import com.apimarketplace.auth.service.mail.AccountMailCatalog;
import com.apimarketplace.auth.service.mail.BrandedMail;
import com.apimarketplace.auth.service.mail.MailLocaleResolver;
import com.apimarketplace.common.i18n.MessageCatalog;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Sends the "someone tried to register your install" recovery email
 * (doc §1 #41, victim-UX deadlock closure).
 *
 * <p>Failures are logged + swallowed; the audit row ({@code SUSPECTED_CROSS_USER_RESET}) is the
 * durable record so ops can notify the victim out-of-band if email is down.
 *
 * <p>The email body never mentions the attacker's identity (would be a disclosure oracle: the
 * squatter could trigger emails by attempting to register the victim's install_id, then ask the
 * victim what they saw). The audit table holds {@code owned_by_user_id} for forensics; the email
 * holds only the recovery link.
 *
 * <p>Written in the victim's language ({@code auth.users.locale}, V527) through the shared
 * {@code i18n/account-mail} catalog and the {@link BrandedMail} shell - this is a security alert,
 * which is the worst kind of message to send in a language the reader does not follow.
 *
 * <p>The stated expiry is read from the SAME property as the token's own TTL. It used to say "60
 * minutes" in prose while the TTL was configurable, so an operator who shortened it turned this
 * e-mail into a false promise.
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class SquatRecoveryMailer {

    private static final Logger log = LoggerFactory.getLogger(SquatRecoveryMailer.class);

    static final MessageCatalog CATALOG = AccountMailCatalog.INSTANCE;

    private final JavaMailSender mailSender;
    private final MailLocaleResolver locales;
    private final String mailFrom;
    private final String mailFromName;
    private final String frontendUrl;
    private final long tokenTtlMinutes;

    public SquatRecoveryMailer(
            JavaMailSender mailSender,
            MailLocaleResolver locales,
            @Value("${app.mail.from:noreply@livecontext.ai}") String mailFrom,
            @Value("${app.mail.from-name:LiveContext}") String mailFromName,
            @Value("${oauth2.frontend-url:http://localhost:3000}") String frontendUrl,
            @Value("${cloud-link.squat-recovery.token-ttl-minutes:60}") long tokenTtlMinutes) {
        this.mailSender = mailSender;
        this.locales = locales;
        this.mailFrom = mailFrom;
        this.mailFromName = mailFromName;
        this.frontendUrl = frontendUrl;
        this.tokenTtlMinutes = tokenTtlMinutes;
    }

    /**
     * Sends the recovery email. {@code recoveryToken} is embedded in the URL path-segment, not a
     * query param - keeps the secret out of browser and CDN query-string log surfaces.
     *
     * <p>Merged into ONE block deliberately: javadoc keeps only the last comment before a
     * declaration, so a second block here would silently delete the path-segment rationale above
     * - which is the security reason this method looks the way it does.
     *
     * @param knownLocale the language read off the victim's row by the caller, which has it in
     *                    hand. Null falls back to a lookup by address: a second query for
     *                    something already loaded, which answers English if the database is
     *                    unreachable at that moment. This is a SECURITY alert, the worst of the
     *                    five mails to send in a language its reader does not follow.
     */
    public void sendRecoveryEmail(String victimEmail, String recoveryToken, String knownLocale) {
        try {
            String locale = MailLocaleResolver.resolve(locales, knownLocale, victimEmail);
            // Resolved BEFORE the URL is built, because the URL now carries the locale prefix.
            String recoveryUrl = recoveryUrl(frontendUrl, locale, recoveryToken);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom, mailFromName);
            helper.setTo(victimEmail);
            helper.setSubject(BrandedMail.subject(CATALOG.text(locale, "squat.subject")));

            String title = CATALOG.text(locale, "squat.title");
            List<String> paragraphs = List.of(
                    AccountMailCatalog.greeting(locale, null),
                    CATALOG.text(locale, "squat.body"),
                    CATALOG.text(locale, "squat.wasyou"),
                    CATALOG.text(locale, "squat.wasnotyou"));
            String note = CATALOG.text(locale, "squat.expiry",
                    Map.of("minutes", String.valueOf(tokenTtlMinutes)));
            BrandedMail.Button button = new BrandedMail.Button(recoveryUrl, CATALOG.text(locale, "squat.action"));
            BrandedMail.Footer footer = BrandedMail.Footer.of(CATALOG.text(locale, "common.footer"));

            // The sign-off goes in its own SLOT, not at the end of the paragraph list.
            //
            // As a paragraph it rendered above the button - and in the verification mail, between
            // "use the code below" and the code - because the shell emits paragraphs first. Five
            // mails read backwards for it, and nothing asserted block order.
            String signature = CATALOG.text(locale, "common.signature");
            helper.setText(
                    BrandedMail.plain(title, paragraphs, null, note, button, signature, footer),
                    BrandedMail.html(BrandedMail.logoUrl(frontendUrl), locale, null, title, paragraphs, null, note, button, signature, footer));
            mailSender.send(message);
            log.info("SquatRecovery email sent to {}", victimEmail);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            // Best-effort - audit row is the durable record. Don't fail the upstream caller.
            log.warn("Failed to send squat-recovery email to {}", victimEmail, e);
        } catch (Exception unexpected) {
            log.warn("Unexpected error sending squat-recovery email to {}", victimEmail, unexpected);
        }
    }

    /**
     * Builds the squat-recovery consume URL: the unified Cloud page, under {@code /app}, in the
     * reader's language.
     *
     * <p>Two things were wrong here, and both landed on a security path.
     *
     * <p>The {@code /app} segment was MISSING. The page is
     * {@code app/[locale]/app/settings/cloud-account/recover/[token]}, and
     * {@code appRouteAuth.ts} registers the public route as
     * {@code /app/settings/cloud-account/recover}. There is no {@code [locale]/settings} route and
     * no rewrite for one, so the address this method emitted resolved to the not-found page: the
     * victim of an address takeover received a mail whose only call to action was a dead link. The
     * old comment described the shape it wanted rather than the one the app serves, and a test
     * pinned the same wrong string.
     *
     * <p>And it carried no LANGUAGE, so it was the one link the localization pass skipped. An
     * unprefixed path is redirected on the browser's language, not the account's, which undoes the
     * point of translating the mail at all.
     *
     * <p>Package-private so the path contract is unit-tested without mail plumbing.
     */
    static String recoveryUrl(String frontendUrl, String locale, String recoveryToken) {
        return frontendUrl + MessageCatalog.localizedPath(
                locale, "/app/settings/cloud-account/recover/" + recoveryToken);
    }

}
