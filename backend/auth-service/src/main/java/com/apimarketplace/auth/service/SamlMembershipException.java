package com.apimarketplace.auth.service;

public class SamlMembershipException extends RuntimeException {

    public SamlMembershipException(String message) {
        super(message);
    }

    public SamlMembershipException(String message, Throwable cause) {
        super(message, cause);
    }
}
