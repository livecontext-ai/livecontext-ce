package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.common.classification.DataSensitivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LC-004: once a conversation holds Gmail / Drive content, every later turn re-sends it, so the
 * turn is tagged and a provider outside the allow-list is refused before dispatch.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RestrictedDataTransferGuard")
class RestrictedDataTransferGuardTest {

    @Mock private JdbcTemplate jdbc;

    private RestrictedDataTransferGuard guard;

    @BeforeEach
    void setUp() {
        guard = new RestrictedDataTransferGuard(jdbc);
    }

    private void conversationRestricted(boolean restricted) {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("conv-1"), eq("conv-1")))
                .thenReturn(restricted);
    }

    @Test
    @DisplayName("restricted conversation + provider outside the allow-list: the turn is refused")
    void refusesDisallowedProvider() {
        conversationRestricted(true);
        Map<String, Object> credentials = new HashMap<>();

        assertThatThrownBy(() -> guard.apply("conv-1", "openrouter", credentials))
                .isInstanceOf(RestrictedDataTransferGuard.RestrictedDataTransferDeniedException.class)
                .hasMessageContaining("openrouter")
                .hasMessageContaining("Gmail");
    }

    @Test
    @DisplayName("restricted conversation + CLI bridge: refused as well")
    void refusesBridge() {
        conversationRestricted(true);
        assertThatThrownBy(() -> guard.apply("conv-1", "claude-code", new HashMap<>()))
                .isInstanceOf(RestrictedDataTransferGuard.RestrictedDataTransferDeniedException.class);
    }

    @Test
    @DisplayName("restricted conversation + allowed provider: proceeds, and the execution is tagged")
    void tagsAllowedProvider() {
        conversationRestricted(true);
        Map<String, Object> credentials = new HashMap<>();

        guard.apply("conv-1", "anthropic", credentials);

        assertThat(credentials).containsEntry(DataSensitivity.CREDENTIAL_KEY, "RESTRICTED");
    }

    @Test
    @DisplayName("ordinary conversation: no tag, any provider")
    void ordinaryConversationUntouched() {
        conversationRestricted(false);
        Map<String, Object> credentials = new HashMap<>();

        guard.apply("conv-1", "openrouter", credentials);

        assertThat(credentials).doesNotContainKey(DataSensitivity.CREDENTIAL_KEY);
    }

    @Test
    @DisplayName("an execution already tagged upstream is enforced without a lookup")
    void upstreamTagEnforced() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put(DataSensitivity.CREDENTIAL_KEY, "RESTRICTED");

        assertThatThrownBy(() -> guard.apply("conv-1", "deepseek", credentials))
                .isInstanceOf(RestrictedDataTransferGuard.RestrictedDataTransferDeniedException.class);
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), eq("conv-1"), eq("conv-1"));
    }

    @Test
    @DisplayName("the lookup only counts live RESTRICTED rows, not REDACTED ones")
    void lookupSql() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("conv-2"), eq("conv-2"))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            assertThat(sql).contains("conversation.tool_results").contains("conversation.messages")
                    .contains("data_sensitivity = 'RESTRICTED'");
            return Boolean.FALSE;
        });
        assertThat(guard.conversationHoldsRestrictedData("conv-2")).isFalse();
        assertThat(guard.conversationHoldsRestrictedData(null)).isFalse();
    }
}
