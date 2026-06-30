package com.apimarketplace.agent.cloud;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.streaming.StreamingCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CloudRelayProvider")
class CloudRelayProviderTest {

    @Mock private LLMProvider delegate;
    @Mock private CloudLlmRelayClient relayClient;

    @Test
    @DisplayName("streamReactive propagates downstream cancellation through shouldStop")
    void streamReactivePropagatesCancellationThroughShouldStop() {
        CloudLlmRuntimeCredentials credentials = new CloudLlmRuntimeCredentials(
                "access-token", "install-1", "https://cloud.livecontext.test/api");
        when(delegate.getProviderName()).thenReturn("deepseek");
        CloudRelayProvider provider = new CloudRelayProvider(delegate, credentials, relayClient);
        CompletionRequest request = CompletionRequest.builder()
                .tenantId("tenant-1")
                .model("deepseek-chat")
                .userPrompt("hello")
                .build();
        doAnswer(invocation -> {
            StreamingCallback callback = invocation.getArgument(2);
            callback.onChunk("first");
            assertThat(callback.shouldStop()).isTrue();
            return null;
        }).when(relayClient).stream(eq(credentials), any(), any());

        provider.streamReactive(request)
                .take(1)
                .blockLast(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("stamps the executionId onto each relay request for centralized billing")
    void stampsExecutionIdOntoRelayRequests() {
        CloudLlmRuntimeCredentials credentials = new CloudLlmRuntimeCredentials(
                "access-token", "install-1", "https://cloud.livecontext.test/api");
        when(delegate.getProviderName()).thenReturn("deepseek");
        CloudRelayProvider provider = new CloudRelayProvider(delegate, credentials, relayClient, "exec-1");
        CompletionRequest request = CompletionRequest.builder()
                .tenantId("tenant-1").model("deepseek-chat").userPrompt("hello").build();
        when(relayClient.complete(eq(credentials), any())).thenReturn(CompletionResponse.builder().build());

        provider.complete(request);
        provider.completeStreaming(request, mock(StreamingCallback.class));

        ArgumentCaptor<CloudLlmRelayRequest> completeCaptor = ArgumentCaptor.forClass(CloudLlmRelayRequest.class);
        ArgumentCaptor<CloudLlmRelayRequest> streamCaptor = ArgumentCaptor.forClass(CloudLlmRelayRequest.class);
        verify(relayClient).complete(eq(credentials), completeCaptor.capture());
        verify(relayClient).stream(eq(credentials), streamCaptor.capture(), any());
        assertThat(completeCaptor.getValue().executionId()).isEqualTo("exec-1");
        assertThat(streamCaptor.getValue().executionId()).isEqualTo("exec-1");
    }

    @Test
    @DisplayName("legacy 3-arg constructor sends a null executionId (per-call billing)")
    void legacyConstructorSendsNullExecutionId() {
        CloudLlmRuntimeCredentials credentials = new CloudLlmRuntimeCredentials(
                "access-token", "install-1", "https://cloud.livecontext.test/api");
        when(delegate.getProviderName()).thenReturn("deepseek");
        CloudRelayProvider provider = new CloudRelayProvider(delegate, credentials, relayClient);
        CompletionRequest request = CompletionRequest.builder()
                .tenantId("tenant-1").model("deepseek-chat").userPrompt("hello").build();

        provider.completeStreaming(request, mock(StreamingCallback.class));

        ArgumentCaptor<CloudLlmRelayRequest> streamCaptor = ArgumentCaptor.forClass(CloudLlmRelayRequest.class);
        verify(relayClient).stream(eq(credentials), streamCaptor.capture(), any());
        assertThat(streamCaptor.getValue().executionId()).isNull();
    }
}
