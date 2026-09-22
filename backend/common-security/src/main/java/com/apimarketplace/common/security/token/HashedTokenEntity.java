package com.apimarketplace.common.security.token;

import java.util.List;

/**
 * An entity with one or more capability-token columns stored through
 * {@link EncryptedTokenConverter} and looked up through a sibling hash column.
 *
 * <p>Declare the entity with {@code @EntityListeners(HashedTokenListener.class)} and return one
 * {@link TokenSlot} per token column. The listener recomputes every slot's hash from the
 * plaintext on persist and on update, so a service that regenerates a token only calls the
 * plain setter, exactly as before.
 */
public interface HashedTokenEntity {

    /** The token columns of this entity, each paired with its hash accessor. */
    List<TokenSlot> tokenSlots();
}
