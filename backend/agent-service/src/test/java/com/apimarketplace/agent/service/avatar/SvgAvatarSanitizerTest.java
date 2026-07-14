package com.apimarketplace.agent.service.avatar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sanitizer is the first of two layers (with the serve-time no-script CSP) that make
 * LLM-generated SVG avatars safe to store and serve anonymously from the app origin.
 * Every test here encodes an attack or a keep-guarantee.
 */
class SvgAvatarSanitizerTest {

    private final SvgAvatarSanitizer sanitizer = new SvgAvatarSanitizer();

    @Test
    @DisplayName("keeps shapes, gradients and SMIL animation (the avatar vocabulary)")
    void keepsAvatarVocabulary() {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
                  <defs>
                    <linearGradient id="bg"><stop offset="0%" stop-color="#7C3AED"/><stop offset="100%" stop-color="#4338CA"/></linearGradient>
                  </defs>
                  <circle cx="50" cy="50" r="50" fill="url(#bg)"/>
                  <g opacity="0.5"><rect x="10" y="10" width="20" height="20" rx="4" fill="white">
                    <animateTransform attributeName="transform" type="rotate" from="0 50 50" to="360 50 50" dur="20s" repeatCount="indefinite"/>
                  </rect></g>
                </svg>""";

        String out = sanitizer.sanitize(svg);

        assertThat(out).contains("<linearGradient", "<stop", "<circle", "<rect", "<animateTransform");
        assertThat(out).contains("fill=\"url(#bg)\"");
    }

    @Test
    @DisplayName("strips <script> elements entirely")
    void stripsScript() {
        String out = sanitizer.sanitize(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script><circle cx=\"1\" cy=\"1\" r=\"1\"/></svg>");
        assertThat(out).doesNotContain("script").doesNotContain("alert");
        assertThat(out).contains("<circle");
    }

    @Test
    @DisplayName("strips event-handler attributes (onload, onclick, ...)")
    void stripsEventHandlers() {
        String out = sanitizer.sanitize(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"evil()\"><circle onclick=\"evil()\" cx=\"1\" cy=\"1\" r=\"1\"/></svg>");
        assertThat(out).doesNotContain("onload").doesNotContain("onclick").doesNotContain("evil");
    }

    @Test
    @DisplayName("strips foreignObject / image / use smuggling vectors")
    void stripsSmugglingElements() {
        String out = sanitizer.sanitize("""
                <svg xmlns="http://www.w3.org/2000/svg">
                  <foreignObject><body xmlns="http://www.w3.org/1999/xhtml"><script>1</script></body></foreignObject>
                  <image href="https://evil/x.png"/>
                  <use href="https://evil/x.svg#a"/>
                  <circle cx="1" cy="1" r="1"/>
                </svg>""");
        assertThat(out).doesNotContain("foreignObject").doesNotContain("image").doesNotContain("evil");
        assertThat(out).contains("<circle");
    }

    @Test
    @DisplayName("strips href/xlink:href and non-local url(...) references")
    void stripsExternalReferences() {
        String out = sanitizer.sanitize("""
                <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
                  <circle cx="1" cy="1" r="1" fill="url(https://evil/grad)"/>
                  <rect x="1" y="1" width="2" height="2" fill="url(#local)"/>
                </svg>""");
        assertThat(out).doesNotContain("evil");
        assertThat(out).contains("url(#local)");
    }

    @Test
    @DisplayName("strips style attributes and <style> blocks (CSS url()/import escape hatch)")
    void stripsStyles() {
        String out = sanitizer.sanitize(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><style>*{fill:red}</style><circle style=\"fill:url(https://evil)\" cx=\"1\" cy=\"1\" r=\"1\"/></svg>");
        assertThat(out).doesNotContain("style").doesNotContain("evil");
    }

    @Test
    @DisplayName("rejects a DOCTYPE (XXE) instead of parsing it")
    void rejectsDoctype() {
        assertThatThrownBy(() -> sanitizer.sanitize(
                "<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><svg xmlns=\"http://www.w3.org/2000/svg\">&x;</svg>"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects non-SVG roots, unparseable markup, blank and oversized input")
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> sanitizer.sanitize("<html xmlns=\"http://www.w3.org/1999/xhtml\"></html>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sanitizer.sanitize("not xml at all"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sanitizer.sanitize("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sanitizer.sanitize("<svg>" + "a".repeat(200 * 1024) + "</svg>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    @Test
    @DisplayName("always pins the SVG namespace on the root (served standalone as image/svg+xml)")
    void pinsNamespace() {
        String out = sanitizer.sanitize("<svg viewBox=\"0 0 100 100\"><circle cx=\"1\" cy=\"1\" r=\"1\"/></svg>");
        assertThat(out).contains("xmlns=\"http://www.w3.org/2000/svg\"");
    }
}
