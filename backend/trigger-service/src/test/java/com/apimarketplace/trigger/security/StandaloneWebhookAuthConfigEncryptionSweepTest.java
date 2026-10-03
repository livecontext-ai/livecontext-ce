package com.apimarketplace.trigger.security;

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
 * (trigger.standalone_webhooks.auth_config), so v1 values drain and a key rotation finishes here too.
 */
@DisplayName("StandaloneWebhookAuthConfigEncryptionSweep (LC-024 per-service re-encryption)")
class StandaloneWebhookAuthConfigEncryptionSweepTest {

    @Test
    @DisplayName("startup scans trigger.standalone_webhooks.auth_config (delay 0 runs inline)")
    void startupScansItsOwnColumn() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        CredentialEncryptionService encryption = new CredentialEncryptionService(
                "test-password-123", "0123456789abcdef", "", "", false, false, "2", "allow", "");

        new StandaloneWebhookAuthConfigEncryptionSweep(jdbc, new ObjectMapper(), encryption, 0).migrateOnStartup();

        verify(jdbc).queryForList(contains("FROM trigger.standalone_webhooks"));
        verify(jdbc).queryForList(contains("auth_config"));
    }

    @Test
    @DisplayName("only the three keys StandaloneWebhookService decrypts are ever re-encrypted")
    void fieldRestricted() {
        assertThat(StandaloneWebhookAuthConfigEncryptionSweep.TABLES).singleElement()
                .satisfies(t -> assertThat(t.fields()).containsExactlyInAnyOrder("basicPassword", "authHeaderValue", "jwtSecretKey"));
    }
}
