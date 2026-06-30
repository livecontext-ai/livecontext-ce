package com.apimarketplace.common.web;

/**
 * Exception thrown when tenant ID is required but not provided.
 * This typically occurs when the X-User-ID header is missing from the request.
 */
public class TenantRequiredException extends RuntimeException {

    public TenantRequiredException(String message) {
        super(message);
    }

    public TenantRequiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
