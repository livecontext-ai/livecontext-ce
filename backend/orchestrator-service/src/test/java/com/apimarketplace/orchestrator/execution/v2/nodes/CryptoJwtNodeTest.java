package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CryptoJwtNode.
 * CryptoJwtNode provides cryptographic operations and JWT management.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CryptoJwtNode")
class CryptoJwtNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("input", "hello world");

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    // ===============================================================
    // Constructor tests
    // ===============================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create CryptoJwtNode with nodeId and config")
        void shouldCreateCryptoJwtNodeWithNodeIdAndConfig() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "hash", "SHA-256", "test", null, null, null, null, null);

            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            assertEquals("core:crypto", node.getNodeId());
            assertEquals(NodeType.CRYPTO_JWT, node.getType());
            assertNotNull(node.getConfig());
            assertEquals("hash", node.getConfig().operation());
        }

        @Test
        @DisplayName("Should handle null config with defaults")
        void shouldHandleNullConfigWithDefaults() {
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", null);

            assertNotNull(node.getConfig());
            assertEquals("hash", node.getConfig().operation());
            assertEquals("SHA-256", node.getConfig().algorithm());
        }

        @Test
        @DisplayName("Should create CryptoJwtNode using builder")
        void shouldCreateCryptoJwtNodeUsingBuilder() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "base64Encode", null, "hello", null, null, null, null, null);

            CryptoJwtNode node = CryptoJwtNode.builder()
                .nodeId("core:my_crypto")
                .cryptoJwtConfig(config)
                .build();

            assertEquals("core:my_crypto", node.getNodeId());
            assertEquals("base64Encode", node.getConfig().operation());
        }
    }

    // ===============================================================
    // Hash operation tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - Hash")
    class HashTests {

        @Test
        @DisplayName("Should hash value with SHA-256")
        void shouldHashValueWithSha256() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "hash", "SHA-256", "hello", null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            String hash = (String) result.output().get("result");
            assertNotNull(hash);
            // SHA-256 of "hello" is known
            assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash);
        }

        @Test
        @DisplayName("Should hash value with MD5")
        void shouldHashValueWithMd5() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "hash", "MD5", "hello", null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            String hash = (String) result.output().get("result");
            assertNotNull(hash);
            assertEquals("5d41402abc4b2a76b9719d911017c592", hash);
        }

        @Test
        @DisplayName("Should hash value with SHA-512")
        void shouldHashValueWithSha512() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "hash", "SHA-512", "hello", null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            String hash = (String) result.output().get("result");
            assertNotNull(hash);
            // SHA-512 produces 128 hex chars
            assertEquals(128, hash.length());
        }

        @Test
        @DisplayName("Should fail hash when value is missing")
        void shouldFailHashWhenValueIsMissing() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "hash", "SHA-256", null, null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }
    }

    // ===============================================================
    // HMAC operation tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - HMAC")
    class HmacTests {

        @Test
        @DisplayName("Should HMAC sign value with HmacSHA256")
        void shouldHmacSignValueWithHmacSha256() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "hmacSign", "HmacSHA256", "hello", null, "mysecret", null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            String signature = (String) result.output().get("result");
            assertNotNull(signature);
            // HmacSHA256 produces 64 hex chars
            assertEquals(64, signature.length());
        }

        @Test
        @DisplayName("Should HMAC sign value with HmacSHA512")
        void shouldHmacSignValueWithHmacSha512() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "hmacSign", "HmacSHA512", "hello", null, "mysecret", null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            String signature = (String) result.output().get("result");
            assertNotNull(signature);
            // HmacSHA512 produces 128 hex chars
            assertEquals(128, signature.length());
        }

        @Test
        @DisplayName("Should verify valid HMAC signature")
        void shouldVerifyValidHmacSignature() {
            // First sign
            Core.CryptoJwtConfig signConfig = new Core.CryptoJwtConfig(
                "hmacSign", "HmacSHA256", "hello", null, "mysecret", null, null, null);
            CryptoJwtNode signNode = new CryptoJwtNode("core:crypto", signConfig);
            NodeExecutionResult signResult = signNode.execute(context);
            String signature = (String) signResult.output().get("result");

            // Then verify
            Core.CryptoJwtConfig verifyConfig = new Core.CryptoJwtConfig(
                "hmacVerify", "HmacSHA256", "hello", null, "mysecret", signature, null, null);
            CryptoJwtNode verifyNode = new CryptoJwtNode("core:crypto", verifyConfig);
            NodeExecutionResult verifyResult = verifyNode.execute(context);

            assertTrue(verifyResult.isSuccess());
            assertEquals(true, verifyResult.output().get("result"));
        }

        @Test
        @DisplayName("Should reject invalid HMAC signature")
        void shouldRejectInvalidHmacSignature() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "hmacVerify", "HmacSHA256", "hello", null, "mysecret", "invalidsignature", null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(false, result.output().get("result"));
        }

        @Test
        @DisplayName("Should fail HMAC sign when secret is missing")
        void shouldFailHmacSignWhenSecretIsMissing() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "hmacSign", "HmacSHA256", "hello", null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }
    }

    // ===============================================================
    // AES encrypt/decrypt tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - AES Encrypt/Decrypt")
    class AesTests {

        @Test
        @DisplayName("Should encrypt and decrypt roundtrip successfully")
        void shouldEncryptAndDecryptRoundtrip() {
            String originalValue = "Hello, World! This is a secret message.";
            String key = "my-encryption-key-2024";

            // Encrypt
            Core.CryptoJwtConfig encryptConfig = new Core.CryptoJwtConfig(
                "encrypt", "AES", originalValue, key, null, null, null, null);
            CryptoJwtNode encryptNode = new CryptoJwtNode("core:crypto", encryptConfig);
            NodeExecutionResult encryptResult = encryptNode.execute(context);

            assertTrue(encryptResult.isSuccess());
            String encrypted = (String) encryptResult.output().get("result");
            assertNotNull(encrypted);
            assertNotEquals(originalValue, encrypted);

            // Decrypt
            Core.CryptoJwtConfig decryptConfig = new Core.CryptoJwtConfig(
                "decrypt", "AES", encrypted, key, null, null, null, null);
            CryptoJwtNode decryptNode = new CryptoJwtNode("core:crypto", decryptConfig);
            NodeExecutionResult decryptResult = decryptNode.execute(context);

            assertTrue(decryptResult.isSuccess());
            assertEquals(originalValue, decryptResult.output().get("result"));
        }

        @Test
        @DisplayName("Should fail encrypt when key is missing")
        void shouldFailEncryptWhenKeyIsMissing() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "encrypt", "AES", "hello", null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should fail decrypt with wrong key")
        void shouldFailDecryptWithWrongKey() {
            // Encrypt with one key
            Core.CryptoJwtConfig encryptConfig = new Core.CryptoJwtConfig(
                "encrypt", "AES", "hello", "correct-key", null, null, null, null);
            CryptoJwtNode encryptNode = new CryptoJwtNode("core:crypto", encryptConfig);
            NodeExecutionResult encryptResult = encryptNode.execute(context);
            String encrypted = (String) encryptResult.output().get("result");

            // Decrypt with different key - should fail
            Core.CryptoJwtConfig decryptConfig = new Core.CryptoJwtConfig(
                "decrypt", "AES", encrypted, "wrong-key", null, null, null, null);
            CryptoJwtNode decryptNode = new CryptoJwtNode("core:crypto", decryptConfig);
            NodeExecutionResult decryptResult = decryptNode.execute(context);

            assertTrue(decryptResult.isFailure());
        }

        @Test
        @DisplayName("Should fail decrypt with invalid data")
        void shouldFailDecryptWithInvalidData() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "decrypt", "AES", "not-base64-valid!", "my-key", null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }
    }

    // ===============================================================
    // JWT operation tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - JWT")
    class JwtTests {

        @Test
        @DisplayName("Should create a valid JWT")
        void shouldCreateValidJwt() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("sub", "1234567890");
            payload.put("name", "John Doe");

            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "jwtCreate", "HS256", null, null, "my-jwt-secret", null, payload, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            String token = (String) result.output().get("result");
            assertNotNull(token);
            // JWT has 3 parts separated by dots
            String[] parts = token.split("\\.");
            assertEquals(3, parts.length);
        }

        @Test
        @DisplayName("Should decode a JWT")
        void shouldDecodeJwt() {
            // First create a JWT
            Map<String, Object> payload = new HashMap<>();
            payload.put("sub", "user123");
            payload.put("role", "admin");

            Core.CryptoJwtConfig createConfig = new Core.CryptoJwtConfig(
                "jwtCreate", "HS256", null, null, "secret", null, payload, null);
            CryptoJwtNode createNode = new CryptoJwtNode("core:crypto", createConfig);
            NodeExecutionResult createResult = createNode.execute(context);
            String token = (String) createResult.output().get("result");

            // Then decode it
            Core.CryptoJwtConfig decodeConfig = new Core.CryptoJwtConfig(
                "jwtDecode", null, null, null, null, token, null, null);
            CryptoJwtNode decodeNode = new CryptoJwtNode("core:crypto", decodeConfig);
            NodeExecutionResult decodeResult = decodeNode.execute(context);

            assertTrue(decodeResult.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> decoded = (Map<String, Object>) decodeResult.output().get("result");
            assertNotNull(decoded.get("header"));
            assertNotNull(decoded.get("payload"));
            assertNotNull(decoded.get("signature"));

            @SuppressWarnings("unchecked")
            Map<String, Object> decodedPayload = (Map<String, Object>) decoded.get("payload");
            assertEquals("user123", decodedPayload.get("sub"));
            assertEquals("admin", decodedPayload.get("role"));
        }

        @Test
        @DisplayName("Should verify a valid JWT")
        void shouldVerifyValidJwt() {
            // Create a JWT
            Map<String, Object> payload = new HashMap<>();
            payload.put("sub", "user123");

            Core.CryptoJwtConfig createConfig = new Core.CryptoJwtConfig(
                "jwtCreate", "HS256", null, null, "my-secret", null, payload, null);
            CryptoJwtNode createNode = new CryptoJwtNode("core:crypto", createConfig);
            NodeExecutionResult createResult = createNode.execute(context);
            String token = (String) createResult.output().get("result");

            // Verify it
            Core.CryptoJwtConfig verifyConfig = new Core.CryptoJwtConfig(
                "jwtVerify", "HS256", null, null, "my-secret", token, null, null);
            CryptoJwtNode verifyNode = new CryptoJwtNode("core:crypto", verifyConfig);
            NodeExecutionResult verifyResult = verifyNode.execute(context);

            assertTrue(verifyResult.isSuccess());
            assertEquals(true, verifyResult.output().get("result"));
        }

        @Test
        @DisplayName("Should reject JWT with wrong secret")
        void shouldRejectJwtWithWrongSecret() {
            // Create a JWT
            Map<String, Object> payload = new HashMap<>();
            payload.put("sub", "user123");

            Core.CryptoJwtConfig createConfig = new Core.CryptoJwtConfig(
                "jwtCreate", "HS256", null, null, "correct-secret", null, payload, null);
            CryptoJwtNode createNode = new CryptoJwtNode("core:crypto", createConfig);
            NodeExecutionResult createResult = createNode.execute(context);
            String token = (String) createResult.output().get("result");

            // Verify with wrong secret
            Core.CryptoJwtConfig verifyConfig = new Core.CryptoJwtConfig(
                "jwtVerify", "HS256", null, null, "wrong-secret", token, null, null);
            CryptoJwtNode verifyNode = new CryptoJwtNode("core:crypto", verifyConfig);
            NodeExecutionResult verifyResult = verifyNode.execute(context);

            assertTrue(verifyResult.isSuccess());
            assertEquals(false, verifyResult.output().get("result"));
        }

        @Test
        @DisplayName("Should reject invalid JWT format on decode")
        void shouldRejectInvalidJwtFormatOnDecode() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "jwtDecode", null, null, null, null, "not.a.valid.jwt.token", null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should reject JWT verify with invalid format")
        void shouldRejectJwtVerifyWithInvalidFormat() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "jwtVerify", "HS256", null, null, "secret", "invalid", null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(false, result.output().get("result"));
        }

        @Test
        @DisplayName("Should fail jwtCreate when payload is missing")
        void shouldFailJwtCreateWhenPayloadIsMissing() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "jwtCreate", "HS256", null, null, "secret", null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should fail jwtCreate when secret is missing")
        void shouldFailJwtCreateWhenSecretIsMissing() {
            Map<String, Object> payload = Map.of("sub", "user");
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "jwtCreate", "HS256", null, null, null, null, payload, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should create and verify JWT with HS384")
        void shouldCreateAndVerifyJwtWithHs384() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("sub", "user-384");
            payload.put("role", "editor");

            // Create with HS384
            Core.CryptoJwtConfig createConfig = new Core.CryptoJwtConfig(
                "jwtCreate", "HS384", null, null, "hs384-secret", null, payload, null);
            CryptoJwtNode createNode = new CryptoJwtNode("core:crypto", createConfig);
            NodeExecutionResult createResult = createNode.execute(context);

            assertTrue(createResult.isSuccess());
            String token = (String) createResult.output().get("result");
            assertNotNull(token);
            String[] parts = token.split("\\.");
            assertEquals(3, parts.length);

            // Decode and check header algorithm is HS384
            Core.CryptoJwtConfig decodeConfig = new Core.CryptoJwtConfig(
                "jwtDecode", null, null, null, null, token, null, null);
            CryptoJwtNode decodeNode = new CryptoJwtNode("core:crypto", decodeConfig);
            NodeExecutionResult decodeResult = decodeNode.execute(context);
            assertTrue(decodeResult.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> decoded = (Map<String, Object>) decodeResult.output().get("result");
            @SuppressWarnings("unchecked")
            Map<String, Object> header = (Map<String, Object>) decoded.get("header");
            assertEquals("HS384", header.get("alg"));

            // Verify with correct secret
            Core.CryptoJwtConfig verifyConfig = new Core.CryptoJwtConfig(
                "jwtVerify", "HS384", null, null, "hs384-secret", token, null, null);
            CryptoJwtNode verifyNode = new CryptoJwtNode("core:crypto", verifyConfig);
            NodeExecutionResult verifyResult = verifyNode.execute(context);
            assertTrue(verifyResult.isSuccess());
            assertEquals(true, verifyResult.output().get("result"));

            // Verify with wrong secret should fail
            Core.CryptoJwtConfig wrongVerifyConfig = new Core.CryptoJwtConfig(
                "jwtVerify", "HS384", null, null, "wrong-secret", token, null, null);
            CryptoJwtNode wrongVerifyNode = new CryptoJwtNode("core:crypto", wrongVerifyConfig);
            NodeExecutionResult wrongVerifyResult = wrongVerifyNode.execute(context);
            assertTrue(wrongVerifyResult.isSuccess());
            assertEquals(false, wrongVerifyResult.output().get("result"));
        }

        @Test
        @DisplayName("Should create and verify JWT with HS512")
        void shouldCreateAndVerifyJwtWithHs512() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("sub", "user-512");
            payload.put("iat", 1700000000);

            // Create with HS512
            Core.CryptoJwtConfig createConfig = new Core.CryptoJwtConfig(
                "jwtCreate", "HS512", null, null, "hs512-long-secret", null, payload, null);
            CryptoJwtNode createNode = new CryptoJwtNode("core:crypto", createConfig);
            NodeExecutionResult createResult = createNode.execute(context);

            assertTrue(createResult.isSuccess());
            String token = (String) createResult.output().get("result");
            assertNotNull(token);

            // Decode and check header algorithm is HS512
            Core.CryptoJwtConfig decodeConfig = new Core.CryptoJwtConfig(
                "jwtDecode", null, null, null, null, token, null, null);
            CryptoJwtNode decodeNode = new CryptoJwtNode("core:crypto", decodeConfig);
            NodeExecutionResult decodeResult = decodeNode.execute(context);
            assertTrue(decodeResult.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> decoded = (Map<String, Object>) decodeResult.output().get("result");
            @SuppressWarnings("unchecked")
            Map<String, Object> header = (Map<String, Object>) decoded.get("header");
            assertEquals("HS512", header.get("alg"));

            // Verify roundtrip
            Core.CryptoJwtConfig verifyConfig = new Core.CryptoJwtConfig(
                "jwtVerify", "HS512", null, null, "hs512-long-secret", token, null, null);
            CryptoJwtNode verifyNode = new CryptoJwtNode("core:crypto", verifyConfig);
            NodeExecutionResult verifyResult = verifyNode.execute(context);
            assertTrue(verifyResult.isSuccess());
            assertEquals(true, verifyResult.output().get("result"));
        }

        @Test
        @DisplayName("Should fail jwtCreate with unsupported algorithm")
        void shouldFailJwtCreateWithUnsupportedAlgorithm() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("sub", "user123");

            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "jwtCreate", "RS256", null, null, "secret", null, payload, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().isPresent());
            assertTrue(result.errorMessage().get().contains("Unsupported JWT algorithm"));
            assertTrue(result.errorMessage().get().contains("RS256"));
            assertTrue(result.errorMessage().get().contains("HS256"));
        }

        @Test
        @DisplayName("Should not cross-verify HS256 token with HS512 secret")
        void shouldNotCrossVerifyDifferentAlgorithms() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("sub", "user123");
            String secret = "shared-secret";

            // Create with HS256
            Core.CryptoJwtConfig createConfig = new Core.CryptoJwtConfig(
                "jwtCreate", "HS256", null, null, secret, null, payload, null);
            CryptoJwtNode createNode = new CryptoJwtNode("core:crypto", createConfig);
            NodeExecutionResult createResult = createNode.execute(context);
            String hs256Token = (String) createResult.output().get("result");

            // Create with HS512 (same payload, same secret)
            Core.CryptoJwtConfig createConfig512 = new Core.CryptoJwtConfig(
                "jwtCreate", "HS512", null, null, secret, null, payload, null);
            CryptoJwtNode createNode512 = new CryptoJwtNode("core:crypto", createConfig512);
            NodeExecutionResult createResult512 = createNode512.execute(context);
            String hs512Token = (String) createResult512.output().get("result");

            // Tokens should be different (different algorithms produce different signatures)
            assertNotEquals(hs256Token, hs512Token);
        }

        @Test
        @DisplayName("Should create, decode, and verify JWT roundtrip")
        void shouldCreateDecodeAndVerifyJwtRoundtrip() {
            String secret = "roundtrip-secret";
            Map<String, Object> payload = new HashMap<>();
            payload.put("user_id", "u-42");
            payload.put("exp", 1999999999);

            // Create
            Core.CryptoJwtConfig createConfig = new Core.CryptoJwtConfig(
                "jwtCreate", "HS256", null, null, secret, null, payload, null);
            CryptoJwtNode createNode = new CryptoJwtNode("core:crypto", createConfig);
            NodeExecutionResult createResult = createNode.execute(context);
            assertTrue(createResult.isSuccess());
            String token = (String) createResult.output().get("result");

            // Decode
            Core.CryptoJwtConfig decodeConfig = new Core.CryptoJwtConfig(
                "jwtDecode", null, null, null, null, token, null, null);
            CryptoJwtNode decodeNode = new CryptoJwtNode("core:crypto", decodeConfig);
            NodeExecutionResult decodeResult = decodeNode.execute(context);
            assertTrue(decodeResult.isSuccess());

            @SuppressWarnings("unchecked")
            Map<String, Object> decoded = (Map<String, Object>) decodeResult.output().get("result");
            @SuppressWarnings("unchecked")
            Map<String, Object> decodedPayload = (Map<String, Object>) decoded.get("payload");
            assertEquals("u-42", decodedPayload.get("user_id"));

            // Verify
            Core.CryptoJwtConfig verifyConfig = new Core.CryptoJwtConfig(
                "jwtVerify", "HS256", null, null, secret, token, null, null);
            CryptoJwtNode verifyNode = new CryptoJwtNode("core:crypto", verifyConfig);
            NodeExecutionResult verifyResult = verifyNode.execute(context);
            assertTrue(verifyResult.isSuccess());
            assertEquals(true, verifyResult.output().get("result"));
        }
    }

    // ===============================================================
    // Base64 operation tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - Base64")
    class Base64Tests {

        @Test
        @DisplayName("Should base64 encode a value")
        void shouldBase64EncodeValue() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "base64Encode", null, "Hello, World!", null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("SGVsbG8sIFdvcmxkIQ==", result.output().get("result"));
        }

        @Test
        @DisplayName("Should base64 decode a value")
        void shouldBase64DecodeValue() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "base64Decode", null, "SGVsbG8sIFdvcmxkIQ==", null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("Hello, World!", result.output().get("result"));
        }

        @Test
        @DisplayName("Should base64 encode/decode roundtrip")
        void shouldBase64EncodeDecodeRoundtrip() {
            String original = "Test data with special chars: @#$%^&*()";

            // Encode
            Core.CryptoJwtConfig encodeConfig = new Core.CryptoJwtConfig(
                "base64Encode", null, original, null, null, null, null, null);
            CryptoJwtNode encodeNode = new CryptoJwtNode("core:crypto", encodeConfig);
            NodeExecutionResult encodeResult = encodeNode.execute(context);
            String encoded = (String) encodeResult.output().get("result");

            // Decode
            Core.CryptoJwtConfig decodeConfig = new Core.CryptoJwtConfig(
                "base64Decode", null, encoded, null, null, null, null, null);
            CryptoJwtNode decodeNode = new CryptoJwtNode("core:crypto", decodeConfig);
            NodeExecutionResult decodeResult = decodeNode.execute(context);

            assertEquals(original, decodeResult.output().get("result"));
        }

        @Test
        @DisplayName("Should fail base64 encode when value is missing")
        void shouldFailBase64EncodeWhenValueIsMissing() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "base64Encode", null, null, null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }
    }

    // ===============================================================
    // UUID generation tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - Generate UUID")
    class GenerateUuidTests {

        @Test
        @DisplayName("Should generate a valid UUID")
        void shouldGenerateValidUuid() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "generateUuid", null, null, null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            String uuid = (String) result.output().get("result");
            assertNotNull(uuid);
            // Validate UUID format: 8-4-4-4-12
            assertTrue(uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        }

        @Test
        @DisplayName("Should generate unique UUIDs")
        void shouldGenerateUniqueUuids() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "generateUuid", null, null, null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result1 = node.execute(context);
            NodeExecutionResult result2 = node.execute(context);

            assertNotEquals(result1.output().get("result"), result2.output().get("result"));
        }
    }

    // ===============================================================
    // Secret generation tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - Generate Secret")
    class GenerateSecretTests {

        @Test
        @DisplayName("Should generate a hex secret of correct length")
        void shouldGenerateHexSecretOfCorrectLength() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "generateSecret", null, null, null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            String secret = (String) result.output().get("result");
            assertNotNull(secret);
            // 32 bytes = 64 hex chars
            assertEquals(64, secret.length());
            // Validate hex format
            assertTrue(secret.matches("[0-9a-f]+"));
        }

        @Test
        @DisplayName("Should generate unique secrets")
        void shouldGenerateUniqueSecrets() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "generateSecret", null, null, null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result1 = node.execute(context);
            NodeExecutionResult result2 = node.execute(context);

            assertNotEquals(result1.output().get("result"), result2.output().get("result"));
        }
    }

    // ===============================================================
    // Error handling tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should fail for unknown operation")
        void shouldFailForUnknownOperation() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "unknownOp", null, "test", null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().isPresent());
            assertTrue(result.errorMessage().get().contains("Unknown crypto operation"));
        }

        @Test
        @DisplayName("Should fail hash with invalid algorithm")
        void shouldFailHashWithInvalidAlgorithm() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "hash", "INVALID-ALG", "test", null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }
    }

    // ===============================================================
    // Metadata tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - Metadata")
    class MetadataTests {

        @Test
        @DisplayName("Should include mandatory metadata fields")
        void shouldIncludeMandatoryMetadataFields() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "generateUuid", null, null, null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertEquals("CRYPTO_JWT", result.output().get("node_type"));
            assertEquals(0, result.output().get("item_index"));
            assertEquals(0, result.output().get("itemIndex"));
            assertEquals("item-1", result.output().get("item_id"));
            assertNotNull(result.output().get("resolved_params"));
        }

        @Test
        @DisplayName("Should include operation in output")
        void shouldIncludeOperationInOutput() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "base64Encode", null, "test", null, null, null, null, null);
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);

            NodeExecutionResult result = node.execute(context);

            assertEquals("base64Encode", result.output().get("operation"));
        }
    }

    // ===============================================================
    // Builder tests
    // ===============================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build node with all fields via builder")
        void shouldBuildNodeWithAllFieldsViaBuilder() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "hash", "SHA-512", "data", null, null, null, null, "hex");

            CryptoJwtNode node = CryptoJwtNode.builder()
                .nodeId("core:hash_node")
                .cryptoJwtConfig(config)
                .build();

            assertEquals("core:hash_node", node.getNodeId());
            assertEquals(NodeType.CRYPTO_JWT, node.getType());
            assertEquals("hash", node.getConfig().operation());
            assertEquals("SHA-512", node.getConfig().algorithm());
        }

        @Test
        @DisplayName("Should build node with null config")
        void shouldBuildNodeWithNullConfig() {
            CryptoJwtNode node = CryptoJwtNode.builder()
                .nodeId("core:crypto")
                .cryptoJwtConfig(null)
                .build();

            assertNotNull(node.getConfig());
        }
    }

    // ===============================================================
    // getNextNodes() tests
    // ===============================================================

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", null);

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:crypto", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", null);

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:crypto", "Error");

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertTrue(nextNodes.isEmpty());
        }
    }

    // ===============================================================
    // onComplete() tests
    // ===============================================================

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw exception on success result")
        void shouldNotThrowExceptionOnSuccessResult() {
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", null);
            NodeExecutionResult result = NodeExecutionResult.success("core:crypto", Map.of());
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            CryptoJwtNode node = new CryptoJwtNode("core:crypto", null);
            NodeExecutionResult result = NodeExecutionResult.failure("core:crypto", "Error");
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ===============================================================
    // #D9 Template resolution
    // ===============================================================

    @Nested
    @DisplayName("#D9 Template resolution")
    class TemplateResolutionTests {

        @Test
        @DisplayName("Should resolve {{...}} in value for hash op")
        void shouldResolveValueForHash() throws Exception {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "hash", "SHA-256", "{{trigger:input.output.password}}", null, null, null, null, null);

            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);
            node.acceptServices(ServiceRegistry.builder()
                .templateAdapter(mockTemplateAdapter)
                .build());

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenAnswer(echoUnlessTemplate("secret123"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess(),
                "Template in value must be resolved before hashing (#D9)");
            // SHA-256 of "secret123" is known
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest("secret123".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            assertEquals(hex.toString(), result.output().get("result"),
                "Hash must be computed over the resolved value, not the raw placeholder");
        }

        @Test
        @DisplayName("Should resolve {{...}} in secret for hmacSign op")
        void shouldResolveSecretForHmac() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "hmacSign", "HmacSHA256", "payload", null, "{{trigger:input.output.secret}}", null, null, null);

            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);
            node.acceptServices(ServiceRegistry.builder()
                .templateAdapter(mockTemplateAdapter)
                .build());

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenAnswer(echoUnlessTemplate("mySecret"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess(),
                "Template in secret must be resolved before HMAC (#D9)");
            assertNotNull(result.output().get("result"));
        }

        @Test
        @DisplayName("Should resolve {{...}} in token for jwtDecode op")
        void shouldResolveTokenForJwtDecode() {
            // Valid JWT: {"alg":"HS256","typ":"JWT"}.{"user":"alice"}.<sig>
            String validJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
                ".eyJ1c2VyIjoiYWxpY2UifQ" +
                ".signature";

            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "jwtDecode", "HS256", null, null, null, "{{trigger:input.output.token}}", null, null);

            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);
            node.acceptServices(ServiceRegistry.builder()
                .templateAdapter(mockTemplateAdapter)
                .build());

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenAnswer(echoUnlessTemplate(validJwt));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess(),
                "Template in token must be resolved before JWT decode (#D9)");
        }

        @Test
        @DisplayName("Should fail without template resolution when secret is a raw placeholder")
        void shouldFailWithoutTemplateResolution() {
            // No templateAdapter injected - resolveTemplateString returns placeholder as-is
            // The HMAC will still succeed but produce a signature over the literal "{{...}}" secret.
            // Use base64Encode which actually exposes the template as the *value* being encoded.
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "base64Encode", null, "{{trigger:input.output.value}}", null, null, null, null, null);

            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess(), "base64 of raw placeholder still runs");
            String encoded = (String) result.output().get("result");
            String decoded = new String(java.util.Base64.getDecoder().decode(encoded),
                java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("{{trigger:input.output.value}}", decoded,
                "Without resolution the raw placeholder is encoded - proves fix is needed");
        }

        @Test
        @DisplayName("Should encode resolved value, not placeholder")
        void shouldEncodeResolvedValue() {
            Core.CryptoJwtConfig config = new Core.CryptoJwtConfig(
                "base64Encode", null, "{{trigger:input.output.value}}", null, null, null, null, null);

            CryptoJwtNode node = new CryptoJwtNode("core:crypto", config);
            node.acceptServices(ServiceRegistry.builder()
                .templateAdapter(mockTemplateAdapter)
                .build());

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenAnswer(echoUnlessTemplate("hello"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            String expected = java.util.Base64.getEncoder().encodeToString("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(expected, result.output().get("result"),
                "base64Encode must encode the resolved value (#D9)");
        }

        private org.mockito.stubbing.Answer<Map<String, Object>> echoUnlessTemplate(String replacement) {
            return invocation -> {
                Map<String, Object> in = invocation.getArgument(0);
                Object raw = in.get("__v__");
                if (raw instanceof String s && s.contains("{{")) {
                    return Map.of("__v__", replacement);
                }
                return in;
            };
        }
    }

    // ===============================================================
    // Helper methods
    // ===============================================================

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }
}
