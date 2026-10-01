package com.apimarketplace.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Creates the Keycloak identity of the daily sign-up canary (deploy/signup-canary). The public
 * registration form carries a reCAPTCHA a robot must not pass, so the canary gets its identity
 * here and then does everything else a new user does through the real UI: sign-in, onboarding
 * with the real e-mail code, the app, "Delete my account", and a check that the account is gone.
 *
 * <p>Deliberately narrow. It can create ONE identity: the configured canary address, which must
 * also be a {@link TestAccountPolicy test account} so its deletion is immediate. The caller proves
 * itself with a dedicated token, compared in constant time. Anything unset means disabled.
 */
@Service
public class SignupCanaryService {

    private static final Logger logger = LoggerFactory.getLogger(SignupCanaryService.class);

    static final int MIN_TOKEN_LENGTH = 32;
    static final int MIN_PASSWORD_LENGTH = 16;

    public enum Result { CREATED, ALREADY_EXISTS, DISABLED, UNAUTHORIZED, INVALID_PASSWORD }

    private final String email;
    private final byte[] token;
    private final KeycloakAdminEmailVerifier keycloakAdmin;

    @Autowired
    public SignupCanaryService(@Value("${account.signup-canary.email:}") String email,
                               @Value("${account.signup-canary.token:}") String token,
                               TestAccountPolicy testAccountPolicy,
                               @Autowired(required = false) KeycloakAdminEmailVerifier keycloakAdmin) {
        String normalized = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
        boolean configured = !normalized.isEmpty() && token != null && !token.isBlank();
        String problem = null;
        if (configured && token.trim().length() < MIN_TOKEN_LENGTH) {
            problem = "account.signup-canary.token is shorter than " + MIN_TOKEN_LENGTH + " characters";
        } else if (configured && !testAccountPolicy.isTestAccount(normalized)) {
            problem = "account.signup-canary.email is not matched by account.test-accounts.email-pattern, "
                    + "so its deletion would wait 30 days and the next day's sign-up would collide";
        } else if (configured && keycloakAdmin == null) {
            problem = "no Keycloak admin client (auth.mode is not keycloak)";
        }
        if (problem != null) {
            logger.error("Sign-up canary disabled: {}", problem);
        }
        boolean enabled = configured && problem == null;
        this.email = enabled ? normalized : null;
        this.token = enabled ? token.trim().getBytes(StandardCharsets.UTF_8) : null;
        this.keycloakAdmin = enabled ? keycloakAdmin : null;
    }

    public Result createIdentity(String presentedToken, String password) {
        if (email == null) return Result.DISABLED;
        if (presentedToken == null
                || !MessageDigest.isEqual(token, presentedToken.trim().getBytes(StandardCharsets.UTF_8))) {
            return Result.UNAUTHORIZED;
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) return Result.INVALID_PASSWORD;
        KeycloakAdminEmailVerifier.CreateOutcome outcome = keycloakAdmin.createPasswordUser(email, password);
        if (outcome == KeycloakAdminEmailVerifier.CreateOutcome.ALREADY_EXISTS) {
            logger.warn("Sign-up canary: identity {} already exists, the previous run's deletion did not complete", email);
            return Result.ALREADY_EXISTS;
        }
        logger.info("Sign-up canary: identity {} created", email);
        return Result.CREATED;
    }
}
