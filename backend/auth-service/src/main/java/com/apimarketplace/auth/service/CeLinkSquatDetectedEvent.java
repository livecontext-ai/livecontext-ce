package com.apimarketplace.auth.service;

import java.util.UUID;

/**
 * Fired when a register attempt collides with an install_id already owned by a
 * different user (the SUSPECTED_CROSS_USER_RESET audit-event scenario, doc §7).
 *
 * <p>The legitimate owner (the {@code victimUserId}) gets a recovery email
 * carrying a one-time HMAC token (PR3c.3 {@code SquatRecoveryService}). The
 * attacker's userId is carried for the audit trail; the email itself never
 * exposes it.
 *
 * @param victimUserId   cloud user that legitimately owns the install_id
 * @param attackerUserId cloud user that tried to register the same install_id
 * @param installId      the contested ce_link install_id
 */
public record CeLinkSquatDetectedEvent(Long victimUserId, Long attackerUserId, UUID installId) {
}
