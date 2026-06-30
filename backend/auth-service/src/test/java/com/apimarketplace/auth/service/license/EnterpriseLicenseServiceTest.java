package com.apimarketplace.auth.service.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class EnterpriseLicenseServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-15T00:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void validLicenseExposesNumericAndUnlimitedResourceLimits() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        Path licenseFile = writeLicense(keyPair.getPrivate(), payload("SELF_HOSTED_ENTERPRISE", NOW.minusSeconds(60), NOW.plusSeconds(3600)));

        EnterpriseLicenseService service = service(licenseFile, publicKeyPem(keyPair));

        EnterpriseLicenseStatus status = service.currentStatus();
        EnterpriseLicenseResourceLimit workflow = service.resolveResourceLimit("WORKFLOW");
        EnterpriseLicenseResourceLimit agent = service.resolveResourceLimit("AGENT");

        assertThat(status.active()).isTrue();
        assertThat(status.planCode()).isEqualTo(EnterpriseLicenseService.SELF_HOSTED_PLAN_CODE);
        assertThat(status.customerName()).isEqualTo("Example Corp");
        assertThat(workflow.licensed()).isTrue();
        assertThat(workflow.limit()).isEqualTo(25);
        assertThat(agent.licensed()).isTrue();
        assertThat(agent.limit()).isNull();
    }

    @Test
    void rsaLicenseEnvelopeIsAccepted() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        Path licenseFile = writeLicense(
                keyPair.getPrivate(),
                payload("SELF_HOSTED_ENTERPRISE", NOW.minusSeconds(60), NOW.plusSeconds(3600)),
                "SHA256withRSA");

        EnterpriseLicenseStatus status = service(licenseFile, publicKeyPem(keyPair)).currentStatus();

        assertThat(status.active()).isTrue();
        assertThat(status.planCode()).isEqualTo(EnterpriseLicenseService.SELF_HOSTED_PLAN_CODE);
    }

    @Test
    void invalidSignatureIsInactive() throws Exception {
        KeyPair signingKey = ed25519KeyPair();
        KeyPair verificationKey = ed25519KeyPair();
        Path licenseFile = writeLicense(signingKey.getPrivate(), payload("SELF_HOSTED_ENTERPRISE", NOW.minusSeconds(60), NOW.plusSeconds(3600)));

        EnterpriseLicenseService service = service(licenseFile, publicKeyPem(verificationKey));

        assertThat(service.currentStatus().active()).isFalse();
        assertThat(service.currentStatus().reason()).isEqualTo("license_signature_invalid");
    }

    @Test
    void expiredLicenseIsInactive() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        Path licenseFile = writeLicense(keyPair.getPrivate(), payload("SELF_HOSTED_ENTERPRISE", NOW.minusSeconds(3600), NOW.minusSeconds(1)));

        EnterpriseLicenseStatus status = service(licenseFile, publicKeyPem(keyPair)).currentStatus();

        assertThat(status.active()).isFalse();
        assertThat(status.reason()).isEqualTo("license_expired");
    }

    @Test
    void wrongEditionIsInactive() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        Path licenseFile = writeLicense(keyPair.getPrivate(), payload("CLOUD", NOW.minusSeconds(60), NOW.plusSeconds(3600)));

        EnterpriseLicenseStatus status = service(licenseFile, publicKeyPem(keyPair)).currentStatus();

        assertThat(status.active()).isFalse();
        assertThat(status.reason()).isEqualTo("license_edition_unsupported");
    }

    @Test
    void missingResourceEntitlementFailsClosedWithZeroLimit() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        ObjectNode payload = payload("SELF_HOSTED_ENTERPRISE", NOW.minusSeconds(60), NOW.plusSeconds(3600));
        ((ObjectNode) payload.get("entitlements")).remove("resources.interface.max");
        Path licenseFile = writeLicense(keyPair.getPrivate(), payload);

        EnterpriseLicenseResourceLimit limit = service(licenseFile, publicKeyPem(keyPair))
                .resolveResourceLimit("INTERFACE");

        assertThat(limit.licensed()).isTrue();
        assertThat(limit.limit()).isZero();
    }

    @Test
    void missingLicensePathIsInactive() {
        EnterpriseLicenseService service = new EnterpriseLicenseService(
                objectMapper,
                "",
                "",
                "",
                Clock.fixed(NOW, ZoneOffset.UTC));

        EnterpriseLicenseStatus status = service.currentStatus();

        assertThat(status.active()).isFalse();
        assertThat(status.reason()).isEqualTo("license_path_missing");
    }

    private EnterpriseLicenseService service(Path licenseFile, String publicKeyPem) {
        return new EnterpriseLicenseService(
                objectMapper,
                licenseFile.toString(),
                publicKeyPem,
                "",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Path writeLicense(PrivateKey privateKey, ObjectNode payload) throws Exception {
        return writeLicense(privateKey, payload, "Ed25519");
    }

    private Path writeLicense(PrivateKey privateKey, ObjectNode payload, String algorithm) throws Exception {
        byte[] payloadBytes = objectMapper.writeValueAsBytes(payload);
        byte[] signature = sign(payloadBytes, privateKey, algorithm);

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("version", 1);
        envelope.put("algorithm", algorithm);
        envelope.put("key_id", "test-key");
        envelope.put("payload", Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes));
        envelope.put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature));

        Path licenseFile = tempDir.resolve("license.json");
        Files.writeString(licenseFile, objectMapper.writeValueAsString(envelope), StandardCharsets.UTF_8);
        return licenseFile;
    }

    private ObjectNode payload(String edition, Instant validFrom, Instant validUntil) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("license_id", "lic-test");
        payload.put("customer_name", "Example Corp");
        payload.put("edition", edition);
        payload.put("valid_from", validFrom.toString());
        payload.put("valid_until", validUntil.toString());

        ObjectNode entitlements = payload.putObject("entitlements");
        entitlements.put("resources.workflow.max", 25);
        entitlements.putNull("resources.agent.max");
        entitlements.put("resources.datasource.max", 10);
        entitlements.put("resources.interface.max", 5);
        entitlements.put("resources.application.max", 3);
        return payload;
    }

    private static KeyPair ed25519KeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static byte[] sign(byte[] payload, PrivateKey privateKey, String algorithm) throws Exception {
        Signature signature = Signature.getInstance(algorithm);
        signature.initSign(privateKey);
        signature.update(payload);
        return signature.sign();
    }

    private static String publicKeyPem(KeyPair keyPair) {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(keyPair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n";
    }
}
