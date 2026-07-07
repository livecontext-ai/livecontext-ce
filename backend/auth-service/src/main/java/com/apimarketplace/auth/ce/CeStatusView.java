package com.apimarketplace.auth.ce;

import java.time.Instant;

/**
 * DTO returned by {@code GET /api/ce/status}. Intentionally omits
 * {@code bootstrapAdminId} - the endpoint is public (gateway-allowlisted), so
 * leaking the admin's internal id would be a mild information disclosure.
 *
 * <p>{@code registrationOpen} reflects whether {@code /api/auth/register}
 * currently accepts new signups. The fallback when the singleton row is
 * missing is {@code false} (fail-CLOSED) - if our state-of-the-world is
 * unknown, do NOT silently re-open public signup. The frontend wizard guard
 * still routes correctly because it gates on {@code bootstrapped=false}.
 *
 * <p>{@code hasUsers} is the first-run signal: {@code false} means NO account
 * exists yet, so the login page routes to admin-account creation instead of
 * showing "Welcome back" to someone who cannot possibly sign in. It only
 * discloses whether an install is virgin (boolean), never a count.
 */
public record CeStatusView(
        boolean bootstrapped,
        Instant bootstrappedAt,
        String version,
        boolean registrationOpen,
        boolean hasUsers
) {
    /**
     * Back-compat convenience (pre-hasUsers callsites, mostly tests): defaults
     * {@code hasUsers=false}. CAUTION: {@code false} is the "virgin install"
     * reading that routes /login to account creation - the OPPOSITE of the
     * fail-safe direction ({@code CeInstallStateService.hasAnyUser} fails to
     * {@code true}). Production callers must pass {@code hasUsers} explicitly.
     */
    public CeStatusView(boolean bootstrapped, Instant bootstrappedAt, String version, boolean registrationOpen) {
        this(bootstrapped, bootstrappedAt, version, registrationOpen, false);
    }

    /**
     * Back-compat overload: defaults {@code hasUsers=false}. Same caution as
     * the 4-arg constructor - test convenience, not a production default.
     */
    public static CeStatusView notBootstrapped() {
        return notBootstrapped(false);
    }

    public static CeStatusView notBootstrapped(boolean hasUsers) {
        // Fail-CLOSED on registration: if the DB singleton is missing, do not
        // re-open public signup. The wizard still works because bootstrapped=false
        // and the wizard endpoint will be re-opening registration via the
        // markBootstrapped → state.setRegistrationOpen(false) cycle. The frontend
        // CeSetup page calls setRegistrationOpen(true) implicitly through its
        // re-open flow if the operator wants it.
        return new CeStatusView(false, null, CeInstallState.CURRENT_VERSION, false, hasUsers);
    }

    public static CeStatusView from(CeInstallState state, boolean hasUsers) {
        return new CeStatusView(
                state.isBootstrapped(),
                state.getBootstrappedAt(),
                state.getVersion(),
                state.isRegistrationOpen(),
                hasUsers
        );
    }
}
