package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.ApiKeyResponse;
import com.apimarketplace.auth.dto.UserResolutionResponse;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Service for API key lifecycle: generation, secure storage, and resolution.
 * Keys are stored as HMAC-SHA256 hashes; plaintext is returned only once on generation.
 */
@Service
@Transactional
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private static final String KEY_PREFIX = "lc_live_";
    private static final int KEY_RANDOM_BYTES = 32;
    private static final int HINT_SUFFIX_LENGTH = 4;

    private final UserRepository userRepository;
    private final CredentialEncryptionService encryptionService;
    private final UserResolutionService userResolutionService;
    private final GatewayCacheClient gatewayCacheClient;
    private final SecureRandom secureRandom;

    public ApiKeyService(UserRepository userRepository,
                         CredentialEncryptionService encryptionService,
                         UserResolutionService userResolutionService,
                         GatewayCacheClient gatewayCacheClient) {
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
        this.userResolutionService = userResolutionService;
        this.gatewayCacheClient = gatewayCacheClient;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Returns current API key info (hint, no plaintext).
     */
    @Transactional(readOnly = true)
    public ApiKeyResponse getCurrentKeyInfo(Long userId) {
        log.debug("Getting current API key info for userId: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        ApiKeyResponse response = new ApiKeyResponse();
        response.setApiKey(null); // Never return plaintext on GET

        if (user.getApiKeyHint() != null) {
            response.setMaskedApiKey(user.getApiKeyHint());
            response.setCreatedAt(user.getApiKeyCreatedAt());
            response.setActive(true);
        } else {
            response.setMaskedApiKey(null);
            response.setCreatedAt(null);
            response.setActive(false);
        }
        return response;
    }

    /**
     * Generates a new API key, invalidating any previous key.
     * Returns plaintext once; caller must show it to user immediately.
     */
    public ApiKeyResponse regenerateKey(Long userId) {
        log.info("Regenerating API key for userId: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // Generate plaintext key: lc_live_<64 hex chars>
        byte[] randomBytes = new byte[KEY_RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);
        String plaintextKey = KEY_PREFIX + HexFormat.of().formatHex(randomBytes);

        // Hash with HMAC-SHA256 for storage
        String hash = encryptionService.hmacHash(plaintextKey);

        // Build hint: "lc_live_...xxxx" (prefix + last 4 chars)
        String hint = KEY_PREFIX + "..." + plaintextKey.substring(plaintextKey.length() - HINT_SUFFIX_LENGTH);

        // Store on user entity
        user.setApiKeyHash(hash);
        user.setApiKeyHint(hint);
        user.setApiKeyCreatedAt(LocalDateTime.now());
        userRepository.save(user);

        // Bust the gateway's user-resolution cache so the OLD key stops authenticating
        // NOW, not after the 5-min TTL: entries cached under "apikey:<old plaintext>"
        // are swept by providerId in QuotaCacheService.invalidateUserCache. After-commit
        // so a rollback fires no eviction and no replica re-caches pre-commit state
        // (same pattern as OrganizationMemberService.bustGatewayCacheFor).
        bustGatewayCacheAfterCommit(user.getProviderId());

        log.info("API key regenerated for userId: {}", userId);

        // Build response with plaintext (shown once)
        ApiKeyResponse response = new ApiKeyResponse();
        response.setApiKey(plaintextKey);
        response.setMaskedApiKey(hint);
        response.setCreatedAt(user.getApiKeyCreatedAt());
        response.setActive(true);
        return response;
    }

    private void bustGatewayCacheAfterCommit(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return;
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            gatewayCacheClient.invalidateUserCache(providerId);
                        }
                    });
        } else {
            gatewayCacheClient.invalidateUserCache(providerId);
        }
    }

    /**
     * Resolves a user by plaintext API key.
     * Hashes the key, looks up by hash, then delegates to UserResolutionService.
     *
     * <p>NOT_SUPPORTED (was readOnly=true): {@code UserResolutionService.resolveUser}
     * is deliberately non-transactional and performs writes in their own transactions
     * (ensureFreeSubscription INSERT, atomic last-login update, ...). Wrapped in an
     * enclosing read-only transaction, the first such write marked it rollback-only
     * and the commit blew up with UnexpectedRollbackException - a 500 on every
     * API-key resolution for a fresh or 10-min-idle user, in BOTH editions
     * (gateway /resolve-by-api-key and the CE MonolithSecurityFilter resolver).</p>
     *
     * @return UserResolutionResponse or null if key is invalid/user disabled
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UserResolutionResponse resolveByPlaintextKey(String plaintextKey) {
        if (plaintextKey == null || plaintextKey.isBlank()) {
            return null;
        }

        String hash = encryptionService.hmacHash(plaintextKey);
        Optional<User> userOpt = userRepository.findByApiKeyHash(hash);

        if (userOpt.isEmpty()) {
            log.debug("No user found for API key hash");
            return null;
        }

        User user = userOpt.get();
        if (!user.isEnabled()) {
            log.debug("User {} is disabled, rejecting API key", user.getId());
            return null;
        }

        // Delegate to existing resolution logic (reuses org lookup, credit balance, etc.)
        return userResolutionService.resolveUser(user.getProviderId(), null);
    }
}
