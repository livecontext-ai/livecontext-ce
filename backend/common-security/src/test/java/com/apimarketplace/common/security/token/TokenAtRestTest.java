package com.apimarketplace.common.security.token;

import com.apimarketplace.common.security.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TokenAtRest: converter, listener, lookup")
class TokenAtRestTest {

    static final CredentialEncryptionService SERVICE =
            new CredentialEncryptionService("test-password-123", "0123456789abcdef");

    @BeforeAll
    static void install() {
        TokenAtRest.install(SERVICE);
    }

    /** A minimal entity with two slots, as SharedLinkEntity has. */
    static final class TwoTokens implements HashedTokenEntity {
        String token;
        String tokenHash;
        String resourceToken;
        String resourceTokenHash;

        @Override
        public List<TokenSlot> tokenSlots() {
            return List.of(new TokenSlot(() -> token, h -> tokenHash = h),
                    new TokenSlot(() -> resourceToken, h -> resourceTokenHash = h));
        }
    }

    @Nested
    @DisplayName("EncryptedTokenConverter")
    class Converter {
        private final EncryptedTokenConverter converter = new EncryptedTokenConverter();

        @Test
        @DisplayName("writes ENC: ciphertext and reads the plaintext back")
        void roundTrip() {
            String stored = converter.convertToDatabaseColumn("wh_abc123");
            assertThat(stored).startsWith("ENC:").isNotEqualTo("wh_abc123");
            assertThat(converter.convertToEntityAttribute(stored)).isEqualTo("wh_abc123");
        }

        @Test
        @DisplayName("two writes of the same token differ (random IV): equality on the column can never match")
        void randomIv() {
            assertThat(converter.convertToDatabaseColumn("wh_abc123"))
                    .isNotEqualTo(converter.convertToDatabaseColumn("wh_abc123"));
        }

        @Test
        @DisplayName("a pre-change row (plaintext in the column) is read as-is")
        void legacyPlaintextPassesThrough() {
            assertThat(converter.convertToEntityAttribute("wh_legacy")).isEqualTo("wh_legacy");
        }

        @Test
        @DisplayName("null and blank are left alone in both directions")
        void nullAndBlank() {
            assertThat(converter.convertToDatabaseColumn(null)).isNull();
            assertThat(converter.convertToDatabaseColumn("")).isEmpty();
            assertThat(converter.convertToEntityAttribute(null)).isNull();
        }

        @Test
        @DisplayName("ciphertext written under another key is returned unchanged, not thrown (one bad row must not kill a listing)")
        void foreignCiphertextDoesNotThrow() {
            String foreign = new CredentialEncryptionService("other-password-456", "fedcba9876543210").encrypt("wh_x");
            assertThat(converter.convertToEntityAttribute(foreign)).isEqualTo(foreign);
        }
    }

    @Nested
    @DisplayName("HashedTokenListener")
    class Listener {
        private final HashedTokenListener listener = new HashedTokenListener();

        @Test
        @DisplayName("fills every slot's hash from the plaintext, deterministically")
        void fillsHashes() {
            TwoTokens e = new TwoTokens();
            e.token = "sl_1";
            e.resourceToken = "ch_2";

            listener.computeHashes(e);

            assertThat(e.tokenHash).isEqualTo(TokenAtRest.hash("sl_1")).hasSize(64);
            assertThat(e.resourceTokenHash).isEqualTo(TokenAtRest.hash("ch_2"));
            assertThat(e.tokenHash).isNotEqualTo(e.resourceTokenHash);
        }

        @Test
        @DisplayName("a regenerated token gets a new hash on the next update, with no extra call")
        void regenerateRehashes() {
            TwoTokens e = new TwoTokens();
            e.token = "sl_1";
            listener.computeHashes(e);
            String before = e.tokenHash;

            e.token = "sl_2";
            listener.computeHashes(e);

            assertThat(e.tokenHash).isNotEqualTo(before).isEqualTo(TokenAtRest.hash("sl_2"));
        }

        @Test
        @DisplayName("a null token (sharing never enabled) keeps a null hash")
        void nullTokenNullHash() {
            TwoTokens e = new TwoTokens();
            listener.computeHashes(e);
            assertThat(e.tokenHash).isNull();
            assertThat(e.resourceTokenHash).isNull();
        }

        @Test
        @DisplayName("ignores entities that are not HashedTokenEntity")
        void ignoresOthers() {
            listener.computeHashes(new Object());
        }
    }

    @Nested
    @DisplayName("lookup")
    class Lookup {

        @Test
        @DisplayName("queries by the hash of the plaintext and returns the hit without touching the fallback")
        void hitOnHash() {
            List<String> queried = new ArrayList<>();
            AtomicInteger legacyCalls = new AtomicInteger();
            Optional<String> r = TokenAtRest.lookup("wh_1",
                    h -> { queried.add(h); return Optional.of("row"); },
                    t -> { legacyCalls.incrementAndGet(); return Optional.empty(); });
            assertThat(r).contains("row");
            assertThat(queried).containsExactly(TokenAtRest.hash("wh_1"));
            assertThat(legacyCalls.get()).isZero();
        }

        @Test
        @DisplayName("on a miss, the read-only plaintext fallback is consulted once with the PLAINTEXT and its hit is returned")
        void fallbackOnMiss() {
            List<String> legacyArgs = new ArrayList<>();
            Optional<String> r = TokenAtRest.lookup("wh_1", h -> Optional.empty(),
                    t -> { legacyArgs.add(t); return Optional.of("legacy-row"); });
            assertThat(r).contains("legacy-row");
            assertThat(legacyArgs).containsExactly("wh_1");

            assertThat(TokenAtRest.lookup("wh_1", h -> Optional.empty(), t -> Optional.empty())).isEmpty();
        }

        @Test
        @DisplayName("null or blank plaintext never touches the repository")
        void blankShortCircuits() {
            AtomicInteger calls = new AtomicInteger();
            assertThat(TokenAtRest.lookup(null, h -> { calls.incrementAndGet(); return Optional.of("x"); }, t -> Optional.of("y"))).isEmpty();
            assertThat(TokenAtRest.lookup("  ", h -> { calls.incrementAndGet(); return Optional.of("x"); }, t -> Optional.of("y"))).isEmpty();
            assertThat(calls.get()).isZero();
        }
    }

    @Test
    @DisplayName("hash is keyed: a different encryption password gives a different hash for the same token")
    void hashIsKeyed() {
        String a = TokenAtRest.hash("wh_1");
        TokenAtRest.install(new CredentialEncryptionService("other-password-456", "fedcba9876543210"));
        try {
            assertThat(TokenAtRest.hash("wh_1")).isNotEqualTo(a);
        } finally {
            TokenAtRest.install(SERVICE);
        }
    }

    @Test
    @DisplayName("hash of null or blank is null (a nullable token column keeps a null hash)")
    void hashOfBlank() {
        assertThat(TokenAtRest.hash(null)).isNull();
        assertThat(TokenAtRest.hash(" ")).isNull();
    }

    @Test
    @DisplayName("an uninstalled registry fails loudly, naming the bootstrap, rather than writing plaintext")
    void uninstalledFailsLoudly() {
        assertThat(TokenAtRest.isInstalled()).isTrue();
        TokenAtRest.clearForTests();
        try {
            assertThat(TokenAtRest.isInstalled()).isFalse();
            assertThatThrownBy(() -> TokenAtRest.hash("x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TokenAtRestBootstrap");
            assertThatThrownBy(() -> new EncryptedTokenConverter().convertToDatabaseColumn("x"))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TokenAtRest.install(SERVICE);
        }
    }
}
