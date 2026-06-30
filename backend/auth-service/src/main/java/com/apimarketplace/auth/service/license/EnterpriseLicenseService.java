package com.apimarketplace.auth.service.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Verifies and exposes the signed offline license used by Self-Hosted Enterprise.
 *
 * <p>The license file is a detached-signature envelope. The exact payload bytes
 * are base64url encoded and signed; verification never canonicalizes JSON, so
 * license issuance can be implemented in any language without relying on
 * object-key ordering rules.
 */
@Service
public class EnterpriseLicenseService {

    public static final String SELF_HOSTED_PLAN_CODE = "SELF_HOSTED_ENTERPRISE";
    public static final String NO_LICENSE_PLAN_CODE = "__NONE__";

    private static final Logger log = LoggerFactory.getLogger(EnterpriseLicenseService.class);
    private static final String SUPPORTED_EDITION = "SELF_HOSTED_ENTERPRISE";
    private static final String DEFAULT_ALGORITHM = "Ed25519";
    private static final Set<String> SUPPORTED_ALGORITHMS = Set.of("Ed25519", "SHA256withRSA");
    private static final Map<String, String> RESOURCE_LIMIT_KEYS = Map.of(
            "WORKFLOW", "resources.workflow.max",
            "AGENT", "resources.agent.max",
            "DATASOURCE", "resources.datasource.max",
            "INTERFACE", "resources.interface.max",
            "APPLICATION", "resources.application.max");

    private final ObjectMapper objectMapper;
    private final String licensePath;
    private final String publicKeyPem;
    private final String publicKeyPath;
    private final Clock clock;

    @Autowired
    public EnterpriseLicenseService(
            ObjectMapper objectMapper,
            @Value("${enterprise.license.path:}") String licensePath,
            @Value("${enterprise.license.public-key-pem:}") String publicKeyPem,
            @Value("${enterprise.license.public-key-path:}") String publicKeyPath) {
        this(objectMapper, licensePath, publicKeyPem, publicKeyPath, Clock.systemUTC());
    }

    EnterpriseLicenseService(
            ObjectMapper objectMapper,
            String licensePath,
            String publicKeyPem,
            String publicKeyPath,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.licensePath = licensePath;
        this.publicKeyPem = publicKeyPem;
        this.publicKeyPath = publicKeyPath;
        this.clock = clock;
    }

    public EnterpriseLicenseStatus currentStatus() {
        if (isBlank(licensePath)) {
            return EnterpriseLicenseStatus.inactive("license_path_missing");
        }
        try {
            String envelopeJson = Files.readString(Path.of(licensePath), StandardCharsets.UTF_8);
            JsonNode envelope = objectMapper.readTree(envelopeJson);
            String payloadEncoded = text(envelope, "payload");
            String signatureEncoded = text(envelope, "signature");
            String algorithm = textOrDefault(envelope, "algorithm", DEFAULT_ALGORITHM);
            if (isBlank(payloadEncoded) || isBlank(signatureEncoded)) {
                return EnterpriseLicenseStatus.inactive("license_envelope_incomplete");
            }
            if (!SUPPORTED_ALGORITHMS.contains(algorithm)) {
                return EnterpriseLicenseStatus.inactive("license_algorithm_unsupported");
            }

            byte[] payloadBytes = decodeBase64Url(payloadEncoded);
            byte[] signatureBytes = decodeBase64Url(signatureEncoded);
            PublicKey publicKey = loadPublicKey(algorithm);
            if (!verify(payloadBytes, signatureBytes, publicKey, algorithm)) {
                return EnterpriseLicenseStatus.inactive("license_signature_invalid");
            }

            JsonNode payload = objectMapper.readTree(payloadBytes);
            return validatePayload(payload);
        } catch (Exception ex) {
            log.warn("Self-hosted enterprise license verification failed: {}", ex.getMessage());
            return EnterpriseLicenseStatus.inactive("license_verification_failed");
        }
    }

