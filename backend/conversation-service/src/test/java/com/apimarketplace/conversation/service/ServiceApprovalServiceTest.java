package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.service.approval.ServiceApprovalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceApprovalService Tests")
class ServiceApprovalServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    private ServiceApprovalService serviceApprovalService;

    @BeforeEach
    void setUp() {
        serviceApprovalService = new ServiceApprovalService(conversationRepository);
    }

    private Conversation buildConversation(String id) {
        Conversation conv = new Conversation("user-1", "Test", "model", "provider");
        conv.setId(id);
        conv.setApprovedServices(new HashSet<>());
        return conv;
    }

    // ================================================================
    // approveServices()
    // ================================================================

    @Nested
    @DisplayName("approveServices()")
    class ApproveServices {

        @Test
        @DisplayName("should approve services and persist")
        void shouldApproveServicesAndPersist() {
            Conversation conv = buildConversation("conv-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            boolean result = serviceApprovalService.approveServices("conv-1", Set.of("gmail", "slack"));

            assertThat(result).isTrue();
            assertThat(conv.getApprovedServices()).containsExactlyInAnyOrder("gmail", "slack");
            verify(conversationRepository).save(conv);
        }

        @Test
        @DisplayName("should be idempotent when approving already approved service")
        void shouldBeIdempotent() {
            Conversation conv = buildConversation("conv-1");
            conv.approveService("gmail");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            boolean result = serviceApprovalService.approveServices("conv-1", Set.of("gmail"));

            assertThat(result).isTrue();
            assertThat(conv.getApprovedServices()).containsExactly("gmail");
            verify(conversationRepository).save(conv);
        }

        @Test
        @DisplayName("should return false when conversation not found")
        void shouldReturnFalseWhenNotFound() {
            when(conversationRepository.findById("missing")).thenReturn(Optional.empty());

            boolean result = serviceApprovalService.approveServices("missing", Set.of("gmail"));

            assertThat(result).isFalse();
            verify(conversationRepository, never()).save(any());
        }

        @Test
        @DisplayName("should return false for null conversationId")
        void shouldReturnFalseForNullConversationId() {
            boolean result = serviceApprovalService.approveServices(null, Set.of("gmail"));

            assertThat(result).isFalse();
            verify(conversationRepository, never()).findById(any());
        }

        @Test
        @DisplayName("should return false for null services set")
        void shouldReturnFalseForNullServices() {
            boolean result = serviceApprovalService.approveServices("conv-1", null);

            assertThat(result).isFalse();
            verify(conversationRepository, never()).findById(any());
        }

        @Test
        @DisplayName("should return false for empty services set")
        void shouldReturnFalseForEmptyServices() {
            boolean result = serviceApprovalService.approveServices("conv-1", Set.of());

            assertThat(result).isFalse();
            verify(conversationRepository, never()).findById(any());
        }
    }

    // ================================================================
    // approveService() - single service
    // ================================================================

    @Nested
    @DisplayName("approveService()")
    class ApproveService {

        @Test
        @DisplayName("should approve a single service")
        void shouldApproveSingleService() {
            Conversation conv = buildConversation("conv-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            boolean result = serviceApprovalService.approveService("conv-1", "gmail");

            assertThat(result).isTrue();
            assertThat(conv.getApprovedServices()).contains("gmail");
        }
    }

    // ================================================================
    // getApprovedServices()
    // ================================================================

    @Nested
    @DisplayName("getApprovedServices()")
    class GetApprovedServices {

        @Test
        @DisplayName("should return correct approved services set")
        void shouldReturnCorrectSet() {
            Conversation conv = buildConversation("conv-1");
            conv.approveService("gmail");
            conv.approveService("slack");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            Set<String> approved = serviceApprovalService.getApprovedServices("conv-1");

            assertThat(approved).containsExactlyInAnyOrder("gmail", "slack");
        }

        @Test
        @DisplayName("should return empty set when no services approved")
        void shouldReturnEmptySetWhenNoApprovals() {
            Conversation conv = buildConversation("conv-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            Set<String> approved = serviceApprovalService.getApprovedServices("conv-1");

            assertThat(approved).isEmpty();
        }

        @Test
        @DisplayName("should return empty set when conversation not found")
        void shouldReturnEmptySetWhenNotFound() {
            when(conversationRepository.findById("missing")).thenReturn(Optional.empty());

            Set<String> approved = serviceApprovalService.getApprovedServices("missing");

            assertThat(approved).isEmpty();
        }

        @Test
        @DisplayName("should return empty set for null conversationId")
        void shouldReturnEmptySetForNull() {
            Set<String> approved = serviceApprovalService.getApprovedServices(null);

            assertThat(approved).isEmpty();
        }
    }

    // ================================================================
    // isServiceApproved()
    // ================================================================

    @Nested
    @DisplayName("isServiceApproved()")
    class IsServiceApproved {

        @Test
        @DisplayName("should return true for approved service")
        void shouldReturnTrueForApproved() {
            Conversation conv = buildConversation("conv-1");
            conv.approveService("gmail");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            assertThat(serviceApprovalService.isServiceApproved("conv-1", "gmail")).isTrue();
        }

        @Test
        @DisplayName("should return false for non-approved service")
        void shouldReturnFalseForNonApproved() {
            Conversation conv = buildConversation("conv-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            assertThat(serviceApprovalService.isServiceApproved("conv-1", "gmail")).isFalse();
        }

        @Test
        @DisplayName("should return false for null parameters")
        void shouldReturnFalseForNullParams() {
            assertThat(serviceApprovalService.isServiceApproved(null, "gmail")).isFalse();
            assertThat(serviceApprovalService.isServiceApproved("conv-1", null)).isFalse();
        }
    }

    // ================================================================
    // revokeServiceApproval()
    // ================================================================

    @Nested
    @DisplayName("revokeServiceApproval()")
    class RevokeServiceApproval {

        @Test
        @DisplayName("should revoke an approved service")
        void shouldRevokeApprovedService() {
            Conversation conv = buildConversation("conv-1");
            conv.approveService("gmail");
            conv.approveService("slack");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            boolean result = serviceApprovalService.revokeServiceApproval("conv-1", "gmail");

            assertThat(result).isTrue();
            assertThat(conv.getApprovedServices()).containsExactly("slack");
            verify(conversationRepository).save(conv);
        }

        @Test
        @DisplayName("should handle revoking non-approved service gracefully")
        void shouldHandleRevokeNonApproved() {
            Conversation conv = buildConversation("conv-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            boolean result = serviceApprovalService.revokeServiceApproval("conv-1", "unknown");

            assertThat(result).isTrue();
            verify(conversationRepository).save(conv);
        }

        @Test
        @DisplayName("should return false when conversation not found")
        void shouldReturnFalseWhenNotFound() {
            when(conversationRepository.findById("missing")).thenReturn(Optional.empty());

            boolean result = serviceApprovalService.revokeServiceApproval("missing", "gmail");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false for null parameters")
        void shouldReturnFalseForNullParams() {
            assertThat(serviceApprovalService.revokeServiceApproval(null, "gmail")).isFalse();
            assertThat(serviceApprovalService.revokeServiceApproval("conv-1", null)).isFalse();
        }
    }

    // ================================================================
    // clearAllApprovals()
    // ================================================================

    @Nested
    @DisplayName("clearAllApprovals()")
    class ClearAllApprovals {

        @Test
        @DisplayName("should clear all approved services")
        void shouldClearAllApprovals() {
            Conversation conv = buildConversation("conv-1");
            conv.approveService("gmail");
            conv.approveService("slack");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            serviceApprovalService.clearAllApprovals("conv-1");

            assertThat(conv.getApprovedServices()).isEmpty();
            verify(conversationRepository).save(conv);
        }

        @Test
        @DisplayName("should handle null conversationId gracefully")
        void shouldHandleNullConversationId() {
            serviceApprovalService.clearAllApprovals(null);

            verify(conversationRepository, never()).findById(any());
        }
    }
}
