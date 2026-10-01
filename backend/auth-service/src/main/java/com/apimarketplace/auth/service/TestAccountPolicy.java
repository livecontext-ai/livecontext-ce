package com.apimarketplace.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which accounts are TEST accounts: the daily sign-up canary and the addresses used to replay
 * onboarding by hand (Keycloak, Google, GitHub). A test account asking for deletion is purged at
 * once instead of after the 30-day grace period, through the same purge a real account gets, so
 * the same address can sign up again the next minute and every sign-up replays the whole journey.
 *
 * <p>Off unless {@code account.test-accounts.email-pattern} is set (a regex matched against the
 * whole lower-cased e-mail). A pattern broad enough to match an ordinary address is refused at
 * startup: it would silently take the grace period away from real customers.
 */
@Component
public class TestAccountPolicy {

    /**
     * Addresses no test pattern may match. Matching one means the pattern is too broad. They cover
     * the usual slips: a wildcard local part, a whole mail provider, and every plus-address (a
     * test family written as {@code .*\+.*@...} instead of one owner's tag).
     */
    static final String[] ORDINARY_ADDRESSES = {
            "someone@example.com", "user@example.com", "user@example.com",
            "john.smith@yahoo.com", "user@example.com", "user@example.com",
            "user@example.com", "ops.team@company.co", "not.a.test@livecontext.ai"
    };

    private final Pattern pattern;

    public TestAccountPolicy(@Value("${account.test-accounts.email-pattern:}") String emailPattern) {
        if (emailPattern == null || emailPattern.isBlank()) {
            this.pattern = null;
            return;
        }
        this.pattern = Pattern.compile(emailPattern.trim(), Pattern.CASE_INSENSITIVE);
        for (String ordinary : ORDINARY_ADDRESSES) {
            if (pattern.matcher(ordinary).matches()) {
                throw new IllegalStateException("account.test-accounts.email-pattern matches the ordinary address "
                        + ordinary + ": it would purge real accounts without their grace period. Narrow it.");
            }
        }
    }

    public boolean isTestAccount(String email) {
        return pattern != null && email != null
                && pattern.matcher(email.trim().toLowerCase(Locale.ROOT)).matches();
    }
}
