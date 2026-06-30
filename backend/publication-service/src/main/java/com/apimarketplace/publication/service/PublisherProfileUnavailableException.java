package com.apimarketplace.publication.service;

/**
 * Thrown by {@link PublisherProfileSnapshotter} when auth-service cannot supply
 * the publisher's identity at (re)publish time (transport failure, unknown
 * user, etc.).
 *
 * <p>Distinct from {@link IllegalStateException} on purpose - the latter is
 * used by publication-service for genuine state conflicts (e.g. "republish
 * blocked while pending review") and is mapped to HTTP 409 in the internal
 * controllers. Publisher-identity resolution failure is an upstream / transient
 * condition, not a conflict, so we use a separate type to route it to a
 * 5xx response instead.
 */
public class PublisherProfileUnavailableException extends RuntimeException {

    public PublisherProfileUnavailableException(String tenantId) {
        super("Failed to resolve publisher identity for tenant " + tenantId);
    }
}
