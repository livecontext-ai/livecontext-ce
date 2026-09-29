package com.apimarketplace.auth.service;

import com.apimarketplace.auth.service.mail.AccountMailCatalog;
import com.apimarketplace.common.i18n.MessageCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The account e-mail catalog ({@code i18n/account-mail_*.properties}): strict parity, and every
 * locale REALLY translated. A missing key falls back to English at runtime, so an English value
 * pasted into {@code de} would pass parity and still show English to German readers.
 *
 * <p>Same shape as {@link NotificationMailCatalogTest}, deliberately: two catalogs with two
 * different ideas of what "translated" means is how one of them drifts.
 */
@DisplayName("Account mail catalog - six locales, parity and real translations")
class AccountMailCatalogTest {

    private static final MessageCatalog CATALOG = AccountDeactivationMailer.CATALOG;

    /** Values that are legitimately the same in every language. Add a proper noun here, never a sentence. */
    private static final Set<String> PROPER_NOUNS = Set.of("LiveContext");

    /** Same as English is allowed only for a proper noun or a value made of placeholders and punctuation. */
    static boolean mayEqualEnglish(String value) {
        if (value == null) return false;
        if (PROPER_NOUNS.contains(value.trim())) return true;
        return value.replaceAll("\\{[A-Za-z_][A-Za-z0-9_]*\\}", "").replaceAll("[\\p{P}\\p{S}\\s]", "").isEmpty();
    }

    @Test
    @DisplayName("every key in all six locales, same placeholders, no dash, nothing empty")
    void parity() {
        assertThat(CATALOG.parityProblems()).isEmpty();
        for (String locale : MessageCatalog.LOCALES) {
            assertThat(CATALOG.keys(locale)).as(locale).containsExactlyInAnyOrderElementsOf(CATALOG.keys("en"));
        }
    }

    @Test
    @DisplayName("every non-English value differs from English (proper nouns and placeholder-only values excepted)")
    void reallyTranslated() {
        for (String locale : MessageCatalog.LOCALES) {
            if ("en".equals(locale)) continue;
            int checked = 0;
            for (String key : CATALOG.keys("en")) {
                String en = CATALOG.raw("en", key);
                if (mayEqualEnglish(en)) continue;
                checked++;
                assertThat(CATALOG.raw(locale, key)).as(locale + " " + key + " is still English").isNotEqualTo(en);
            }
            assertThat(checked).as(locale + ": the check must actually compare something").isPositive();
        }
    }

    @Test
    @DisplayName("every key the mailers ask for exists in English, so nothing renders as a bare key name")
    void everyKeyUsedIsDefined() {
        // MessageCatalog.raw falls back to the KEY ITSELF when a key is absent, which would put
        // "deactivated.retention" in front of a customer instead of a sentence. Listing the keys
        // the code asks for is what turns that silent fallback into a failing test.
        //
        // The list is checked against what the mailers ACTUALLY ask for, read out of their
        // source below. On its own it connects to nothing: add a CATALOG.text(..., "reset.new")
        // to a mailer and a hand-typed list stays green while the reader gets the literal string
        // "reset.new" in their mail, which is the exact silent fallback this test is named for.
        Set<String> used = Set.of(
                "common.greeting", "common.greetingNoName", "common.signature", "common.footer",
                "verify.subject", "verify.preheader", "verify.title", "verify.intro",
                "verify.expiry", "verify.footer",
                "invite.subject", "invite.preheader", "invite.title", "invite.body",
                "invite.action", "invite.fallback", "invite.footer",
                "deactivated.subject", "deactivated.title", "deactivated.body",
                "deactivated.retention", "deactivated.undo", "deactivated.action",
                "deactivated.footer",
                "restored.subject", "restored.title", "restored.body", "restored.notyou",
                "restored.footer",
                "purged.subject", "purged.title", "purged.body", "purged.detail", "purged.welcome",
                "purged.action", "purged.footer",
                "reset.subject", "reset.title", "reset.body", "reset.action",
                "reset.expiry", "reset.ignore", "reset.fallback",
                "squat.subject", "squat.title", "squat.body", "squat.wasyou", "squat.wasnotyou",
                "squat.action", "squat.expiry");

        assertThat(CATALOG.keys("en")).containsAll(used);
        // And nothing is defined that no mailer asks for: a stale key is six translations of
        // something nobody reads, and it hides which text a change actually affects.
        assertThat(CATALOG.keys("en")).containsExactlyInAnyOrderElementsOf(used);

        // Every key the mailers name as a LITERAL is in the list above, read from their own source
        // so the list cannot quietly fall behind them. Containment, not equality: three of these
        // mails build their keys from a prefix (`keyPrefix + ".subject"`, a label key passed as a
        // parameter), and no source scan can resolve those - claiming equality would fail on exactly
        // the keys it cannot see. The other direction, a catalog key no mailer asks for, is covered
        // by the exact-match assertion above.
        assertThat(used)
                .as("keys the mailers name literally, vs the list this test maintains")
                .containsAll(keysAskedForInSource());
    }

