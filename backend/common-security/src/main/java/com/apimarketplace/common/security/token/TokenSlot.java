package com.apimarketplace.common.security.token;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One token column of a {@link HashedTokenEntity}: where the plaintext is read from and where
 * its lookup hash is written.
 *
 * @param plaintext  reads the entity's (decrypted) token value
 * @param hashWriter writes the HMAC that {@code findBy<Token>Hash} queries compare against
 */
public record TokenSlot(Supplier<String> plaintext, Consumer<String> hashWriter) {
}
