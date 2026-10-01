package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PartnerApplication;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.mail.BrandedMail;
import com.apimarketplace.common.i18n.MessageCatalog;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tells an applicant the admin's decision on their partner application, in the language of
 * their account ({@code i18n/partner-mail_*.properties}), inside the shared {@link BrandedMail}
 * shell.
 *
 * <p>Never throws: the decision is already committed when this runs, so a mail failure is
 * logged and the applicant still sees the outcome on their partner dashboard. Refuses the same
 * addresses {@link NotificationMailer} refuses (deactivated account, unverified address).
 */
@Service
public class PartnerProgramMailer {

    private static final Logger log = LoggerFactory.getLogger(PartnerProgramMailer.class);
    static final MessageCatalog CATALOG = MessageCatalog.load("i18n/partner-mail");
    static final String DASHBOARD_PATH = "/app/settings/partner";

    private final JavaMailSender mailSender;
    private final UserRepository userRepository;
    private final String mailFrom;
    private final String mailFromName;
    private final String frontendUrl;
    private final String logoUrl;

    public PartnerProgramMailer(JavaMailSender mailSender,
                                UserRepository userRepository,
                                @Value("${app.mail.from:noreply@livecontext.ai}") String mailFrom,
                                @Value("${app.mail.from-name:LiveContext}") String mailFromName,
                                @Value("${oauth2.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.mailSender = mailSender;
        this.userRepository = userRepository;
        this.mailFrom = mailFrom;
        this.mailFromName = mailFromName;
        this.frontendUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        this.logoUrl = BrandedMail.logoUrl(frontendUrl);
    }

    /**
     * @param commissionMonths how long the partner earns per customer (approval only)
     * @return true when a mail was handed to the mail server; never throws
     */
    public boolean sendDecision(PartnerApplication application, String code, double commissionPercent,
                                int commissionMonths) {
        if (application == null || application.getStatus() == PartnerApplication.Status.PENDING) return false;
        try {
            return send(application, code, commissionPercent, commissionMonths);
        } catch (Exception e) {
            // The decision is already committed: a lookup or mail failure must not turn it into a 500.
            log.warn("Partner decision mail for application {} not sent: {}", application.getId(), e.getMessage());
            return false;
        }
    }

    private boolean send(PartnerApplication application, String code, double commissionPercent,
                         int commissionMonths) throws Exception {
        Optional<User> found = userRepository.findById(application.getUserId());
        if (found.isEmpty()) return false;
        User user = found.get();
        if (user.getDeactivatedAt() != null || user.getEmail() == null || user.getEmail().isBlank()
                || !user.isEmailVerified()) {
            log.info("Partner decision mail not sent for application {}: no usable address", application.getId());
            return false;
        }
        String locale = MessageCatalog.normalizeLocale(user.getLocale());
        boolean approved = application.getStatus() == PartnerApplication.Status.APPROVED;
        String prefix = approved ? "approved" : "rejected";
        List<String> paragraphs = new ArrayList<>();
        paragraphs.add(CATALOG.text(locale, "common.greeting"));
        if (approved) {
            // plainValue: the emphasis stars are the catalog's; a company literally named "*Acme*"
            // must not bring its own (same rule as OrganizationInvitationMailer).
            paragraphs.add(CATALOG.text(locale, "approved.intro", Map.of(
                    "company", BrandedMail.plainValue(application.getCompanyName()),
                    "percent", formatPercent(commissionPercent, locale),
                    "months", String.valueOf(commissionMonths))));
            paragraphs.add(CATALOG.text(locale, "approved.code", Map.of("code", code == null ? "" : code)));
            paragraphs.add(CATALOG.text(locale, "approved.next"));
        } else {
            paragraphs.add(CATALOG.text(locale, "rejected.intro",
                    Map.of("company", BrandedMail.plainValue(application.getCompanyName()))));
            if (application.getDecisionNote() != null && !application.getDecisionNote().isBlank()) {
                paragraphs.add(CATALOG.text(locale, "rejected.note",
                        Map.of("note", BrandedMail.plainValue(application.getDecisionNote()))));
            }
            paragraphs.add(CATALOG.text(locale, "rejected.next"));
        }
        BrandedMail.Button button = new BrandedMail.Button(
                frontendUrl + MessageCatalog.localizedPath(locale, DASHBOARD_PATH),
                CATALOG.text(locale, prefix + ".action"));
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(mailFrom, mailFromName);
        helper.setTo(user.getEmail());
        helper.setSubject(BrandedMail.subject(CATALOG.text(locale, prefix + ".subject")));
        String title = CATALOG.text(locale, prefix + ".title");
        String signature = CATALOG.text(locale, "common.signature");
        BrandedMail.Footer footer = BrandedMail.Footer.of(CATALOG.text(locale, "common.footer"));
        helper.setText(
                BrandedMail.plain(title, paragraphs, null, null, button, signature, footer),
                BrandedMail.html(logoUrl, locale, null, title, paragraphs, null, null, button, signature, footer));
        mailSender.send(message);
        log.info("Partner {} mail sent for application {}", prefix, application.getId());
        return true;
    }

    /**
     * The rate in the reader's number format: 50 reads "50", 12.5 reads "12.5" in English and
     * "12,5" in French. Never a trailing ".0".
     */
    static String formatPercent(double percent, String locale) {
        java.text.NumberFormat format = java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag(locale));
        format.setMaximumFractionDigits(2);
        format.setGroupingUsed(false);
        return format.format(percent);
    }
}
