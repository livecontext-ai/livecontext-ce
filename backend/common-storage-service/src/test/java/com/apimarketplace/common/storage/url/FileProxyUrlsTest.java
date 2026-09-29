package com.apimarketplace.common.storage.url;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FileProxyUrls.storageKeyOf")
class FileProxyUrlsTest {

    private static final String KEY = "1/ed253a2d/run_123/core:watermark/clip.mp4";
    private static final String ENCODED_KEY = "1%2Fed253a2d%2Frun_123%2Fcore%3Awatermark%2Fclip.mp4";

    @Nested
    @DisplayName("recognises")
    class Recognises {

        @Test
        @DisplayName("the ABSOLUTE signed URL a core:public_link node mints - the shape that was invisible to the snapshot walkers and 403'd once its exp passed")
        void absoluteSignedPublicLinkUrl() {
            String url = "https://livecontext.ai/api/files/proxy-signed?key=" + ENCODED_KEY
                    + "&exp=1787778662&disposition=inline&sig=Zm9vYmFy";

            assertThat(FileProxyUrls.storageKeyOf(url)).isEqualTo(KEY);
        }

        @Test
        @DisplayName("the relative signed URL the showcase rewriter mints (so re-signing is idempotent)")
        void relativeSignedUrl() {
            String url = "/api/files/proxy-signed?key=" + ENCODED_KEY + "&exp=1&disposition=inline&sig=x";

            assertThat(FileProxyUrls.storageKeyOf(url)).isEqualTo(KEY);
        }

        @Test
        @DisplayName("the authenticated proxy URL, relative")
        void relativeAuthenticatedProxyUrl() {
            assertThat(FileProxyUrls.storageKeyOf("/api/files/proxy?key=" + ENCODED_KEY + "&disposition=inline"))
                    .isEqualTo(KEY);
        }

        @Test
        @DisplayName("the authenticated proxy URL, absolute")
        void absoluteAuthenticatedProxyUrl() {
            assertThat(FileProxyUrls.storageKeyOf("http://localhost:3000/api/files/proxy?key=" + ENCODED_KEY))
                    .isEqualTo(KEY);
        }

        @Test
        @DisplayName("a key that is the LAST query parameter (no trailing '&' to stop at)")
        void keyAsLastParameter() {
            assertThat(FileProxyUrls.storageKeyOf("/api/files/proxy-signed?exp=1&disposition=inline&key=" + ENCODED_KEY))
                    .isEqualTo(KEY);
        }

        @Test
        @DisplayName("an install served under a PATH PREFIX - app.public-url=https://host/lc mints /lc/api/files/proxy-signed, and anchoring on the authority would stop it recognising its own links")
        void subPathInstall() {
            assertThat(FileProxyUrls.storageKeyOf("https://host/lc/api/files/proxy-signed?key=" + ENCODED_KEY + "&exp=1&sig=s"))
                    .isEqualTo(KEY);
        }

        @Test
        @DisplayName("a protocol-relative URL")
        void protocolRelative() {
            FileProxyUrls.ProxyUrl parsed = FileProxyUrls.parse("//host/api/files/proxy?key=" + ENCODED_KEY);
            assertThat(parsed).isNotNull();
            assertThat(parsed.key()).isEqualTo(KEY);
            // It names a host, so it is NOT the relative form the renderer bakes in.
            assertThat(parsed.absolute()).isTrue();
        }

        @Test
        @DisplayName("an uppercase scheme")
        void uppercaseScheme() {
            assertThat(FileProxyUrls.storageKeyOf("HTTPS://host/api/files/proxy?key=" + ENCODED_KEY))
                    .isEqualTo(KEY);
        }

