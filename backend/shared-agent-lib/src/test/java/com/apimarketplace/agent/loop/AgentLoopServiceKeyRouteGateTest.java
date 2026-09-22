package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.KeyRoute;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.AbstractLLMProvider;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.resolver.LlmCredentialResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The loop's pre-flight "is the provider configured" gate must answer from the context's
 * tenant and pinned route, never from the calling thread: a dequeued execution has no
 * servlet request, and the pre-fix gate refused it with "not configured" for a tenant whose
 * own key would have served the call, while the same agent proceeded over sync HTTP.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentLoopService - the configured gate is keyed on tenant + key route")
class AgentLoopServiceKeyRouteGateTest {

    @Mock private LLMProviderFactory providerFactory;
    @Mock private LLMProvider foreignProvider;

    /** A direct-API provider with no env key: only what the resolver returns can serve a call. */
    static class DirectProvider extends AbstractLLMProvider {
        @Override protected String getApiKey() { return null; }
        @Override protected String getApiUrl() { return "http://localhost/x"; }
        @Override protected Map<String, Object> buildRequestBody(CompletionRequest request) { return Map.of(); }
        @Override protected CompletionResponse parseResponse(Map<String, Object> response) { return CompletionResponse.text("x"); }
        @Override protected HttpHeaders buildHeaders(CompletionRequest request) { return new HttpHeaders(); }
        @Override protected String processStreamingLine(String line) { return null; }
        @Override public String getProviderName() { return "direct"; }
        @Override public String getDefaultModel() { return "m"; }
        @Override public List<String> getSupportedModels() { return List.of("m"); }
    }

    private static LlmCredentialResolver resolverWith(String userKey, String platformKey) {
        return new LlmCredentialResolver() {
            @Override public Optional<String> resolveApiKey(String providerName) {
                throw new AssertionError("the calling thread must never be consulted by the loop gate");
            }
            @Override public Optional<String> resolveApiKey(String userId, String providerName) {
                return Optional.ofNullable(userId == null ? platformKey : userKey != null ? userKey : platformKey);
            }
            @Override public Optional<String> resolveUserApiKey(String userId, String providerName) {
                return Optional.ofNullable(userKey);
            }
        };
    }

    private static AgentLoopContext context(String provider, KeyRoute route) {
        return AgentLoopContext.builder()
            .provider(provider)
            .model("m")
            .userPrompt("hi")
            .tenantId("tenant-9")
            .keyRoute(route)
            .maxIterations(1)
            .build();
    }

    @Test
    @DisplayName("an OWN_KEY pin with no saved user key is refused before any model call, with the user-facing reason")
    void ownKeyPinWithoutUserKeyIsRefusedWithReason() {
        DirectProvider provider = new DirectProvider();
        provider.setCredentialResolver(resolverWith(null, "sk-platform"));
        when(providerFactory.getProvider("direct")).thenReturn(provider);
        AgentLoopService service = new AgentLoopService(providerFactory, null, null, null);

        AgentLoopResult result = service.execute(context("direct", KeyRoute.OWN_KEY));

        assertThat(result.success()).isFalse();
        // The platform key exists and would have served the call: the pin forbids it.
        assertThat(result.error()).contains("is not configured").contains("own direct key").contains("Save a key");
    }

    @Test
    @DisplayName("regression: a tenant whose OWN key is the only key passes the gate off any thread (no servlet request bound)")
    void tenantOwnKeyPassesTheGateWithoutAServletRequest() {
        DirectProvider provider = new DirectProvider();
        provider.setCredentialResolver(resolverWith("sk-user-9", null));
        when(providerFactory.getProvider("direct")).thenReturn(provider);
        AgentLoopService service = new AgentLoopService(providerFactory, null, null, null);

        AgentLoopResult result = service.execute(context("direct", KeyRoute.OWN_KEY));

        // The pre-fix gate called the request-less isConfigured() here, which on a worker
        // thread found no user and no platform key and answered "not configured".
        assertThat(String.valueOf(result.error())).doesNotContain("not configured");
    }

    @Test
    @DisplayName("a provider outside the direct-API base class keeps its own request-less check")
    void foreignProviderKeepsItsOwnCheck() {
        when(providerFactory.getProvider("foreign")).thenReturn(foreignProvider);
        when(foreignProvider.isConfigured()).thenReturn(false);
        AgentLoopService service = new AgentLoopService(providerFactory, null, null, null);

        AgentLoopResult result = service.execute(context("foreign", KeyRoute.OWN_KEY));

        assertThat(result.success()).isFalse();
        // Verbatim historical text for the generic case: no appended reason, no stutter.
        assertThat(result.error()).isEqualTo("Provider foreign is not configured");
    }

    @Test
    @DisplayName("notConfiguredMessage: generic reason keeps the historical text, a specific reason is appended once")
    void notConfiguredMessageShape() {
        assertThat(AgentLoopService.notConfiguredMessage("openai", AbstractLLMProvider.NOT_CONFIGURED_MESSAGE))
            .isEqualTo("Provider openai is not configured");
        assertThat(AgentLoopService.notConfiguredMessage("openai", null))
            .isEqualTo("Provider openai is not configured");
        assertThat(AgentLoopService.notConfiguredMessage("openai", "pinned to your own openai key, but no usable openai API key is saved"))
            .isEqualTo("Provider openai is not configured: pinned to your own openai key, but no usable openai API key is saved");
    }

    @Test
    @DisplayName("configurationProblemFor is the single seam: direct providers answer per route, others per isConfigured()")
    void configurationProblemForSeam() {
        DirectProvider provider = new DirectProvider();
        provider.setCredentialResolver(resolverWith(null, "sk-platform"));

        assertThat(AgentLoopService.configurationProblemFor(provider, context("direct", KeyRoute.PLATFORM))).isNull();
        assertThat(AgentLoopService.configurationProblemFor(provider, context("direct", KeyRoute.OWN_KEY))).contains("own direct key");
        when(foreignProvider.isConfigured()).thenReturn(true);
        assertThat(AgentLoopService.configurationProblemFor(foreignProvider, context("foreign", null))).isNull();
    }
}
