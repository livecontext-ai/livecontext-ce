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
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * The three e-mails of the account lifecycle: deactivated, restored, and data permanently
 * deleted.
 *
 * <p>Written in the language of the account the address belongs to ({@code auth.users.locale},
 * V527), through the shared {@code i18n/account-mail} catalog and the shared
 * {@link BrandedMail} shell. Before that, all three carried their own copy of the same HTML
 * table and said {@code <html lang="en">} to every reader.
 *
 * <p>The grace period is stated ONCE, from {@link UserService#ACCOUNT_GRACE_PERIOD_DAYS}: the
 * three texts used to spell "30 days" by hand, so a change to the policy would have left at
 * least one e-mail promising the old one.
 */
@Service
public class AccountDeactivationMailer {

    private static final Logger logger = LoggerFactory.getLogger(AccountDeactivationMailer.class);

    static final MessageCatalog CATALOG = AccountMailCatalog.INSTANCE;

    private final JavaMailSender mailSender;
    private final MailLocaleResolver locales;
    private final String mailFrom;
    private final String mailFromName;
    private final String logoUrl;

    /**
     * Where a reader writes if one of these mails is about an account they did not deactivate.
     *
     * <p>A property, not a constant. This bean is unconditional, so it runs in a self-hosted
     * install too, and a hard-coded address told those users to write to the cloud vendor about
     * their own server. Every other address in this constructor was already configurable.
     */
    private final String supportEmail;

    /** Where a reader goes to act on one of these mails: sign in again, or start over. */
    private final String frontendUrl;

    public AccountDeactivationMailer(
            JavaMailSender mailSender,
            MailLocaleResolver locales,
            @Value("${app.mail.from:noreply@livecontext.ai}") String mailFrom,
            @Value("${app.mail.from-name:LiveContext}") String mailFromName,
            @Value("${oauth2.frontend-url:http://localhost:3000}") String frontendUrl,
            @Value("${app.mail.support:support@livecontext.ai}") String supportEmail) {
        this.mailSender = mailSender;
        this.locales = locales;
        this.mailFrom = mailFrom;
        this.mailFromName = mailFromName;
        this.logoUrl = BrandedMail.logoUrl(frontendUrl);
        this.supportEmail = supportEmail;
        this.frontendUrl = frontendUrl;
    }

    /**
     * @param knownLocale the language read off the account by the caller, which still has the row.
     *                    Null falls back to a lookup by address, which is not equivalent: it is a
     *                    second query for something the caller already has, and it answers English
     *                    if the database is unreachable at that moment.
     */
    public void sendDeactivationEmail(String email, String displayName, String knownLocale) {
        String locale = resolveLocale(email, knownLocale);
        String days = String.valueOf(UserService.ACCOUNT_GRACE_PERIOD_DAYS);
        // With a button, because the paragraph below tells them to sign in again and a mail that
        // says that with nothing to click is a dead end: this reader has just left, and the one
        // place they could act from is the product they no longer have open.
        send(email, locale, "deactivated", button(locale, "deactivated.action", SIGN_IN_PATH), List.of(
                AccountMailCatalog.greeting(locale, displayName),
                CATALOG.text(locale, "deactivated.body"),
                CATALOG.text(locale, "deactivated.retention", Map.of("days", days)),
                CATALOG.text(locale, "deactivated.undo", Map.of("days", days, "supportEmail", supportEmail))
        ));
    }

    /**
     * Confirms that a scheduled deletion was cancelled. Sent on every genuine restore, because
     * the deactivation e-mail promised the account would be erased: if that promise is undone,
     * the person needs to know - not least so an unexpected restore is visible to them.
     *
     * @param knownLocale as in {@link #sendDeactivationEmail}: the caller holds the row.
     */
    public void sendRestorationEmail(String email, String displayName, String knownLocale) {
        String locale = resolveLocale(email, knownLocale);
        // No button, and that is deliberate rather than an oversight: this mail reports that
        // nothing happened. It asks the reader to do nothing, so there is nothing to offer them
        // except a way to contact us, which the body already carries.
        send(email, locale, "restored", null, List.of(
                AccountMailCatalog.greeting(locale, displayName),
                CATALOG.text(locale, "restored.body"),
                CATALOG.text(locale, "restored.notyou", Map.of("supportEmail", supportEmail))
        ));
    }

    /**
     * @param knownLocale the account's language, captured by the caller BEFORE the purge. This is
     *                    the one mail sent after the row is gone, so looking the address up here
     *                    would always answer English. Null falls back to the lookup, which is
     *                    correct for any caller that still has the account.
     */
    public void sendPurgeConfirmationEmail(String email, String displayName, String knownLocale) {
        String locale = resolveLocale(email, knownLocale);
        // With a button: "you are welcome to create a new account" is an invitation, and an
        // invitation with no way to accept it is just an apology.
        send(email, locale, "purged", button(locale, "purged.action", REGISTER_PATH), List.of(
                AccountMailCatalog.greeting(locale, displayName),
                CATALOG.text(locale, "purged.body",
                        Map.of("days", String.valueOf(UserService.ACCOUNT_GRACE_PERIOD_DAYS))),
                CATALOG.text(locale, "purged.detail"),
                CATALOG.text(locale, "purged.welcome")
        ));
    }

    /**
     * The language this mail is written in: what the caller read off the account, else a lookup by
     * address, else English.
     *
     * <p>One helper for all three mails, so they cannot drift: the purge mail had this logic
     * inline (it is the one mail sent AFTER the row is gone, where a lookup can only ever answer
     * English) while its two siblings went straight to the lookup.
     */
    private String resolveLocale(String email, String knownLocale) {
        return MailLocaleResolver.resolve(locales, knownLocale, email);
    }


    /**
     * Where the deactivation mail sends a reader: the app, which bounces them to sign-in and then
     * offers to restore the account. The whole point of that mail is that signing in undoes it.
     */
    private static final String SIGN_IN_PATH = "/app";

    /**
     * Where the PURGE mail sends a reader, and it cannot be the same place.
     *
     * <p>That button reads "Create a new account" in all six languages, and it pointed at
     * {@code /app} - the app root, which redirects to sign-in. The account it would sign into no
     * longer exists: this mail is sent because the grace period ended and every row was deleted.
     * So the one mail whose reader has nothing left to sign into was the one being sent to the
     * login page, under a button promising registration.
     */
    private static final String REGISTER_PATH = "/register";

    /** The action button for a mail that has one, in the reader's language. */
    private BrandedMail.Button button(String locale, String labelKey, String path) {
        // Language-prefixed, not the bare host: these routes live under `app/[locale]/`, so an
        // unprefixed link is redirected on the BROWSER's language rather than the account's, which
        // would undo the translation of the mail around it. A no-op for English.
        return new BrandedMail.Button(
                frontendUrl + MessageCatalog.localizedPath(locale, path),
                CATALOG.text(locale, labelKey));
    }

    /**
     * One send, one log line, and never a thrown exception: these e-mails follow an action that
     * has already happened (the account IS deactivated, the data IS gone), so failing to announce
     * it must not fail the operation that caused it.
     *
     * <p>This comment used to sit above {@code button(...)}, where javadoc keeps only the last
     * block before a declaration - so the one explaining the swallow policy was attached to
     * nothing and would have vanished from any generated doc.
     */
    private void send(String email, String locale, String keyPrefix,
                      BrandedMail.Button button, List<String> paragraphs) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom, mailFromName);
            helper.setTo(email);
            String subject = BrandedMail.subject(CATALOG.text(locale, keyPrefix + ".subject"));
            String title = CATALOG.text(locale, keyPrefix + ".title");
            // The footer belongs to THIS mail, keyed off the same prefix as everything else in it.
            //
            // All three shared common.footer for a while, which reads "you are receiving this email
            // at the address on your LiveContext account" - sent moments after the purge deleted every
            // row of that account. Three distinct sentences had been collapsed into one generic and,
            // on the last of the three, false one.
            BrandedMail.Footer footer =
                    BrandedMail.Footer.of(CATALOG.text(locale, keyPrefix + ".footer"));
            helper.setSubject(subject);
            // The sign-off goes in its own SLOT, not at the end of the paragraph list.
            //
            // As a paragraph it rendered above the button - and in the verification mail, between
            // "use the code below" and the code - because the shell emits paragraphs first. Five
            // mails read backwards for it, and nothing asserted block order.
            String signature = CATALOG.text(locale, "common.signature");
            helper.setText(
                    BrandedMail.plain(title, paragraphs, null, null, button, signature, footer),
                    // The support address is the one value here the PRODUCT chose, so it is the one
                    // address allowed to become a link. Everything else in these paragraphs is a name
                    // somebody typed.
                    BrandedMail.html(logoUrl, locale, null, title, paragraphs, null, null, button, signature, footer,
                            supportEmail));
            mailSender.send(message);
            logger.info("Account {} email sent to {}", keyPrefix, email);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            logger.warn("Failed to send account {} email to {}", keyPrefix, email, e);
        } catch (Exception e) {
            logger.warn("Unexpected error sending account {} email to {}", keyPrefix, email, e);
        }
    }
}