        @Test
        @DisplayName("a FOREIGN host - parsing establishes no trust, so the caller can see the key and refuse it")
        void foreignHostIsParsedNotTrusted() {
            FileProxyUrls.ProxyUrl parsed = FileProxyUrls.parse(
                    "https://attacker.example/api/files/proxy-signed?key=" + ENCODED_KEY + "&exp=7&disposition=attachment&sig=zzz");
            assertThat(parsed).isNotNull();
            assertThat(parsed.key()).isEqualTo(KEY);
            assertThat(parsed.signed()).isTrue();
            assertThat(parsed.absolute()).isTrue();
            assertThat(parsed.exp()).isEqualTo(7L);
            assertThat(parsed.disposition()).isEqualTo("attachment");
            assertThat(parsed.sig()).isEqualTo("zzz");
        }

        @Test
        @DisplayName("a relative URL reports absolute=false and defaults the disposition to inline, matching the endpoint's own default")
        void relativeDefaults() {
            FileProxyUrls.ProxyUrl parsed = FileProxyUrls.parse("/api/files/proxy?key=" + ENCODED_KEY);
            assertThat(parsed.absolute()).isFalse();
            assertThat(parsed.signed()).isFalse();
            assertThat(parsed.disposition()).isEqualTo("inline");
            assertThat(parsed.exp()).isZero();
            assertThat(parsed.sig()).isNull();
        }
        @Test
        @DisplayName("a non-http scheme carrying the same path (no origin to trust)")
        void nonHttpScheme() {
            assertThat(FileProxyUrls.storageKeyOf("ftp://host/api/files/proxy?key=" + ENCODED_KEY)).isNull();
        }

        @Test
        @DisplayName("an origin with no path at all")
        void originOnly() {
            assertThat(FileProxyUrls.storageKeyOf("https://livecontext.ai")).isNull();
        }

        @Test
        @DisplayName("a fragment is cut off instead of ending up inside the key - `#frag` would name a file that exists nowhere")
        void fragmentIsNotPartOfTheKey() {
            assertThat(FileProxyUrls.storageKeyOf("/api/files/proxy?key=" + ENCODED_KEY + "#section"))
                    .isEqualTo(KEY);
        }
        @Test
        @DisplayName("a malformed percent-escape yields null, not the still-encoded text - a bogus key fails far away from the input that caused it")
        void malformedEscape() {
            assertThat(FileProxyUrls.storageKeyOf("/api/files/proxy?key=%zz")).isNull();
        }

        @Test
        @DisplayName("the endpoint appearing inside ANOTHER query string - that is a redirect target, not our URL")
        void markerInsideAnotherQuery() {
            assertThat(FileProxyUrls.storageKeyOf("/redirect?to=/api/files/proxy?key=" + ENCODED_KEY)).isNull();
        }

        @Test
        @DisplayName("a repeated key parameter takes the FIRST, the same one ServletRequest.getParameter hands the endpoint")
        void repeatedKeyTakesTheFirst() {
            assertThat(FileProxyUrls.storageKeyOf("/api/files/proxy?key=" + ENCODED_KEY + "&key=9%2Fother.png"))
                    .isEqualTo(KEY);
        }
        @Test
        @DisplayName("a different endpoint that starts the same way")
        void neighbouringEndpoint() {
            assertThat(FileProxyUrls.storageKeyOf("/api/files/proxy-signed-v2?key=" + ENCODED_KEY)).isNull();
            assertThat(FileProxyUrls.storageKeyOf("/api/files/proxyfoo?key=" + ENCODED_KEY)).isNull();
            assertThat(FileProxyUrls.storageKeyOf("/api/files/by-id/abc/raw?key=" + ENCODED_KEY)).isNull();
        }



