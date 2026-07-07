package com.apimarketplace.auth.ce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Reads/writes the CE install-state singleton.
 *
 * <p>Two callers: {@code CeInstallController} (HTTP) and tests. No cross-service
 * access - auth-service owns the {@code auth} schema.
 */
@Service
public class CeInstallStateService {

    private static final Logger log = LoggerFactory.getLogger(CeInstallStateService.class);

    private final CeInstallStateRepository repository;
    private final com.apimarketplace.auth.repository.UserRepository userRepository;

    public CeInstallStateService(CeInstallStateRepository repository,
                                 com.apimarketplace.auth.repository.UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    /**
     * Fail-open read: if the singleton row is somehow missing (e.g. a botched
     * migration) we return {@code bootstrapped=false} rather than throwing. The
     * frontend guard then falls through to the wizard, which is the safe default.
     */
    @Transactional(readOnly = true)
    public CeStatusView getStatus() {
        boolean hasUsers = hasAnyUser();
        return repository.findById(CeInstallState.SINGLETON_ID)
                .map(state -> CeStatusView.from(state, hasUsers))
                .orElseGet(() -> CeStatusView.notBootstrapped(hasUsers));
    }

    /**
     * First-run probe behind {@code CeStatusView.hasUsers} (LIMIT 1, no count
     * scan). Fail-SAFE to {@code true} on error: {@code false} is what routes
     * the login page to account creation, so an unknown state must never send
     * existing users of a working install to the register page.
     */
    private boolean hasAnyUser() {
        try {
            return userRepository.findFirstBy().isPresent();
        } catch (Exception e) {
            log.warn("[CE] user-existence probe failed - reporting hasUsers=true (fail-safe): {}", e.getMessage());
            return true;
        }
    }

    /**
     * Idempotent completion write. First caller flips {@code bootstrapped=true},
     * stamps {@code bootstrapped_at}, and records the admin's id. Subsequent calls
     * are no-ops (do NOT overwrite the original timestamp - that would erase the
     * "when did we first go live" audit signal).
     *
     * <p>Uses {@code SELECT ... FOR UPDATE} via
     * {@link CeInstallStateRepository#findSingletonForUpdate} so two concurrent
     * admin POSTs serialize on the row; the second reads the already-bootstrapped
     * state and returns.
     */
    @Transactional
    public CeStatusView markBootstrapped(Long adminUserId) {
        CeInstallState state = repository.findSingletonForUpdate()
                .orElseGet(this::createSingletonIfMissing);

        if (state.isBootstrapped()) {
            log.debug("CE install already bootstrapped at {} (admin={}) - no-op",
                    state.getBootstrappedAt(), state.getBootstrapAdminId());
            return CeStatusView.from(state, hasAnyUser());
        }

        Instant now = Instant.now();
        state.setBootstrapped(true);
        state.setBootstrappedAt(now);
        state.setBootstrapAdminId(adminUserId);
        // Auto-close public registration once the wizard is done. Admin can
        // re-open via setRegistrationOpen(true). Closing the door is the safe
        // default for a self-hosted box that has just gone live.
        state.setRegistrationOpen(false);
        state.setUpdatedAt(now);
        CeInstallState saved = repository.save(state);

        log.info("[CE] install bootstrapped by admin {} at {} - registration auto-closed", adminUserId, now);
        return CeStatusView.from(saved, hasAnyUser());
    }

    /**
     * Returns the current public-registration flag. Used by
     * {@code EmbeddedAuthController.register} to deny new signups when an
     * admin has closed the door.
     */
    @Transactional(readOnly = true)
    public boolean isRegistrationOpen() {
        return getStatus().registrationOpen();
    }

    /**
     * Admin-only mutation. Toggling the flag is idempotent - calling with the
     * same value as the current state is a no-op (returns the existing view).
     */
    @Transactional
    public CeStatusView setRegistrationOpen(boolean open) {
        CeInstallState state = repository.findSingletonForUpdate()
                .orElseGet(this::createSingletonIfMissing);

        if (state.isRegistrationOpen() == open) {
            return CeStatusView.from(state, hasAnyUser());
        }

        state.setRegistrationOpen(open);
        state.setUpdatedAt(Instant.now());
        CeInstallState saved = repository.save(state);

        log.info("[CE] registration {} by admin action", open ? "OPENED" : "CLOSED");
        return CeStatusView.from(saved, hasAnyUser());
    }

    /**
     * Defensive: re-create the singleton if it's missing. V121 seeds it, so this
     * branch should never fire in practice - but if it does, we would rather
     * self-heal than surface a 500 to the admin clicking Finish.
     */
    private CeInstallState createSingletonIfMissing() {
        log.warn("[CE] ce_install_state singleton row was missing - recreating. "
                + "Check whether V121 ran.");
        CeInstallState state = new CeInstallState();
        state.setId(CeInstallState.SINGLETON_ID);
        state.setBootstrapped(false);
        state.setVersion(CeInstallState.CURRENT_VERSION);
        state.setUpdatedAt(Instant.now());
        return repository.save(state);
    }
}
