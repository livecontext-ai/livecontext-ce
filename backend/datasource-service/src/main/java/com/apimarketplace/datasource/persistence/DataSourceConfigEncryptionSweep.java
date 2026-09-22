package com.apimarketplace.datasource.persistence;

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
 * Once per start, encrypts in place every {@code datasource.data_sources.source_config} value
 * that the widened {@code SensitiveFieldDetector} recognises as a secret but that an older
 * version wrote in clear. Rows already fully encrypted are not touched.
 */
@Component
public class DataSourceConfigEncryptionSweep {

    public static final TableSpec DATA_SOURCES = new TableSpec("datasource.data_sources", "id", "source_config");

    static final List<TableSpec> TABLES = List.of(DATA_SOURCES);

    private final SensitiveJsonbBackfill sweep;
    private final Duration startupDelay;

    /** Delayed like the token backfill: a replica on the previous image decrypts only the legacy names. */
    public DataSourceConfigEncryptionSweep(JdbcTemplate jdbc, ObjectMapper objectMapper, CredentialEncryptionService encryptionService,
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
