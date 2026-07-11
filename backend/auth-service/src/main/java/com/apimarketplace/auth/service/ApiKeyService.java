package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.ApiKey;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.ApiKeyEntryResponse;
import com.apimarketplace.auth.dto.ApiKeyResponse;
import com.apimarketplace.auth.dto.CreateApiKeyResponse;
import com.apimarketplace.auth.dto.UserResolutionResponse;
import com.apimarketplace.auth.repository.ApiKeyRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    private static final int MAX_NAME_LENGTH = 100;
    /** Hard cap of active (non-revoked) named keys per user. */
    static final int MAX_ACTIVE_KEYS = 20;
    /** last_used_at is refreshed at most this often (best-effort observability). */
    private static final Duration LAST_USED_REFRESH_INTERVAL = Duration.ofMinutes(15);

    private final UserRepository userRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final CredentialEncryptionService encryptionService;
    private final UserResolutionService userResolutionService;
    private final GatewayCacheClient gatewayCacheClient;
    private final SecureRandom secureRandom;

    public ApiKeyService(UserRepository userRepository,
                         ApiKeyRepository apiKeyRepository,
                         CredentialEncryptionService encryptionService,
                         UserResolutionService userResolutionService,
                         GatewayCacheClient gatewayCacheClient) {
        this.userRepository = userRepository;
        this.apiKeyRepository = apiKeyRepository;
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

    /**
     * Lists the user's active (non-revoked) named keys, newest first.
     * Never returns plaintext (only hints).
     */
    @Transactional(readOnly = true)
    public List<ApiKeyEntryResponse> listKeys(Long userId) {
        return apiKeyRepository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId).stream()
                .map(this::toEntryResponse)
                .toList();
    }

    /**
     * Creates a new named API key for the user. Plaintext is returned once.
     *
     * @param name   required, trimmed, max {@value #MAX_NAME_LENGTH} chars
     * @param scopes MCP tool names the key may call; {@code null} = full access.
     *               Normalized (trim, lowercase, dedupe, blanks dropped); a list
     *               that normalizes to empty is rejected - a key must grant at
     *               least one tool, or full access via {@code null}.
     * @throws ApiKeyValidationException on invalid name, empty scope list, or key cap
     */
    public CreateApiKeyResponse createKey(Long userId, String name, List<String> scopes) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        String trimmedName = name != null ? name.trim() : "";
        if (trimmedName.isEmpty()) {
            throw new ApiKeyValidationException("Key name is required");
        }
        if (trimmedName.length() > MAX_NAME_LENGTH) {
            throw new ApiKeyValidationException("Key name must be at most " + MAX_NAME_LENGTH + " characters");
        }

        List<String> normalizedScopes = normalizeScopes(scopes);

        if (apiKeyRepository.countByUserIdAndRevokedAtIsNull(userId) >= MAX_ACTIVE_KEYS) {
            throw new ApiKeyValidationException("Maximum of " + MAX_ACTIVE_KEYS + " active API keys reached");
        }

        // Same generation as regenerateKey: lc_live_ + 64 hex chars, HMAC-hashed at rest.
        byte[] randomBytes = new byte[KEY_RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);
        String plaintextKey = KEY_PREFIX + HexFormat.of().formatHex(randomBytes);
        String hash = encryptionService.hmacHash(plaintextKey);
        String hint = KEY_PREFIX + "..." + plaintextKey.substring(plaintextKey.length() - HINT_SUFFIX_LENGTH);

        ApiKey key = new ApiKey(userId, trimmedName, hash, hint,
                normalizedScopes != null ? String.join(",", normalizedScopes) : null);
        key = apiKeyRepository.save(key);

        // Same after-commit cache bust as regenerateKey: keeps the gateway's per-provider
        // resolution state coherent with any key-set change.
        bustGatewayCacheAfterCommit(user.getProviderId());

        log.info("Named API key created for userId: {} (keyId: {}, scoped: {})",
                userId, key.getId(), normalizedScopes != null);

        CreateApiKeyResponse response = new CreateApiKeyResponse();
        fillEntry(response, key);
        response.setApiKey(plaintextKey);
        return response;
    }

    /**
     * Soft-revokes a named key (sets revoked_at) and busts the gateway cache after
     * commit so the plaintext stops authenticating immediately, not after the TTL.
     *
     * @throws IllegalArgumentException when the key does not exist or belongs to another user
     */
    public void revokeKey(Long userId, UUID keyId) {
        ApiKey key = apiKeyRepository.findById(keyId)
                .filter(k -> k.getUserId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("API key not found: " + keyId));

        if (key.getRevokedAt() == null) {
            key.setRevokedAt(LocalDateTime.now());
            apiKeyRepository.save(key);

            userRepository.findById(userId)
                    .map(User::getProviderId)
                    .ifPresent(this::bustGatewayCacheAfterCommit);

            log.info("Named API key revoked for userId: {} (keyId: {})", userId, keyId);
        }
    }

    /**
     * Normalizes a scope list: trim, lowercase, dedupe (order-preserving), drop blanks.
     *
     * @return the normalized list, or {@code null} for a {@code null} input (full access)
     * @throws ApiKeyValidationException when a NON-null list normalizes to empty - an
     *         empty scope list is neither "no access" nor "full access", so it is rejected
     */
    private static List<String> normalizeScopes(List<String> scopes) {
        if (scopes == null) {
            return null;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope == null) {
                continue;
            }
            String cleaned = scope.trim().toLowerCase();
            if (!cleaned.isEmpty()) {
                normalized.add(cleaned);
            }
        }
        if (normalized.isEmpty()) {
            throw new ApiKeyValidationException(
                    "Scope list must contain at least one tool name (omit scopes for full access)");
        }
        return new ArrayList<>(normalized);
    }

    private ApiKeyEntryResponse toEntryResponse(ApiKey key) {
        ApiKeyEntryResponse response = new ApiKeyEntryResponse();
        fillEntry(response, key);
        return response;
    }

    private void fillEntry(ApiKeyEntryResponse response, ApiKey key) {
        response.setId(key.getId());
        response.setName(key.getName());
        response.setMaskedApiKey(key.getKeyHint());
        response.setScopes(parseScopes(key.getScopes()));
        response.setCreatedAt(key.getCreatedAt());
        response.setLastUsedAt(key.getLastUsedAt());
    }

    /** Comma-separated column value -> list; null/blank column = full access = null. */
    private static List<String> parseScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return null;
        }
        List<String> parsed = new ArrayList<>();
        for (String scope : scopes.split(",")) {
            String cleaned = scope.trim();
            if (!cleaned.isEmpty()) {
                parsed.add(cleaned);
            }
        }
        return parsed.isEmpty() ? null : parsed;
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

        // 1. Legacy single key on auth.users: full access, scopes stay null.
        Optional<User> userOpt = userRepository.findByApiKeyHash(hash);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            if (!user.isEnabled()) {
                log.debug("User {} is disabled, rejecting API key", user.getId());
                return null;
            }
            // Delegate to existing resolution logic (reuses org lookup, credit balance, etc.)
            return userResolutionService.resolveUser(user.getProviderId(), null);
        }

        // 2. Named multi keys (auth.api_keys, V398): non-revoked rows only.
        Optional<ApiKey> keyOpt = apiKeyRepository.findByKeyHashAndRevokedAtIsNull(hash);
        if (keyOpt.isEmpty()) {
            log.debug("No user found for API key hash");
            return null;
        }

        ApiKey key = keyOpt.get();
        Optional<User> ownerOpt = userRepository.findById(key.getUserId());
        if (ownerOpt.isEmpty()) {
            log.debug("No owner found for API key {}", key.getId());
            return null;
        }
        User owner = ownerOpt.get();
        if (!owner.isEnabled()) {
            log.debug("User {} is disabled, rejecting API key", owner.getId());
            return null;
        }

        UserResolutionResponse response = userResolutionService.resolveUser(owner.getProviderId(), null);
        if (response == null) {
            return null;
        }
        // Scoped key -> attach the tool allow-list; full-access key keeps scopes null.
        response.setApiKeyScopes(parseScopes(key.getScopes()));

        touchLastUsedAtBestEffort(key);
        return response;
    }

    /**
     * Refreshes last_used_at at most once per {@link #LAST_USED_REFRESH_INTERVAL},
     * in its own transaction (repository method is REQUIRES_NEW). Failures are
     * swallowed: the resolve path must never fail because of this write.
     */
    private void touchLastUsedAtBestEffort(ApiKey key) {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime lastUsed = key.getLastUsedAt();
            if (lastUsed == null || Duration.between(lastUsed, now).compareTo(LAST_USED_REFRESH_INTERVAL) >= 0) {
                apiKeyRepository.touchLastUsedAt(key.getId(), now);
            }
        } catch (Exception e) {
            log.debug("Best-effort last_used_at update failed for API key {}: {}", key.getId(), e.getMessage());
        }
    }
}
