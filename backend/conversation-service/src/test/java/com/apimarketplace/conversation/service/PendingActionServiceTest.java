package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PendingActionService Tests")
class PendingActionServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    private PendingActionService pendingActionService;

    @BeforeEach
    void setUp() {
        pendingActionService = new PendingActionService(conversationRepository);
    }

    private Conversation buildConversation(String id) {
        Conversation conv = new Conversation("user-1", "Test", "model", "provider");
        conv.setId(id);
        return conv;
    }

    private Map<String, Object> buildPendingAction(String waitingFor, boolean expired) {
        Map<String, Object> action = new HashMap<>();
        action.put("tool_call", Map.of("id", "call-1", "name", "my_tool", "arguments", Map.of()));
        action.put("waiting_for", waitingFor);
        action.put("original_request", "Check my email");
        action.put("context_summary", "Found gmail tool");
        action.put("created_at", Instant.now().toString());
        Instant expiresAt = expired
                ? Instant.now().minus(2, ChronoUnit.HOURS)
                : Instant.now().plus(1, ChronoUnit.HOURS);
        action.put("expires_at", expiresAt.toString());
        return action;
    }

    // ================================================================
    // setPendingAction()
    // ================================================================

    @Nested
    @DisplayName("setPendingAction()")
    class SetPendingAction {

        @Test
        @DisplayName("should set pending action on conversation")
        void shouldSetPendingAction() {
            Conversation conv = buildConversation("conv-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            boolean result = pendingActionService.setPendingAction(
                    "conv-1", "call-1", "gmail_read", Map.of("query", "inbox"),
                    "credential:gmail", "Check my Gmail", "Found gmail tool");

            assertThat(result).isTrue();
            assertThat(conv.getPendingAction()).isNotNull();
            assertThat(conv.getWaitingFor()).isEqualTo("credential:gmail");

            verify(conversationRepository).save(conv);
        }

        @Test
        @DisplayName("should return false when conversation not found")
        void shouldReturnFalseWhenNotFound() {
            when(conversationRepository.findById("missing")).thenReturn(Optional.empty());

            boolean result = pendingActionService.setPendingAction(
                    "missing", "call-1", "tool", null, "credential:x", "req", "ctx");

            assertThat(result).isFalse();
            verify(conversationRepository, never()).save(any());
        }

        @Test
        @DisplayName("tool-authorization pending action persists application_id so a reload can reopen the install modal")
        void toolAuthorizationPersistsApplicationId() {
            Conversation conv = buildConversation("conv-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            boolean result = pendingActionService.setToolAuthorizationPendingAction(
                    "conv-1", "application:acquire", "application", "acquire",
                    "call-1", "{\"action\":\"acquire\"}", "pub-123");

            assertThat(result).isTrue();
            assertThat(conv.getPendingAction()).containsEntry("waiting_for", "tool_authorization");
            assertThat(conv.getPendingAction()).containsEntry("application_id", "pub-123");
        }

        @Test
        @DisplayName("tool-authorization pending action omits application_id for non-acquire rules")
        void toolAuthorizationOmitsApplicationIdWhenNull() {
            Conversation conv = buildConversation("conv-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            pendingActionService.setToolAuthorizationPendingAction(
                    "conv-1", "application:execute", "application", "execute",
                    "call-1", "{\"action\":\"execute\"}", null);

            assertThat(conv.getPendingAction()).doesNotContainKey("application_id");
        }

        @Test
        @DisplayName("should handle null tool arguments")
        void shouldHandleNullToolArguments() {
            Conversation conv = buildConversation("conv-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            boolean result = pendingActionService.setPendingAction(
                    "conv-1", "call-1", "tool", null,
                    "credential:gmail", "Check email", "summary");

            assertThat(result).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> toolCall = (Map<String, Object>) conv.getPendingAction().get("tool_call");
            assertThat(toolCall.get("arguments")).isEqualTo(Map.of());
        }

        @Test
        @DisplayName("should include expiration time")
        void shouldIncludeExpirationTime() {
            Conversation conv = buildConversation("conv-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            pendingActionService.setPendingAction(
                    "conv-1", "call-1", "tool", null,
                    "credential:gmail", "req", "ctx");

            String expiresAt = (String) conv.getPendingAction().get("expires_at");
            assertThat(expiresAt).isNotNull();
            Instant expiry = Instant.parse(expiresAt);
            assertThat(expiry).isAfter(Instant.now());
        }
    }

    // ================================================================
    // getPendingAction()
    // ================================================================

    @Nested
    @DisplayName("getPendingAction()")
    class GetPendingAction {

        @Test
        @DisplayName("should return pending action when not expired")
        void shouldReturnPendingAction() {
            Conversation conv = buildConversation("conv-1");
            conv.setPendingAction(buildPendingAction("credential:gmail", false));
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            Optional<Map<String, Object>> result = pendingActionService.getPendingAction("conv-1");

            assertThat(result).isPresent();
            assertThat(result.get().get("waiting_for")).isEqualTo("credential:gmail");
        }

        @Test
        @DisplayName("should return empty when pending action is expired")
        void shouldReturnEmptyWhenExpired() {
            Conversation conv = buildConversation("conv-1");
            conv.setPendingAction(buildPendingAction("credential:gmail", true));
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            Optional<Map<String, Object>> result = pendingActionService.getPendingAction("conv-1");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when no pending action set")
        void shouldReturnEmptyWhenNoPendingAction() {
            Conversation conv = buildConversation("conv-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            Optional<Map<String, Object>> result = pendingActionService.getPendingAction("conv-1");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when conversation not found")
        void shouldReturnEmptyWhenConversationNotFound() {
            when(conversationRepository.findById("missing")).thenReturn(Optional.empty());

            Optional<Map<String, Object>> result = pendingActionService.getPendingAction("missing");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when pending action is empty map")
        void shouldReturnEmptyWhenPendingActionIsEmptyMap() {
            Conversation conv = buildConversation("conv-1");
            conv.setPendingAction(new HashMap<>());
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            Optional<Map<String, Object>> result = pendingActionService.getPendingAction("conv-1");

            assertThat(result).isEmpty();
        }
    }

    // ================================================================
    // clearPendingAction()
    // ================================================================

    @Nested
    @DisplayName("clearPendingAction()")
    class ClearPendingAction {

        @Test
        @DisplayName("should clear pending action and persist")
        void shouldClearPendingAction() {
            Conversation conv = buildConversation("conv-1");
            conv.setPendingAction(buildPendingAction("credential:gmail", false));
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            pendingActionService.clearPendingAction("conv-1");

            assertThat(conv.getPendingAction()).isNull();
            verify(conversationRepository).save(conv);
        }

        @Test
        @DisplayName("should handle clearing when conversation not found")
        void shouldHandleClearWhenNotFound() {
            when(conversationRepository.findById("missing")).thenReturn(Optional.empty());

            pendingActionService.clearPendingAction("missing");

            verify(conversationRepository, never()).save(any());
        }
    }

    // ================================================================
    // findConversationsWaitingFor()
    // ================================================================

    @Nested
    @DisplayName("findConversationsWaitingFor()")
    class FindConversationsWaitingFor {

        @Test
        @DisplayName("should find conversations with matching waiting_for")
        void shouldFindMatchingConversations() {
            Conversation conv1 = buildConversation("conv-1");
            conv1.setPendingAction(buildPendingAction("credential:gmail", false));

            Conversation conv2 = buildConversation("conv-2");
            conv2.setPendingAction(buildPendingAction("credential:slack", false));

            Conversation conv3 = buildConversation("conv-3");
            // No pending action

            when(conversationRepository.findAll()).thenReturn(List.of(conv1, conv2, conv3));

            List<String> result = pendingActionService.findConversationsWaitingFor("credential:gmail");

            assertThat(result).containsExactly("conv-1");
        }

        @Test
        @DisplayName("should exclude expired pending actions")
        void shouldExcludeExpired() {
            Conversation conv1 = buildConversation("conv-1");
            conv1.setPendingAction(buildPendingAction("credential:gmail", true)); // expired

            when(conversationRepository.findAll()).thenReturn(List.of(conv1));

            List<String> result = pendingActionService.findConversationsWaitingFor("credential:gmail");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when no conversations match")
        void shouldReturnEmptyWhenNoMatch() {
            when(conversationRepository.findAll()).thenReturn(List.of());

            List<String> result = pendingActionService.findConversationsWaitingFor("credential:gmail");

            assertThat(result).isEmpty();
        }
    }

    // ================================================================
    // findConversationsWaitingForCredential()
    // ================================================================

    @Nested
    @DisplayName("findConversationsWaitingForCredential()")
    class FindConversationsWaitingForCredential {

        @Test
        @DisplayName("should prepend credential: prefix")
        void shouldPrependPrefix() {
            Conversation conv = buildConversation("conv-1");
            conv.setPendingAction(buildPendingAction("credential:gmail", false));
            when(conversationRepository.findAll()).thenReturn(List.of(conv));

            List<String> result = pendingActionService.findConversationsWaitingForCredential("gmail");

            assertThat(result).containsExactly("conv-1");
        }
    }

    // ================================================================
    // setServiceApprovalPendingAction()
    // ================================================================

    @Nested
    @DisplayName("setServiceApprovalPendingAction()")
    class SetServiceApprovalPendingAction {

        @Test
        @DisplayName("should set service approval pending action")
        void shouldSetServiceApprovalAction() {
            Conversation conv = buildConversation("conv-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            List<Map<String, Object>> services = List.of(
                    Map.of("type", "gmail", "name", "Gmail"),
                    Map.of("type", "slack", "name", "Slack")
            );

            boolean result = pendingActionService.setServiceApprovalPendingAction(
                    "conv-1", services, "Agent needs access", false);

            assertThat(result).isTrue();
            assertThat(conv.getPendingAction()).isNotNull();
            assertThat(conv.getWaitingFor()).isEqualTo("service_approval");
            verify(conversationRepository).save(conv);
        }

        @Test
        @DisplayName("should return false when conversation not found")
        void shouldReturnFalseWhenNotFound() {
            when(conversationRepository.findById("missing")).thenReturn(Optional.empty());

            boolean result = pendingActionService.setServiceApprovalPendingAction(
                    "missing", List.of(), "reason", false);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should handle null services list")
        void shouldHandleNullServices() {
            Conversation conv = buildConversation("conv-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            boolean result = pendingActionService.setServiceApprovalPendingAction(
                    "conv-1", null, "reason", false);

            assertThat(result).isTrue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stored = (List<Map<String, Object>>) conv.getPendingAction().get("services");
            assertThat(stored).isEmpty();
        }
    }

    // ================================================================
    // Multiple parallel pending actions (V309)
    // ================================================================

    @Nested
    @DisplayName("multiple pending actions")
    class MultiplePendingActions {

        private Map<String, Object> serviceAction(String type) {
            return serviceAction(type, false);
        }

        private Map<String, Object> serviceAction(String type, boolean needsAttention) {
            return PendingActionService.buildServiceApprovalAction(
                    List.of(Map.of("serviceType", type, "serviceName", type)), "need " + type, needsAttention);
        }

        private Map<String, Object> authAction(String rule) {
            return PendingActionService.buildToolAuthorizationAction(rule, "application", "acquire",
                    "call-x", "{}", null);
        }

        @Test
        @DisplayName("setPendingActions stores the full list and syncs the single pending_action to element[0]")
        void setPendingActionsStoresListAndSyncsSingle() {
            Conversation conv = buildConversation("conv-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            Map<String, Object> a = serviceAction("gmail");
            Map<String, Object> b = authAction("application:acquire");
            boolean result = pendingActionService.setPendingActions("conv-1", List.of(a, b));

            assertThat(result).isTrue();
            assertThat(conv.getPendingActions()).hasSize(2);
            // Legacy single stays in sync with the first card for back-compat.
            assertThat(conv.getPendingAction()).isEqualTo(a);
            verify(conversationRepository).save(conv);
        }

        @Test
        @DisplayName("setPendingActions with an empty list clears both list and single")
        void setPendingActionsEmptyClearsBoth() {
            Conversation conv = buildConversation("conv-1");
            conv.setPendingActions(new ArrayList<>(List.of(serviceAction("gmail"))));
            conv.setPendingAction(serviceAction("gmail"));
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            pendingActionService.setPendingActions("conv-1", List.of());

            assertThat(conv.getPendingActions()).isEmpty();
            assertThat(conv.getPendingAction()).isNull();
        }

        @Test
        @DisplayName("addPendingActions merges normal credential approvals into one card")
        void addPendingActionsMergesAndDedups() {
            Conversation conv = buildConversation("conv-1");
            // A card already pending from a prior turn.
            conv.setPendingActions(new ArrayList<>(List.of(serviceAction("slack"))));
            conv.setPendingAction(serviceAction("slack"));
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            // The new turn raises the SAME slack card again (dedup) + a NEW gmail card.
            pendingActionService.addPendingActions("conv-1",
                    List.of(serviceAction("slack"), serviceAction("gmail")));

            assertThat(conv.getPendingActions()).hasSize(1);
            assertThat(PendingActionService.pendingActionKey(conv.getPendingActions().get(0)))
                    .isEqualTo("svc:connect");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> services =
                    (List<Map<String, Object>>) conv.getPendingActions().get(0).get("services");
            assertThat(services).extracting(s -> s.get("serviceType"))
                    .containsExactly("slack", "gmail");
        }

        @Test
        @DisplayName("addPendingActions keeps normal connect and forced reconnect cards separate")
        void addPendingActionsKeepsConnectAndAttentionSeparate() {
            Conversation conv = buildConversation("conv-1");
            conv.setPendingActions(new ArrayList<>(List.of(serviceAction("gmail"))));
            conv.setPendingAction(serviceAction("gmail"));
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            pendingActionService.addPendingActions("conv-1", List.of(serviceAction("gmail", true)));

            List<String> keys = conv.getPendingActions().stream()
                    .map(PendingActionService::pendingActionKey).toList();
            assertThat(keys).containsExactly("svc:connect", "svc:attention");
        }

        @Test
        @DisplayName("clearOnePendingAction removes only the matching card and re-syncs the single")
        void clearOneRemovesOnlyTarget() {
            Conversation conv = buildConversation("conv-1");
            Map<String, Object> gmail = serviceAction("gmail");
            Map<String, Object> acquire = authAction("application:acquire");
            conv.setPendingActions(new ArrayList<>(List.of(gmail, acquire)));
            conv.setPendingAction(gmail);
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            boolean removed = pendingActionService.clearOnePendingAction("conv-1", "auth:application:acquire");

            assertThat(removed).isTrue();
            assertThat(conv.getPendingActions()).containsExactly(gmail);
            assertThat(conv.getPendingAction()).isEqualTo(gmail);
        }

        @Test
        @DisplayName("clearOnePendingAction on a pre-V309 legacy single row clears it when the key matches")
        void clearOneClearsLegacySingle() {
            Conversation conv = buildConversation("conv-1");
            conv.setPendingActions(new ArrayList<>()); // empty list (legacy)
            conv.setPendingAction(authAction("application:execute"));
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            boolean removed = pendingActionService.clearOnePendingAction("conv-1", "auth:application:execute");

            assertThat(removed).isTrue();
            assertThat(conv.getPendingAction()).isNull();
        }

        @Test
        @DisplayName("clearOnePendingAction is a no-op when the key matches nothing")
        void clearOneNoMatch() {
            Conversation conv = buildConversation("conv-1");
            Map<String, Object> gmail = serviceAction("gmail");
            conv.setPendingActions(new ArrayList<>(List.of(gmail)));
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            boolean removed = pendingActionService.clearOnePendingAction("conv-1", "auth:nope");

            assertThat(removed).isFalse();
            assertThat(conv.getPendingActions()).containsExactly(gmail);
            verify(conversationRepository, never()).save(any());
        }

        @Test
        @DisplayName("pendingActionKey is auth:<rule> for authorization and mode-based for approvals")
        void pendingActionKeyShape() {
            assertThat(PendingActionService.pendingActionKey(authAction("application:acquire")))
                    .isEqualTo("auth:application:acquire");
            Map<String, Object> multi = PendingActionService.buildServiceApprovalAction(
                    List.of(Map.of("serviceType", "slack"), Map.of("serviceType", "gmail")), "r", false);
            assertThat(PendingActionService.pendingActionKey(multi)).isEqualTo("svc:connect");
            Map<String, Object> attention = PendingActionService.buildServiceApprovalAction(
                    List.of(Map.of("serviceType", "gmail")), "r", true);
            assertThat(PendingActionService.pendingActionKey(attention)).isEqualTo("svc:attention");
        }
    }

    // ================================================================
    // Static extraction methods
    // ================================================================

    @Nested
    @DisplayName("Static extraction methods")
    class StaticExtractionMethods {

        @Test
        @DisplayName("extractToolCall should return tool_call map")
        void shouldExtractToolCall() {
            Map<String, Object> action = buildPendingAction("credential:gmail", false);

            Map<String, Object> toolCall = PendingActionService.extractToolCall(action);

            assertThat(toolCall).isNotNull();
            assertThat(toolCall.get("name")).isEqualTo("my_tool");
        }

        @Test
        @DisplayName("extractToolCall should return null for null input")
        void shouldReturnNullForNullInput() {
            assertThat(PendingActionService.extractToolCall(null)).isNull();
        }

        @Test
        @DisplayName("extractOriginalRequest should return original request")
        void shouldExtractOriginalRequest() {
            Map<String, Object> action = buildPendingAction("credential:gmail", false);

            String request = PendingActionService.extractOriginalRequest(action);

            assertThat(request).isEqualTo("Check my email");
        }

        @Test
        @DisplayName("extractOriginalRequest should return null for null input")
        void shouldReturnNullForNullOriginalRequest() {
            assertThat(PendingActionService.extractOriginalRequest(null)).isNull();
        }
    }
}
