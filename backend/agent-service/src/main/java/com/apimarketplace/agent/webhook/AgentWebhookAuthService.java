package com.apimarketplace.agent.webhook;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Service for validating agent webhook authentication.
 * Supports: none, basic, header, jwt.
 */
@Service
public class AgentWebhookAuthService {

    private static final Logger logger = LoggerFactory.getLogger(AgentWebhookAuthService.class);

    /**
     * Result of authentication validation.
     */
    public record AuthResult(boolean valid, String message) {
        public static AuthResult success() {
            return new AuthResult(true, null);
        }

        public static AuthResult failure(String message) {
            return new AuthResult(false, message);
        }
    }

    /**
     * Validates authentication based on agent webhook configuration.
     */
    public AuthResult validateAuth(AgentWebhookConfig config, HttpHeaders headers) {
        if (config == null || !config.requiresAuth()) {
            return AuthResult.success();
        }

        String authType = config.authType();
        logger.debug("Validating agent webhook auth type: {}", authType);

        return switch (authType.toLowerCase()) {
            case "basic" -> validateBasicAuth(config, headers);
            case "header" -> validateHeaderAuth(config, headers);
            case "jwt" -> validateJwtAuth(config, headers);
            // Fail closed: requiresAuth() already returned true, so an unrecognized
            // authType is a misconfiguration, never a reason to accept the request.
            default -> AuthResult.failure("Unsupported authentication type: " + authType);
        };
    }

    /**
     * Validates Basic Authentication.
     */
    private AuthResult validateBasicAuth(AgentWebhookConfig config, HttpHeaders headers) {
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.toLowerCase().startsWith("basic ")) {
            logger.debug("Missing or invalid Basic auth header");
            return AuthResult.failure("Missing Basic authentication");
        }

        try {
            String base64Credentials = authHeader.substring(6);
            String credentials = new String(Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
            String[] parts = credentials.split(":", 2);

            if (parts.length != 2) {
                logger.debug("Invalid Basic auth format");
                return AuthResult.failure("Invalid Basic auth format");
            }

            String username = parts[0];
            String password = parts[1];

            String expectedUsername = config.basicUsername();
            String expectedPassword = config.basicPassword();

            if (expectedUsername == null || expectedPassword == null) {
                logger.warn("Basic auth configured but credentials not set");
                return AuthResult.failure("Server configuration error");
            }

            if (constantTimeEquals(username, expectedUsername) && constantTimeEquals(password, expectedPassword)) {
                logger.debug("Basic auth validation successful");
                return AuthResult.success();
            }

            logger.debug("Basic auth credentials mismatch");
            return AuthResult.failure("Invalid credentials");

        } catch (IllegalArgumentException e) {
            logger.debug("Invalid Base64 in Basic auth header");
            return AuthResult.failure("Invalid Basic auth encoding");
        }
    }

    /**
     * Validates Header Authentication.
     */
    private AuthResult validateHeaderAuth(AgentWebhookConfig config, HttpHeaders headers) {
        String headerName = config.authHeaderName();
        String expectedValue = config.authHeaderValue();

        if (headerName == null || headerName.isBlank()) {
            logger.warn("Header auth configured but header name not set");
            return AuthResult.failure("Server configuration error");
        }

        String actualValue = headers.getFirst(headerName);

        if (actualValue == null) {
            logger.debug("Missing required header: {}", headerName);
            return AuthResult.failure("Missing header: " + headerName);
        }

        if (expectedValue == null || expectedValue.isBlank()) {
            logger.warn("Header auth configured but expected value not set");
            return AuthResult.failure("Server configuration error");
        }

        if (constantTimeEquals(actualValue, expectedValue)) {
            logger.debug("Header auth validation successful");
            return AuthResult.success();
        }

        logger.debug("Header value mismatch for: {}", headerName);
        return AuthResult.failure("Invalid header value");
    }

    /**
     * Validates JWT Authentication.
     */
    private AuthResult validateJwtAuth(AgentWebhookConfig config, HttpHeaders headers) {
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.toLowerCase().startsWith("bearer ")) {
            logger.debug("Missing or invalid Bearer auth header");
            return AuthResult.failure("Missing Bearer token");
        }

        String token = authHeader.substring(7);
        String secretKey = config.jwtSecretKey();

        if (secretKey == null || secretKey.isBlank()) {
            logger.warn("JWT auth configured but secret key not set");
            return AuthResult.failure("Server configuration error");
        }

        try {
            Algorithm algorithm = getJwtAlgorithm(config.jwtAlgorithm(), secretKey);
            JWTVerifier verifier = JWT.require(algorithm).build();
            verifier.verify(token);
            logger.debug("JWT validation successful");
            return AuthResult.success();

        } catch (JWTVerificationException e) {
            logger.debug("JWT verification failed: {}", e.getMessage());
            return AuthResult.failure("Invalid JWT: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.debug("Invalid JWT algorithm: {}", e.getMessage());
            return AuthResult.failure("Invalid JWT configuration");
        }
    }

    /**
     * Constant-time string comparison to avoid leaking secret length/content
     * through response timing. Returns false if either value is null.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private Algorithm getJwtAlgorithm(String algorithmName, String secret) {
        if (algorithmName == null) {
            algorithmName = "HS256";
        }

        return switch (algorithmName.toUpperCase()) {
            case "HS384" -> Algorithm.HMAC384(secret);
            case "HS512" -> Algorithm.HMAC512(secret);
            default -> Algorithm.HMAC256(secret);
        };
    }
}
