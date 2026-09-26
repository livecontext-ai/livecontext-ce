package com.apimarketplace.auth.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps every platform admin under a TOTP second factor (see {@link MfaService}).
 *
 * <p>A sweep rather than a hook, because an admin can lose the factor in places this
 * service never sees: removing it on a Keycloak page, a support reset in the Keycloak
 * console, or being granted {@code ADMIN} by a direct role change. Each run re-arms
 * {@code CONFIGURE_TOTP} on whoever has no factor, so the gap is at most one interval.
 * The admin list is a handful of rows, so the sweep costs a few Keycloak reads.
 *
 * <p>Cloud only ({@code auth.mode=keycloak}): CE has no Keycloak to arm.
 */
@Component
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class AdminMfaEnforcementScheduler {

    private static final Logger log = LoggerFactory.getLogger(AdminMfaEnforcementScheduler.class);

    private final MfaService mfaService;

    public AdminMfaEnforcementScheduler(MfaService mfaService) {
        this.mfaService = mfaService;
    }

    @Scheduled(initialDelayString = "${auth.mfa.admin-enforcement.initial-delay:PT2M}",
               fixedDelayString = "${auth.mfa.admin-enforcement.interval:PT15M}")
    @SchedulerLock(name = "admin_mfa_enforcement", lockAtMostFor = "PT10M")
    public void enforce() {
        try {
            int armed = mfaService.enforceTotpForAllAdmins();
            if (armed > 0) {
                log.info("Two-factor enrollment required for {} platform admin(s) at their next login", armed);
            }
        } catch (RuntimeException e) {
            // The scheduler thread must survive; the next run retries.
            log.warn("Admin two-factor enforcement run failed: {}", e.getMessage());
        }
    }
}
