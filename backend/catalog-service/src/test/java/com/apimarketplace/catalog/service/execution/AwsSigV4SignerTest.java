package com.apimarketplace.catalog.service.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates {@link AwsSigV4Signer} against AWS SigV4 reference behavior.
 *
 * Uses the canonical examples from
 * <a href="https://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html">AWS docs</a>
 * wherever possible. The signature computation is deterministic given a fixed timestamp,
 * so we exercise the static helpers (canonical URI, canonical query, key derivation, SHA-256)
 * against published test vectors.
 */
class AwsSigV4SignerTest {

    private AwsSigV4Signer signer;

    @BeforeEach
    void setUp() {
        signer = new AwsSigV4Signer();
    }

    @Test
    @DisplayName("deriveServiceFromHost extracts service prefix for AWS hostnames")
    void deriveService() {
        assertEquals("sns", AwsSigV4Signer.deriveServiceFromHost("sns.us-east-1.amazonaws.com"));
        assertEquals("sqs", AwsSigV4Signer.deriveServiceFromHost("sqs.eu-west-1.amazonaws.com"));
        assertEquals("s3",  AwsSigV4Signer.deriveServiceFromHost("s3.amazonaws.com"));
        assertEquals("lambda", AwsSigV4Signer.deriveServiceFromHost("lambda.us-west-2.amazonaws.com"));
        // CN variant
        assertEquals("ec2", AwsSigV4Signer.deriveServiceFromHost("ec2.cn-north-1.amazonaws.com.cn"));
        // Non-AWS host
        assertNull(AwsSigV4Signer.deriveServiceFromHost("api.example.com"));
        assertNull(AwsSigV4Signer.deriveServiceFromHost(null));
    }

    @Test
    @DisplayName("deriveRegionFromHost extracts region or defaults to us-east-1")
    void deriveRegion() {
        assertEquals("us-east-1", AwsSigV4Signer.deriveRegionFromHost("sns.us-east-1.amazonaws.com"));
        assertEquals("eu-west-1", AwsSigV4Signer.deriveRegionFromHost("sqs.eu-west-1.amazonaws.com"));
        assertEquals("ap-northeast-1", AwsSigV4Signer.deriveRegionFromHost("lambda.ap-northeast-1.amazonaws.com"));
        // Short form (s3.amazonaws.com) -> default
        assertEquals("us-east-1", AwsSigV4Signer.deriveRegionFromHost("s3.amazonaws.com"));
    }

    @Test
    @DisplayName("canonicalizeUri URL-encodes path segments, preserves slashes")
    void canonicalizeUri() {
        assertEquals("/", AwsSigV4Signer.canonicalizeUri(""));
        assertEquals("/", AwsSigV4Signer.canonicalizeUri(null));
        assertEquals("/", AwsSigV4Signer.canonicalizeUri("/"));
        assertEquals("/foo", AwsSigV4Signer.canonicalizeUri("/foo"));
        assertEquals("/foo/bar", AwsSigV4Signer.canonicalizeUri("/foo/bar"));
        // Spaces and special chars get %-encoded per segment
        assertEquals("/my%20folder/file.txt", AwsSigV4Signer.canonicalizeUri("/my folder/file.txt"));
    }

    @Test
    @DisplayName("canonicalizeQuery sorts by key, URL-encodes values")
    void canonicalizeQuery() {
        assertEquals("", AwsSigV4Signer.canonicalizeQuery(""));
        assertEquals("", AwsSigV4Signer.canonicalizeQuery(null));
        // Single pair
        assertEquals("foo=bar", AwsSigV4Signer.canonicalizeQuery("foo=bar"));
        // Sorts by key
        assertEquals("a=1&b=2&c=3", AwsSigV4Signer.canonicalizeQuery("c=3&a=1&b=2"));
        // Secondary sort by value
        assertEquals("k=1&k=2", AwsSigV4Signer.canonicalizeQuery("k=2&k=1"));
        // Values with spaces (originally %20) get re-encoded
        assertEquals("q=hello%20world", AwsSigV4Signer.canonicalizeQuery("q=hello%20world"));
    }

    @Test
    @DisplayName("rfc3986Encode produces strict RFC 3986 encoding (space→%20, *→%2A, ~ kept)")
    void rfc3986Encoding() {
        assertEquals("hello%20world", AwsSigV4Signer.rfc3986Encode("hello world"));
        assertEquals("a%2Ab", AwsSigV4Signer.rfc3986Encode("a*b"));
        assertEquals("~foo", AwsSigV4Signer.rfc3986Encode("~foo"));
        assertEquals("abc123-_.~", AwsSigV4Signer.rfc3986Encode("abc123-_.~"));
    }

