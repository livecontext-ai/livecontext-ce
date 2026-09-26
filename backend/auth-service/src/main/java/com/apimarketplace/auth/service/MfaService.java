package com.apimarketplace.auth.service;

import com.apimarketplace.auth.audit.AuditEventTypes;
import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Two-factor authentication (TOTP) for a user account: what the account holds, and the
 * rule that a platform admin must hold one.
 *
 * <p>Bi-mode on purpose, so it is not gated at the bean level: in the cloud the factor
 * lives in Keycloak ({@link KeycloakOtpCredentialClient}); in CE there is no TOTP yet and
 * {@link #getStatus} answers {@code available=false}, which the settings page reads to
 * hide the card instead of offering a button that leads nowhere.
 *
 * <p>Who MUST have a factor: platform admins ({@code ADMIN} in {@code auth.user_roles}),
 * while {@code auth.mfa.admin-enforcement.enabled} is on. Keycloak does not know that
 * role, so the rule cannot live in a Keycloak flow condition: it is applied by arming
 * Keycloak's {@code CONFIGURE_TOTP} and {@code CONFIGURE_RECOVERY_AUTHN_CODES} required
 * actions on every admin missing either ({@link #enforceTotpForAllAdmins}, run by
 * {@link AdminMfaEnforcementScheduler}). The admin then cannot finish a login without
 * enrolling, and once enrolled the realm's conditional second factor asks for the code (or
 * a recovery code) at every login.
 *
 * <p>Arming also ends the admin's Keycloak sessions, because a required action only runs
 * at a login and sessions last 14 days: without the logout an admin without a factor
 * could keep working for two weeks. What the logout cannot reach is an access token
 * already issued: the gateway verifies tokens by signature, so one stays valid until it
 * expires. That is the same limit every Keycloak-side revocation on this platform has.
 */
@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    static final String ADMIN_ROLE = "ADMIN";

    private final UserRepository userRepository;
    private final ObjectProvider<KeycloakOtpCredentialClient> keycloak;
    private final ObjectProvider<AuditLogger> auditLogger;
    private final boolean adminEnforcementEnabled;

    public MfaService(UserRepository userRepository,
                      ObjectProvider<KeycloakOtpCredentialClient> keycloak,
                      ObjectProvider<AuditLogger> auditLogger,
                      @Value("${auth.mfa.admin-enforcement.enabled:true}") boolean adminEnforcementEnabled) {
        this.userRepository = userRepository;
        this.keycloak = keycloak;
        this.auditLogger = auditLogger;
        this.adminEnforcementEnabled = adminEnforcementEnabled;
    }

    /** One registered authenticator app. */
    public record TotpDevice(String id, String label, Instant createdAt) {}

    /**
     * The account's single-use recovery codes. {@code remaining} / {@code total} are null
     * when Keycloak's credential data could not be read.
     */
    public record RecoveryCodesStatus(Integer remaining, Integer total) {}

    /**
     * @param available     false when this edition / account cannot hold a TOTP factor
     *                      (CE, or an account Keycloak does not know)
     * @param totpEnabled   at least one authenticator app is registered
     * @param devices       the registered authenticator apps
     * @param required      the account may not go without a factor (platform admin)
     * @param setupPending  Keycloak will make the user enroll an app at their next login
     * @param recoveryCodes the recovery codes, null when the account has none
     */
    public record MfaStatus(boolean available, boolean totpEnabled, List<TotpDevice> devices,
                            boolean required, boolean setupPending, RecoveryCodesStatus recoveryCodes) {

        static MfaStatus unavailable() {
            return new MfaStatus(false, false, List.of(), false, false, null);
        }
    }

    /** Keycloak could not be read, so the status is unknown and must not be guessed. */
    public static class MfaStatusUnavailableException extends RuntimeException {
        public MfaStatusUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    public enum AdminEnforcement { ALREADY_ENROLLED, ALREADY_PENDING, SETUP_REQUIRED }

    /**
     * The two-factor status of an account.
     *
     * <p>Also retires ORPHAN recovery codes: codes left behind after the last authenticator
     * app was removed. Keycloak keeps them, and its conditional second factor then counts
     * them as "configured", so every login would demand a recovery code, burning one each
     * time, until the account is locked out. The settings page reads this status right
     * after a removal (Keycloak sends the user back to it), so the orphan never outlives the
     * removal by more than that round trip.
     *
     * @throws MfaStatusUnavailableException when Keycloak cannot be read
     */
    public MfaStatus getStatus(Long userId) {
        KeycloakOtpCredentialClient client = keycloak.getIfAvailable();
        if (client == null) return MfaStatus.unavailable();

        User user = userRepository.findById(userId).orElse(null);
        if (user == null || isBlank(user.getProviderId())) return MfaStatus.unavailable();

        KeycloakOtpCredentialClient.SecondFactors factors;
        List<String> actions;
        try {
            factors = client.listSecondFactors(user.getProviderId());
            actions = client.getRequiredActions(user.getProviderId());
        } catch (RuntimeException e) {
            throw new MfaStatusUnavailableException("Two-factor status could not be read from Keycloak", e);
        }

        KeycloakOtpCredentialClient.RecoveryCodes codes = factors.recoveryCodes();
        if (factors.otp().isEmpty() && codes != null && retireOrphanRecoveryCodes(client, user, codes)) {
            codes = null;
        }

        List<TotpDevice> devices = new ArrayList<>();
        for (KeycloakOtpCredentialClient.OtpCredential c : factors.otp()) {
            devices.add(new TotpDevice(c.id(), c.label(), c.createdAt()));
        }
        return new MfaStatus(true, !devices.isEmpty(), List.copyOf(devices), isTotpRequired(user),
                actions.contains(KeycloakOtpCredentialClient.CONFIGURE_TOTP),
                codes == null ? null : new RecoveryCodesStatus(codes.remaining(), codes.total()));
    }

    /** Best effort: a failure leaves the codes, reported as they are, and the next read retries. */
    private boolean retireOrphanRecoveryCodes(KeycloakOtpCredentialClient client, User user,
                                              KeycloakOtpCredentialClient.RecoveryCodes codes) {
        try {
            client.deleteCredential(user.getProviderId(), codes.id());
        } catch (RuntimeException e) {
            log.warn("Orphan recovery codes of userId={} could not be removed (no authenticator app left): {}",
                    user.getId(), e.getMessage());
            return false;
        }
        AuditLogger audit = auditLogger.getIfAvailable();
        if (audit != null) {
            audit.event(AuditEventTypes.MFA_RECOVERY_CODES_RETIRED)
                    .user(user.getId()).success().detail("reason", "no_authenticator_app").write();
        }
        log.info("Removed the recovery codes of userId={}: their last authenticator app is gone", user.getId());
        return true;
    }

    /** True when this account may not go without a second factor. */
    public boolean isTotpRequired(User user) {
        return adminEnforcementEnabled && user.getRoles() != null && user.getRoles().contains(ADMIN_ROLE);
    }

    /**
     * Makes sure an admin holds an authenticator app AND recovery codes, or will be made to
     * set up what is missing at their next login (app first, then codes: Keycloak runs the
     * required actions by priority), and ends their sessions so that login happens now.
     * Without recovery codes a lost phone locks an admin out until someone edits Keycloak.
     * Reads before it writes, so nothing present is re-armed, and keeps every other pending
     * required action.
     *
     * @throws RuntimeException when Keycloak cannot be read or written
     */
    public AdminEnforcement enforceTotpForAdmin(User admin) {
        KeycloakOtpCredentialClient client = keycloak.getObject();
        String providerId = admin.getProviderId();
        KeycloakOtpCredentialClient.SecondFactors factors = client.listSecondFactors(providerId);

        List<String> missing = new ArrayList<>();
        if (factors.otp().isEmpty()) missing.add(KeycloakOtpCredentialClient.CONFIGURE_TOTP);
        if (factors.recoveryCodes() == null) missing.add(KeycloakOtpCredentialClient.CONFIGURE_RECOVERY_CODES);
        if (missing.isEmpty()) return AdminEnforcement.ALREADY_ENROLLED;

        List<String> actions = client.getRequiredActions(providerId);
        List<String> toArm = missing.stream().filter(a -> !actions.contains(a)).toList();
        if (toArm.isEmpty()) return AdminEnforcement.ALREADY_PENDING;

        List<String> updated = new ArrayList<>(actions);
        updated.addAll(toArm);
        client.setRequiredActions(providerId, updated);
        try {
            client.logoutAllSessions(providerId);
        } catch (RuntimeException e) {
            // Enrollment is armed either way; it then waits for the next login instead.
            log.warn("Sessions of platform admin userId={} could not be ended, enrollment waits for their next login: {}",
                    admin.getId(), e.getMessage());
        }

        AuditLogger audit = auditLogger.getIfAvailable();
        if (audit != null) {
            audit.event(AuditEventTypes.MFA_SETUP_REQUIRED)
                    .user(admin.getId()).success().detail("reason", "platform_admin")
                    .detail("actions", String.join(",", toArm)).write();
        }
        log.info("Two-factor setup {} armed for platform admin userId={}", toArm, admin.getId());
        return AdminEnforcement.SETUP_REQUIRED;
    }

    /**
     * Applies {@link #enforceTotpForAdmin} to every enabled admin Keycloak knows. One
     * admin failing (Keycloak hiccup, user deleted in Keycloak) never stops the others:
     * the next run retries it.
     *
     * @return how many admins were newly required to enroll
     */
    public int enforceTotpForAllAdmins() {
        if (!adminEnforcementEnabled || keycloak.getIfAvailable() == null) return 0;
        int armed = 0;
        for (User admin : userRepository.findEnabledAdminsWithProviderId()) {
            try {
                if (enforceTotpForAdmin(admin) == AdminEnforcement.SETUP_REQUIRED) armed++;
            } catch (RuntimeException e) {
                log.warn("Two-factor enforcement skipped for admin userId={} this run: {}",
                        admin.getId(), e.getMessage());
            }
        }
        return armed;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
