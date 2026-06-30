package com.apimarketplace.publication.ce.tls;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.security.cert.CertificateException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeTlsController")
class CeTlsControllerTest {

    @Mock
    private CeTlsProbeService probeService;
    @Mock
    private CeCustomTrustStore trustStore;

    private CeTlsController controller;

    private static final String ADMIN = "ADMIN";
    private static final String NON_ADMIN = "USER";

    @BeforeEach
    void setUp() {
        controller = new CeTlsController(probeService, trustStore);
    }

    @Test
    @DisplayName("probe is forbidden for non-admins (no probe is run)")
    void probeForbiddenForNonAdmin() {
        ResponseEntity<?> response = controller.probe(NON_ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(probeService);
    }

    @Test
    @DisplayName("probe delegates to the probe service (configured cloud host only) for an admin")
    void probeDelegatesForAdmin() {
        CeTlsProbeService.ProbeResult result =
                new CeTlsProbeService.ProbeResult(true, true, "auth.example",
                        "CN=Proxy CA", "CN=Proxy CA", "ab12", "-----BEGIN CERTIFICATE-----\n", true, null);
        when(probeService.probe()).thenReturn(result);

        ResponseEntity<?> response = controller.probe(ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(result);
    }

    @Test
    @DisplayName("trust is forbidden for non-admins (no CA is stored)")
    void trustForbiddenForNonAdmin() {
        ResponseEntity<?> response = controller.trust(Map.of("pem", CeTlsTestCerts.CA_PEM), NON_ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(trustStore);
    }

    @Test
    @DisplayName("trust rejects a request without a pem field")
    void trustRejectsMissingPem() {
        ResponseEntity<?> response = controller.trust(Map.of(), ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(asMap(response)).containsEntry("error", "invalid_request");
        verify(trustStore, never()).listTrustedCas();
    }

    @Test
    @DisplayName("trust stores a valid CA and returns its identity")
    void trustStoresValidCa() throws Exception {
        when(trustStore.addTrustedCa(anyString()))
                .thenReturn(new CeCustomTrustStore.TrustedCa("CN=Proxy CA", "CN=Proxy CA", "deadbeef"));

        ResponseEntity<?> response = controller.trust(Map.of("pem", CeTlsTestCerts.CA_PEM), ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asMap(response)).containsEntry("trusted", true);
        assertThat(asMap(response)).containsEntry("sha256", "deadbeef");
        verify(trustStore).addTrustedCa(CeTlsTestCerts.CA_PEM);
    }

    @Test
    @DisplayName("trust returns 400 invalid_certificate when the bytes are not an X.509 cert")
    void trustRejectsInvalidCertificate() throws Exception {
        when(trustStore.addTrustedCa(anyString()))
                .thenThrow(new CertificateException("bad cert"));

        ResponseEntity<?> response = controller.trust(Map.of("pem", "garbage"), ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(asMap(response)).containsEntry("error", "invalid_certificate");
    }

    @Test
    @DisplayName("trust returns 500 trust_failed when the certificate cannot be persisted")
    void trustReturns500OnStorageFailure() throws Exception {
        when(trustStore.addTrustedCa(anyString()))
                .thenThrow(new java.io.IOException("disk full"));

        ResponseEntity<?> response = controller.trust(Map.of("pem", CeTlsTestCerts.CA_PEM), ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(asMap(response)).containsEntry("error", "trust_failed");
    }

    @Test
    @DisplayName("trusted lists CAs for an admin and is forbidden otherwise")
    void trustedListing() {
        when(trustStore.listTrustedCas()).thenReturn(java.util.List.of(
                new CeCustomTrustStore.TrustedCa("CN=Proxy CA", "CN=Proxy CA", "abc")));

        assertThat(controller.trusted(NON_ADMIN).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<?> ok = controller.trusted(ADMIN);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asMap(ok)).containsKey("trusted");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}
