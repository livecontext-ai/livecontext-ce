package com.apimarketplace.auth.service;

import java.util.UUID;

/**
 * Spring application event fired by {@link CeLinkService#revoke} AFTER the
 * DB commit. Listeners get a clean post-commit hook so any side-effects
 * (KC session logout, cleanup) run only after the registry mutation is durable.
 *
 * <p>Use {@code @TransactionalEventListener(phase = AFTER_COMMIT)} when
 * subscribing - a plain {@code @EventListener} would fire even on rollback.
 *
 * @param userId    cloud user id that owned the link (numeric, internal)
 * @param installId the UUID of the ce_link row just transitioned to REVOKED
 */
public record CeLinkRevokedEvent(Long userId, UUID installId) {
}
