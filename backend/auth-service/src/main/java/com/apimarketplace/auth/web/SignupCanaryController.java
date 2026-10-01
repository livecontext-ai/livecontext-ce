package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.SignupCanaryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The daily sign-up canary's identity (see {@link SignupCanaryService}). Public at the gateway,
 * authenticated here by {@code X-Signup-Canary-Token}. Disabled answers 404, like a missing route.
 */
@RestController
@RequestMapping("/api/signup-canary")
public class SignupCanaryController {

    private final SignupCanaryService signupCanaryService;

    public SignupCanaryController(SignupCanaryService signupCanaryService) {
        this.signupCanaryService = signupCanaryService;
    }

    @PostMapping("/identity")
    public ResponseEntity<Map<String, String>> createIdentity(
            @RequestHeader(value = "X-Signup-Canary-Token", required = false) String token,
            @RequestBody(required = false) Map<String, String> body) {
        String password = body == null ? null : body.get("password");
        SignupCanaryService.Result result;
        try {
            result = signupCanaryService.createIdentity(token, password);
        } catch (IllegalStateException keycloakFailure) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "keycloak_failed"));
        }
        return switch (result) {
            case CREATED -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of("status", "created"));
            case ALREADY_EXISTS -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "identity_exists"));
            case INVALID_PASSWORD -> ResponseEntity.badRequest().body(Map.of("error", "password_too_short"));
            case UNAUTHORIZED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            case DISABLED -> ResponseEntity.notFound().build();
        };
    }
}
