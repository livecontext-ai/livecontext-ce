package com.apimarketplace.common.scope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GrantedScopes - one rule for reading a provider's granted-scope answer")
class GrantedScopesTest {

    @Nested
    @DisplayName("parse() - the delimiter a provider actually answered in")
    class Parse {

        @Test
        @DisplayName("splits the space-delimited form RFC 6749 specifies")
        void splitsSpaces() {
            assertThat(GrantedScopes.parse("read write admin"))
                    .containsExactly("read", "write", "admin");
        }

        @Test
        @DisplayName("splits the comma-delimited form LinkedIn and TikTok answer in")
        void splitsCommas() {
            assertThat(GrantedScopes.parse("video.list,video.upload,video.publish"))
                    .containsExactly("video.list", "video.upload", "video.publish");
        }

        @Test
        @DisplayName("drops blank fragments from a trailing or padded separator")
        void dropsBlanks() {
            assertThat(GrantedScopes.parse(",read, write ,  admin,"))
                    .containsExactly("read", "write", "admin");
        }

        @Test
        @DisplayName("null and blank answer an empty list, never null")
        void emptyInputs() {
            assertThat(GrantedScopes.parse(null)).isEmpty();
            assertThat(GrantedScopes.parse("   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("normalize() - repairing a list already stored in the wrong shape")
    class Normalize {

        @Test
        @DisplayName("a single comma-joined blob becomes the scopes it names")
        void blobIsReSplit() {
            assertThat(GrantedScopes.normalize(List.of("video.list,video.publish")))
                    .containsExactly("video.list", "video.publish");
        }

        @Test
        @DisplayName("an ordinary list passes through unchanged, so this is safe on every row")
        void ordinaryListUntouched() {
            assertThat(GrantedScopes.normalize(List.of("video.list", "video.publish")))
                    .containsExactly("video.list", "video.publish");
        }

        @Test
        @DisplayName("duplicates across entries collapse")
        void deduplicates() {
            assertThat(GrantedScopes.normalize(List.of("a,b", "b c")))
                    .containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("null and empty answer an empty set, never null")
        void emptyInputs() {
            assertThat(GrantedScopes.normalize(null)).isEmpty();
            assertThat(GrantedScopes.normalize(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("missingFrom() - what an endpoint requires and the grant does not cover")
    class MissingFrom {

        @Test
        @DisplayName("a credential storing one comma blob covers its scopes: this is the bug")
        void blobGrantCoversItsScopes() {
            Set<String> missing = GrantedScopes.missingFrom(
                    List.of("video.publish"),
                    List.of("user.info.basic,video.list,video.upload,video.publish"));
            assertThat(missing)
                    .as("a grant that genuinely holds the scope must not be refused because "
                            + "auth-service stored it comma-joined before Sep 2026")
                    .isEmpty();
        }

        @Test
        @DisplayName("a scope genuinely absent is still reported missing")
        void genuinelyMissingIsReported() {
            assertThat(GrantedScopes.missingFrom(
                    List.of("video.publish"),
                    List.of("user.info.basic,video.list,video.upload")))
                    .containsExactly("video.publish");
        }

        @Test
        @DisplayName("no requirement means nothing missing, whatever was granted")
        void noRequirement() {
            assertThat(GrantedScopes.missingFrom(null, List.of("anything"))).isEmpty();
            assertThat(GrantedScopes.missingFrom(List.of(), null)).isEmpty();
        }

        @Test
        @DisplayName("an empty grant leaves every requirement missing")
        void emptyGrant() {
            assertThat(GrantedScopes.missingFrom(List.of("a", "b"), null))
                    .containsExactly("a", "b");
        }

        @Test
        @DisplayName("matching is EXACT: a prefix of a granted scope is not a grant")
        void prefixIsNotAMatch() {
            // The widening accepts a whole entry OR one of its pieces. It must never accept a
            // string that merely LOOKS like one of them: this is the direction where a mistake
            // grants access rather than denying it, so it is pinned separately.
            assertThat(GrantedScopes.missingFrom(List.of("video.publish"), List.of("video.publish.all")))
                    .containsExactly("video.publish");
            assertThat(GrantedScopes.missingFrom(List.of("video"), List.of("video.publish")))
                    .containsExactly("video");
        }

        @Test
        @DisplayName("matching is EXACT: a substring of a granted scope is not a grant")
        void substringIsNotAMatch() {
            assertThat(GrantedScopes.missingFrom(List.of("publish"), List.of("video.publish")))
                    .containsExactly("publish");
        }

        @Test
        @DisplayName("matching is EXACT: case must agree")
        void caseMustAgree() {
            assertThat(GrantedScopes.missingFrom(List.of("Video.Publish"), List.of("video.publish")))
                    .containsExactly("Video.Publish");
        }

        @Test
        @DisplayName("a null or blank entry inside the stored list is ignored, not matched")
        void nullAndBlankEntriesAreSkipped() {
            // A stored list can hold these: CredentialController persists whatever the request
            // carried, without going through the connect-time parse.
            List<String> stored = java.util.Arrays.asList("video.list", null, "   ");
            assertThat(GrantedScopes.missingFrom(List.of("video.list"), stored)).isEmpty();
            assertThat(GrantedScopes.missingFrom(List.of("video.publish"), stored))
                    .containsExactly("video.publish");
        }

        @Test
        @DisplayName("a padded whole entry still matches, so stored whitespace is not a refusal")
        void paddedWholeEntryMatches() {
            assertThat(GrantedScopes.missingFrom(
                    List.of("Tenant Non-Configurable"),
                    List.of("  Tenant Non-Configurable  ")))
                    .isEmpty();
        }

        @Test
        @DisplayName("the REQUIRED side is never split, so a space-bearing requirement survives")
        void requiredSideIsNotSplit() {
            // workday.json declares the scope "Tenant Non-Configurable". No endpoint requires it
            // today, but splitting the required side would turn it into two requirements that
            // could never be met, which is why only the granted side is normalized.
            assertThat(GrantedScopes.missingFrom(
                    List.of("Tenant Non-Configurable"),
                    List.of("Tenant Non-Configurable")))
                    .as("an exact match must hold even when the scope contains a space")
                    .isEmpty();
        }
    }
}
