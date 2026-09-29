package com.apimarketplace.auth.service.mail;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.IncorrectResultSizeDataAccessException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Which language one e-mail is written in, resolved from the address it is going to.
 *
 * <p>The contract worth pinning is the refusal to fail: this runs while a message is being
 * composed, and a missing translation is a worse e-mail, while a thrown exception is no e-mail at
 * all.
 *
 * <p>What makes the lookup throw is the database being unreachable, not a duplicate address:
 * `uk_users_email_unique` (V3) forbids two rows with the same non-null one. The test below feeds
 * the exception Spring Data raises for a multi-row result anyway, because it is the shape the
 * catch has to survive whatever produced it.
 */
@DisplayName("MailLocaleResolver - answers a language, never an exception")
class MailLocaleResolverTest {

    private UserRepository userRepository;
    private MailLocaleResolver resolver;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        resolver = new MailLocaleResolver(userRepository);
    }

    private static User withLocale(String locale) {
        User user = new User();
        user.setLocale(locale);
        return user;
    }

    @Test
    @DisplayName("answers the locale of the account that owns the address")
    void resolvesFromTheAccount() {
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.of(withLocale("fr")));

        assertThat(resolver.forEmail("reader@example.com")).isEqualTo("fr");
    }

    @Test
    @DisplayName("trims the address, because it arrives from a request body")
    void trimsTheAddress() {
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.of(withLocale("de")));

        assertThat(resolver.forEmail("  reader@example.com  ")).isEqualTo("de");
    }

    @Test
    @DisplayName("English when no account owns the address, which is the invitation case")
    void unknownAddressIsEnglish() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThat(resolver.forEmail("stranger@example.com")).isEqualTo("en");
    }

    @Test
    @DisplayName("English when the account has no locale yet")
    void accountWithoutLocaleIsEnglish() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(withLocale(null)));

        assertThat(resolver.forEmail("new@example.com")).isEqualTo("en");
    }

    @Test
    @DisplayName("a locale the app does not support reads as English, never as itself")
    void unsupportedLocaleIsEnglish() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(withLocale("it")));

        assertThat(resolver.forEmail("someone@example.com")).isEqualTo("en");
    }

    @Test
    @DisplayName("a regional tag narrows to the app locale: pt-BR reads as pt")
    void regionalTagNarrows() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(withLocale("pt-BR")));

        assertThat(resolver.forEmail("someone@example.com")).isEqualTo("pt");
    }

    @Test
    @DisplayName("a lookup that THROWS answers English instead of failing the send")
    void duplicateAddressDoesNotThrow() {
        // Any RuntimeException from the lookup, of which an unreachable database is the reachable
        // cause; the multi-row exception is used because it is the one Spring Data raises from this
        // call shape. Letting it escape would turn "we could not tell which language" into "the
        // mail was never sent".
        when(userRepository.findByEmail(anyString()))
                .thenThrow(new IncorrectResultSizeDataAccessException(1, 2));

        assertThat(resolver.forEmail("shared@example.com")).isEqualTo("en");
    }

    @Test
    @DisplayName("a blank or null address is answered without touching the database")
    void blankAddressShortCircuits() {
        assertThat(resolver.forEmail(null)).isEqualTo("en");
        assertThat(resolver.forEmail("   ")).isEqualTo("en");

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("the lookup is CASE-SENSITIVE, which is a silent English mail rather than an error")
    void lookupIsCaseSensitive() {
        // Not a wish: a record of what happens, because the class javadoc used to enumerate its
        // failure modes as "the database being unavailable" and this is the one that will occur first.
        //
        // `uk_users_email_unique` is a plain unique index on `users(email)`, and the OAuth and
        // Keycloak paths store the address exactly as the identity provider spells it. So an account
        // can be Bob@Corp.com while an invitation arrives lower-cased: the lookup misses, nothing
        // throws, and the reader gets English despite a stored language. Fixing it means a new
        // repository method and an index to match, on a path every mail shares, which is a decision
        // to take on its own rather than inside a localization pass.
        when(userRepository.findByEmail("bob@corp.com")).thenReturn(Optional.empty());

        assertThat(resolver.forEmail("bob@corp.com")).isEqualTo("en");

        // And the same address as stored resolves, so the miss above is the CASE and not the query.
        when(userRepository.findByEmail("Bob@Corp.com")).thenReturn(Optional.of(withLocale("de")));
        assertThat(resolver.forEmail("Bob@Corp.com")).isEqualTo("de");
    }

    @Test
    @DisplayName("resolve prefers what the caller carried, and never throws for want of a resolver")
    void resolvePrefersTheCarriedLanguage() {
        // The one answer to the question five mailers ask. It had drifted into three private copies
        // with three DIFFERENT behaviours for a null resolver: one threw a NullPointerException, one
        // answered English, and the third sat inside a try whose catch swallowed the throw - so a
        // null resolver there meant NO MAIL AT ALL rather than an English one, on a security alert.
        assertThat(MailLocaleResolver.resolve(resolver, "de", "someone@example.com")).isEqualTo("de");
        // Normalized on the way through, so a caller passing a stored value it has not vetted cannot
        // reach the catalog with a locale that has no bundle.
        assertThat(MailLocaleResolver.resolve(resolver, "DE", "someone@example.com")).isEqualTo("de");
        assertThat(MailLocaleResolver.resolve(resolver, "kl", "someone@example.com")).isEqualTo("en");
        // A carried language means NO lookup: that is the point of carrying it.
        verifyNoInteractions(userRepository);

        // Nothing carried: the lookup, as before.
        when(userRepository.findByEmail("known@example.com")).thenReturn(Optional.of(withLocale("pt")));
        assertThat(MailLocaleResolver.resolve(resolver, null, "known@example.com")).isEqualTo("pt");
        assertThat(MailLocaleResolver.resolve(resolver, "   ", "known@example.com")).isEqualTo("pt");
    }

    @Test
    @DisplayName("resolve answers English rather than throwing when no resolver was injected")
    void resolveToleratesAMissingResolver() {
        // A directly constructed mailer in a test has no resolver. English is a worse mail; a throw
        // inside a compose is no mail, and one of the three old copies did exactly that.
        assertThat(MailLocaleResolver.resolve(null, null, "someone@example.com")).isEqualTo("en");
        // And a carried language still wins, because it needs nothing from the database.
        assertThat(MailLocaleResolver.resolve(null, "fr", "someone@example.com")).isEqualTo("fr");
    }

    @Test
    @DisplayName("forUser reads an account already in hand, and tolerates a null one")
    void resolvesFromALoadedUser() {
        assertThat(resolver.forUser(withLocale("es"))).isEqualTo("es");
        assertThat(resolver.forUser(withLocale(null))).isEqualTo("en");
        assertThat(resolver.forUser(null)).isEqualTo("en");

        // No lookup: the caller already had the row.
        verifyNoInteractions(userRepository);
    }
}
