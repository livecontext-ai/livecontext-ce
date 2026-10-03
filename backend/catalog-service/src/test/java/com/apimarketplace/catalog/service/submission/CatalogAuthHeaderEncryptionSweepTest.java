package com.apimarketplace.catalog.service.submission;

import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CASA LC-024: this service registers the re-encryption sweep for its own encrypted column
 * (catalog.apis.auth_header_value), so v1 values drain and a key rotation finishes here too.
 */
@DisplayName("CatalogAuthHeaderEncryptionSweep (LC-024 per-service re-encryption)")
class CatalogAuthHeaderEncryptionSweepTest {

    @Test
    @DisplayName("startup scans catalog.apis.auth_header_value (delay 0 runs inline)")
    void startupScansItsOwnColumn() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        CredentialEncryptionService encryption = new CredentialEncryptionService(
                "test-password-123", "0123456789abcdef", "", "", false, false, "2", "allow", "");

        new CatalogAuthHeaderEncryptionSweep(jdbc, new ObjectMapper(), encryption, 0).migrateOnStartup();

        verify(jdbc).queryForList(contains("FROM catalog.apis"));
        verify(jdbc).queryForList(contains("auth_header_value"));
    }

    @Test
    @DisplayName("re-seal only: a seeded plaintext header value (exported verbatim in catalog bundles) is never encrypted")
    void resealOnly() {
        assertThat(CatalogAuthHeaderEncryptionSweep.COLUMNS).singleElement()
                .satisfies(c -> assertThat(c.resealOnly()).isTrue());
    }
}
