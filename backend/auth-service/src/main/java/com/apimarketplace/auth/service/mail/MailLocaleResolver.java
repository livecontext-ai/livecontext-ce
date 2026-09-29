package com.apimarketplace.auth.service.mail;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.i18n.MessageCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The language one e-mail is written in, resolved from the ADDRESS it is going to.
 *
 * <p>The account mailers are handed an e-mail address, not a user: an invitation goes to
 * somebody who may not have an account yet, and a purge confirmation goes to an account that has
 * just been emptied. So the locale is looked up by address ({@code auth.users.locale}, V527) and
 * falls back to English when no account owns it.
 *
 * <p>Never throws and never fails a send. A missing translation is a worse e-mail; a thrown
 * exception is no e-mail at all, and this runs while a message is being composed.
 *
 * <p>Two things can go wrong, and only one of them is an exception.
 *
 * <p>The database being unavailable at that moment, which is caught below. It is NOT a duplicate
 * address - `uk_users_email_unique` (V3) forbids two rows with the same non-null address, so the
 * single-result lookup cannot throw for that reason. Several comments in this package used to say
 * it could, which read as "duplicates are normal here" to anyone who believed them.
 *
 * <p>And a MISS, silently, because the lookup is case-sensitive and so is the unique index. The
 * OAuth and Keycloak paths store the address as the identity provider spells it, so an account can
 * be {@code Bob@Corp.com} while an invitation arrives lower-cased; both this lookup and the
 * caller's own miss, and the reader gets English despite a stored language. Pinned by a test rather
 * than fixed here on purpose: making it case-insensitive means a new repository method and an index
 * to match, on a path shared by every mail, which is a decision to take on its own.
 */
@Service
public class MailLocaleResolver {

    private static final Logger logger = LoggerFactory.getLogger(MailLocaleResolver.class);

    private final UserRepository userRepository;

    public MailLocaleResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** One of the app locales for the account that owns {@code email}, else English. */
    public String forEmail(String email) {
        if (email == null || email.isBlank()) return MessageCatalog.DEFAULT_LOCALE;
        try {
            Optional<User> user = userRepository.findByEmail(email.trim());
            return MessageCatalog.normalizeLocale(user.map(User::getLocale).orElse(null));
        } catch (RuntimeException e) {
            // The database being unavailable at the moment a mail is composed (a duplicate address
            // cannot happen: uk_users_email_unique forbids it). English is a fine answer; not
            // sending is not.
            logger.debug("[mail] locale for an address could not be resolved: {}", e.getMessage());
            return MessageCatalog.DEFAULT_LOCALE;
        }
    }

    /**
     * One of the app locales for an already-loaded account, else English.
     *
     * <p>STATIC, and it touches no repository: the answer is on the row. It was an instance method,
     * which made it look like a lookup and made callers inject a collaborator to reach it - and one
     * of them then "removed the useless collaborator" by copying the body into a private method
     * instead, leaving this one with no production caller at all and two copies of one line in a
     * class whose premise is that copies drift.
     */
    public static String forUser(User user) {
        return MessageCatalog.normalizeLocale(user == null ? null : user.getLocale());
    }

    /**
     * The language ONE mail is written in: what the caller read off the row, else a lookup by
     * address, else English. The single answer to a question five mailers ask.
     *
     * <p>Static, and taking the resolver, because it had drifted into three private copies with
     * three DIFFERENT failure behaviours for the same input: one threw a NullPointerException on a
     * null resolver, one answered English, and the third sat inside a {@code try} whose {@code
     * catch} swallowed the throw, so a null resolver there meant no mail at all rather than an
     * English one. Three answers to one question, in the package whose premise is that five copies
     * of anything is five chances to drift.
     *
     * @param resolver may be null, which is what a directly constructed mailer in a test has. The
     *                 answer is then English: a worse mail, never a missing one.
     */
    public static String resolve(MailLocaleResolver resolver, String knownLocale, String email) {
        if (knownLocale != null && !knownLocale.isBlank()) {
            return MessageCatalog.normalizeLocale(knownLocale);
        }
        return resolver == null ? MessageCatalog.DEFAULT_LOCALE : resolver.forEmail(email);
    }
}
