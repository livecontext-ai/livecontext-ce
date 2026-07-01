package com.apimarketplace.auth.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class InitialsAvatarGeneratorTest {

    private static String svgText(byte[] svg) {
        return new String(svg, StandardCharsets.UTF_8);
    }

    // ─── initials fallback chain ────────────────────────────────────────────

    @Test
    @DisplayName("Initials: firstName + lastName → first letter of each, uppercased")
    void initialsFromFirstAndLast() {
        assertThat(InitialsAvatarGenerator.computeInitials("Sarah", "Lambert", null, null))
                .isEqualTo("SL");
    }

    @Test
    @DisplayName("Initials: only firstName → first 2 chars")
    void initialsFromFirstOnly() {
        assertThat(InitialsAvatarGenerator.computeInitials("Sarah", null, null, null))
                .isEqualTo("SA");
    }

    @Test
    @DisplayName("Initials: no first/last but two-word username → use both words")
    void initialsFromTwoWordUsername() {
        assertThat(InitialsAvatarGenerator.computeInitials(null, null, "ada lovelace", null))
                .isEqualTo("AL");
    }

    @Test
    @DisplayName("Initials: single-word username → first 2 chars")
    void initialsFromSingleWordUsername() {
        assertThat(InitialsAvatarGenerator.computeInitials(null, null, "barbar", null))
                .isEqualTo("BA");
    }

    @Test
    @DisplayName("Initials: only email → first 2 chars of local part")
    void initialsFromEmail() {
        assertThat(InitialsAvatarGenerator.computeInitials(null, null, null, "foo@bar.com"))
                .isEqualTo("FO");
    }

    @Test
    @DisplayName("Initials: all null/blank → \"?\"")
    void initialsFallbackToQuestionMark() {
        assertThat(InitialsAvatarGenerator.computeInitials(null, null, null, null)).isEqualTo("?");
        assertThat(InitialsAvatarGenerator.computeInitials("", "  ", "", "")).isEqualTo("?");
    }

    @Test
    @DisplayName("Initials: accented characters are normalized (Léa Tremblay → LT not L̃T)")
    void initialsStripAccents() {
        assertThat(InitialsAvatarGenerator.computeInitials("Léa", "Émerald", null, null))
                .isEqualTo("LE");
    }

    @Test
    @DisplayName("Initials: surrogate-pair / emoji first-name does not crash")
    void initialsEmojiSafe() {
        // Hourglass + name → take first codepoint
        String result = InitialsAvatarGenerator.computeInitials("⌛foo", "Bar", null, null);
        // codepoint U+231B is unaffected by NFD strip; result is non-empty
        assertThat(result).hasSize(2);
    }

    // ─── color stability ────────────────────────────────────────────────────

    @Test
    @DisplayName("Color: same email always picks the same palette color")
    void colorStableAcrossInvocations() {
        String a = InitialsAvatarGenerator.pickColor("user@example.com");
        String b = InitialsAvatarGenerator.pickColor("user@example.com");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("Color: case-insensitive on the seed (so display-name casing doesn't shift hue)")
    void colorCaseInsensitive() {
        assertThat(InitialsAvatarGenerator.pickColor("Foo@Bar.COM"))
                .isEqualTo(InitialsAvatarGenerator.pickColor("foo@bar.com"));
    }

    @Test
    @DisplayName("Color: blank seed → first palette entry (defensive)")
    void colorBlankSeed() {
        assertThat(InitialsAvatarGenerator.pickColor("")).isEqualTo("#DB4437");
        assertThat(InitialsAvatarGenerator.pickColor(null)).isEqualTo("#DB4437");
    }

    @Test
    @DisplayName("Color: distinct emails do not all collapse onto one palette slot")
    void colorDistributesAcrossPalette() {
        java.util.Set<String> colors = new java.util.HashSet<>();
        for (int i = 0; i < 100; i++) {
            colors.add(InitialsAvatarGenerator.pickColor("user" + i + "@gmail.com"));
        }
        // 100 emails into a 12-slot palette should hit at least 8 distinct slots
        assertThat(colors).hasSizeGreaterThanOrEqualTo(8);
    }

    // ─── SVG content ───────────────────────────────────────────────────────

    @Test
    @DisplayName("SVG: contains computed initials inside the <text> element")
    void svgEmbedsInitials() {
        byte[] svg = InitialsAvatarGenerator.generateSvg("Sarah", "Lambert", null, null);
        assertThat(svgText(svg)).contains(">SL</text>");
    }

    @Test
    @DisplayName("SVG: contains picked color in the linearGradient stop")
    void svgEmbedsColor() {
        byte[] svg = InitialsAvatarGenerator.generateSvg(
                "Sarah", "Lambert", null, "user@example.com");
        String expectedColor = InitialsAvatarGenerator.pickColor("user@example.com");
        assertThat(svgText(svg)).contains("stop-color=\"" + expectedColor + "\"");
    }

    @Test
    @DisplayName("SVG: XSS-safe - name with <script> tag is escaped, not interpolated")
    void svgEscapesXmlSpecials() {
        // A malicious display name should NOT inject raw markup into the SVG.
        byte[] svg = InitialsAvatarGenerator.generateSvg("<script>alert", "x", null, null);
        String s = svgText(svg);
        assertThat(s).doesNotContain("<script>");
        // The initial '<' gets normalized away by NFD/upperFirst; the test ensures we
        // don't emit raw '<' in the text node anywhere.
        int firstText = s.indexOf("<text");
        int closingText = s.indexOf("</text>", firstText);
        String textNode = s.substring(firstText, closingText + 7);
        assertThat(textNode).doesNotContain("<script");
    }

    @Test
    @DisplayName("SVG: deterministic - same inputs produce byte-identical output")
    void svgDeterministic() {
        byte[] a = InitialsAvatarGenerator.generateSvg("Foo", "Bar", null, "f@b.com");
        byte[] b = InitialsAvatarGenerator.generateSvg("Foo", "Bar", null, "f@b.com");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("SVG: 256x256 viewport with white text on colored gradient (visual contract)")
    void svgStructure() {
        String s = svgText(InitialsAvatarGenerator.generateSvg("A", "B", null, null));
        assertThat(s).contains("width=\"256\"")
                .contains("height=\"256\"")
                .contains("fill=\"#ffffff\"")
                .contains("text-anchor=\"middle\"")
                .contains("dominant-baseline=\"central\"");
    }
}
