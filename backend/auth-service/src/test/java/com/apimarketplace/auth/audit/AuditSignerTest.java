package com.apimarketplace.auth.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuditSigner")
class AuditSignerTest {

    private static final String FIXED_KEY = Base64.getEncoder().encodeToString(new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
    });

    @Test
    @DisplayName("sign is deterministic for the same input + key")
    void sign_deterministic() {
        AuditSigner s = new AuditSigner(FIXED_KEY);
        String a = s.sign("{\"foo\":\"bar\"}");
        String b = s.sign("{\"foo\":\"bar\"}");
        assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("sign produces different outputs for different inputs")
    void sign_differentInputs_differentOutputs() {
        AuditSigner s = new AuditSigner(FIXED_KEY);
        assertThat(s.sign("a")).isNotEqualTo(s.sign("b"));
    }

    @Test
    @DisplayName("two signers with the same configured key produce identical signatures")
    void sign_sameKey_sameSignatureAcrossInstances() {
        AuditSigner s1 = new AuditSigner(FIXED_KEY);
        AuditSigner s2 = new AuditSigner(FIXED_KEY);
        assertThat(s1.sign("payload")).isEqualTo(s2.sign("payload"));
    }

    @Test
    @DisplayName("ephemeral key (null/blank config) still produces 64-char hex signature")
    void sign_ephemeralKey_works() {
        AuditSigner s = new AuditSigner(null);
        String sig = s.sign("payload");
        assertThat(sig).hasSize(64).matches("[0-9a-f]+");

        AuditSigner s2 = new AuditSigner("");
        assertThat(s2.sign("payload")).hasSize(64);
    }

    @Test
    @DisplayName("ephemeral key signers produce DIFFERENT signatures (no cross-restart verifiability)")
    void sign_ephemeralKey_notReproducible() {
        AuditSigner s1 = new AuditSigner(null);
        AuditSigner s2 = new AuditSigner(null);
        // Different random keys → different HMACs. This is what makes the
        // ephemeral mode unsuitable for production verification.
        assertThat(s1.sign("payload")).isNotEqualTo(s2.sign("payload"));
    }
}
