package com.apimarketplace.catalog.service.exception;

/**
 * Base exception for all catalog service exceptions.
 * Provides a common hierarchy for error handling and consistent error responses.
 */
public class CatalogServiceException extends RuntimeException {

    private final String errorCode;

    public CatalogServiceException(String message) {
        super(message);
        this.errorCode = "CATALOG_ERROR";
    }

    public CatalogServiceException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public CatalogServiceException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "CATALOG_ERROR";
    }

    public CatalogServiceException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
