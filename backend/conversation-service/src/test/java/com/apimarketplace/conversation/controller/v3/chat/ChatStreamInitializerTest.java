package com.apimarketplace.conversation.controller.v3.chat;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.ai.ChatStreamingService;
import com.apimarketplace.conversation.streaming.StreamMetadata;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.apimarketplace.conversation.streaming.StreamStateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("ChatStreamInitializer")
@ExtendWith(MockitoExtension.class)
class ChatStreamInitializerTest {

    @Mock
    private ChatStreamingService chatStreamingService;

    @Mock
    private ConversationHistoryService conversationHistoryService;

    @Mock
    private StreamStateService stateService;

    @Mock
    private StreamPubSubService pubSubService;

    @InjectMocks
    private ChatStreamInitializer initializer;

    @Nested
    @DisplayName("initializeStreamAsync")
    class InitializeStreamAsyncTests {

        @Test
        @DisplayName("should return JSON with conversationId, streamId, model for existing conversation")
        void shouldReturnJsonForExistingConversation() {
            ChatRequest request = new ChatRequest();
            request.setMessage("Hello");
            request.setModel("gpt-4");
            request.setProvider("openai");
            request.setConversationId("conv-existing");

            StreamMetadata metadata = StreamMetadata.create("stream-new", "user-1", "conv-existing", "gpt-4", "openai");

            when(stateService.createStream("user-1", "conv-existing", "gpt-4", "openai"))
                    .thenReturn(Mono.just(metadata));
            when(pubSubService.publish(eq("stream-new"), any())).thenReturn(Mono.just(1L));

            Mono<ResponseEntity<Map<String, String>>> result = initializer.initializeStreamAsync(request, "user-1");

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        Map<String, String> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.get("conversationId")).isEqualTo("conv-existing");
                        assertThat(body.get("streamId")).isEqualTo("stream-new");
                        assertThat(body.get("model")).isEqualTo("gpt-4");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should create conversation when conversationId is null")
        void shouldCreateConversationWhenNull() {
            ChatRequest request = new ChatRequest();
            request.setMessage("Hello");
            request.setModel("claude-3");
            request.setProvider("anthropic");
            // No conversationId set

            StreamMetadata metadata = StreamMetadata.create("stream-new", "user-1", "conv-new", "claude-3", "anthropic");

            // PR21 R2 - initializer now calls the org-scoped createConversation overload.
            // ChatRequest has no orgId set in this test → routed as personal scope (orgId=null).
            when(conversationHistoryService.createConversation("user-1", null, "Generating Title...", "claude-3", "anthropic", null, null))
                    .thenReturn("conv-new");
            when(stateService.createStream("user-1", "conv-new", "claude-3", "anthropic"))
                    .thenReturn(Mono.just(metadata));
            when(pubSubService.publish(eq("stream-new"), any())).thenReturn(Mono.just(1L));

            Mono<ResponseEntity<Map<String, String>>> result = initializer.initializeStreamAsync(request, "user-1");

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        Map<String, String> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.get("conversationId")).isEqualTo("conv-new");
                        assertThat(body.get("streamId")).isEqualTo("stream-new");
                        assertThat(body.get("model")).isEqualTo("claude-3");
                    })
                    .verifyComplete();

            verify(conversationHistoryService).createConversation("user-1", null, "Generating Title...", "claude-3", "anthropic", null, null);
        }

        @Test
        @DisplayName("regression: new chat preserves draft chatConfig when creating first conversation")
        void shouldCreateConversationWithDraftChatConfig() {
            ChatRequest request = new ChatRequest();
            request.setMessage("Hello configured chat");
            request.setModel("claude-3");
            request.setProvider("anthropic");
            request.setChatConfig(Map.of(
                    "systemPrompt", "Use the draft configuration.",
                    "maxTokens", 1111,
                    "toolsMode", "none"
            ));
            request.setDefaultSkillIds(List.of("skill-a"));

            StreamMetadata metadata = StreamMetadata.create("stream-config", "user-1", "conv-config", "claude-3", "anthropic");

            when(conversationHistoryService.createConversation(
                    eq("user-1"), eq(null), eq("Generating Title..."), eq("claude-3"), eq("anthropic"), eq(null), anyMap()))
                    .thenReturn("conv-config");
            when(stateService.createStream("user-1", "conv-config", "claude-3", "anthropic"))
                    .thenReturn(Mono.just(metadata));
            when(pubSubService.publish(eq("stream-config"), any())).thenReturn(Mono.just(1L));

            Mono<ResponseEntity<Map<String, String>>> result = initializer.initializeStreamAsync(request, "user-1");

            StepVerifier.create(result).assertNext(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK)).verifyComplete();

            verify(conversationHistoryService).createConversation(
                    eq("user-1"),
                    eq(null),
                    eq("Generating Title..."),
                    eq("claude-3"),
                    eq("anthropic"),
                    eq(null),
                    argThat(chatConfig ->
                            "Use the draft configuration.".equals(chatConfig.get("systemPrompt"))
                                    && Integer.valueOf(1111).equals(chatConfig.get("maxTokens"))
                                    && "none".equals(chatConfig.get("toolsMode"))
                                    && List.of("skill-a").equals(chatConfig.get("defaultSkillIds"))));
        }

        @Test
        @DisplayName("PR21 R2 - should thread orgId from ChatRequest into createConversation when team workspace")
        void shouldThreadOrgIdIntoNewConversation() {
            // Regression guard for the round-1 reviewer B+C finding: pre-R2 the v3 chat
            // write path silently dropped X-Organization-ID on conversation creation, so
            // every new team-workspace chat landed with organization_id = NULL and was
            // invisible to the org-strict sidebar finder. R2 threads request.orgId through
            // to ConversationHistoryService.createConversation; this test pins that contract.
            ChatRequest request = new ChatRequest();
            request.setMessage("Hello team");
            request.setModel("claude-3");
            request.setProvider("anthropic");
            request.setOrgId("org-acme");
            // No conversationId set

            StreamMetadata metadata = StreamMetadata.create("stream-team", "user-1", "conv-team", "claude-3", "anthropic");
            AtomicReference<String> observedOrg = new AtomicReference<>();

            when(conversationHistoryService.createConversation(
                    "user-1", "org-acme", "Generating Title...", "claude-3", "anthropic", null, null))
                    .thenAnswer(invocation -> {
                        observedOrg.set(TenantResolver.currentRequestOrganizationId());
                        return "conv-team";
                    });
            when(stateService.createStream("user-1", "conv-team", "claude-3", "anthropic"))
                    .thenReturn(Mono.just(metadata));
            when(pubSubService.publish(eq("stream-team"), any())).thenReturn(Mono.just(1L));

            Mono<ResponseEntity<Map<String, String>>> result = initializer.initializeStreamAsync(request, "user-1");

            StepVerifier.create(result).assertNext(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK)).verifyComplete();

            // The new conversation MUST be created with the org tag. Without this guard,
            // a regression that drops request.getOrgId() at the initializer site re-introduces
            // the original sidebar-leak bug for chat-driven creates.
            verify(conversationHistoryService).createConversation(
                    "user-1", "org-acme", "Generating Title...", "claude-3", "anthropic", null, null);
            assertThat(observedOrg).hasValue("org-acme");
        }

        @Test
        @DisplayName("should publish stream_started event to Redis")
        void shouldPublishStreamStartedEvent() {
            ChatRequest request = new ChatRequest();
            request.setMessage("Hello");
            request.setModel("gpt-4");
            request.setProvider("openai");
            request.setConversationId("conv-1");

            StreamMetadata metadata = StreamMetadata.create("stream-1", "user-1", "conv-1", "gpt-4", "openai");

            when(stateService.createStream("user-1", "conv-1", "gpt-4", "openai"))
                    .thenReturn(Mono.just(metadata));
            when(pubSubService.publish(eq("stream-1"), any())).thenReturn(Mono.just(1L));

            initializer.initializeStreamAsync(request, "user-1").block();

            verify(pubSubService).publish(eq("stream-1"), any());
        }

        @Test
        @DisplayName("should return retryable 503 when Redis stream creation keeps timing out")
        void shouldReturnRetryable503WhenStreamCreationTimesOut() {
            ChatRequest request = new ChatRequest();
            request.setMessage("Credentials configured, continue");
            request.setModel("claude-opus-4-8");
            request.setProvider("claude-code");
            request.setConversationId("conv-bridge");

            when(stateService.createStream("user-1", "conv-bridge", "claude-opus-4-8", "claude-code"))
                    .thenReturn(Mono.error(new QueryTimeoutException("Redis command timed out")));

            Mono<ResponseEntity<Map<String, String>>> result = initializer.initializeStreamAsync(request, "user-1");

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        Map<String, String> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.get("code")).isEqualTo("STREAM_INIT_TEMPORARILY_UNAVAILABLE");
                        assertThat(body.get("retryable")).isEqualTo("true");
                        assertThat(body.get("conversationId")).isEqualTo("conv-bridge");
                    })
                    .verifyComplete();

            verifyNoInteractions(chatStreamingService);
            verifyNoInteractions(pubSubService);
        }

        @Test
        @DisplayName("should retry transient Redis stream creation timeout before API provider routing")
        void shouldRetryTransientStreamCreationTimeoutBeforeApiProviderRouting() {
            ChatRequest request = new ChatRequest();
            request.setMessage("Credentials configured, continue");
            request.setModel("gpt-4o");
            request.setProvider("openai");
            request.setConversationId("conv-api");

            StreamMetadata metadata = StreamMetadata.create("stream-api", "user-1", "conv-api", "gpt-4o", "openai");
            AtomicInteger attempts = new AtomicInteger();

            when(stateService.createStream("user-1", "conv-api", "gpt-4o", "openai"))
                    .thenReturn(Mono.defer(() -> {
                        if (attempts.incrementAndGet() == 1) {
                            return Mono.error(new QueryTimeoutException("Redis command timed out"));
                        }
                        return Mono.just(metadata);
                    }));
            when(pubSubService.publish(eq("stream-api"), any())).thenReturn(Mono.just(1L));

            Mono<ResponseEntity<Map<String, String>>> result = initializer.initializeStreamAsync(request, "user-1");

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        Map<String, String> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.get("conversationId")).isEqualTo("conv-api");
                        assertThat(body.get("streamId")).isEqualTo("stream-api");
                        assertThat(body.get("model")).isEqualTo("gpt-4o");
                    })
                    .verifyComplete();

            assertThat(attempts).hasValue(2);
            verify(pubSubService).publish(eq("stream-api"), any());
        }
    }
}
