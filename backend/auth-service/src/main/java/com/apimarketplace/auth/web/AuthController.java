package com.apimarketplace.auth.web;

import com.apimarketplace.auth.security.JwtKeyPairManager;
import com.apimarketplace.auth.web.dto.JwksResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Public authentication endpoints: JWKS for JWT validation and a simple authorize stub.
 */
@RestController
@CrossOrigin(origins = "*")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    @Autowired(required = false)
    private JwtKeyPairManager keyPairManager;

    /**
     * GET /.well-known/jwks.json - Expose public keys for JWT validation.
     * Returns RSA public key when auth.mode=embedded, HMAC key otherwise.
     */
    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<JwksResponse> getJwks() {
        try {
            JwksResponse.JwkKey key;

            if (keyPairManager != null) {
                // RSA mode (CE embedded auth)
                RSAPublicKey rsaPublicKey = keyPairManager.getRsaPublicKey();
                key = JwksResponse.JwkKey.builder()
                        .kty("RSA")
                        .kid(keyPairManager.getKeyId())
                        .use("sig")
                        .alg("RS256")
                        .n(Base64.getUrlEncoder().withoutPadding().encodeToString(rsaPublicKey.getModulus().toByteArray()))
                        .e(Base64.getUrlEncoder().withoutPadding().encodeToString(rsaPublicKey.getPublicExponent().toByteArray()))
                        .build();
            } else {
                // HMAC mode (EE Keycloak) - never expose symmetric secret in JWKS.
                // JWKS is designed for asymmetric public keys only.
                logger.warn("JWKS requested in HMAC mode - returning empty key set (symmetric secrets must not be exposed)");
                JwksResponse jwks = JwksResponse.builder()
                        .keys(new JwksResponse.JwkKey[0])
                        .build();
                return ResponseEntity.ok(jwks);
            }

            JwksResponse jwks = JwksResponse.builder()
                    .keys(new JwksResponse.JwkKey[]{key})
                    .build();

            logger.debug("JWKS endpoint accessed, mode={}", keyPairManager != null ? "RSA" : "HMAC");
            return ResponseEntity.ok(jwks);

        } catch (Exception e) {
            logger.error("Error generating JWKS keys", e);
            return ResponseEntity.status(500).body(null);
        }
    }

    /**
     * POST /v1/authorize - Synchronous authorization stub.
     */
    @PostMapping("/v1/authorize")
    public ResponseEntity<Map<String, Object>> authorize(@RequestBody Map<String, Object> request) {
        try {
            String orgId = (String) request.get("org_id");
            String userId = (String) request.get("user_id");
            String resource = (String) request.get("resource");

            logger.info("Authorization request for orgId={}, userId={}, resource={}", orgId, userId, resource);

            boolean authorized = true; // to be implemented per business rules

            Map<String, Object> response = new HashMap<>();
            response.put("authorized", authorized);
            response.put("reason", authorized ? "OK" : "Quota exceeded");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error during authorization", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("authorized", false);
            errorResponse.put("reason", "Internal error");
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

}
