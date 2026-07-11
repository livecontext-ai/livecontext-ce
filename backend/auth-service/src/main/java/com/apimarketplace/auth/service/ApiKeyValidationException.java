package com.apimarketplace.auth.service;

/**
 * Client-error (HTTP 400) validation failure on API-key management operations
 * (missing/too-long name, empty scope list, active-key cap). Distinct from
 * {@link IllegalArgumentException}, which the API-key endpoints map to 404
 * ("user/key not found") for historical consistency with /current and /regenerate.
 */
public class ApiKeyValidationException extends RuntimeException {

    public ApiKeyValidationException(String message) {
        super(message);
    }
}