    @Test
    @DisplayName("sha256Hex matches known vector for empty input")
    void sha256EmptyVector() {
        // Well-known SHA-256 hash of empty string
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            AwsSigV4Signer.sha256Hex(new byte[0]));
    }

    @Test
    @DisplayName("deriveSigningKey matches AWS sample at docs.aws.amazon.com/general/latest/gr/sigv4_signing.html")
    void deriveSigningKeyAwsSample() {
        // Official AWS sample key derivation - the final derived key must match this hex.
        // AWS4 + "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY" → 20150830 → us-east-1 → iam → aws4_request
        byte[] key = AwsSigV4Signer.deriveSigningKey(
            "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            "20150830", "us-east-1", "iam");
        String hex = AwsSigV4Signer.bytesToHex(key);
        assertEquals("c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9", hex);
    }

    @Test
    @DisplayName("sign() adds x-amz-date, x-amz-content-sha256, and Authorization headers")
    void signAddsRequiredHeaders() {
        HttpHeaders headers = new HttpHeaders();
        signer.sign(
            "POST",
            "https://sns.us-east-1.amazonaws.com/",
            headers,
            "Action=Publish&Message=hello".getBytes(StandardCharsets.UTF_8),
            "AKIAEXAMPLE00000001",
            "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            "us-east-1",
            null);

        assertTrue(headers.containsKey("x-amz-date"));
        assertTrue(headers.containsKey("x-amz-content-sha256"));
        assertTrue(headers.containsKey(HttpHeaders.AUTHORIZATION));

        String auth = headers.getFirst(HttpHeaders.AUTHORIZATION);
        assertNotNull(auth);
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 "));
        assertTrue(auth.contains("Credential=AKIAEXAMPLE00000001/"));
        assertTrue(auth.contains("/us-east-1/sns/aws4_request"));
        assertTrue(auth.contains("SignedHeaders="));
        assertTrue(auth.contains("Signature="));
    }

    @Test
    @DisplayName("sign() no-ops when credentials are missing")
    void signSkipsWhenCredentialsMissing() {
        HttpHeaders headers = new HttpHeaders();
        signer.sign("POST", "https://sns.us-east-1.amazonaws.com/",
            headers, new byte[0], null, "secret", "us-east-1", null);
        assertFalse(headers.containsKey(HttpHeaders.AUTHORIZATION));

        signer.sign("POST", "https://sns.us-east-1.amazonaws.com/",
            headers, new byte[0], "key", "", "us-east-1", null);
        assertFalse(headers.containsKey(HttpHeaders.AUTHORIZATION));
    }

    @Test
    @DisplayName("sign() derives region from host when region arg is null")
    void signDerivesRegionFromHost() {
        HttpHeaders headers = new HttpHeaders();
        signer.sign(
            "GET",
            "https://sqs.eu-west-1.amazonaws.com/123456/my-queue",
            headers, new byte[0],
            "AKIA", "secretXYZ",
            null, // region derivation from host
            null);
        String auth = headers.getFirst(HttpHeaders.AUTHORIZATION);
        assertNotNull(auth);
        assertTrue(auth.contains("/eu-west-1/sqs/aws4_request"), "expected eu-west-1 region in credential scope; got: " + auth);
    }

    @Test
    @DisplayName("sign() with credentialFields map convenience wrapper works identically")
    void signWithCredentialsMap() {
        HttpHeaders headers = new HttpHeaders();
        signer.sign(
            "POST",
            "https://sns.us-east-1.amazonaws.com/",
            headers,
            "Action=Publish".getBytes(StandardCharsets.UTF_8),
            Map.of(
                "access_key_id", "AKIAEXAMPLE00000001",
                "secret_access_key", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                "region", "us-east-1"
            ));

        assertTrue(headers.containsKey(HttpHeaders.AUTHORIZATION));
        String auth = headers.getFirst(HttpHeaders.AUTHORIZATION);
        assertTrue(auth.contains("/us-east-1/sns/aws4_request"));
    }

    @Test
    @DisplayName("sign() leaves Authorization header absent for non-AWS hosts (service cannot be derived)")
    void signNonAwsHost() {
        HttpHeaders headers = new HttpHeaders();
        signer.sign(
            "POST",
            "https://api.example.com/v1/publish",
            headers, new byte[0],
            "AKIA", "secret", "us-east-1", null);
        assertFalse(headers.containsKey(HttpHeaders.AUTHORIZATION));
    }

    @Test
    @DisplayName("sign() payload hash is SHA-256 of the body bytes")
    void signPayloadHash() {
        HttpHeaders headers = new HttpHeaders();
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        signer.sign(
            "POST",
            "https://sns.us-east-1.amazonaws.com/",
            headers, body,
            "AKIA", "secret", "us-east-1", null);
        // SHA-256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            headers.getFirst("x-amz-content-sha256"));
    }
}
