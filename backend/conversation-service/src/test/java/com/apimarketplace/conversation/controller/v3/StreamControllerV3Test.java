package com.apimarketplace.conversation.controller.v3;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.streaming.StreamMetadata;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.apimarketplace.conversation.streaming.StreamState;
import com.apimarketplace.conversation.streaming.StreamStateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("StreamControllerV3")
@ExtendWith(MockitoExtension.class)
class StreamControllerV3Test {

    @Mock
    private StreamStateService stateService;

    @Mock
    private StreamPubSubService pubSubService;

    @Mock
    private AgentClient agentClient;

    @Mock
    private ConversationRepository conversationRepository;

    @InjectMocks
    private StreamControllerV3 controller;

    /** A conversation in the caller's scope: owned by the user, no org tag (personal). */
    private static Conversation ownedBy(String userId, String orgId) {
        Conversation c = new Conversation();
        c.setUserId(userId);
        c.setOrganizationId(orgId);
        return c;
    }

    @Nested
    @DisplayName("getStreamState (reconnection)")
    class GetStreamStateTests {

        @Test
        @DisplayName("returns stream state with content and tool events for the owner")
        void shouldReturnStreamStateWithContentAndToolsForOwner() {
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(ownedBy("user-1", null)));
            StreamMetadata metadata = StreamMetadata.create("stream-1", "user-1", "conv-1", "gpt-4", "openai");

            when(stateService.getByConversationId("conv-1")).thenReturn(Mono.just(metadata));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("Hello, world!"));
            when(stateService.getToolEvents("stream-1")).thenReturn(Flux.just(
                    "{\"toolName\":\"test\",\"toolId\":\"t1\",\"arguments\":\"{}\"}",
                    "{\"success\":true,\"resultId\":\"r1\"}"
            ));

