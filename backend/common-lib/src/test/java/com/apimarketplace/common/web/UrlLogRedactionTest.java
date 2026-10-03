package com.apimarketplace.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Item 7 (LC-010, CASA readiness): the redaction used on every catalog log line and refusal that
 * may quote a URL handles every scheme, userinfo, queries and fragments.
 */
@DisplayName("UrlLogRedaction")
class UrlLogRedactionTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "I/O error on GET request for \"https://api.example.com/search?key=SECRET&q=inbox\"",
        "failed http://h.example/p?SECRET",
        "ftp://user:SECRET@files.example.com/x",
        "jdbc:postgresql://db.example.com:5432/app?password=SECRET",
        "wss://stream.example.com/socket#SECRET",
        "redis://:SECRET@cache.example.com:6379/0"
    })
    @DisplayName("the secret part of a URL of any scheme never survives")
    void secretsAreRemoved(String message) {
        String redacted = UrlLogRedaction.redact(message);
        assertThat(redacted).doesNotContain("SECRET");
    }

    @Test
    @DisplayName("host and path stay, so the line is still useful")
    void hostAndPathStay() {
        assertThat(UrlLogRedaction.redact("GET https://api.example.com/v1/users?token=x failed"))
                .isEqualTo("GET https://api.example.com/v1/users?<redacted> failed");
    }

    @Test
    @DisplayName("long messages are capped, null stays null")
    void capAndNull() {
        assertThat(UrlLogRedaction.redact("x".repeat(5000)).length()).isLessThan(UrlLogRedaction.MESSAGE_CAP + 40);
        assertThat(UrlLogRedaction.redact(null)).isNull();
    }

    @Test
    @DisplayName("origin keeps scheme, host and port only")
    void origin() {
        assertThat(UrlLogRedaction.origin("https://u:p@api.example.com:8443/a/b?c=d")).isEqualTo("https://api.example.com:8443");
        assertThat(UrlLogRedaction.origin("mailto:someone")).isEqualTo("<no host>");
        assertThat(UrlLogRedaction.origin("not a url")).isEqualTo("<unparseable url>");
    }
}