        @Test
        @DisplayName("a non-numeric exp degrades to 0 rather than throwing - it can only make a signature check fail, which is the safe direction")
        void nonNumericExp() {
            assertThat(FileProxyUrls.parse("/api/files/proxy-signed?key=" + ENCODED_KEY + "&exp=soon&sig=s").exp())
                    .isZero();
        }
        @Test
        @DisplayName("a URL padded with whitespace is still entirely a URL - a workflow writing one into a text field can leave a trailing newline, and refusing it would leave that media uncopied and expired, the very bug this exists to prevent")
        void surroundingWhitespaceIsTrimmed() {
            assertThat(FileProxyUrls.storageKeyOf("  /api/files/proxy?key=" + ENCODED_KEY + "\n"))
                    .isEqualTo(KEY);
        }
    }

    @Nested
    @DisplayName("refuses")
    class Refuses {

        @Test
        @DisplayName("null and empty")
        void nullAndEmpty() {
            assertThat(FileProxyUrls.storageKeyOf(null)).isNull();
            assertThat(FileProxyUrls.storageKeyOf("")).isNull();
        }

        @Test
        @DisplayName("prose that merely quotes a proxy URL - rewriting it would replace the sentence with a bare link")
        void proseContainingAUrl() {
            String prose = "Download it here: https://livecontext.ai/api/files/proxy-signed?key=" + ENCODED_KEY;

            assertThat(FileProxyUrls.storageKeyOf(prose)).isNull();
        }

        @Test
        @DisplayName("a URL with no key parameter")
        void noKeyParameter() {
            assertThat(FileProxyUrls.storageKeyOf("/api/files/proxy?disposition=inline")).isNull();
            assertThat(FileProxyUrls.storageKeyOf("/api/files/proxy-signed?exp=1&sig=x")).isNull();
        }

        @Test
        @DisplayName("a blank key parameter")
        void blankKeyParameter() {
            assertThat(FileProxyUrls.storageKeyOf("/api/files/proxy?key=&disposition=inline")).isNull();
            assertThat(FileProxyUrls.storageKeyOf("/api/files/proxy?key=%20%20")).isNull();
        }

        @Test
        @DisplayName("another parameter whose name merely ENDS with 'key' - a substring search would return its value")
        void decoyParameterEndingInKey() {
            assertThat(FileProxyUrls.storageKeyOf("/api/files/proxy?sortKey=" + ENCODED_KEY)).isNull();
        }


        @Test
        @DisplayName("ordinary values found next to files in interface data")
        void ordinaryValues() {
            assertThat(FileProxyUrls.storageKeyOf("A sunset over the sea")).isNull();
            assertThat(FileProxyUrls.storageKeyOf("https://example.com/photo.jpg")).isNull();
            assertThat(FileProxyUrls.storageKeyOf("/api/files/upload")).isNull();
        }
    }

    @Nested
    @DisplayName("signedPath")
    class SignedPath {

        @Test
        @DisplayName("builds the relative signed URL that parse reads back field for field (key with slashes and a colon, base64url sig)")
        void roundTrips() {
            String url = FileProxyUrls.signedPath(KEY, 1787778662L, "inline", "Zm9v-YmFy_");

            assertThat(url).startsWith("/api/files/proxy-signed?key=" + ENCODED_KEY);
            FileProxyUrls.ProxyUrl parsed = FileProxyUrls.parse(url);
            assertThat(parsed.signed()).isTrue();
            assertThat(parsed.absolute()).isFalse();
            assertThat(parsed.key()).isEqualTo(KEY);
            assertThat(parsed.exp()).isEqualTo(1787778662L);
            assertThat(parsed.disposition()).isEqualTo("inline");
            assertThat(parsed.sig()).isEqualTo("Zm9v-YmFy_");
        }

        @Test
        @DisplayName("anything other than attachment is signed as inline, the endpoint's own default")
        void normalisesDisposition() {
            assertThat(FileProxyUrls.parse(FileProxyUrls.signedPath(KEY, 1L, "attachment", "s")).disposition()).isEqualTo("attachment");
            assertThat(FileProxyUrls.parse(FileProxyUrls.signedPath(KEY, 1L, "weird", "s")).disposition()).isEqualTo("inline");
        }
    }
}