            Mono<ResponseEntity<Map<String, Object>>> result = controller.getStreamState("conv-1", "user-1", null);

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        Map<String, Object> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.get("streamId")).isEqualTo("stream-1");
                        assertThat(body.get("conversationId")).isEqualTo("conv-1");
                        assertThat(body.get("model")).isEqualTo("gpt-4");
                        assertThat(body.get("content")).isEqualTo("Hello, world!");
                        assertThat(body.get("hasActiveStream")).isEqualTo(false); // CREATED state is not reconnectable
                        assertThat(body.get("toolEvents")).isInstanceOf(List.class);
                        assertThat((List<?>) body.get("toolEvents")).hasSize(2);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("grants org teammates access to an org-tagged conversation")
        void shouldGrantOrgTeammateAccess() {
            // Conversation belongs to user-1 in org-9; the CALLER is a different
            // user (user-2) but in the same org workspace - org workspaces are
            // shared, so strict scope grants by org match.
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(ownedBy("user-1", "org-9")));
            StreamMetadata metadata = StreamMetadata.create("stream-1", "user-1", "conv-1", "gpt-4", "openai");
            when(stateService.getByConversationId("conv-1")).thenReturn(Mono.just(metadata));
            when(stateService.getFullContent("stream-1")).thenReturn(Mono.just("teammate-visible"));
            when(stateService.getToolEvents("stream-1")).thenReturn(Flux.empty());

            Mono<ResponseEntity<Map<String, Object>>> result = controller.getStreamState("conv-1", "user-2", "org-9");

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getBody().get("content")).isEqualTo("teammate-visible"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("does NOT leak content to an out-of-scope caller - responds as no-stream")
        void shouldNotLeakToOutOfScopeCaller() {
            // Conversation owned by user-1 in org-1; an unrelated caller asks for it.
            when(conversationRepository.findById("conv-secret")).thenReturn(Optional.of(ownedBy("user-1", "org-1")));

            Mono<ResponseEntity<Map<String, Object>>> result =
                    controller.getStreamState("conv-secret", "intruder", "intruder-org");

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        Map<String, Object> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.get("conversationId")).isEqualTo("conv-secret");
                        assertThat(body.get("hasActiveStream")).isEqualTo(false);
                        // The leak we are closing: the REAL buffered content, streamId and
                        // model are never exposed - content/toolEvents come back empty,
                        // identical to a genuine no-stream response.
                        assertThat(body.get("content")).isEqualTo("");
                        assertThat((List<?>) body.get("toolEvents")).isEmpty();
                        assertThat(body).doesNotContainKeys("streamId", "model");
                    })
                    .verifyComplete();

            // The stream store must never even be consulted for an out-of-scope read.
            verify(stateService, never()).getByConversationId(anyString());
            verify(stateService, never()).getFullContent(anyString());
        }

        @Test
        @DisplayName("treats a non-existent conversation as no-stream (no existence leak)")
        void shouldTreatMissingConversationAsNoStream() {
            when(conversationRepository.findById("conv-ghost")).thenReturn(Optional.empty());

            Mono<ResponseEntity<Map<String, Object>>> result =
                    controller.getStreamState("conv-ghost", "user-1", "org-1");

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody().get("hasActiveStream")).isEqualTo(false);
                    })
                    .verifyComplete();
            verify(stateService, never()).getByConversationId(anyString());
        }

        @Test
        @DisplayName("returns hasActiveStream=false when the owner has no stream")
        void shouldReturnNoActiveStreamWhenNotFound() {
            when(conversationRepository.findById("conv-999")).thenReturn(Optional.of(ownedBy("user-1", null)));
            when(stateService.getByConversationId("conv-999")).thenReturn(Mono.empty());

            Mono<ResponseEntity<Map<String, Object>>> result = controller.getStreamState("conv-999", "user-1", null);

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        Map<String, Object> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.get("conversationId")).isEqualTo("conv-999");
                        assertThat(body.get("hasActiveStream")).isEqualTo(false);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("returns empty content when none buffered")
        void shouldReturnEmptyContentWhenNoneBuffered() {
            when(conversationRepository.findById("conv-2")).thenReturn(Optional.of(ownedBy("user-1", null)));
            StreamMetadata metadata = StreamMetadata.create("stream-2", "user-1", "conv-2", "gpt-4", "openai");

            when(stateService.getByConversationId("conv-2")).thenReturn(Mono.just(metadata));
            when(stateService.getFullContent("stream-2")).thenReturn(Mono.empty());
            when(stateService.getToolEvents("stream-2")).thenReturn(Flux.empty());

            Mono<ResponseEntity<Map<String, Object>>> result = controller.getStreamState("conv-2", "user-1", null);

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        Map<String, Object> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.get("content")).isEqualTo("");
                        assertThat((List<?>) body.get("toolEvents")).isEmpty();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("handles downstream errors gracefully")
        void shouldHandleErrors() {
            when(conversationRepository.findById("conv-err")).thenReturn(Optional.of(ownedBy("user-1", null)));
            when(stateService.getByConversationId("conv-err"))
                    .thenReturn(Mono.error(new RuntimeException("Redis error")));

            Mono<ResponseEntity<Map<String, Object>>> result = controller.getStreamState("conv-err", "user-1", null);

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR))
                    .verifyComplete();
        }

        @Test
        @DisplayName("allows the personal owner even when the org header is absent")
        void shouldAllowPersonalOwnerWhenOrgHeaderAbsent() {
            // Personal-scope row (org null) + caller sends no X-Organization-ID:
            // strict scope's personal branch (owner match AND row-org null) grants.
            when(conversationRepository.findById("conv-p")).thenReturn(Optional.of(ownedBy("user-1", null)));
            StreamMetadata metadata = StreamMetadata.create("stream-p", "user-1", "conv-p", "gpt-4", "openai");
            when(stateService.getByConversationId("conv-p")).thenReturn(Mono.just(metadata));
            when(stateService.getFullContent("stream-p")).thenReturn(Mono.just("personal"));
            when(stateService.getToolEvents("stream-p")).thenReturn(Flux.empty());

            Mono<ResponseEntity<Map<String, Object>>> result = controller.getStreamState("conv-p", "user-1", null);

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getBody().get("content")).isEqualTo("personal"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("denies a caller in a different org than the conversation")
        void shouldDenyDifferentOrgCaller() {
            // Row owned by user-1 in org-A; caller is user-2 in org-B (neither
            // owner nor same org) - strict scope denies.
            when(conversationRepository.findById("conv-x")).thenReturn(Optional.of(ownedBy("user-1", "org-A")));

            Mono<ResponseEntity<Map<String, Object>>> result =
                    controller.getStreamState("conv-x", "user-2", "org-B");

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getBody().get("hasActiveStream")).isEqualTo(false);
                        assertThat(response.getBody().get("content")).isEqualTo("");
                        assertThat(response.getBody()).doesNotContainKeys("streamId", "model");
                    })
                    .verifyComplete();
            verify(stateService, never()).getByConversationId(anyString());
        }

        @Test
        @DisplayName("fails closed (no-stream) when the conversation lookup throws")
        void shouldFailClosedWhenConversationLookupThrows() {
            when(conversationRepository.findById("conv-boom"))
                    .thenThrow(new RuntimeException("db unavailable"));

            Mono<ResponseEntity<Map<String, Object>>> result =
                    controller.getStreamState("conv-boom", "user-1", "org-1");

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody().get("hasActiveStream")).isEqualTo(false);
                        assertThat(response.getBody().get("content")).isEqualTo("");
                        assertThat(response.getBody()).doesNotContainKeys("streamId", "model");
                    })
                    .verifyComplete();
            // A lookup failure must never fall through to the stream store.
            verify(stateService, never()).getByConversationId(anyString());
        }
    }

    @Nested
    @DisplayName("getStreamStatusByConversation")
    class GetStreamStatusByConversationTests {

        @Test
        @DisplayName("reports no active stream to an out-of-scope caller without consulting the store")
        void shouldHideStatusFromOutOfScopeCaller() {
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(ownedBy("user-1", "org-1")));

            Mono<ResponseEntity<com.apimarketplace.conversation.dto.StreamStatusResponse>> result =
                    controller.getStreamStatusByConversation("conv-1", "intruder", "intruder-org");

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody()).isNotNull();
                        assertThat(response.getBody().hasActiveStream()).isFalse();
                    })
                    .verifyComplete();
            verify(stateService, never()).getByConversationId(anyString());
        }
    }

    @Nested
    @DisplayName("stopStreamByConversation")
    class StopStreamByConversationTests {

        @Test
        @DisplayName("passes organizationId to the async cascade when the owner has no active stream")
        void shouldPassOrganizationIdToAsyncCascadeWhenNoStreamExists() {
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(ownedBy("user-1", "org-1")));
            when(stateService.getByConversationId("conv-1")).thenReturn(Mono.empty());

            Mono<ResponseEntity<Void>> result =
                    controller.stopStreamByConversation("conv-1", "user-1", "org-1");

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                    .verifyComplete();

            verify(agentClient, timeout(1000)).cancelWorkflowsForConversation("conv-1", "org-1");
            verify(agentClient, timeout(1000)).cancelTasksForConversation("conv-1", "user-1", "org-1");
        }

        @Test
        @DisplayName("refuses to stop or cascade a conversation the caller cannot see")
        void shouldRefuseStopForOutOfScopeCaller() {
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(ownedBy("user-1", "org-1")));

            Mono<ResponseEntity<Void>> result =
                    controller.stopStreamByConversation("conv-1", "intruder", "intruder-org");

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                    .verifyComplete();

            // No cross-user stop and no cascade-cancel of someone else's workflows/tasks.
            verify(stateService, never()).getByConversationId(anyString());
            verify(agentClient, never()).cancelWorkflowsForConversation(anyString(), anyString());
            verify(agentClient, never()).cancelTasksForConversation(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("stops a live stream for the in-scope owner and publishes the stopped event")
        void shouldStopLiveStreamForOwner() {
            when(conversationRepository.findById("conv-live")).thenReturn(Optional.of(ownedBy("user-1", "org-1")));
            StreamMetadata metadata = StreamMetadata.create("stream-live", "user-1", "conv-live", "gpt-4", "openai")
                    .withState(StreamState.STREAMING);
            when(stateService.getByConversationId("conv-live")).thenReturn(Mono.just(metadata));
            when(stateService.stop("stream-live")).thenReturn(Mono.just(true));
            when(stateService.setCancelKey("stream-live")).thenReturn(Mono.just(true));
            when(stateService.getFullContent("stream-live")).thenReturn(Mono.just("partial answer"));
            when(pubSubService.publishStopped("stream-live", "partial answer")).thenReturn(Mono.just(1L));

            Mono<ResponseEntity<Void>> result =
                    controller.stopStreamByConversation("conv-live", "user-1", "org-1");

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK))
                    .verifyComplete();

            verify(stateService).stop("stream-live");
            verify(pubSubService).publishStopped("stream-live", "partial answer");
        }
    }

    @Nested
    @DisplayName("by-streamId endpoints (status / stop)")
    class ByStreamIdTests {

        @Test
        @DisplayName("getStreamStatus returns metadata for the in-scope owner")
        void statusReturnedForOwner() {
            StreamMetadata metadata = StreamMetadata.create("stream-1", "user-1", "conv-1", "gpt-4", "openai");
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata));
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(ownedBy("user-1", "org-1")));

            Mono<ResponseEntity<com.apimarketplace.conversation.dto.StreamStatusResponse>> result =
                    controller.getStreamStatus("stream-1", "user-1", "org-1");

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody().conversationId()).isEqualTo("conv-1");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("getStreamStatus hides another conversation's stream (404, no metadata leak)")
        void statusHiddenForOutOfScopeCaller() {
            StreamMetadata metadata = StreamMetadata.create("stream-1", "user-1", "conv-1", "gpt-4", "openai");
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata));
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(ownedBy("user-1", "org-1")));

            Mono<ResponseEntity<com.apimarketplace.conversation.dto.StreamStatusResponse>> result =
                    controller.getStreamStatus("stream-1", "intruder", "intruder-org");

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(response.getBody()).isNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("stopStream refuses to stop another user's stream and never publishes stopped")
        void stopRefusedForOutOfScopeCaller() {
            StreamMetadata metadata = StreamMetadata.create("stream-1", "user-1", "conv-1", "gpt-4", "openai")
                    .withState(StreamState.STREAMING);
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata));
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(ownedBy("user-1", "org-1")));

            Mono<ResponseEntity<Void>> result =
                    controller.stopStream("stream-1", "intruder", "intruder-org");

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                    .verifyComplete();

            verify(stateService, never()).stop(anyString());
            verify(pubSubService, never()).publishStopped(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("StreamMetrics record")
    class StreamMetricsTests {

        @Test
        @DisplayName("should create StreamMetrics with values")
        void shouldCreateWithValues() {
            StreamControllerV3.StreamMetrics metrics = new StreamControllerV3.StreamMetrics(
                    5, "Test note"
            );

            assertThat(metrics.localActiveStreams()).isEqualTo(5);
            assertThat(metrics.note()).isEqualTo("Test note");
        }

        @Test
        @DisplayName("should create StreamMetrics with zero streams")
        void shouldCreateWithZeroStreams() {
            StreamControllerV3.StreamMetrics metrics = new StreamControllerV3.StreamMetrics(
                    0, "Redis-based, check Redis for active count"
            );

            assertThat(metrics.localActiveStreams()).isZero();
            assertThat(metrics.note()).isEqualTo("Redis-based, check Redis for active count");
        }

        @Test
        @DisplayName("should support record equality")
        void shouldSupportEquality() {
            StreamControllerV3.StreamMetrics m1 = new StreamControllerV3.StreamMetrics(0, "note");
            StreamControllerV3.StreamMetrics m2 = new StreamControllerV3.StreamMetrics(0, "note");

            assertThat(m1).isEqualTo(m2);
            assertThat(m1.hashCode()).isEqualTo(m2.hashCode());
        }

        @Test
        @DisplayName("should have proper toString")
        void shouldHaveProperToString() {
            StreamControllerV3.StreamMetrics metrics = new StreamControllerV3.StreamMetrics(
                    3, "test"
            );

            String str = metrics.toString();
            assertThat(str).contains("3");
            assertThat(str).contains("test");
        }
    }
}
