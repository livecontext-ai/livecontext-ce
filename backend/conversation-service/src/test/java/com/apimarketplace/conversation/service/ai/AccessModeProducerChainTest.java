package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.AgentConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The chat leg of the access-mode chain, which is the leg that cannot be derived from
 * {@link ToolAccessControl#ACCESS_MODE_KEYS}: a record's components are typed, so a
 * category added to the registry does not appear here on its own. Nothing fails when one
 * is missing either. The value is simply not parsed, the relay has nothing to forward,
 * and the tool at the far end sees a caller with no stated permissions, which
 * {@code checkWriteAccess} reads as ALLOWED.
 *
 * <p>That is not hypothetical: {@code mailboxAccessMode} was wired through agent-service
 * and absent from this record, so a mailbox agent configured read-only could send mail
 * from the user's address in every chat. Its own provider tests were green throughout,
 * because each of them built the credential map by hand.
 */
@DisplayName("Access-mode producer chain (chat path)")
@ExtendWith(MockitoExtension.class)
class AccessModeProducerChainTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private AgentConfigProvider agentConfigProvider;

    /**
     * The invariant, stated against the registry rather than a hand-written list, so a
     * tenth category cannot be added without this failing.
     */
    @Test
    @DisplayName("the ToolsConfig record carries a component for EVERY enforced access mode")
    void recordCoversEveryEnforcedCategory() {
        List<String> components = Arrays.stream(AgentConfigProvider.ToolsConfig.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        for (String key : ToolAccessControl.ACCESS_MODE_KEYS) {
            assertThat(components)
                    .as("ToolsConfig has no '%s' component, so the chat path cannot parse it and "
                            + "the restriction silently does not exist", key)
                    .contains(key);
        }
    }

    @Test
    @DisplayName("every enforced access mode survives the parse, read back off the record")
    void everyAccessModeIsParsed() throws Exception {
        StringBuilder modes = new StringBuilder();
        for (String key : ToolAccessControl.ACCESS_MODE_KEYS) {
            modes.append(",\"").append(key).append("\":\"read\"");
        }
        String json = "{\"name\":\"A\",\"toolsConfig\":{\"mode\":\"all\"" + modes + "}}";

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(json, HttpStatus.OK));

        AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", "tenant-1");
        assertThat(config).isNotNull();
        AgentConfigProvider.ToolsConfig tc = config.toolsConfig();
        assertThat(tc).isNotNull();

        for (String key : ToolAccessControl.ACCESS_MODE_KEYS) {
            Object value = AgentConfigProvider.ToolsConfig.class.getMethod(key).invoke(tc);
            assertThat(value)
                    .as("'%s' was sent as 'read' and came back as %s", key, value)
                    .isEqualTo("read");
        }
    }

    /**
     * The opt-in half of the same chain. {@code toMap} is what the resolver reads, so a
     * grant it does not emit is a switch the owner turned on and the agent never receives.
     */
    @Test
    @DisplayName("toMap emits both opt-in grants raw, so the resolver decides and nobody else")
    void toMapEmitsTheOptInGrants() throws Exception {
        String json = "{\"name\":\"A\",\"toolsConfig\":{\"mode\":\"all\","
                + "\"generation\":true,\"mailbox\":{\"enabled\":true}}}";

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(json, HttpStatus.OK));

        Map<String, Object> map = agentConfigProvider.getAgentConfig("agent-1", "tenant-1")
                .toolsConfig().toMap();

        assertThat(map).containsEntry("generation", true);
        assertThat(map.get("mailbox"))
                .as("the richer {enabled:...} shape must survive UNPARSED: "
                        + "AgentModuleResolver.isMailboxEnabled owns the single definition of granted")
                .isInstanceOf(Map.class);
        assertThat(com.apimarketplace.agent.config.AgentModuleResolver.resolveEnabledModules(map))
                .contains("mailbox", "generation");
    }

    /**
     * The direction that matters for an opt-in: absent must stay absent all the way to the
     * resolver. An emitted {@code mailbox: false} would be harmless; an emitted
     * {@code mailbox: true} from a config that never said so would hand an agent someone's
     * inbox.
     */
    @Test
    @DisplayName("a config that never mentions the mailbox emits no key, so the agent gets none")
    void absentGrantStaysAbsent() {
        String json = "{\"name\":\"A\",\"toolsConfig\":{\"mode\":\"all\"}}";

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(json, HttpStatus.OK));

        Map<String, Object> map = agentConfigProvider.getAgentConfig("agent-1", "tenant-1")
                .toolsConfig().toMap();

        assertThat(map).doesNotContainKey("mailbox");
        assertThat(com.apimarketplace.agent.config.AgentModuleResolver.resolveEnabledModules(map))
                .doesNotContain("mailbox");
    }
}
