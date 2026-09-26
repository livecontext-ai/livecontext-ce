package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.repository.UserRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Daily cron that finds accounts past the 30-day grace period and delegates
 * hard-deletion to {@link AccountPurgeService}. Separated so the
 * {@code @Transactional} proxy on purgeService works correctly (no
 * self-invocation bypass).
 */
@Component
public class AccountPurgeScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AccountPurgeScheduler.class);

    /**
     * Single source of truth, shared with the deletion date this service reports to the user
     * through {@code getDeletionStatus}. A second constant here would let the date shown drift
     * from the day the purge actually fires, which is worse than showing no date at all.
     */
    public static final int GRACE_PERIOD_DAYS = UserService.ACCOUNT_GRACE_PERIOD_DAYS;

    private final UserRepository userRepository;
    private final UserOnboardingRepository onboardingRepository;
    private final AccountPurgeService purgeService;
    private final AccountDeactivationMailer mailer;

    /**
     * Lifecycle emails (Resend): a purged account's contact is deleted too, so no sequence
     * keeps writing to someone who no longer has an account. Optional; null sends nothing.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.auth.lifecycle.LifecycleEmailService lifecycleEmails;

    public AccountPurgeScheduler(UserRepository userRepository,
                                 UserOnboardingRepository onboardingRepository,
                                 AccountPurgeService purgeService,
                                 AccountDeactivationMailer mailer) {
        this.userRepository = userRepository;
        this.onboardingRepository = onboardingRepository;
        this.purgeService = purgeService;
        this.mailer = mailer;
    }

    @Scheduled(cron = "${account.purge.cron:0 0 3 * * *}", zone = "UTC")
    // One pod runs the pass. Without the lock every auth replica started the same purge at
    // 03:00 (both passes showed up in the prod log). The user row lock taken by purgeUser keeps
    // them from interleaving, but the second pass still redoes the work: it finds the user gone
    // when the first one committed, and retries the very same failure when it rolled back.
    @SchedulerLock(name = "account_purge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void purgeExpiredAccounts() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(GRACE_PERIOD_DAYS);
        List<User> expired = userRepository.findAccountsPastGracePeriod(cutoff);

        if (expired.isEmpty()) {
            logger.debug("Account purge: no expired accounts found");
            return;
        }

        logger.info("Account purge: found {} accounts past {}-day grace period", expired.size(), GRACE_PERIOD_DAYS);

        for (User user : expired) {
            try {
                // An irreversible delete of someone who did sign in during the grace period is
                // worth a line before it happens, not after: it is the one case where support
                // may need to explain what became of an account whose owner half-changed their
                // mind and never pressed the button.
                if (user.getLastLoginAt() != null && user.getLastLoginAt().isAfter(user.getDeactivatedAt())) {
                    logger.warn("Account purge: user {} signed in at {} after requesting deletion at {} "
                                    + "but never restored the account; purging as scheduled.",
                            user.getId(), user.getLastLoginAt(), user.getDeactivatedAt());
                }

                // Capture email + name BEFORE purge deletes the rows
                String email = user.getEmail();
                String displayName = onboardingRepository.findByUserId(user.getId())
                        .map(UserOnboarding::getDisplayName)
                        .orElse(user.getFirstName());

                boolean purged = purgeService.purgeUser(user.getId());
                if (purged) {
                    mailer.sendPurgeConfirmationEmail(email, displayName);
                    if (lifecycleEmails != null) lifecycleEmails.deleteContact(email);
                    logger.info("Account purge: successfully purged user {} ({})", user.getId(), email);
                }
            } catch (Exception e) {
                logger.error("Account purge: failed to purge user {} ({})", user.getId(), user.getEmail(), e);
            }
        }
    }
}
