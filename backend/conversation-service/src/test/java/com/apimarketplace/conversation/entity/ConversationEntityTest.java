package com.apimarketplace.conversation.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Conversation Entity")
class ConversationEntityTest {

    @Nested
    @DisplayName("Constructors")
    class Constructors {

        @Test
        @DisplayName("should create with default constructor")
        void shouldCreateWithDefault() {
            Conversation conv = new Conversation();
            assertThat(conv.getActive()).isTrue();
            assertThat(conv.getMessages()).isEmpty();
        }

        @Test
        @DisplayName("should create with args constructor")
        void shouldCreateWithArgs() {
            Conversation conv = new Conversation("user-1", "Title", "gpt-4", "openai");

            assertThat(conv.getUserId()).isEqualTo("user-1");
            assertThat(conv.getTitle()).isEqualTo("Title");
            assertThat(conv.getModel()).isEqualTo("gpt-4");
            assertThat(conv.getProvider()).isEqualTo("openai");
        }
    }

    @Nested
    @DisplayName("Pending actions")
    class PendingActions {

        @Test
        @DisplayName("should have no pending action by default")
        void shouldHaveNoPendingAction() {
            Conversation conv = new Conversation();
            assertThat(conv.hasPendingAction()).isFalse();
            assertThat(conv.getWaitingFor()).isNull();
        }

        @Test
        @DisplayName("should detect pending action")
        void shouldDetectPendingAction() {
            Conversation conv = new Conversation();
            Map<String, Object> action = new HashMap<>();
            action.put("waiting_for", "credential:gmail");
            conv.setPendingAction(action);

            assertThat(conv.hasPendingAction()).isTrue();
            assertThat(conv.getWaitingFor()).isEqualTo("credential:gmail");
        }

        @Test
        @DisplayName("should return false for empty pending action map")
        void shouldReturnFalseForEmpty() {
            Conversation conv = new Conversation();
            conv.setPendingAction(new HashMap<>());

            assertThat(conv.hasPendingAction()).isFalse();
        }

        @Test
        @DisplayName("should clear pending action")
        void shouldClearPendingAction() {
            Conversation conv = new Conversation();
            Map<String, Object> action = new HashMap<>();
            action.put("waiting_for", "credential:gmail");
            conv.setPendingAction(action);

            conv.clearPendingAction();
            assertThat(conv.hasPendingAction()).isFalse();
            assertThat(conv.getPendingAction()).isNull();
        }
    }

    @Nested
    @DisplayName("Approved services")
    class ApprovedServices {

        @Test
        @DisplayName("should start with empty approved services")
        void shouldStartEmpty() {
            Conversation conv = new Conversation();
            assertThat(conv.getApprovedServices()).isEmpty();
        }

        @Test
        @DisplayName("should approve a single service")
        void shouldApproveService() {
            Conversation conv = new Conversation();
            conv.approveService("gmail");

            assertThat(conv.isServiceApproved("gmail")).isTrue();
            assertThat(conv.isServiceApproved("slack")).isFalse();
        }

        @Test
        @DisplayName("should approve multiple services")
        void shouldApproveMultipleServices() {
            Conversation conv = new Conversation();
            conv.approveServices(Set.of("gmail", "slack"));

            assertThat(conv.isServiceApproved("gmail")).isTrue();
            assertThat(conv.isServiceApproved("slack")).isTrue();
        }

        @Test
        @DisplayName("should revoke service approval")
        void shouldRevokeServiceApproval() {
            Conversation conv = new Conversation();
            conv.approveService("gmail");
            assertThat(conv.isServiceApproved("gmail")).isTrue();

            conv.revokeServiceApproval("gmail");
            assertThat(conv.isServiceApproved("gmail")).isFalse();
        }

        @Test
        @DisplayName("should handle null approved services gracefully")
        void shouldHandleNullApprovedServices() {
            Conversation conv = new Conversation();
            conv.setApprovedServices(null);

            assertThat(conv.getApprovedServices()).isEmpty();
            assertThat(conv.isServiceApproved("gmail")).isFalse();
        }

        @Test
        @DisplayName("approveService should initialize null set")
        void shouldInitializeNullSet() {
            Conversation conv = new Conversation();
            conv.setApprovedServices(null);
            conv.approveService("gmail");

            assertThat(conv.isServiceApproved("gmail")).isTrue();
        }

        @Test
        @DisplayName("revokeServiceApproval should handle null set")
        void shouldHandleRevokeOnNullSet() {
            Conversation conv = new Conversation();
            conv.setApprovedServices(null);

            // Should not throw
            conv.revokeServiceApproval("gmail");
        }
    }

    @Nested
    @DisplayName("Message management")
    class MessageManagement {

        @Test
        @DisplayName("should add message")
        void shouldAddMessage() {
            Conversation conv = new Conversation();
            Message msg = new Message();
            msg.setRole(Message.MessageRole.USER);
            msg.setContent("Hello");

            conv.addMessage(msg);

            assertThat(conv.getMessages()).hasSize(1);
            assertThat(msg.getConversation()).isEqualTo(conv);
        }

        @Test
        @DisplayName("should remove message")
        void shouldRemoveMessage() {
            Conversation conv = new Conversation();
            Message msg = new Message();
            conv.addMessage(msg);
            assertThat(conv.getMessages()).hasSize(1);

            conv.removeMessage(msg);
            assertThat(conv.getMessages()).isEmpty();
            assertThat(msg.getConversation()).isNull();
        }
    }

    @Nested
    @DisplayName("Getters and setters")
    class GettersSetters {

        @Test
        @DisplayName("should get and set all fields")
        void shouldGetAndSetAllFields() {
            Conversation conv = new Conversation();
            conv.setId("id-1");
            conv.setUserId("user-1");
            conv.setTitle("Title");
            conv.setModel("gpt-4");
            conv.setProvider("openai");
            conv.setWorkflowId("wf-1");
            conv.setActive(false);

            assertThat(conv.getId()).isEqualTo("id-1");
            assertThat(conv.getUserId()).isEqualTo("user-1");
            assertThat(conv.getTitle()).isEqualTo("Title");
            assertThat(conv.getModel()).isEqualTo("gpt-4");
            assertThat(conv.getProvider()).isEqualTo("openai");
            assertThat(conv.getWorkflowId()).isEqualTo("wf-1");
            assertThat(conv.getActive()).isFalse();
        }
    }
}
