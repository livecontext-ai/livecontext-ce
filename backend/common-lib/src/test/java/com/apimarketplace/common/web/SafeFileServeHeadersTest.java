package com.apimarketplace.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-016 / LC-020: stored files are attacker-controlled in bytes AND declared type. Served inline
 * from the app origin, an html/svg/xml file runs as a first-party document next to the session.
 */
@DisplayName("SafeFileServeHeaders")
class SafeFileServeHeadersTest {

    @ParameterizedTest(name = "{0} may render inline")
    @ValueSource(strings = {
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml",
            "application/pdf", "text/plain", "text/csv", "application/json",
            "video/mp4", "audio/mpeg"
    })
    void renderableTypesStayInline(String mimeType) {
        assertThat(SafeFileServeHeaders.dispositionType(mimeType, true)).isEqualTo("inline");
        assertThat(SafeFileServeHeaders.dispositionType(mimeType, "inline")).isEqualTo("inline");
    }

    @ParameterizedTest(name = "{0} is forced to attachment even when inline is requested")
    @ValueSource(strings = {
            "text/html", "application/xhtml+xml", "text/xml", "application/xml",
            "text/javascript", "application/javascript", "application/octet-stream",
            "application/x-unknown-thing", "TEXT/HTML; charset=utf-8"
    })
    void activeOrUnknownTypesDownload(String mimeType) {
        assertThat(SafeFileServeHeaders.dispositionType(mimeType, true)).isEqualTo("attachment");
        assertThat(SafeFileServeHeaders.dispositionType(mimeType, "inline")).isEqualTo("attachment");
    }

    @Test
    @DisplayName("absent type is not renderable, and an explicit attachment request always wins")
    void absentTypeAndExplicitAttachment() {
        assertThat(SafeFileServeHeaders.dispositionType(null, true)).isEqualTo("attachment");
        assertThat(SafeFileServeHeaders.dispositionType("  ", true)).isEqualTo("attachment");
        assertThat(SafeFileServeHeaders.dispositionType("image/png", false)).isEqualTo("attachment");
        assertThat(SafeFileServeHeaders.dispositionType("image/png", "ATTACHMENT")).isEqualTo("attachment");
        // Any other value of the query parameter means inline (the endpoints' contract).
        assertThat(SafeFileServeHeaders.dispositionType("image/png", (String) null)).isEqualTo("inline");
    }

    @Test
    @DisplayName("parameters and case are ignored, as a browser does")
    void normalisesType() {
        assertThat(SafeFileServeHeaders.isInlineSafe(" IMAGE/PNG ")).isTrue();
        assertThat(SafeFileServeHeaders.isInlineSafe("text/plain;charset=UTF-8")).isTrue();
    }

    @Test
    @DisplayName("the policy denies script and subresources and sandboxes the document")
    void policyIsRestrictive() {
        String csp = SafeFileServeHeaders.CONTENT_SECURITY_POLICY;
        assertThat(csp).startsWith("default-src 'none'");
        assertThat(csp).endsWith("; sandbox");
        assertThat(csp).doesNotContain("script-src").doesNotContain("allow-scripts");
    }

    @Test
    @DisplayName("applyTo adds nosniff + sandboxed CSP to an html/svg response")
    void applyToAddsBothHeaders() {
        ResponseEntity<Void> r = SafeFileServeHeaders.applyTo(ResponseEntity.ok(), "image/svg+xml").build();
        assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeaders().getFirst("Content-Security-Policy"))
                .isEqualTo(SafeFileServeHeaders.CONTENT_SECURITY_POLICY);
    }

    @Test
    @DisplayName("PDF keeps nosniff but gets no sandbox CSP (the sandbox blocks Chromium's PDF viewer)")
    void pdfHasNoSandbox() {
        ResponseEntity<Void> r = SafeFileServeHeaders.applyTo(ResponseEntity.ok(), "application/pdf").build();
        assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeaders().containsKey("Content-Security-Policy")).isFalse();
        // A PDF declared with a parameter is still PDF; anything else still gets the policy.
        assertThat(SafeFileServeHeaders.contentSecurityPolicy("application/pdf; x=y")).isNull();
        assertThat(SafeFileServeHeaders.contentSecurityPolicy(null))
                .isEqualTo(SafeFileServeHeaders.CONTENT_SECURITY_POLICY);
    }
}
