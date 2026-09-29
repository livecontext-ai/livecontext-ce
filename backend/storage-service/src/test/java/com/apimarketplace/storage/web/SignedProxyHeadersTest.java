package com.apimarketplace.storage.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SignedProxyHeaders - a signed link never runs script on the app origin")
class SignedProxyHeadersTest {

    @ParameterizedTest(name = "{0} gets the no-script sandbox policy")
    @ValueSource(strings = {"text/html", "text/html; charset=utf-8", "image/svg+xml", "application/xhtml+xml",
            "text/xml", "application/xml", "application/javascript", "TEXT/HTML"})
    void activeTypesAreSandboxed(String mime) {
        ResponseEntity<Void> r = SignedProxyHeaders.guard(ResponseEntity.ok(), mime).build();

        assertThat(r.getHeaders().getFirst("Content-Security-Policy")).isEqualTo(SignedProxyHeaders.NO_SCRIPT_POLICY);
        assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @ParameterizedTest(name = "{0} plays untouched (nosniff only)")
    @ValueSource(strings = {"video/mp4", "audio/mpeg", "image/png", "application/pdf"})
    void passiveTypesKeepWorking(String mime) {
        ResponseEntity<Void> r = SignedProxyHeaders.guard(ResponseEntity.ok(), mime).build();

        assertThat(r.getHeaders().getFirst("Content-Security-Policy")).isNull();
        assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @DisplayName("an unknown type still gets nosniff, so the browser cannot promote it to HTML")
    void unknownType() {
        ResponseEntity<Void> r = SignedProxyHeaders.guard(ResponseEntity.ok(), null).build();

        assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeaders().getFirst("Content-Security-Policy")).isNull();
    }
}
