package com.apimarketplace.auth.credential.service.oauth2.refresh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LogSafeBody - PII-safe rendering of provider response bodies")
class LogSafeBodyTest {

    @Test
    @DisplayName("null body → <empty>")
    void nullBody() {
        assertThat(LogSafeBody.scrub(null)).isEqualTo("<empty>");
    }

    @Test
    @DisplayName("empty body → <empty>")
    void emptyBody() {
        assertThat(LogSafeBody.scrub("")).isEqualTo("<empty>");
    }

    /**
     * Non-JSON bodies (HTML 5xx pages, plain text errors) must never surface raw - whatever
     * marker we emit ({@code <unparseable:Nb>} for anything Jackson can't even tokenize, or
     * {@code <non-json:Nb>} for parseable-but-non-object), the contract is the same: size-only,
     * never raw content.
     */
    @Test
    @DisplayName("HTML body → marker only, never raw content")
    void htmlBodyIsNeverEchoed() {
        String result = LogSafeBody.scrub("<html><body>500 Server Error</body></html>");
        assertThat(result).startsWith("<");
        assertThat(result).doesNotContain("500 Server Error");
        assertThat(result).doesNotContain("<html>");
    }

    @Test
    @DisplayName("JSON scalar (parseable but not an object) → <non-json:Nb> marker")
    void jsonScalarIsNotAnObject() {
        // A bare JSON number parses successfully but isn't an object - hits the non-json branch.
        String result = LogSafeBody.scrub("42");
        assertThat(result).startsWith("<non-json:");
    }

    @Test
    @DisplayName("JSON array (not object) → <non-json> marker")
    void jsonArrayIsNotAnObject() {
        String result = LogSafeBody.scrub("[1,2,3]");
        assertThat(result).startsWith("<non-json:");
    }

    @Test
    @DisplayName("JSON without error fields → <no-error-fields> marker")
    void jsonWithoutErrorFields() {
        assertThat(LogSafeBody.scrub("{\"foo\":\"bar\"}"))
                .isEqualTo("<no-error-fields>");
    }

    @Test
    @DisplayName("standard RFC body → error=code error_description=...")
    void standardRfcBody() {
        String body = "{\"error\":\"invalid_grant\",\"error_description\":\"refresh token expired\"}";

        String result = LogSafeBody.scrub(body);

        assertThat(result).isEqualTo("error=invalid_grant error_description=refresh token expired");
    }

    @Test
    @DisplayName("error only (no description) → error=code")
    void errorOnly() {
        assertThat(LogSafeBody.scrub("{\"error\":\"invalid_client\"}"))
                .isEqualTo("error=invalid_client");
    }

    /**
     * Description-only bodies shouldn't happen per RFC, but if a provider sends one we still want
     * the description (scrubbed + truncated). The code surfaces as the literal "<null>" marker
     * from truncate().
     */
    @Test
    @DisplayName("description only (no error) → error=<null> error_description=...")
    void descriptionOnly() {
        String result = LogSafeBody.scrub("{\"error_description\":\"some detail\"}");
        assertThat(result).contains("error_description=some detail");
    }

    @Test
    @DisplayName("unparseable JSON → <unparseable:Nb> marker, never the raw body")
    void unparseableJson() {
        String result = LogSafeBody.scrub("{this is not: valid json");
        assertThat(result).startsWith("<unparseable:");
        assertThat(result).doesNotContain("this is not");
    }

    @Test
    @DisplayName("truncate cuts at FIELD_CAP and appends ellipsis")
    void truncateHonorsFieldCap() {
        String long_ = "a".repeat(LogSafeBody.FIELD_CAP + 50);
        String result = LogSafeBody.truncate(long_);

        assertThat(result).hasSize(LogSafeBody.FIELD_CAP + 1);
        assertThat(result).endsWith("…");
    }

    @Test
    @DisplayName("truncate passes short strings through unchanged")
    void truncateLeavesShortStringsAlone() {
        assertThat(LogSafeBody.truncate("hello")).isEqualTo("hello");
    }

    @Test
    @DisplayName("truncate(null) → <null> marker")
    void truncateNull() {
        assertThat(LogSafeBody.truncate(null)).isEqualTo("<null>");
    }

    /**
     * Token-shape heuristic: runs of ≥20 base64url chars get redacted. Critical for providers
     * that echo the refresh_token back into error_description (Salesforce, Azure AD have done
     * this historically).
     */
    @Test
    @DisplayName("scrubTokens redacts a bearer-token-shaped substring")
    void scrubTokensRedactsBearerShape() {
        String desc = "invalid refresh token ya29.a0AfH6SMBqABCDEFghijKLMNopqrstuvwxyz1234 expired";

        String scrubbed = LogSafeBody.scrubTokens(desc);

        assertThat(scrubbed).contains("<redacted>");
        assertThat(scrubbed).doesNotContain("ya29.a0AfH6SMBqABCDEFghijKLMNopqrstuvwxyz1234");
    }

    @Test
    @DisplayName("scrubTokens preserves short words (under 20 chars)")
    void scrubTokensIgnoresShortIdentifiers() {
        assertThat(LogSafeBody.scrubTokens("client_id abc123 is wrong"))
                .isEqualTo("client_id abc123 is wrong");
    }

    @Test
    @DisplayName("scrubTokens handles empty / null gracefully")
    void scrubTokensEmptyInput() {
        assertThat(LogSafeBody.scrubTokens(null)).isNull();
        assertThat(LogSafeBody.scrubTokens("")).isEmpty();
    }

    /**
     * End-to-end: a provider body carrying a leaked refresh token in error_description must be
     * rendered with the token redacted in the final log line - the whole point of this class.
     */
    @Test
    @DisplayName("E2E: refresh_token leaked in error_description is redacted in final output")
    void endToEndTokenLeakageIsBlocked() {
        String body = "{\"error\":\"invalid_grant\","
                + "\"error_description\":\"refresh token 1//0hABCDEFGHIJKLMNopqrstuvwxyz1234567 is revoked\"}";

        String result = LogSafeBody.scrub(body);

        assertThat(result).startsWith("error=invalid_grant");
        assertThat(result).contains("<redacted>");
        assertThat(result).doesNotContain("1//0hABCDEFGHIJKLMNopqrstuvwxyz1234567");
    }

    @Test
    @DisplayName("E2E: very long error_description is truncated AND token-scrubbed")
    void truncationAndScrubCombineCorrectly() {
        String token = "A".repeat(30);
        String padding = "x".repeat(LogSafeBody.FIELD_CAP);
        String body = "{\"error\":\"invalid_grant\",\"error_description\":\""
                + token + " " + padding + "\"}";

        String result = LogSafeBody.scrub(body);

        assertThat(result).contains("<redacted>");
        // Output should not carry the full padded description - truncation must have kicked in.
        assertThat(result.length()).isLessThan(body.length());
    }
}
