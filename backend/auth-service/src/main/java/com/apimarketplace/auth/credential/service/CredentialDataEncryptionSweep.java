package com.apimarketplace.auth.credential.service;

import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;

import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.security.SensitiveJsonbBackfill;
import com.apimarketplace.common.security.SensitiveJsonbBackfill.TableSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Once per start, encrypts in place every {@code auth.credentials.credential_data} value that
 * the widened {@code SensitiveFieldDetector} now recognises as a secret but that was written in
 * clear under the previous 15-name allow-list (an AWS {@code secret_access_key}, a GCP
 * {@code private_key}, ...). Rows already fully encrypted are not touched and no timestamp moves.
 */
@Component
public class CredentialDataEncryptionSweep {

    public static final TableSpec CREDENTIALS = new TableSpec("auth.credentials", "id", "credential_data");

    static final List<TableSpec> TABLES = List.of(CREDENTIALS);

    private final SensitiveJsonbBackfill sweep;
    private final Duration startupDelay;

    /** Delayed like the token backfill: a replica on the previous image decrypts only the legacy names. */
    public CredentialDataEncryptionSweep(JdbcTemplate jdbc, ObjectMapper objectMapper, CredentialEncryptionService encryptionService,
            @Value("${token-at-rest.backfill.delay-seconds:600}") long startupDelaySeconds) {
        this.sweep = new SensitiveJsonbBackfill(jdbc, objectMapper, encryptionService);
        this.startupDelay = Duration.ofSeconds(startupDelaySeconds);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateOnStartup() {
        sweep.scheduleMigrateAll(TABLES, startupDelay);
    }

    /** Drop a sweep that has not started when the context closes. */
    @jakarta.annotation.PreDestroy
    public void cancelPendingSweep() {
        sweep.cancelPending();
    }
}
