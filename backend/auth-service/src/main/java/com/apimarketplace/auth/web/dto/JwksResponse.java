package com.apimarketplace.auth.web.dto;

import lombok.Builder;
import lombok.Data;

/**
 * JWKS response
 */
@Data
@Builder
public class JwksResponse {
    private JwkKey[] keys;

    @Data
    @Builder
    public static class JwkKey {
        private String kty; // Key type
        private String kid; // Key ID
        private String use; // Public key use
        private String alg; // Algorithm
        private String k;   // Key value (for HMAC)
        private String n;   // Modulus (for RSA)
        private String e;   // Exponent (for RSA)
    }
}
