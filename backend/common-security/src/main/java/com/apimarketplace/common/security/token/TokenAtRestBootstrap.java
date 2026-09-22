package com.apimarketplace.common.security.token;

import com.apimarketplace.common.security.CredentialEncryptionService;
import org.springframework.stereotype.Component;

/**
 * Installs the context's {@link CredentialEncryptionService} into {@link TokenAtRest} as soon as
 * the bean exists, which is before any JPA repository can be called. Every service that maps a
 * token column with {@link EncryptedTokenConverter} scans {@code com.apimarketplace.common.security}
 * and therefore gets this component; a {@code @DataJpaTest} slice must {@code @Import} it (and
 * the encryption service) explicitly, or the first persist fails with a message naming this class.
 */
@Component
public class TokenAtRestBootstrap {

    public TokenAtRestBootstrap(CredentialEncryptionService encryptionService) {
        TokenAtRest.install(encryptionService);
    }
}
