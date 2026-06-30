package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the squat-recovery flow (doc §1 #41).
 *
 * <p>On {@link CeLinkSquatDetectedEvent}: mint a one-time token via
 * {@link SquatRecoveryTokenService}, then ship the email via
 * {@link SquatRecoveryMailer}. Rate-limited to {@code 5/hour} per victim (doc
 * §1 #41 dev-workflow cap) so an attacker probing the same install_id can't
 * flood the victim's inbox.
 *
 * <p>Listener is a plain {@code @EventListener} (NOT transactional) - the
 * SUSPECTED_CROSS_USER_RESET audit row commits with the surrounding revoke
 * transaction; this side-effect can run either pre- or post-commit and the
 * worst case is a stale email if the transaction rolls back. Acceptable: the
 * victim is just informed of an attempt that didn't ultimately succeed.
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class SquatRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(SquatRecoveryService.class);

    private final SquatRecoveryTokenService tokenService;
    private final SquatRecoveryMailer mailer;
    private final UserRepository userRepository;

    private final int maxPerWindow;
    private final Cache<Long, AtomicInteger> rateLimiter;

    public SquatRecoveryService(SquatRecoveryTokenService tokenService,
                                SquatRecoveryMailer mailer,
                                UserRepository userRepository,
                                @Value("${cloud-link.squat-recovery.max-per-hour:5}") int maxPerWindow) {
        this.tokenService = tokenService;
        this.mailer = mailer;
        this.userRepository = userRepository;
        this.maxPerWindow = maxPerWindow;
        // expireAfterWrite drains the counter once per victim per hour. Cap is across
        // ALL squat attempts in the window - keeps inbox quiet under attacker-pressure.
        this.rateLimiter = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(1))
                .maximumSize(100_000)
                .build();
    }

    @EventListener
    public void onSquatDetected(CeLinkSquatDetectedEvent event) {
        try {
            handle(event);
        } catch (RuntimeException unexpected) {
            // Safety-net for the listener thread.
            log.warn("SquatRecoveryService.onSquatDetected threw for victimUserId={} installId={}: {}",
                    event.victimUserId(), event.installId(), unexpected.getMessage());
        }
    }

    private void handle(CeLinkSquatDetectedEvent event) {
        if (!consumeRateLimit(event.victimUserId())) {
            log.info("SquatRecovery: rate-limit hit for victimUserId={} - email skipped", event.victimUserId());
            return;
        }

        Optional<User> victim = userRepository.findById(event.victimUserId());
        if (victim.isEmpty() || victim.get().getEmail() == null) {
            log.warn("SquatRecovery: victim userId={} has no resolvable email - skipping", event.victimUserId());
            return;
        }

        String token = tokenService.mint(event.installId(), event.victimUserId());
        mailer.sendRecoveryEmail(victim.get().getEmail(), token);
    }

    /**
     * Increment-and-check counter for {@code victimUserId}. Returns true when
     * the call is still under the per-hour cap, false otherwise. The Caffeine
     * counter is not strictly atomic across the get-then-increment, but the
     * over-count window is at most one extra send under heavy concurrency -
     * acceptable for a defense-in-depth rate limit.
     */
    boolean consumeRateLimit(Long victimUserId) {
        AtomicInteger counter = rateLimiter.get(victimUserId, k -> new AtomicInteger());
        return counter.incrementAndGet() <= maxPerWindow;
    }
}
