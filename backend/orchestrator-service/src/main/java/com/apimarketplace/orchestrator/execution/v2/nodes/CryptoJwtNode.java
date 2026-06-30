package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/**
 * CryptoJWT node - Provides cryptographic operations and JWT management.
 *
 * Operations:
 * - hash: Hash a value with MD5, SHA-1, SHA-256, SHA-512
 * - hmacSign: HMAC sign a value with a secret (HmacSHA256, HmacSHA512)
 * - hmacVerify: Verify an HMAC signature
 * - encrypt: AES encrypt a value with a key
 * - decrypt: AES decrypt a value with a key
 * - jwtCreate: Create a JWT with payload and secret (HS256)
 * - jwtDecode: Decode a JWT (base64 decode parts, no verification)
 * - jwtVerify: Verify a JWT signature with a secret
 * - base64Encode: Base64 encode a value
 * - base64Decode: Base64 decode a value
 * - generateUuid: Generate a random UUID
 * - generateSecret: Generate a random hex secret
 */
public class CryptoJwtNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(CryptoJwtNode.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final SecureRandom secureRandom = new SecureRandom();

    private final Core.CryptoJwtConfig config;

    public CryptoJwtNode(String nodeId, Core.CryptoJwtConfig config) {
        super(nodeId, NodeType.CRYPTO_JWT);
        this.config = config != null ? config : new Core.CryptoJwtConfig(null, null, null, null, null, null, null, null);
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        String operation = config.operation();
        logger.info("CryptoJWT node executing: nodeId={}, operation={}, itemId={}",
            nodeId, operation, context.itemId());

        // Captured outside the try so failure paths still surface the resolved inputs
        // to the inspector "Resolved parameters" panel.
        Core.CryptoJwtConfig resolved = null;

        try {
            Map<String, Object> result = new HashMap<>();

            // Resolve {{...}} templates in all string fields before consuming. Without this,
            // expressions like {{trigger:in.output.password}} never reach their crypto op.
            resolved = resolveConfig(context);

            Object operationResult = switch (operation) {
                case "hash" -> executeHash(resolved);
                case "hmacSign" -> executeHmacSign(resolved);
                case "hmacVerify" -> executeHmacVerify(resolved);
                case "encrypt" -> executeEncrypt(resolved);
                case "decrypt" -> executeDecrypt(resolved);
                case "jwtCreate" -> executeJwtCreate(resolved);
                case "jwtDecode" -> executeJwtDecode(resolved);
                case "jwtVerify" -> executeJwtVerify(resolved);
                case "base64Encode" -> executeBase64Encode(resolved);
                case "base64Decode" -> executeBase64Decode(resolved);
                case "generateUuid" -> executeGenerateUuid();
                case "generateSecret" -> executeGenerateSecret();
                default -> throw new IllegalArgumentException("Unknown crypto operation: " + operation);
            };

            result.put("result", operationResult);
            result.put("operation", operation);

            // MANDATORY metadata
            result.put("node_type", "CRYPTO_JWT");
            result.put("item_index", context.itemIndex());
            result.put("itemIndex", context.itemIndex());
            result.put("item_id", context.itemId());
            Map<String, Object> inputData = new LinkedHashMap<>();
            inputData.put("operation", operation);
            if (resolved.algorithm() != null) inputData.put("algorithm", resolved.algorithm());
            if (resolved.value() != null) inputData.put("value", resolved.value());
            if (resolved.token() != null) inputData.put("token", resolved.token());
            if (resolved.payload() != null) inputData.put("payload", resolved.payload());
            // Intentionally omit secret and key for security
            result.put("resolved_params", inputData);

            logger.info("CryptoJWT completed: nodeId={}, operation={}", nodeId, operation);
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            logger.error("CryptoJWT execution failed: nodeId={}, operation={}, error={}",
                nodeId, operation, e.getMessage(), e);
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("node_type", "CRYPTO_JWT");
            failOutput.put("item_index", context.itemIndex());
            failOutput.put("itemIndex", context.itemIndex());
            failOutput.put("item_id", context.itemId());
            Map<String, Object> failInputData = new LinkedHashMap<>();
            failInputData.put("operation", operation);
            if (resolved != null) {
                if (resolved.algorithm() != null) failInputData.put("algorithm", resolved.algorithm());
                if (resolved.value() != null) failInputData.put("value", resolved.value());
                if (resolved.token() != null) failInputData.put("token", resolved.token());
                if (resolved.payload() != null) failInputData.put("payload", resolved.payload());
            }
            failOutput.put("resolved_params", failInputData);
            failOutput.put("error", e.getMessage());
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0L);
        }
    }

    private Core.CryptoJwtConfig resolveConfig(ExecutionContext context) {
        return new Core.CryptoJwtConfig(
            config.operation(),
            config.algorithm(),
            resolveTemplateString(config.value(), context),
            resolveTemplateString(config.key(), context),
            resolveTemplateString(config.secret(), context),
            resolveTemplateString(config.token(), context),
            config.payload(),
            config.encoding()
        );
    }

    // ========================================================================
    // Hash operations
    // ========================================================================

    private String executeHash(Core.CryptoJwtConfig config) throws Exception {
        String value = requireNonEmpty(config.value(), "value");
        String algorithm = config.algorithm();
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        byte[] hashBytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hashBytes);
    }

    // ========================================================================
    // HMAC operations
    // ========================================================================

    private String executeHmacSign(Core.CryptoJwtConfig config) throws Exception {
        String value = requireNonEmpty(config.value(), "value");
        String secret = requireNonEmpty(config.secret(), "secret");
        String algorithm = config.algorithm(); // HmacSHA256, HmacSHA512
        return computeHmac(value, secret, algorithm);
    }

    private boolean executeHmacVerify(Core.CryptoJwtConfig config) throws Exception {
        String value = requireNonEmpty(config.value(), "value");
        String secret = requireNonEmpty(config.secret(), "secret");
        String signature = requireNonEmpty(config.token(), "token (signature)");
        String algorithm = config.algorithm();
        String computed = computeHmac(value, secret, algorithm);
        return MessageDigest.isEqual(
            computed.getBytes(StandardCharsets.UTF_8),
            signature.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String computeHmac(String data, String secret, String algorithm) throws Exception {
        Mac mac = Mac.getInstance(algorithm);
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm);
        mac.init(keySpec);
        byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hmacBytes);
    }

    // ========================================================================
    // AES encrypt/decrypt
    // ========================================================================

    private String executeEncrypt(Core.CryptoJwtConfig config) throws Exception {
        String value = requireNonEmpty(config.value(), "value");
        String key = requireNonEmpty(config.key(), "key");

        // Derive a 16-byte AES key from the provided key string via SHA-256
        byte[] keyBytes = deriveAesKey(key);
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");

        // Generate random IV
        byte[] iv = new byte[16];
        secureRandom.nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

        // Prepend IV to ciphertext and base64 encode
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    private String executeDecrypt(Core.CryptoJwtConfig config) throws Exception {
        String value = requireNonEmpty(config.value(), "value");
        String key = requireNonEmpty(config.key(), "key");

        byte[] combined = Base64.getDecoder().decode(value);
        if (combined.length < 16) {
            throw new IllegalArgumentException("Invalid encrypted data: too short");
        }

        // Extract IV (first 16 bytes) and ciphertext
        byte[] iv = Arrays.copyOfRange(combined, 0, 16);
        byte[] encrypted = Arrays.copyOfRange(combined, 16, combined.length);

        byte[] keyBytes = deriveAesKey(key);
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
        byte[] decrypted = cipher.doFinal(encrypted);

        return new String(decrypted, StandardCharsets.UTF_8);
    }

    private byte[] deriveAesKey(String key) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] hash = sha256.digest(key.getBytes(StandardCharsets.UTF_8));
        // Use first 16 bytes for AES-128
        return Arrays.copyOf(hash, 16);
    }

    // ========================================================================
    // JWT operations (manual, no external library)
    // ========================================================================

    /**
     * Supported JWT HMAC algorithms and their corresponding Java Mac algorithm names.
     */
    private static final Map<String, String> JWT_ALGORITHM_MAP = Map.of(
        "HS256", "HmacSHA256",
        "HS384", "HmacSHA384",
        "HS512", "HmacSHA512"
    );

    /**
     * Resolve the JWT algorithm from the config. Defaults to HS256 if not specified.
     * Returns a 2-element array: [jwtAlgName, javaMacAlgName].
     * Throws IllegalArgumentException if the algorithm is not supported.
     */
    private String[] resolveJwtAlgorithm(Core.CryptoJwtConfig config) {
        String alg = config.algorithm();
        // Default to HS256 if algorithm is not set or is the generic config default
        if (alg == null || alg.isEmpty() || "SHA-256".equals(alg)) {
            alg = "HS256";
        }
        String macAlg = JWT_ALGORITHM_MAP.get(alg);
        if (macAlg == null) {
            throw new IllegalArgumentException(
                "Unsupported JWT algorithm: " + alg +
                ". Supported algorithms: " + String.join(", ", JWT_ALGORITHM_MAP.keySet()));
        }
        return new String[] { alg, macAlg };
    }

    private String executeJwtCreate(Core.CryptoJwtConfig config) throws Exception {
        String secret = requireNonEmpty(config.secret(), "secret");
        Map<String, Object> payload = config.payload();
        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("JWT payload is required for jwtCreate");
        }

        String[] algInfo = resolveJwtAlgorithm(config);
        String jwtAlg = algInfo[0];
        String macAlg = algInfo[1];

        String headerJson = "{\"alg\":\"" + jwtAlg + "\",\"typ\":\"JWT\"}";
        String headerB64 = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));

        String payloadJson = objectMapper.writeValueAsString(payload);
        String payloadB64 = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));

        String signingInput = headerB64 + "." + payloadB64;
        byte[] signatureBytes = computeHmacBytes(signingInput, secret, macAlg);
        String signatureB64 = base64UrlEncode(signatureBytes);

        return signingInput + "." + signatureB64;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeJwtDecode(Core.CryptoJwtConfig config) throws Exception {
        String token = requireNonEmpty(config.token(), "token");

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid JWT format: expected 3 parts separated by dots");
        }

        String headerJson = new String(base64UrlDecode(parts[0]), StandardCharsets.UTF_8);
        String payloadJson = new String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8);

        Map<String, Object> decoded = new LinkedHashMap<>();
        decoded.put("header", objectMapper.readValue(headerJson, Map.class));
        decoded.put("payload", objectMapper.readValue(payloadJson, Map.class));
        decoded.put("signature", parts[2]);

        return decoded;
    }

    private boolean executeJwtVerify(Core.CryptoJwtConfig config) throws Exception {
        String token = requireNonEmpty(config.token(), "token");
        String secret = requireNonEmpty(config.secret(), "secret");

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }

        // Read algorithm from the JWT header to verify with the correct algorithm
        String headerJson = new String(base64UrlDecode(parts[0]), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> header = objectMapper.readValue(headerJson, Map.class);
        String headerAlg = (String) header.get("alg");

        String macAlg = JWT_ALGORITHM_MAP.get(headerAlg);
        if (macAlg == null) {
            throw new IllegalArgumentException(
                "Unsupported JWT algorithm in token header: " + headerAlg +
                ". Supported algorithms: " + String.join(", ", JWT_ALGORITHM_MAP.keySet()));
        }

        String signingInput = parts[0] + "." + parts[1];
        byte[] expectedSignature = computeHmacBytes(signingInput, secret, macAlg);
        String expectedB64 = base64UrlEncode(expectedSignature);

        return MessageDigest.isEqual(
            expectedB64.getBytes(StandardCharsets.UTF_8),
            parts[2].getBytes(StandardCharsets.UTF_8)
        );
    }

    private byte[] computeHmacBytes(String data, String secret, String algorithm) throws Exception {
        Mac mac = Mac.getInstance(algorithm);
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm);
        mac.init(keySpec);
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private byte[] base64UrlDecode(String data) {
        return Base64.getUrlDecoder().decode(data);
    }

    // ========================================================================
    // Base64 encode/decode
    // ========================================================================

    private String executeBase64Encode(Core.CryptoJwtConfig config) {
        String value = requireNonEmpty(config.value(), "value");
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String executeBase64Decode(Core.CryptoJwtConfig config) {
        String value = requireNonEmpty(config.value(), "value");
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    // ========================================================================
    // Generate operations
    // ========================================================================

    private String executeGenerateUuid() {
        return UUID.randomUUID().toString();
    }

    private String executeGenerateSecret() {
        int length = 32; // 32 bytes = 64 hex chars
        byte[] bytes = new byte[length];
        secureRandom.nextBytes(bytes);
        return bytesToHex(bytes);
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String requireNonEmpty(String value, String fieldName) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing required field: " + fieldName);
        }
        return value;
    }

    // Getters
    public Core.CryptoJwtConfig getConfig() { return config; }

    // Builder
    public static class Builder {
        private String nodeId;
        private Core.CryptoJwtConfig cryptoJwtConfig;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder cryptoJwtConfig(Core.CryptoJwtConfig config) { this.cryptoJwtConfig = config; return this; }
        public CryptoJwtNode build() { return new CryptoJwtNode(nodeId, cryptoJwtConfig); }
    }

    public static Builder builder() { return new Builder(); }
}