    public EnterpriseLicenseResourceLimit resolveResourceLimit(String resourceType) {
        EnterpriseLicenseStatus status = currentStatus();
        if (!status.active()) {
            return EnterpriseLicenseResourceLimit.unlicensed();
        }

        String key = entitlementKeyFor(resourceType);
        if (key == null) {
            return new EnterpriseLicenseResourceLimit(true, status.planCode(), 0);
        }

        JsonNode value = status.entitlements().get(key);
        if (value == null) {
            return new EnterpriseLicenseResourceLimit(true, status.planCode(), 0);
        }
        if (value.isNull()) {
            return new EnterpriseLicenseResourceLimit(true, status.planCode(), null);
        }
        if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0) {
            return new EnterpriseLicenseResourceLimit(true, status.planCode(), value.intValue());
        }

        log.warn("Invalid license entitlement value for {}; refusing resource creation", key);
        return new EnterpriseLicenseResourceLimit(true, status.planCode(), 0);
    }

    private EnterpriseLicenseStatus validatePayload(JsonNode payload) {
        String edition = normalizeEdition(text(payload, "edition"));
        if (!SUPPORTED_EDITION.equals(edition)) {
            return EnterpriseLicenseStatus.inactive("license_edition_unsupported");
        }

        Instant validFrom = instant(payload, "valid_from");
        Instant validUntil = instant(payload, "valid_until");
        if (validFrom == null || validUntil == null) {
            return EnterpriseLicenseStatus.inactive("license_validity_missing");
        }

        Instant now = clock.instant();
        if (now.isBefore(validFrom)) {
            return EnterpriseLicenseStatus.inactive("license_not_yet_valid");
        }
        if (!now.isBefore(validUntil)) {
            return EnterpriseLicenseStatus.inactive("license_expired");
        }

        JsonNode entitlements = payload.path("entitlements");
        if (!entitlements.isObject()) {
            return EnterpriseLicenseStatus.inactive("license_entitlements_missing");
        }

        String planCode = textOrDefault(payload, "plan_code", SELF_HOSTED_PLAN_CODE);
        return new EnterpriseLicenseStatus(
                true,
                "active",
                text(payload, "license_id"),
                text(payload, "customer_name"),
                planCode,
                validUntil,
                entitlements);
    }

    private PublicKey loadPublicKey(String algorithm) throws Exception {
        String pem = publicKeyMaterial();
        if (isBlank(pem)) {
            throw new IllegalStateException("enterprise license public key is not configured");
        }
        byte[] keyBytes = Base64.getMimeDecoder().decode(stripPemArmor(pem));
        String keyFactoryAlgorithm = "Ed25519".equals(algorithm) ? "Ed25519" : "RSA";
        return KeyFactory.getInstance(keyFactoryAlgorithm).generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    private String publicKeyMaterial() throws Exception {
        if (!isBlank(publicKeyPem)) {
            return publicKeyPem.replace("\\n", "\n");
        }
        if (!isBlank(publicKeyPath)) {
            return Files.readString(Path.of(publicKeyPath), StandardCharsets.UTF_8);
        }
        return "";
    }

    private static boolean verify(byte[] payloadBytes, byte[] signatureBytes, PublicKey publicKey, String algorithm)
            throws Exception {
        Signature verifier = Signature.getInstance(algorithm);
        verifier.initVerify(publicKey);
        verifier.update(payloadBytes);
        return verifier.verify(signatureBytes);
    }

    private static String entitlementKeyFor(String resourceType) {
        if (resourceType == null || resourceType.isBlank()) {
            return null;
        }
        return RESOURCE_LIMIT_KEYS.get(resourceType.trim().toUpperCase(Locale.ROOT));
    }

    private static byte[] decodeBase64Url(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static String stripPemArmor(String pem) {
        return pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (isBlank(value)) {
            return null;
        }
        return Instant.parse(value);
    }

    private static String textOrDefault(JsonNode node, String field, String defaultValue) {
        String value = text(node, field);
        return isBlank(value) ? defaultValue : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static String normalizeEdition(String edition) {
        if (edition == null) {
            return "";
        }
        return edition.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
