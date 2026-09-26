package com.apimarketplace.orchestrator.tools.websearch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BrowserAgentRelayTenantSigner")
class BrowserAgentRelayTenantSignerTest {

    private final BrowserAgentRelayTenantSigner signer = new BrowserAgentRelayTenantSigner();

    @Test
    @DisplayName("a signature verifies for its own tenant only")
    void signatureBindsTheTenant() {
        String sig = signer.sign("42");

        assertThat(signer.verify("42", sig)).isTrue();
        assertThat(signer.verify("7", sig)).isFalse();
    }

    @Test
    @DisplayName("a signature from another instance (another boot, another process) never verifies")
    void signatureFromAnotherKeyIsRejected() {
        String foreign = new BrowserAgentRelayTenantSigner().sign("42");

        assertThat(signer.verify("42", foreign)).isFalse();
    }

    @Test
    @DisplayName("blank, missing and malformed inputs are refused without throwing")
    void malformedInputsFailClosed() {
        String sig = signer.sign("42");

        assertThat(signer.verify(null, sig)).isFalse();
        assertThat(signer.verify(" ", sig)).isFalse();
        assertThat(signer.verify("42", null)).isFalse();
        assertThat(signer.verify("42", "")).isFalse();
        assertThat(signer.verify("42", "not base64 at all !!")).isFalse();
        // Standard base64 (+ and /) is not the URL-safe form the signer emits.
        assertThat(signer.verify("42", "ab+/cd==")).isFalse();
        // A truncated signature is a different MAC length: refused.
        assertThat(signer.verify("42", sig.substring(0, sig.length() - 2))).isFalse();
    }
}
