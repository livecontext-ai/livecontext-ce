package com.apimarketplace.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ModelAuditHmacConfig - HMAC key validation")
class ModelAuditHmacConfigTest {

    private static ModelAuditHmacConfig configWith(String key) throws Exception {
        ModelAuditHmacConfig cfg = new ModelAuditHmacConfig();
        Field f = ModelAuditHmacConfig.class.getDeclaredField("hmacKey");
        f.setAccessible(true);
        f.set(cfg, key);
        return cfg;
    }

    @Test
    @DisplayName("Empty key is accepted (audit disabled, trigger no-ops)")
    void emptyKeyAccepted() throws Exception {
        assertDoesNotThrow(() -> configWith("").validate());
        assertDoesNotThrow(() -> configWith(null).validate());
    }

    @Test
    @DisplayName("Base64 key (openssl rand -base64 32) is accepted")
    void base64KeyAccepted() throws Exception {
        // 32 random bytes -> 44 base64 chars with trailing '='
        String key = "U2ltdWxhdGVkQmFzZTY0S2V5Rm9yVW5pdFRlc3Rpbmc9PQ==";
        assertDoesNotThrow(() -> configWith(key).validate());
    }

    @Test
    @DisplayName("URL-safe base64 (hyphen + underscore) is accepted")
    void urlSafeBase64Accepted() throws Exception {
        assertDoesNotThrow(() -> configWith("abc-DEF_ghi123+/=").validate());
    }

    @Test
    @DisplayName("Single-quote rejected - would break out of SQL literal")
    void singleQuoteRejected() throws Exception {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> configWith("abc'DROP TABLE x--").validate());
        assertTrue(ex.getMessage().contains("base64"));
    }

    @Test
    @DisplayName("Semicolon rejected - statement delimiter")
    void semicolonRejected() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> configWith("abc;DROP").validate());
    }

    @Test
    @DisplayName("Newline rejected - breaks SQL literal")
    void newlineRejected() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> configWith("abc\ndef").validate());
    }

    @Test
    @DisplayName("Backslash rejected - escape character")
    void backslashRejected() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> configWith("abc\\def").validate());
    }

    @Test
    @DisplayName("Key over 256 chars rejected (implausibly long)")
    void oversizedKeyRejected() throws Exception {
        String key = "a".repeat(257);
        assertThrows(IllegalStateException.class,
                () -> configWith(key).validate());
    }

    @Test
    @DisplayName("256-char key accepted (boundary)")
    void boundaryKeyAccepted() throws Exception {
        String key = "a".repeat(256);
        assertDoesNotThrow(() -> configWith(key).validate());
    }
}
