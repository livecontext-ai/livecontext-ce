package com.apimarketplace.agent.security;

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
 * (agent.agent_webhook_tokens.auth_config), so v1 values drain and a key rotation finishes here too.
 */
@DisplayName("AgentWebhookAuthConfigEncryptionSweep (LC-024 per-service re-encryption)")
class AgentWebhookAuthConfigEncryptionSweepTest {

    @Test
    @DisplayName("startup scans agent.agent_webhook_tokens.auth_config (delay 0 runs inline)")
    void startupScansItsOwnColumn() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        CredentialEncryptionService encryption = new CredentialEncryptionService(
                "test-password-123", "0123456789abcdef", "", "", false, false, "2", "allow", "");

        new AgentWebhookAuthConfigEncryptionSweep(jdbc, new ObjectMapper(), encryption, 0).migrateOnStartup();

        verify(jdbc).queryForList(contains("FROM agent.agent_webhook_tokens"));
        verify(jdbc).queryForList(contains("auth_config"));
    }

    @Test
    @DisplayName("only the three keys AgentWebhookTokenService decrypts are ever re-encrypted")
    void fieldRestricted() {
        assertThat(AgentWebhookAuthConfigEncryptionSweep.TABLES).singleElement()
                .satisfies(t -> assertThat(t.fields()).containsExactlyInAnyOrder("basicPassword", "authHeaderValue", "jwtSecretKey"));
    }
}
