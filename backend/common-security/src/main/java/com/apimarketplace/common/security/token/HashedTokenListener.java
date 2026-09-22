package com.apimarketplace.common.security.token;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/**
 * Fills every {@link TokenSlot} hash of a {@link HashedTokenEntity} before Hibernate writes the
 * row. Runs on persist and on update: the entity keeps the plaintext in memory (the converter
 * only encrypts on the way to the column), so the hash is always derived from the current value
 * and a regenerated token gets a fresh hash with no extra call at the service.
 */
public class HashedTokenListener {

    @PrePersist
    @PreUpdate
    public void computeHashes(Object entity) {
        if (entity instanceof HashedTokenEntity hashed) {
            for (TokenSlot slot : hashed.tokenSlots()) {
                slot.hashWriter().accept(TokenAtRest.hash(slot.plaintext().get()));
            }
        }
    }
}
