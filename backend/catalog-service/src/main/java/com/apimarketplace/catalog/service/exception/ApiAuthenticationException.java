package com.apimarketplace.catalog.service.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception thrown when an external API returns 401 Unauthorized or 403 Forbidden.
 * Preserves the HTTP status code so controllers can return the proper status.
 */
public class ApiAuthenticationException extends CatalogServiceException {

    private final HttpStatus status;
    private final String service;

    public ApiAuthenticationException(String message, HttpStatus status, String service) {
        super(message, "API_AUTHENTICATION_ERROR");
        this.status = status;
        this.service = service;
    }

    public ApiAuthenticationException(String message, HttpStatus status, String service, Throwable cause) {
        super(message, "API_AUTHENTICATION_ERROR", cause);
        this.status = status;
        this.service = service;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getService() {
        return service;
    }

    /**
     * Factory method for 401 Unauthorized errors.
     */
    public static ApiAuthenticationException unauthorized(String service, String message) {
        return new ApiAuthenticationException(message, HttpStatus.UNAUTHORIZED, service);
    }

    /**
     * Factory method for 401 Unauthorized errors with cause.
     */
    public static ApiAuthenticationException unauthorized(String service, String message, Throwable cause) {
        return new ApiAuthenticationException(message, HttpStatus.UNAUTHORIZED, service, cause);
    }

    /**
     * Factory method for 403 Forbidden errors.
     */
    public static ApiAuthenticationException forbidden(String service, String message) {
        return new ApiAuthenticationException(message, HttpStatus.FORBIDDEN, service);
    }

    /**
     * Factory method for 403 Forbidden errors with cause.
     */
    public static ApiAuthenticationException forbidden(String service, String message, Throwable cause) {
        return new ApiAuthenticationException(message, HttpStatus.FORBIDDEN, service, cause);
    }
}
