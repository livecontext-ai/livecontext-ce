package com.apimarketplace.common.storage.exception;

/**
 * Exception levee lorsque le quota de stockage est depasse.
 */
public class QuotaExceededException extends RuntimeException {

    private final String tenantId;

    public QuotaExceededException(String message) {
        super(message);
        this.tenantId = null;
    }

    public QuotaExceededException(String message, String tenantId) {
        super(message);
        this.tenantId = tenantId;
    }

    public String getTenantId() {
        return tenantId;
    }
}
