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
 * Sends organization invitation emails.
 *
 * <p>Since PR4 (Q2=a explicit consent), the mailer is called from
 * {@link OrganizationMemberService#inviteMember} for EVERY invitation - both new-user and
 * existing-user paths take the click-to-accept route. The silent auto-accept branch was removed;
 * users no longer end up in organizations they didn't explicitly join. A send failure is logged +
 * swallowed - the invitation row stays {@code PENDING} for its 7-day TTL and the user can accept
 * via either the email link or the /app/invitations inbox (PR4b).
 *
 * <p>Language: the invitee's, when the address already belongs to an account
 * ({@code auth.users.locale}, V527), English otherwise. An invitation is the one account e-mail
 * that routinely reaches somebody who has no account yet, so English is a real outcome here and
 * not a fallback worth engineering around: nothing on the request says what they read.
 *
 * <p>Body and shell come from the shared {@code i18n/account-mail} catalog and
 * {@link BrandedMail}. The inviter, organization and role are interpolated into the translated
 * sentence and escaped by the shell; the {@code *stars*} in the catalog are what keeps them bold,
 * without letting a hostile organization name inject markup.
 */
@Service
public class OrganizationInvitationMailer {

    private static final Logger logger = LoggerFactory.getLogger(OrganizationInvitationMailer.class);

    static final MessageCatalog CATALOG = AccountMailCatalog.INSTANCE;

    private final JavaMailSender mailSender;
    private final MailLocaleResolver locales;
    private final String mailFrom;
    private final String mailFromName;
    private final String frontendUrl;

    public OrganizationInvitationMailer(
            JavaMailSender mailSender,
            MailLocaleResolver locales,
            @Value("${app.mail.from:noreply@livecontext.ai}") String mailFrom,
            @Value("${app.mail.from-name:LiveContext}") String mailFromName,
            // Aligns with the actual key used everywhere else (EmailVerificationService,
            // OAuth2Controller, OAuth2Service, application.yml). The earlier draft used
            // "app.frontend.url" which is undefined -> mail would have sent localhost URLs in
            // prod. P0 fix from PR-3 audit.
            @Value("${oauth2.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.mailSender = mailSender;
        this.locales = locales;
        this.mailFrom = mailFrom;
        this.mailFromName = mailFromName;
        this.frontendUrl = frontendUrl;
    }

    /**
     * Send an invitation email. Best-effort: on failure we log and swallow so the inviteMember
     * business path always returns the persisted invitation row to the caller.
     *
     * @param email       invitee email
     * @param orgName     organisation display name shown in the body
     * @param inviterName who sent the invite (display name)
     * @param token       opaque invitation token (query-param in the accept URL)
     * @param role        role being granted (display only - server still enforces it)
     * @param knownLocale the language on the invitee's row when the caller already holds it, null
     *                    when the address has no account yet (the common case for an invitation,
     *                    and the reason the lookup below stays). This mailer was the last of five
     *                    still going to the database for a language its caller had in hand one
     *                    line above the call: a second query, answering English if the connection
     *                    hiccups between the invitation row being written and the mail composed.
     */
    public void sendInvitationEmail(String email, String orgName, String inviterName,
                                    String token, String role, String knownLocale) {
        try {
            // The link carries the account's LANGUAGE, like the mail around it.
            //
            // These routes live under `app/[locale]/`, and an unprefixed path is redirected by the
            // proxy, which picks the locale from the NEXT_LOCALE cookie or Accept-Language. So an
            // account whose stored language is French, opened on a browser that advertises English,
            // got a French mail whose only call to action landed on the English page - exactly the
            // cross-device case this feature exists for. `MessageCatalog.localizedPath` is what the
            // sibling NotificationMailer already uses, and it is a no-op for English.
            String locale = MailLocaleResolver.resolve(locales, knownLocale, email);
            String acceptUrl = frontendUrl
                    + MessageCatalog.localizedPath(locale, "/invitations/accept")
                    + "?token=" + token;
            String org = orgName == null ? "" : orgName;

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom, mailFromName);
            helper.setTo(email);
            // plainValue for the same reason as the body below: the emphasis markers in a translated
            // sentence are the catalog's, so an organisation literally named "*Star* Labs" would
            // otherwise carry its own into the subject line.
            helper.setSubject(BrandedMail.subject(
                    CATALOG.text(locale, "invite.subject", Map.of("org", BrandedMail.plainValue(org)))));

            String title = CATALOG.text(locale, "invite.title");
            // The emphasis markers in the sentence are the catalog's. A value that brings one of
            // its own ("*Star* Labs") would shift every bold boundary after it, so the stars are
            // stripped from what people typed and kept in what we wrote.
            List<String> paragraphs = List.of(
                    CATALOG.text(locale, "invite.body", Map.of(
                            "inviter", BrandedMail.plainValue(inviterName),
                            "org", BrandedMail.plainValue(org),
                            "role", BrandedMail.plainValue(role))));
            String note = CATALOG.text(locale, "invite.fallback", Map.of("url", acceptUrl));
            BrandedMail.Button button = new BrandedMail.Button(acceptUrl, CATALOG.text(locale, "invite.action"));
            BrandedMail.Footer footer = BrandedMail.Footer.of(CATALOG.text(locale, "invite.footer"));

            // The PREHEADER, which the shell supports and this mail silently lost.
            //
            // The template this replaced emitted one; the extraction passed null and no
            // `invite.preheader` key existed, so every invitation preview line became the first words
            // of the body ("Alice invited you to join...") instead of the teaser. Exactly two mails
            // had a preheader before this branch and exactly one of them kept it.
            String preheader = CATALOG.text(locale, "invite.preheader",
                    Map.of("org", BrandedMail.plainValue(org)));

            // The sign-off goes in its own SLOT, not at the end of the paragraph list.
            //
            // As a paragraph it rendered above the button - and in the verification mail, between
            // "use the code below" and the code - because the shell emits paragraphs first. Five
            // mails read backwards for it, and nothing asserted block order.
            String signature = CATALOG.text(locale, "common.signature");
            helper.setText(
                    BrandedMail.plain(title, paragraphs, null, note, button, signature, footer),
                    BrandedMail.html(BrandedMail.logoUrl(frontendUrl), locale, preheader, title,
                            paragraphs, null, note, button, signature, footer));
            mailSender.send(message);
            logger.info("Invitation email sent to {} for org {}", email, orgName);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            // Don't bubble - invitation row stays PENDING and the user can accept via the
            // /app/invitations inbox (PR4b) even without the email. WARN with stack so ops can
            // diagnose SMTP drift.
            logger.warn("Failed to send invitation email to {} for org {}", email, orgName, e);
        } catch (Exception e) {
            logger.warn("Unexpected error sending invitation email to {}", email, e);
        }
    }


}
