package com.apimarketplace.auth.audit;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides one-way pseudonymization for IPs and User-Agents in audit logs.
 *
 * Security properties:
 * - IPs are hashed with sha256(ip || daily_salt). The salt rotates every 24h
 *   so an IP cannot be correlated across days (defense-in-depth for GDPR).
 * - The salt is held in memory only - never persisted, never logged.
 * - On restart a fresh salt is generated → previous-day correlation impossible
 *   even with full disk access.
 * - User-Agents are hashed with a static per-deployment pepper (audit.pepper),
 *   keeping a stable fingerprint without exposing the raw UA string.
 */
@Component
public class AuditPseudonymizer {

    private static final Logger log = LoggerFactory.getLogger(AuditPseudonymizer.class);
    private static final int SALT_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final AtomicReference<DailySalt> currentSalt = new AtomicReference<>();
    private final byte[] uaPepper;

    public AuditPseudonymizer(@Value("${audit.ua-pepper:}") String uaPepperB64) {
        if (uaPepperB64 == null || uaPepperB64.isBlank()) {
            // Generate ephemeral pepper if none configured (dev mode warning).
            byte[] ephemeral = new byte[32];
            new SecureRandom().nextBytes(ephemeral);
            this.uaPepper = ephemeral;
            log.warn("audit.ua-pepper not configured - using ephemeral pepper. " +
                    "UA hashes will reset on restart. Set AUDIT_UA_PEPPER in production.");
        } else {
            this.uaPepper = Base64.getDecoder().decode(uaPepperB64);
        }
    }

    @PostConstruct
    void init() {
        rotateSalt();
    }

    /**
     * Rotate the IP salt daily at midnight UTC.
     * Cron: every day at 00:00.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    @SchedulerLock(name = "audit_rotate_salt", lockAtMostFor = "PT5M")
    public void rotateSalt() {
        byte[] bytes = new byte[SALT_BYTES];
        random.nextBytes(bytes);
        currentSalt.set(new DailySalt(LocalDate.now(), bytes));
        log.info("Audit IP salt rotated for {}", LocalDate.now());
    }

    public String hashIp(String ip) {
        if (ip == null || ip.isBlank()) return null;
        DailySalt salt = currentSalt.get();
        // Encode FIRST so the buffer is sized in bytes, not chars. The previous
        // version sized via ip.length() (UTF-16 code units) which silently
        // truncated multi-byte UTF-8 sequences and produced hash collisions.
        byte[] ipBytes = ip.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[salt.bytes.length + ipBytes.length];
        System.arraycopy(salt.bytes, 0, data, 0, salt.bytes.length);
        System.arraycopy(ipBytes, 0, data, salt.bytes.length, ipBytes.length);
        return sha256Hex(data);
    }

    public String hashUserAgent(String ua) {
        if (ua == null || ua.isBlank()) return null;
        byte[] uaBytes = ua.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[uaPepper.length + uaBytes.length];
        System.arraycopy(uaPepper, 0, data, 0, uaPepper.length);
        System.arraycopy(uaBytes, 0, data, uaPepper.length, uaBytes.length);
        return sha256Hex(data);
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record DailySalt(LocalDate date, byte[] bytes) {}
}
