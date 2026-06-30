package com.apimarketplace.auth.credential.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates PKCE (Proof Key for Code Exchange, RFC 7636) challenges for the OAuth2 authorization
 * code flow.
 *
 * <p>Always uses the {@code S256} challenge method - the {@code plain} method is insecure and
 * no modern provider requires it.
 *
 * <p>The verifier is a 43-character URL-safe base64 string derived from 32 random bytes, which
 * gives ~256 bits of entropy (RFC 7636 §4.1 requires 43-128 characters of unreserved alphabet).
 */
@Service
public class PkceService {

    public static final String CHALLENGE_METHOD_S256 = "S256";

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generate a fresh PKCE challenge. Call once per authorization request; store the verifier
     * in the OAuth2 state and send only the challenge to the provider.
     */
    public PkceChallenge generate() {
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        String verifier = base64UrlNoPadding(random);
        String challenge = base64UrlNoPadding(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        return new PkceChallenge(verifier, challenge, CHALLENGE_METHOD_S256);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JDK spec; this branch is unreachable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String base64UrlNoPadding(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * A PKCE pair. {@code verifier} must be kept server-side (in Redis state) and sent only to
     * the token endpoint at callback time. {@code challenge} and {@code method} are sent on the
     * authorization URL.
     */
    public record PkceChallenge(String verifier, String challenge, String method) {}
}
