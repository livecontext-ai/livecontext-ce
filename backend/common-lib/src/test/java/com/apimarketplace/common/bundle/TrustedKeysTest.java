package com.apimarketplace.common.bundle;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit contract for {@link TrustedKeys}: parsing the flat
 * {@code keyId=base64;keyId2=base64} spec, tolerance to malformed entries,
 * and lookup semantics.
 */
@DisplayName("TrustedKeys - flat spec parsing + lookup")
class TrustedKeysTest {

    private static String pubB64;

    @BeforeAll
    static void keypair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();
        pubB64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    @Test
    @DisplayName("Single keyId=base64 entry parses and is findable")
    void singleEntry() {
        TrustedKeys keys = new TrustedKeys("k1=" + pubB64);

        assertThat(keys.hasKeys()).isTrue();
        assertThat(keys.keyIds()).containsExactly("k1");
        assertThat(keys.find("k1")).isPresent();
    }

    @Test
    @DisplayName("Multiple entries split on ';' with surrounding whitespace tolerated")
    void multipleEntries() {
        TrustedKeys keys = new TrustedKeys(" k-new=" + pubB64 + " ; k-old=" + pubB64 + " ");

        assertThat(keys.keyIds()).containsExactly("k-new", "k-old");
        assertThat(keys.find("k-new")).isPresent();
        assertThat(keys.find("k-old")).isPresent();
    }

    @Test
    @DisplayName("Empty or null spec → no keys, find always empty")
    void emptySpec() {
        assertThat(new TrustedKeys("").hasKeys()).isFalse();
        assertThat(new TrustedKeys("   ").hasKeys()).isFalse();
        assertThat(new TrustedKeys(null).hasKeys()).isFalse();
        assertThat(new TrustedKeys("").find("k1")).isEmpty();
    }

    @Test
    @DisplayName("Unknown or null keyId → empty (never throws)")
    void unknownKeyId() {
        TrustedKeys keys = new TrustedKeys("k1=" + pubB64);

        assertThat(keys.find("nope")).isEmpty();
        assertThat(keys.find(null)).isEmpty();
    }

    @Test
    @DisplayName("Malformed entries are skipped but good ones are kept")
    void tolerantOfGarbage() {
        TrustedKeys keys = new TrustedKeys(
                "bad-entry-no-equals; nokey=; k1=" + pubB64 + "; k2=!!!!garbage!!!");

        assertThat(keys.hasKeys()).isTrue();
        assertThat(keys.keyIds()).containsExactly("k1");
        assertThat(keys.find("k1")).isPresent();
        assertThat(keys.find("k2")).isEmpty();
    }

    @Test
    @DisplayName("PEM armor on the public key value is stripped")
    void pemArmorStripped() {
        String armored = "-----BEGIN PUBLIC KEY-----" + pubB64 + "-----END PUBLIC KEY-----";
        TrustedKeys keys = new TrustedKeys("k1=" + armored);

        assertThat(keys.find("k1")).isPresent();
    }
}
