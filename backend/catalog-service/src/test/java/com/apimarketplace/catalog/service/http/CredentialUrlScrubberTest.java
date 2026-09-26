package com.apimarketplace.catalog.service.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CredentialUrlScrubber - keeps the credential out of logged and returned text")
class CredentialUrlScrubberTest {

    private static final String TOKEN = "1234567890:FAKE-test-token-not-a-real-one_xyz";
    private static final String REAL = "https://api.telegram.org/bot" + TOKEN + "/sendMessage";
    private static final String SAFE = "https://api.telegram.org/bot{token}/sendMessage";

    @Test
    @DisplayName("RestTemplate's transport-failure wording loses the real URL and keeps the safe one")
    void transportMessageRewrittenToSafeUrl() {
        String message = "I/O error on POST request for \"" + REAL + "\": Connection reset";

        String out = CredentialUrlScrubber.scrub(message, REAL, SAFE, List.of());

        assertThat(out).isEqualTo("I/O error on POST request for \"" + SAFE + "\": Connection reset");
    }

    @Test
    @DisplayName("The raw secret is masked wherever it appears, even outside a URL")
    void rawSecretMasked() {
        String out = CredentialUrlScrubber.scrub("rejected key " + TOKEN + " twice: " + TOKEN, null, null, List.of(TOKEN));

        assertThat(out).isEqualTo("rejected key <redacted> twice: <redacted>");
    }

    @Test
    @DisplayName("The URL-encoded form of the secret is masked too (a query-injected key travels encoded)")
    void urlEncodedSecretMasked() {
        String secret = "ab/cd+ef==xyz";
        String out = CredentialUrlScrubber.scrub("GET https://x.example/v1?api_key=ab%2Fcd%2Bef%3D%3Dxyz", null, null, List.of(secret));

        assertThat(out).isEqualTo("GET https://x.example/v1?api_key=<redacted>");
    }

    @Test
    @DisplayName("Masking threshold: 7 characters is left alone, 8 is masked")
    void maskingThresholdBoundary() {
        assertThat(CredentialUrlScrubber.scrub("id abcdefg here", null, null, List.of("abcdefg")))
                .isEqualTo("id abcdefg here");
        assertThat(CredentialUrlScrubber.scrub("id abcdefgh here", null, null, List.of("abcdefgh")))
                .isEqualTo("id <redacted> here");
    }

    @Test
    @DisplayName("Regression (audit): RestTemplate drops the query, so the query-less real URL is rewritten too")
    void queryLessTransportMessageRewritten() {
        String real = "https://api.telegram.org/bot" + TOKEN + "/getUpdates?offset=5";
        String safe = "https://api.telegram.org/bot{token}/getUpdates?offset=5";
        String message = "I/O error on GET request for \"https://api.telegram.org/bot" + TOKEN
                + "/getUpdates\": Connection refused";

        // Even with no secret list at all, the query-less URL form is rewritten.
        assertThat(CredentialUrlScrubber.scrub(message, real, safe, List.of()))
                .isEqualTo("I/O error on GET request for \"https://api.telegram.org/bot{token}/getUpdates\": Connection refused");
    }

    @Test
    @DisplayName("Every value that entered the URL is masked, the longest first so none is left half-masked")
    void everySubstitutedValueMaskedLongestFirst() {
        String out = CredentialUrlScrubber.scrub("a=secretvalue b=secretvalue-extended c=other-secret",
                null, null, Arrays.asList("secretvalue", "secretvalue-extended", null, "other-secret"));

        assertThat(out).isEqualTo("a=<redacted> b=<redacted> c=<redacted>");
    }

    @Test
    @DisplayName("Null and empty text pass through; null URLs and secret are a no-op")
    void nullSafe() {
        assertThat(CredentialUrlScrubber.scrub(null, REAL, SAFE, List.of(TOKEN))).isNull();
        assertThat(CredentialUrlScrubber.scrub("", REAL, SAFE, List.of(TOKEN))).isEmpty();
        assertThat(CredentialUrlScrubber.scrub("plain text", null, null, null)).isEqualTo("plain text");
    }

    @Test
    @DisplayName("withRedactedQueryParam picks ? or & from the existing query")
    void queryParamSeparator() {
        assertThat(CredentialUrlScrubber.withRedactedQueryParam("https://x.example/v1", "key"))
                .isEqualTo("https://x.example/v1?key=<redacted>");
        assertThat(CredentialUrlScrubber.withRedactedQueryParam("https://x.example/v1?a=1", "key"))
                .isEqualTo("https://x.example/v1?a=1&key=<redacted>");
    }
}
