package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.RefreshToken;
import com.apimarketplace.auth.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Answers, for one CE access token, whether the login session it was minted for may still
 * authenticate (CASA LC-015).
 *
 * <p>A CE access token lives 14 days (a deliberate trade-off, see application-ce.yml: a short
 * TTL brings back the multi-tab refresh-rotation race that logs users out). Before this, nothing
 * could withdraw one: logout, password change and refresh-token reuse detection revoked refresh
 * tokens only, so a lifted access token kept working until it expired. Access tokens now carry the
 * id of the refresh-token row that backs the login ({@code sid}), and every revocation path that
 * already existed revokes those rows, so it now withdraws the matching access tokens too.
 *
 * <p>A revoked row is also what NORMAL rotation leaves behind ({@code refresh()} revokes the
 * presented row and creates its successor). Requests in flight with the previous access token are
 * therefore tolerated for a short grace, and only while the user still holds a live session:
 * logout-everywhere, password change and reuse detection revoke every row, so they are refused
 * at once. Residual: a single-device sign-out by a user signed in elsewhere leaves that device's
 * access token usable for the grace window (30s default, 0 disables it).
 *
 * <p>Positive answers are cached for {@link #CACHE_TTL_MS} so a busy CE page does not turn every
 * request into a query; a refusal is never cached.
 *
 * <p><b>Behaviour changes to know about (documented for operators):</b>
 * <ul>
 *   <li><b>Fail closed.</b> If the lookup throws (database down, pool exhausted), the exception
 *       reaches {@code MonolithSecurityFilter.validateAndExtractClaims}, which treats any
 *       exception as an invalid token: the request is answered 401. A CE install with its
 *       database down therefore signs every browser out instead of serving requests on
 *       unverifiable tokens. Sessions already in the 5s positive cache keep working until
 *       their entry expires.</li>
 *   <li><b>Too many sessions ends them all.</b> {@code PasswordAuthService.generateTokenPair}
 *       revokes EVERY refresh-token row of a user who reaches {@code MAX_ACTIVE_TOKENS_PER_USER}
 *       live sessions. Before, that only forced other devices to log in again at their next
 *       refresh; now their access tokens stop working too (within the 5s cache), because their
 *       session rows are revoked.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "embedded")
public class CeSessionRevocationService {

    private static final Logger logger = LoggerFactory.getLogger(CeSessionRevocationService.class);

    static final long CACHE_TTL_MS = 5_000L;
    private static final int MAX_CACHE_ENTRIES = 10_000;

    private final RefreshTokenRepository refreshTokenRepository;
    private final long rotationGraceMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<Long, Long> activeUntil = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public CeSessionRevocationService(
            RefreshTokenRepository refreshTokenRepository,
            @Value("${auth.jwt.session-rotation-grace-seconds:30}") long rotationGraceSeconds) {
        this(refreshTokenRepository, rotationGraceSeconds, System::currentTimeMillis);
    }

    CeSessionRevocationService(RefreshTokenRepository refreshTokenRepository, long rotationGraceSeconds,
                               LongSupplier clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.rotationGraceMs = Math.max(0L, rotationGraceSeconds) * 1000L;
        this.clock = clock;
    }

    /**
     * @param sessionId the token's {@code sid} claim
     * @return true when the session may still authenticate
     */
    @Transactional(readOnly = true)
    public boolean isSessionActive(String sessionId) {
        Long id = parseId(sessionId);
        if (id == null) {
            return false;
        }
        long nowMs = clock.getAsLong();
        Long cachedUntil = activeUntil.get(id);
        if (cachedUntil != null && cachedUntil > nowMs) {
            return true;
        }

        boolean active = lookup(id);
        if (active) {
            if (activeUntil.size() >= MAX_CACHE_ENTRIES) {
                activeUntil.clear();
            }
            activeUntil.put(id, nowMs + CACHE_TTL_MS);
        } else {
            activeUntil.remove(id);
        }
        return active;
    }

    private boolean lookup(Long id) {
        RefreshToken session = refreshTokenRepository.findById(id).orElse(null);
        if (session == null) {
            // Never existed, or the daily cleanup deleted it (it only deletes rows expired or
            // revoked more than seven days ago): either way the session is over.
            return false;
        }
        if (session.isExpired()) {
            return false;
        }
        if (!session.isRevoked()) {
            return true;
        }
        LocalDateTime revokedAt = session.getRevokedAt();
        if (revokedAt == null || rotationGraceMs == 0) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (!revokedAt.plusNanos(rotationGraceMs * 1_000_000L).isAfter(now)) {
            return false;
        }
        boolean rotated = refreshTokenRepository.countActiveByUserId(session.getUser().getId(), now) > 0;
        if (!rotated) {
            logger.debug("Session {} was revoked and the user holds no live session: refusing", id);
        }
        return rotated;
    }

    private static Long parseId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(sessionId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
