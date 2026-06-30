package com.apimarketplace.auth.service;

public class SamlProvisioningException extends RuntimeException {

    public SamlProvisioningException(String message) {
        super(message);
    }

    public SamlProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
