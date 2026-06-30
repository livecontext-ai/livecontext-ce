package com.apimarketplace.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentDispositionsTest {

    /**
     * The regression: a macOS screenshot name "Capture d'écran 2026-06-16 114029.png" has a
     * curly apostrophe (U+2019, code point 8217) + accented é. Setting that raw into a response
     * header threw {@code IllegalArgumentException: Unicode character cannot be encoded (>255)}
     * and every such download 500'd. The header value MUST be pure ASCII.
     */
    @Test
    @DisplayName("non-ASCII filename (U+2019 curly apostrophe + accent) yields a pure-ASCII header")
    void nonAsciiFilenameIsAsciiSafe() {
        String name = "Capture d’écran 2026-06-16 114029.png"; // d’écran ...
        String header = ContentDispositions.attachment(name);

        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            assertTrue(c <= 0x7F,
                    "header must be pure ASCII (ISO-8859-1 safe) but found code point "
                            + (int) c + " at index " + i + ": [" + header + "]");
        }
        assertTrue(header.startsWith("attachment; filename=\""), header);
        // RFC 5987 filename* carries the real UTF-8 name: U+2019 -> %E2%80%99, é -> %C3%A9
        assertTrue(header.contains("filename*=UTF-8''"), header);
        assertTrue(header.contains("Capture%20d%E2%80%99%C3%A9cran"), header);
        // ASCII fallback: the two non-ASCII chars ('é) are sanitized to '_'
        assertTrue(header.contains("filename=\"Capture d__cran 2026-06-16 114029.png\""), header);
    }

    @Test
    @DisplayName("plain ASCII filename round-trips unchanged in both parts")
    void asciiFilenameUnchanged() {
        String header = ContentDispositions.inline("photo_2026.png");
        assertTrue(header.startsWith("inline; filename=\"photo_2026.png\""), header);
        assertTrue(header.endsWith("filename*=UTF-8''photo_2026.png"), header);
    }

    @Test
    @DisplayName("of() preserves the inline/attachment disposition")
    void ofPreservesType() {
        assertTrue(ContentDispositions.of("inline", "x.png").startsWith("inline; "));
        assertTrue(ContentDispositions.of("attachment", "x.png").startsWith("attachment; "));
    }

    @Test
    @DisplayName("null/blank filename falls back to 'file'")
    void nullOrBlankFilename() {
        assertTrue(ContentDispositions.attachment(null).contains("filename=\"file\""));
        assertTrue(ContentDispositions.attachment("   ").contains("filename=\"file\""));
    }

    @Test
    @DisplayName("a quote or backslash in the name cannot break out of the quoted-string")
    void quoteAndBackslashAreSanitized() {
        String header = ContentDispositions.attachment("a\"b\\c.png");
        assertTrue(header.contains("filename=\"a_b_c.png\""), header);
        // still pure ASCII
        for (int i = 0; i < header.length(); i++) {
            assertTrue(header.charAt(i) <= 0x7F);
        }
    }

    @Test
    @DisplayName("spaces are %20 in filename* (not '+') so browsers decode them correctly")
    void spacesArePercent20() {
        String header = ContentDispositions.attachment("my file.png");
        assertTrue(header.contains("filename*=UTF-8''my%20file.png"), header);
        assertFalse(header.contains("my+file"), header);
    }
}
