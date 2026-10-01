package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PartnerApplication;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.i18n.MessageCatalog;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The decision mail an applicant receives: in their account's language, with their code and rate
 * on approval, the admin's note on rejection, never to an address that is not theirs to receive,
 * and never a thrown exception (the decision is already committed).
 */
@DisplayName("PartnerProgramMailer")
class PartnerProgramMailerTest {

    private JavaMailSender mailSender;
    private UserRepository userRepository;
    private PartnerProgramMailer mailer;
    private User user;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getDefaultInstance(new Properties())));
        userRepository = mock(UserRepository.class);
        user = new User();
        user.setId(7L);
        user.setEmail("p@acme.io");
        user.setEmailVerified(true);
        user.setLocale("fr");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        mailer = new PartnerProgramMailer(mailSender, userRepository, "noreply@example.com", "LiveContext",
                "https://app.example.com/");
    }

    private static PartnerApplication decided(PartnerApplication.Status status, String note) {
        PartnerApplication a = new PartnerApplication();
        a.setId(5L);
        a.setUserId(7L);
        a.setStatus(status);
        a.setCompanyName("Acme <Automation>");
        a.setDecisionNote(note);
        return a;
    }

    private String sentPayload() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage m = captor.getValue();
        StringBuilder text = new StringBuilder(m.getSubject()).append('\n');
        collectText(m.getContent(), text);
        return text.toString();
    }

    /** The HTML alternative only: the one a mail client renders. */
    private String sentHtml() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        StringBuilder all = new StringBuilder();
        collectHtml(captor.getValue().getContent(), all);
        return all.toString();
    }

    private static void collectHtml(Object content, StringBuilder out) throws Exception {
        if (content instanceof jakarta.mail.Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) collectHtml(multipart.getBodyPart(i).getContent(), out);
        } else if (content instanceof String s && s.stripLeading().startsWith("<")) {
            out.append(s);
        }
    }

    /** Every decoded text part (plain and HTML), walked by content type like the sibling mailer tests. */
    private static void collectText(Object content, StringBuilder out) throws Exception {
        if (content instanceof jakarta.mail.Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) collectText(multipart.getBodyPart(i).getContent(), out);
        } else if (content instanceof String s) {
            out.append(s).append('\n');
        }
    }

    @Test
    @DisplayName("approval: French for a French account, with the code, the rate and a link to the dashboard")
    void approvalInAccountLanguage() throws Exception {
        assertThat(mailer.sendDecision(decided(PartnerApplication.Status.APPROVED, null), "ACME", 50.0, 12)).isTrue();

        String payload = sentPayload();
        assertThat(payload).contains("Bienvenue dans le programme partenaire LiveContext");
        assertThat(payload).contains("ACME");
        assertThat(payload).contains("50 %");
        // The 12-month limit is part of the offer: the mail states it.
        assertThat(payload).contains("pendant 12 mois par client");
        assertThat(payload).contains("https://app.example.com/fr/app/settings/partner");
        // The company name is user-typed: the HTML part carries it escaped, never as markup.
        assertThat(payload).contains("Acme &lt;Automation&gt;");
    }

    @Test
    @DisplayName("rejection: the admin's note is included; without a note there is no empty note line")
    void rejectionCarriesNote() throws Exception {
        user.setLocale("en");
        mailer.sendDecision(decided(PartnerApplication.Status.REJECTED, "Come back with a first client"), null, 0, 0);
        assertThat(sentPayload()).contains("Our note: Come back with a first client")
                .contains("Your LiveContext partner application");

        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getDefaultInstance(new Properties())));
        mailer.sendDecision(decided(PartnerApplication.Status.REJECTED, null), null, 0, 0);
        assertThat(sentPayload()).doesNotContain("Our note:");
    }

    @Test
    @DisplayName("never mails an unverified, deactivated or missing address, nor a still-pending application")
    void refusesUnusableAddresses() {
        user.setEmailVerified(false);
        assertThat(mailer.sendDecision(decided(PartnerApplication.Status.APPROVED, null), "ACME", 50, 12)).isFalse();

        user.setEmailVerified(true);
        user.setDeactivatedAt(LocalDateTime.now());
        assertThat(mailer.sendDecision(decided(PartnerApplication.Status.APPROVED, null), "ACME", 50, 12)).isFalse();

        user.setDeactivatedAt(null);
        assertThat(mailer.sendDecision(decided(PartnerApplication.Status.PENDING, null), "ACME", 50, 12)).isFalse();

        when(userRepository.findById(7L)).thenReturn(Optional.empty());
        assertThat(mailer.sendDecision(decided(PartnerApplication.Status.APPROVED, null), "ACME", 50, 12)).isFalse();

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("regression: a failing user lookup is reported as false, never thrown after the decision committed")
    void lookupFailureIsSwallowed() {
        when(userRepository.findById(7L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        assertThat(mailer.sendDecision(decided(PartnerApplication.Status.APPROVED, null), "ACME", 50, 12)).isFalse();
    }

    @Test
    @DisplayName("typed text cannot restyle the mail: stars are dropped, markup is escaped")
    void hostileCompanyNameAndNote() throws Exception {
        user.setLocale("en");
        PartnerApplication a = decided(PartnerApplication.Status.REJECTED, "*urgent* <b>bold</b>");
        a.setCompanyName("*Acme* <script>alert(1)</script>");

        mailer.sendDecision(a, null, 0, 0);

        String html = sentHtml();
        // The catalog decides emphasis: the applicant's stars would have become <strong>.
        assertThat(html).doesNotContain("<strong>Acme</strong>").doesNotContain("<strong>urgent</strong>");
        // Rendered HTML: typed markup is escaped, never live. (The text/plain part carries it
        // verbatim, which is harmless: nothing renders it.)
        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
        assertThat(html).doesNotContain("<b>bold</b>").contains("&lt;b&gt;bold&lt;/b&gt;");
    }

    @Test
    @DisplayName("the approval mail treats a hostile company name the same way: no restyling, escaped markup")
    void hostileCompanyNameInApproval() throws Exception {
        user.setLocale("en");
        PartnerApplication a = decided(PartnerApplication.Status.APPROVED, null);
        a.setCompanyName("*Acme* <script>alert(1)</script>");

        mailer.sendDecision(a, "ACME", 50, 12);

        String html = sentHtml();
        assertThat(html).doesNotContain("<strong>Acme</strong>").doesNotContain("<script>").contains("&lt;script&gt;");
        // The catalog's own emphasis (the code) still renders.
        assertThat(html).contains("<strong>ACME</strong>");
    }

    @Test
    @DisplayName("a mail server failure is reported as false, never thrown")
    void failureIsSwallowed() {
        doThrow(new MailSendException("down")).when(mailSender).send(any(MimeMessage.class));

        assertThat(mailer.sendDecision(decided(PartnerApplication.Status.APPROVED, null), "ACME", 50, 12)).isFalse();
    }

    @Test
    @DisplayName("a whole percent reads without decimals, a fractional one in the reader number format")
    void percentFormat() {
        assertThat(PartnerProgramMailer.formatPercent(50.0, "en")).isEqualTo("50");
        assertThat(PartnerProgramMailer.formatPercent(12.5, "en")).isEqualTo("12.5");
        assertThat(PartnerProgramMailer.formatPercent(12.5, "fr")).isEqualTo("12,5");
    }

    @Test
    @DisplayName("catalog: every key in all six locales, same placeholders, no dash, nothing empty")
    void catalogParity() {
        assertThat(PartnerProgramMailer.CATALOG.parityProblems()).isEmpty();
        for (String locale : MessageCatalog.LOCALES) {
            assertThat(PartnerProgramMailer.CATALOG.keys(locale)).as(locale)
                    .containsExactlyInAnyOrderElementsOf(PartnerProgramMailer.CATALOG.keys("en"));
        }
    }

    @Test
    @DisplayName("catalog: every non-English value is really translated")
    void catalogReallyTranslated() {
        Set<String> sameEverywhere = Set.of();
        for (String locale : MessageCatalog.LOCALES) {
            if ("en".equals(locale)) continue;
            for (String key : PartnerProgramMailer.CATALOG.keys("en")) {
                if (sameEverywhere.contains(key)) continue;
                assertThat(PartnerProgramMailer.CATALOG.raw(locale, key)).as(locale + " " + key + " is still English")
                        .isNotEqualTo(PartnerProgramMailer.CATALOG.raw("en", key));
            }
        }
    }
}
