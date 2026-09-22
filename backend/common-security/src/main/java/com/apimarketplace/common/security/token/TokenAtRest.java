package com.apimarketplace.common.security.token;

import com.apimarketplace.common.security.CredentialEncryptionService;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * How a capability token (a webhook URL token, a share link, a widget token, an invitation
 * token, a Telegram approval callback) is stored.
 *
 * <p>Until 2026-09-17 these were stored in clear and looked up by equality: 83 rows in
 * production that anyone holding a database copy could replay against the platform with no
 * key at all. They are now stored as two columns: the token itself AES-encrypted
 * ({@code ENC:...}, decrypted on read so the UI can still show the link), and an HMAC-SHA256
 * of the plaintext that every lookup goes through. Both derive from the credential encryption
 * material, so a database copy alone yields neither the token nor a way to forge a lookup.
 *
 * <p>The three pieces that make this transparent to the entities:
 * <ul>
 *   <li>{@link EncryptedTokenConverter}: a JPA converter on the token column;</li>
 *   <li>{@link HashedTokenListener}: an entity listener that fills the hash column from the
 *       plaintext on every persist/update, so regenerating a token needs no extra call;</li>
 *   <li>{@link PlaintextTokenBackfill}: the startup pass (and lazy heal on a missed lookup)
 *       that migrates rows written before the change.</li>
 * </ul>
 *
 * <p><b>Why a static registry rather than injection.</b> Hibernate instantiates converters and
 * listeners itself unless the Spring bean container is wired, and the same classes run inside
 * twelve microservice contexts, the CE monolith (one context, every schema), and
 * {@code @DataJpaTest} slices that load no {@code @Component}. A static, installed-once
 * reference works identically in all of them and fails LOUDLY when nothing installed it,
 * instead of silently writing plaintext because a bean was missing. {@link TokenAtRestBootstrap}
 * installs it from the Spring context; tests call {@link #install} directly.
 */
public final class TokenAtRest {

    private static final AtomicReference<CredentialEncryptionService> SERVICE = new AtomicReference<>();

    private TokenAtRest() {}

    /** Wire the encryption material. Idempotent; the last installed service wins. */
    public static void install(CredentialEncryptionService service) {
        SERVICE.set(Objects.requireNonNull(service, "service"));
    }

    /** @return true once {@link #install} has run in this JVM. */
    public static boolean isInstalled() {
        return SERVICE.get() != null;
    }

    /** Test hook: simulate a JVM where nothing installed the material. */
    static void clearForTests() {
        SERVICE.set(null);
    }

    static CredentialEncryptionService service() {
        CredentialEncryptionService s = SERVICE.get();
        if (s == null) {
            throw new IllegalStateException("TokenAtRest is not initialised: no CredentialEncryptionService was "
                    + "installed. In a Spring context TokenAtRestBootstrap does it (scan "
                    + "com.apimarketplace.common.security); in a test call TokenAtRest.install(...)");
        }
        return s;
    }

    /**
     * Lookup key for a plaintext token: keyed HMAC-SHA256, hex. Null and blank map to null so a
     * nullable token column ({@code conversations.share_token}) keeps a null hash.
     */
    public static String hash(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        return service().hmacHash(plaintext);
    }

    /** Ciphertext for the column. Already-encrypted input is returned unchanged. */
    public static String encrypt(String plaintext) {
        return service().encrypt(plaintext);
    }

    /** Plaintext for the entity. A value without the {@code ENC:} prefix is returned as-is. */
    public static String decrypt(String stored) {
        return service().decrypt(stored);
    }

    /**
     * True when the installed material is per-process (no key configured). Backfills must not
     * rewrite existing rows in that state; see {@link CredentialEncryptionService#isUsingEphemeralMaterial()}.
     */
    public static boolean isUsingEphemeralMaterial() {
        return service().isUsingEphemeralMaterial();
    }

    /** @return true when the stored value carries the encrypted prefix. */
    public static boolean isEncrypted(String stored) {
        return service().isEncrypted(stored);
    }

    /**
     * The one lookup shape every token-bearing service uses: hash the plaintext, query by hash,
     * and on a miss fall back to a READ-ONLY plaintext match for a row written before the change
     * (the repository's native {@code findLegacyPlaintext}, {@code WHERE token = ? AND hash IS NULL}).
     * The fallback itself rewrites nothing, which is what keeps a rolling deploy seamless, and
     * it is gated on {@link PlaintextTokenBackfill#mayHaveLegacyRows} so a public miss costs one
     * query once the table is drained. Note the delayed pass is not the ONLY thing that can move
     * a row inside the window: none of these entities is {@code @DynamicUpdate}, so any ordinary
     * JPA save of a legacy row (a conversation touched by a chat turn, an invitation accepted)
     * writes the whole row and therefore encrypts its token early. That row then stops resolving
     * on a replica running the previous image, which is the honest bound on "rollback is free
     * inside the window": free for rows nobody saved.
     *
     * @param plaintext the token as carried in the URL / callback; null or blank finds nothing
     * @param byHash    the repository's {@code findBy<Token>Hash}
     * @param legacy    the plaintext fallback; a service without a backfill bean (unit test) or
     *                  whose table is drained passes {@code t -> Optional.empty()}
     */
    public static <T> Optional<T> lookup(String plaintext, Function<String, Optional<T>> byHash,
                                         Function<String, Optional<T>> legacy) {
        if (plaintext == null || plaintext.isBlank()) {
            return Optional.empty();
        }
        Optional<T> found = byHash.apply(hash(plaintext));
        if (found.isEmpty()) {
            found = legacy.apply(plaintext);
        }
        return found;
    }
}
