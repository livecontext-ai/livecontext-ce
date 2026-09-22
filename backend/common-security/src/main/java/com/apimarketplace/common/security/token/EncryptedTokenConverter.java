package com.apimarketplace.common.security.token;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JPA converter for a capability-token column: the entity holds the plaintext, the column
 * holds {@code ENC:<aes>}. Put it on the token attribute with
 * {@code @Convert(converter = EncryptedTokenConverter.class)}.
 *
 * <p>Two consequences every mapped entity must respect:
 * <ul>
 *   <li>A JPQL comparison on the converted attribute ({@code WHERE t.token = :token}) can never
 *       match: Hibernate converts the parameter too, and the IV is random. Lookups go through the
 *       hash column ({@link HashedTokenListener}). Repository methods named {@code findByToken}
 *       were removed for that reason; do not reintroduce one.</li>
 *   <li>A row written before the change holds the plaintext. {@link #convertToEntityAttribute}
 *       returns it as-is (no prefix, no decryption), so reads keep working until
 *       {@link PlaintextTokenBackfill} rewrites the row.</li>
 * </ul>
 *
 * <p>Decryption failure (key rotated under the data) is logged and the stored value returned
 * unchanged rather than thrown: a single unreadable row must not take down a listing of fifty.
 * The token is then visibly wrong in the UI and its hash lookup fails, which is the honest
 * outcome; a thrown exception here would have hidden the other forty-nine rows.
 */
@Converter
public class EncryptedTokenConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(EncryptedTokenConverter.class);

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        return TokenAtRest.encrypt(plaintext);
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null || stored.isBlank() || !TokenAtRest.isEncrypted(stored)) {
            return stored;
        }
        try {
            return TokenAtRest.decrypt(stored);
        } catch (RuntimeException e) {
            log.error("Cannot decrypt a stored token: the credential encryption material differs from the one "
                    + "that wrote it. The row is returned as ciphertext and cannot be looked up until the key "
                    + "is restored (cause: {})", e.getMessage());
            return stored;
        }
    }
}
