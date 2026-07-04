package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BrowserAgentModule#maybeRouteLlmToCloudRelay} - the cloud-relay routing that
 * makes the browser agent use the cloud LLM (like chat/workflow agents + web_search) when the install
 * is cloud-linked, instead of a direct provider key.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BrowserAgentModule cloud LLM relay routing")
class BrowserAgentModuleCloudRelayTest {

    @Mock private RestTemplate restTemplate;
    @Mock private WebSearchConfig config;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private CloudLlmRuntimeAccess runtimeAccess;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private BrowserAgentModule module;

    @BeforeEach
    void setUp() {
        lenient().when(config.getServiceUrl()).thenReturn("http://websearch-host:8085");
        module = new BrowserAgentModule(restTemplate, config, redisTemplate, objectMapper);
    }

    private static CloudLlmRuntimeCredentials creds() {
        return new CloudLlmRuntimeCredentials("tok-123", "install-abc", "https://livecontext.ai/api");
    }

    private static Map<String, Object> llm() {
        Map<String, Object> llm = new HashMap<>();
        llm.put("provider", "google");
        llm.put("model", "gemini-3.1-flash-lite");
        return llm;
    }

    @Test
    @DisplayName("cloud-linked: routes the llm block to the cloud relay shim, model stays clean")
    void routesToCloudRelayWhenLinked() {
        module.setCloudRuntimeAccess(runtimeAccess);
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.of(creds()));

        Map<String, Object> llm = llm();
        module.maybeRouteLlmToCloudRelay(llm);

        assertThat(llm.get("provider_kind")).isEqualTo("bridge");
        assertThat(llm.get("bridge_url")).isEqualTo("http://livecontext:8080/api/browser-agent/llm");
        // Model stays CLEAN (pricing/observability read it); provider travels via the
        // X-LLM-Provider header the runner's BridgeChatClient sends to the shim.
        assertThat(llm.get("model")).isEqualTo("gemini-3.1-flash-lite");
        assertThat(llm.get("provider")).isEqualTo("google");
    }

    @Test
    @DisplayName("not linked: leaves the llm block untouched so the direct-key path runs")
    void noOpWhenNotLinked() {
        module.setCloudRuntimeAccess(runtimeAccess);
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.empty());

        Map<String, Object> llm = llm();
        module.maybeRouteLlmToCloudRelay(llm);

        assertThat(llm).doesNotContainKey("provider_kind");
        assertThat(llm.get("model")).isEqualTo("gemini-3.1-flash-lite");
    }

    @Test
    @DisplayName("no runtime-access bean (non-CE / tests): no-op")
    void noOpWhenNoBean() {
        Map<String, Object> llm = llm();
        module.maybeRouteLlmToCloudRelay(llm);
        assertThat(llm).doesNotContainKey("provider_kind");
    }

    @Test
    @DisplayName("explicit direct api_key wins - cloud runtime is never consulted")
    void directKeyWins() {
        module.setCloudRuntimeAccess(runtimeAccess);
        Map<String, Object> llm = llm();
        llm.put("api_key", "sk-direct");

        module.maybeRouteLlmToCloudRelay(llm);

        assertThat(llm).doesNotContainKey("provider_kind");
        verifyNoInteractions(runtimeAccess);
    }

    @Test
    @DisplayName("explicit bridge route is respected, not overwritten")
    void explicitBridgeRespected() {
        module.setCloudRuntimeAccess(runtimeAccess);
        Map<String, Object> llm = new HashMap<>();
        llm.put("provider_kind", "bridge");
        llm.put("bridge_url", "http://custom-bridge:9000");
        llm.put("model", "m");

        module.maybeRouteLlmToCloudRelay(llm);

        assertThat(llm.get("bridge_url")).isEqualTo("http://custom-bridge:9000");
        verifyNoInteractions(runtimeAccess);
    }

    @Test
    @DisplayName("missing provider or model: no-op (default substitution / direct path handles it)")
    void noOpWhenIncomplete() {
        module.setCloudRuntimeAccess(runtimeAccess);
        Map<String, Object> llm = new HashMap<>();
        llm.put("model", "gemini-3.1-flash-lite"); // no provider
        module.maybeRouteLlmToCloudRelay(llm);
        assertThat(llm).doesNotContainKey("provider_kind");
        verifyNoInteractions(runtimeAccess);
    }
}