    /**
     * Every catalog key literal the five account mailers pass to {@code text(...)}/{@code raw(...)}.
     *
     * <p>Read from source because there is no runtime seam: the calls are spread over private
     * methods behind three different public entry points, several of them only reachable with a
     * mail server, so exercising them all to observe the keys would be a bigger fiction than
     * reading them. The theme test reads its {@code .ftl} templates the same way and for the same
     * reason.
     */
    private static Set<String> keysAskedForInSource() {
        // Six files, not five: `common.greeting` and `common.greetingNoName` are now read ONLY by
        // `mail/AccountMailCatalog.java`, because the greeting was extracted out of the mailers. The
        // scan exists so the hand-typed list below cannot fall behind the code, and the extraction
        // moved two keys out of everything it looks at - so a new key added there would have been
        // invisible to it, and the vacuity guard at the end cannot notice (the other five supply
        // plenty).
        java.util.List<String> files = java.util.List.of(
                "AccountDeactivationMailer.java", "OrganizationInvitationMailer.java",
                "SquatRecoveryMailer.java", "PasswordResetMailer.java", "EmailVerificationService.java",
                "mail/AccountMailCatalog.java");
        // The locale argument is any expression, and a key is any dotted name.
        //
        // It used to require a bare identifier for the locale and exactly `letters.letters` for the
        // key, so `CATALOG.text(resolveLocale(email, known), "reset.new")` - the shape this branch
        // introduced in three mailers - and any key with a digit or a third segment simply fell out
        // of the scan. Silently: this helper exists precisely so the hand-typed list below cannot
        // fall behind the code, and a key it stops seeing is a key nobody checks the translations
        // of. The vacuity guard at the end only proves SOME keys were found, not the new one.
        java.util.regex.Pattern call = java.util.regex.Pattern.compile(
                "(?:text|raw)\\((?:[^()\"]|\\([^()]*\\))*?,\\s*\"([a-zA-Z][a-zA-Z0-9]*(?:\\.[a-zA-Z0-9]+)+)\"");
        Set<String> found = new java.util.TreeSet<>();
        for (String file : files) {
            java.nio.file.Path path = mailerSource(file);
            String source;
            try {
                source = java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new AssertionError("could not read " + path, e);
            }
            java.util.regex.Matcher matcher = call.matcher(source);
            while (matcher.find()) found.add(matcher.group(1));
        }
        // A regex that silently matched nothing would make this test vacuous, which is the
        // failure mode of every source-reading test.
        assertThat(found).as("no catalog keys found in the mailer sources").isNotEmpty();
        return found;
    }

    /** The mailer source on disk, found by walking up to the repository root. */
    private static java.nio.file.Path mailerSource(String fileName) {
        String relative = "backend/auth-service/src/main/java/com/apimarketplace/auth/service/";
        java.nio.file.Path here = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (java.nio.file.Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            java.nio.file.Path file = candidate.resolve(relative + fileName);
            if (java.nio.file.Files.isRegularFile(file)) return file;
        }
        throw new AssertionError("could not locate " + relative + fileName + " from " + here);
    }

    @Test
    @DisplayName("every account mailer holds the SAME catalog object, not a copy that can drift")
    void oneCatalogForEveryAccountMail() {
        // Identity, not equality of contents: four mailers used to load the file independently,
        // which is four parsed copies and a test whose only job was to check they still agreed.
        assertThat(OrganizationInvitationMailer.CATALOG).isSameAs(AccountMailCatalog.INSTANCE);
        assertThat(AccountDeactivationMailer.CATALOG).isSameAs(AccountMailCatalog.INSTANCE);
        assertThat(SquatRecoveryMailer.CATALOG).isSameAs(AccountMailCatalog.INSTANCE);
        assertThat(EmailVerificationService.MAIL_CATALOG).isSameAs(AccountMailCatalog.INSTANCE);
        // The reset mail is the fifth, and the one most easily forgotten here: it lives outside
        // the service.mail package with the others, so a sweep of that package misses it.
        assertThat(PasswordResetMailer.CATALOG).isSameAs(AccountMailCatalog.INSTANCE);
    }
}
