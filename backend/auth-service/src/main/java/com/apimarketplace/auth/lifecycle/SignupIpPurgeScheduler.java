package com.apimarketplace.auth.lifecycle;

import com.apimarketplace.auth.repository.UserRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Enforces the retention of {@code auth.users.signup_ip}: an address captured for abuse
 * prevention is nulled 12 months after its capture. The capture instant stays, so the
 * account is never re-captured (see {@code UserRepository#captureSignupIp}).
 *
 * <p>Runs in both editions: it is a privacy obligation, not a Resend feature. ShedLock keeps
 * it to one replica per run.
 */
@Component
public class SignupIpPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(SignupIpPurgeScheduler.class);

    static final int RETENTION_MONTHS = 12;

    private final UserRepository userRepository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public SignupIpPurgeScheduler(UserRepository userRepository) {
        this(userRepository, Clock.systemUTC());
    }

    SignupIpPurgeScheduler(UserRepository userRepository, Clock clock) {
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Scheduled(cron = "${lifecycle.signup-ip.purge-cron:0 30 3 * * *}", zone = "UTC")
    @SchedulerLock(name = "signup_ip_purge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    @Transactional
    public void purgeExpiredSignupIps() {
        purge();
    }

    int purge() {
        Instant cutoff = cutoff();
        int purged = userRepository.purgeSignupIpsCapturedBefore(cutoff);
        if (purged > 0) {
            log.info("Signup IP retention: nulled {} address(es) captured before {}", purged, cutoff);
        }
        return purged;
    }

    Instant cutoff() {
        return clock.instant().atOffset(ZoneOffset.UTC).minusMonths(RETENTION_MONTHS).toInstant();
    }
}
