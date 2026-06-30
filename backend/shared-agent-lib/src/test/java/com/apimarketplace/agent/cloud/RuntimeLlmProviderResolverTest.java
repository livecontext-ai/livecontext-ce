package com.apimarketplace.agent.cloud;

import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.provider.LLMProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RuntimeLlmProviderResolver")
class RuntimeLlmProviderResolverTest {

    @Mock private LLMProviderFactory providerFactory;
    @Mock private CloudLlmRelayClient relayClient;
    @Mock private CloudLlmRuntimeAccess runtimeAccess;
    @Mock private LLMProvider apiProvider;
    @Mock private LLMProvider bridgeProvider;

    private RuntimeLlmProviderResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RuntimeLlmProviderResolver(providerFactory, relayClient, runtimeAccess);
    }

    @Test
    @DisplayName("wraps API providers with the Cloud relay when a CE tenant selects Cloud")
    void wrapsApiProvidersWhenCloudSelected() {
        CloudLlmRuntimeCredentials credentials = new CloudLlmRuntimeCredentials(
                "access-token", "install-1", "https://cloud.livecontext.test/api");
        when(providerFactory.getProvider("deepseek")).thenReturn(apiProvider);
        when(runtimeAccess.isCloudSelected("tenant-1")).thenReturn(true);
        when(runtimeAccess.resolveCloudRuntime("tenant-1")).thenReturn(Optional.of(credentials));

        LLMProvider resolved = resolver.resolve("deepseek", tenantContext());

        assertThat(resolved).isInstanceOf(CloudRelayProvider.class);
        assertThat(resolved).isNotSameAs(apiProvider);
    }

    @Test
    @DisplayName("keeps API providers local when the CE tenant selects API keys")
    void keepsApiProvidersLocalWhenByokSelected() {
        when(providerFactory.getProvider("deepseek")).thenReturn(apiProvider);
        when(runtimeAccess.isCloudSelected("tenant-1")).thenReturn(false);

        LLMProvider resolved = resolver.resolve("deepseek", tenantContext());

        assertThat(resolved).isSameAs(apiProvider);
    }

    @Test
    @DisplayName("never wraps bridge providers because bridge execution remains local")
    void neverWrapsBridgeProviders() {
        when(providerFactory.getProvider("codex")).thenReturn(bridgeProvider);

        LLMProvider resolved = resolver.resolve("codex", AgentLoopContext.builder()
                .tenantId("tenant-1")
                .provider("codex")
                .build());

        assertThat(resolved).isSameAs(bridgeProvider);
    }

    @Test
    @DisplayName("rejects unsupported non-bridge providers when a CE tenant selects Cloud")
    void rejectsUnsupportedProvidersWhenCloudSelected() {
        when(providerFactory.getProvider("local-openai-compatible")).thenReturn(apiProvider);
        when(runtimeAccess.isCloudSelected("tenant-1")).thenReturn(true);

        assertThatThrownBy(() -> resolver.resolve("local-openai-compatible", tenantContext()))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("provider is not supported by the Cloud relay");
    }

    @Test
    @DisplayName("throws explicit provider error when Cloud is selected but the Cloud link is not ready")
    void throwsWhenCloudSelectedButCredentialsMissing() {
        when(providerFactory.getProvider("deepseek")).thenReturn(apiProvider);
        when(runtimeAccess.isCloudSelected("tenant-1")).thenReturn(true);
        when(runtimeAccess.resolveCloudRuntime("tenant-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve("deepseek", tenantContext()))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("Cloud LLM source selected");
    }

    @Test
    @DisplayName("Cloud default provider uses the first API provider by display order and ignores bridges")
    void cloudDefaultProviderIgnoresBridges() {
        LLMProvider openai = provider("openai", 20);
        LLMProvider deepseek = provider("deepseek", 10);
        when(runtimeAccess.isCloudSelected("tenant-1")).thenReturn(true);
        when(providerFactory.getAvailableProviderNames()).thenReturn(List.of("codex", "openai", "deepseek"));
        when(providerFactory.getProvider("openai")).thenReturn(openai);
        when(providerFactory.getProvider("deepseek")).thenReturn(deepseek);
        when(deepseek.getProviderName()).thenReturn("deepseek");

        String providerName = resolver.resolveDefaultProviderName("tenant-1");

        assertThat(providerName).isEqualTo("deepseek");
    }

    @Test
    @DisplayName("settleCeRelay posts a settle request when Cloud is selected and an executionId is present")
    void settleCeRelayPostsWhenCloudWithExecutionId() {
        CloudLlmRuntimeCredentials credentials = new CloudLlmRuntimeCredentials(
                "access-token", "install-1", "https://cloud.livecontext.test/api");
        when(runtimeAccess.isCloudSelected("tenant-1")).thenReturn(true);
        when(runtimeAccess.resolveCloudRuntime("tenant-1")).thenReturn(Optional.of(credentials));

        resolver.settleCeRelay(cloudContextWithExecution("exec-1"));

        verify(relayClient).settle(eq(credentials), eq(new CeRelaySettleRequest("exec-1")));
    }

    @Test
    @DisplayName("settleCeRelay is a no-op when the tenant did not select Cloud")
    void settleCeRelayNoopWhenNotCloud() {
        when(runtimeAccess.isCloudSelected("tenant-1")).thenReturn(false);

        resolver.settleCeRelay(cloudContextWithExecution("exec-1"));

        verify(relayClient, never()).settle(any(), any());
    }

    @Test
    @DisplayName("settleCeRelay is a no-op when there is no executionId (legacy per-call billing)")
    void settleCeRelayNoopWithoutExecutionId() {
        resolver.settleCeRelay(tenantContext()); // no executionId

        verify(relayClient, never()).settle(any(), any());
    }

    @Test
    @DisplayName("settleCeRelay swallows relay failures so a settle hiccup never breaks the loop")
    void settleCeRelaySwallowsFailures() {
        CloudLlmRuntimeCredentials credentials = new CloudLlmRuntimeCredentials(
                "access-token", "install-1", "https://cloud.livecontext.test/api");
        when(runtimeAccess.isCloudSelected("tenant-1")).thenReturn(true);
        when(runtimeAccess.resolveCloudRuntime("tenant-1")).thenReturn(Optional.of(credentials));
        doThrow(new RuntimeException("relay down")).when(relayClient).settle(any(), any());

        assertThatCode(() -> resolver.settleCeRelay(cloudContextWithExecution("exec-1")))
                .doesNotThrowAnyException();
    }

    private static AgentLoopContext tenantContext() {
        return AgentLoopContext.builder()
                .tenantId("tenant-1")
                .provider("deepseek")
                .build();
    }

    private static AgentLoopContext cloudContextWithExecution(String executionId) {
        return AgentLoopContext.builder()
                .tenantId("tenant-1")
                .provider("deepseek")
                .executionId(executionId)
                .build();
    }

    private LLMProvider provider(String name, int displayOrder) {
        LLMProvider provider = org.mockito.Mockito.mock(LLMProvider.class);
        when(provider.getDisplayOrder()).thenReturn(displayOrder);
        return provider;
    }
}
