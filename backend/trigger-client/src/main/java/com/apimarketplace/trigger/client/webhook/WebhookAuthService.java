package com.apimarketplace.trigger.client.webhook;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Service for validating webhook authentication.
 * Supports multiple authentication types: none, basic, header, jwt.
 *
 * <p>This class lives in trigger-client so both orchestrator-service and
 * trigger-service can reuse it. Consumers must declare the bean themselves
 * (e.g. via {@code @Bean} in a {@code @Configuration} class).
 */
public class WebhookAuthService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookAuthService.class);

    /**
     * Result of authentication validation.
     */
    public record WebhookAuthResult(boolean valid, String message) {
        public static WebhookAuthResult success() {
            return new WebhookAuthResult(true, null);
        }

        public static WebhookAuthResult failure(String message) {
            return new WebhookAuthResult(false, message);
        }
    }

    /**
     * Validates authentication based on webhook configuration.
     *
     * @param config  The webhook configuration containing auth settings
     * @param headers The HTTP headers from the incoming request
     * @return WebhookAuthResult indicating success or failure with message
     */
    public WebhookAuthResult validateAuth(WebhookConfig config, HttpHeaders headers) {
        if (config == null || !config.requiresAuth()) {
            return WebhookAuthResult.success();
        }

        String authType = config.authType();
        logger.debug("Validating webhook auth type: {}", authType);

        return switch (authType.toLowerCase()) {
            case "basic" -> validateBasicAuth(config, headers);
            case "header" -> validateHeaderAuth(config, headers);
            case "jwt" -> validateJwtAuth(config, headers);
            default -> WebhookAuthResult.success(); // Unknown type treated as no auth
        };
    }

    /**
     * Validates Basic Authentication.
     * Expects: Authorization: Basic base64(username:password)
     */
    private WebhookAuthResult validateBasicAuth(WebhookConfig config, HttpHeaders headers) {
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.toLowerCase().startsWith("basic ")) {
            logger.debug("Missing or invalid Basic auth header");
            return WebhookAuthResult.failure("Missing Basic authentication");
        }

        try {
            String base64Credentials = authHeader.substring(6);
            String credentials = new String(Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
            String[] parts = credentials.split(":", 2);

            if (parts.length != 2) {
                logger.debug("Invalid Basic auth format");
                return WebhookAuthResult.failure("Invalid Basic auth format");
            }

            String username = parts[0];
            String password = parts[1];

            String expectedUsername = config.basicUsername();
            String expectedPassword = config.basicPassword();

            if (expectedUsername == null || expectedPassword == null) {
                logger.warn("Basic auth configured but credentials not set in webhook config");
                return WebhookAuthResult.failure("Server configuration error");
            }

            if (username.equals(expectedUsername) && password.equals(expectedPassword)) {
                logger.debug("Basic auth validation successful");
                return WebhookAuthResult.success();
            }

            logger.debug("Basic auth credentials mismatch");
            return WebhookAuthResult.failure("Invalid credentials");

        } catch (IllegalArgumentException e) {
            logger.debug("Invalid Base64 in Basic auth header");
            return WebhookAuthResult.failure("Invalid Basic auth encoding");
        }
    }

    /**
     * Validates Header Authentication.
     * Expects a custom header with the configured name and value.
     */
    private WebhookAuthResult validateHeaderAuth(WebhookConfig config, HttpHeaders headers) {
        String headerName = config.authHeaderName();
        String expectedValue = config.authHeaderValue();

        if (headerName == null || headerName.isBlank()) {
            logger.warn("Header auth configured but header name not set");
            return WebhookAuthResult.failure("Server configuration error");
        }

        String actualValue = headers.getFirst(headerName);

        if (actualValue == null) {
            logger.debug("Missing required header: {}", headerName);
            return WebhookAuthResult.failure("Missing header: " + headerName);
        }

        if (expectedValue == null || expectedValue.isBlank()) {
            logger.warn("Header auth configured but expected value not set");
            return WebhookAuthResult.failure("Server configuration error");
        }

        if (actualValue.equals(expectedValue)) {
            logger.debug("Header auth validation successful");
            return WebhookAuthResult.success();
        }

        logger.debug("Header value mismatch for: {}", headerName);
        return WebhookAuthResult.failure("Invalid header value");
    }

    /**
     * Validates JWT Authentication.
     * Expects: Authorization: Bearer <jwt-token>
     * Validates the JWT signature using HMAC algorithm.
     */
    private WebhookAuthResult validateJwtAuth(WebhookConfig config, HttpHeaders headers) {
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.toLowerCase().startsWith("bearer ")) {
            logger.debug("Missing or invalid Bearer auth header");
            return WebhookAuthResult.failure("Missing Bearer token");
        }

        String token = authHeader.substring(7);
        String secretKey = config.jwtSecretKey();

        if (secretKey == null || secretKey.isBlank()) {
            logger.warn("JWT auth configured but secret key not set");
            return WebhookAuthResult.failure("Server configuration error");
        }

        try {
            Algorithm algorithm = getJwtAlgorithm(config.jwtAlgorithm(), secretKey);
            JWTVerifier verifier = JWT.require(algorithm)
                    .build();

            verifier.verify(token);
            logger.debug("JWT validation successful");
            return WebhookAuthResult.success();

        } catch (JWTVerificationException e) {
            logger.debug("JWT verification failed: {}", e.getMessage());
            return WebhookAuthResult.failure("Invalid JWT: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.debug("Invalid JWT algorithm: {}", e.getMessage());
            return WebhookAuthResult.failure("Invalid JWT configuration");
        }
    }

    /**
     * Gets the JWT algorithm based on configuration.
     * Supports: HS256, HS384, HS512
     */
    private Algorithm getJwtAlgorithm(String algorithmName, String secret) {
        if (algorithmName == null) {
            algorithmName = "HS256";
        }

        return switch (algorithmName.toUpperCase()) {
            case "HS384" -> Algorithm.HMAC384(secret);
            case "HS512" -> Algorithm.HMAC512(secret);
            default -> Algorithm.HMAC256(secret); // HS256 is default
        };
    }
}
