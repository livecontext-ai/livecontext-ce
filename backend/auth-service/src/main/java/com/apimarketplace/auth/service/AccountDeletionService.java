package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * "Delete my account". A real account is deactivated and purged by the nightly pass once its
 * grace period ends; a {@link TestAccountPolicy test account} is purged right away by that same
 * purge, so its identity and data are gone before the request returns.
 */
@Service
public class AccountDeletionService {

    private static final Logger logger = LoggerFactory.getLogger(AccountDeletionService.class);

    public enum Outcome { SCHEDULED, PURGED }

    private final UserService userService;
    private final AccountPurgeScheduler purgeScheduler;
    private final TestAccountPolicy testAccountPolicy;

    public AccountDeletionService(UserService userService,
                                  AccountPurgeScheduler purgeScheduler,
                                  TestAccountPolicy testAccountPolicy) {
        this.userService = userService;
        this.purgeScheduler = purgeScheduler;
        this.testAccountPolicy = testAccountPolicy;
    }

    /**
     * @throws IllegalStateException when a test account's purge failed or was declined. The
     *   account is then deactivated but still whole, and the caller must hear about it: a test
     *   account exists to prove deletion works, so a silent failure would defeat it.
     */
    public Outcome requestDeletion(User user) {
        User deactivated = userService.deactivateUser(user);
        if (!testAccountPolicy.isTestAccount(deactivated.getEmail())) {
            return Outcome.SCHEDULED;
        }
        logger.info("Account deletion: user {} is a test account, purging now instead of after the grace period",
                deactivated.getId());
        try {
            if (!purgeScheduler.purgeAccount(deactivated)) {
                throw new IllegalStateException("Immediate purge of test account " + deactivated.getId()
                        + " was declined (account not deactivated)");
            }
        } catch (RuntimeException e) {
            // The caller only answers 500: this line is the one place the cause is recorded.
            logger.error("Account deletion: immediate purge of test account {} failed", deactivated.getId(), e);
            throw e instanceof IllegalStateException ise ? ise : new IllegalStateException(e.getMessage(), e);
        }
        return Outcome.PURGED;
    }
}
