package com.apimarketplace.conversation.service.approval;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("ServiceApprovalService")
@ExtendWith(MockitoExtension.class)
class ServiceApprovalServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @InjectMocks
    private ServiceApprovalService serviceApprovalService;

    @Nested
    @DisplayName("isServiceApproved")
    class IsServiceApproved {

        @Test
        @DisplayName("should return false for null conversationId")
        void shouldReturnFalseForNullConversationId() {
            assertThat(serviceApprovalService.isServiceApproved(null, "gmail")).isFalse();
        }

        @Test
        @DisplayName("should return false for null serviceType")
        void shouldReturnFalseForNullServiceType() {
            assertThat(serviceApprovalService.isServiceApproved("conv-1", null)).isFalse();
        }

        @Test
        @DisplayName("should return true when service is approved")
        void shouldReturnTrueWhenApproved() {
            Conversation conv = new Conversation();
            conv.approveService("gmail");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            assertThat(serviceApprovalService.isServiceApproved("conv-1", "gmail")).isTrue();
        }

        @Test
        @DisplayName("should return false when service is not approved")
        void shouldReturnFalseWhenNotApproved() {
            Conversation conv = new Conversation();
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            assertThat(serviceApprovalService.isServiceApproved("conv-1", "gmail")).isFalse();
        }

        @Test
        @DisplayName("should return false when conversation not found")
        void shouldReturnFalseWhenNotFound() {
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.empty());

            assertThat(serviceApprovalService.isServiceApproved("conv-1", "gmail")).isFalse();
        }
    }

    @Nested
    @DisplayName("getApprovedServices")
    class GetApprovedServices {

        @Test
        @DisplayName("should return empty set for null conversationId")
        void shouldReturnEmptyForNull() {
            assertThat(serviceApprovalService.getApprovedServices(null)).isEmpty();
        }

        @Test
        @DisplayName("should return approved services")
        void shouldReturnApprovedServices() {
            Conversation conv = new Conversation();
            conv.approveService("gmail");
            conv.approveService("slack");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            Set<String> result = serviceApprovalService.getApprovedServices("conv-1");

            assertThat(result).containsExactlyInAnyOrder("gmail", "slack");
        }

        @Test
        @DisplayName("should return empty set when conversation not found")
        void shouldReturnEmptyWhenNotFound() {
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.empty());

            assertThat(serviceApprovalService.getApprovedServices("conv-1")).isEmpty();
        }
    }

    @Nested
    @DisplayName("approveService")
    class ApproveService {

        @Test
        @DisplayName("should approve a single service")
        void shouldApproveSingleService() {
            Conversation conv = new Conversation();
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));
            when(conversationRepository.save(any(Conversation.class))).thenReturn(conv);

            boolean result = serviceApprovalService.approveService("conv-1", "gmail");

            assertThat(result).isTrue();
            verify(conversationRepository).save(conv);
        }
    }

    @Nested
    @DisplayName("approveServices")
    class ApproveServices {

        @Test
        @DisplayName("should return false for null conversationId")
        void shouldReturnFalseForNullConversationId() {
            assertThat(serviceApprovalService.approveServices(null, Set.of("gmail"))).isFalse();
        }

        @Test
        @DisplayName("should return false for null services")
        void shouldReturnFalseForNullServices() {
            assertThat(serviceApprovalService.approveServices("conv-1", null)).isFalse();
        }

        @Test
        @DisplayName("should return false for empty services")
        void shouldReturnFalseForEmptyServices() {
            assertThat(serviceApprovalService.approveServices("conv-1", Set.of())).isFalse();
        }

        @Test
        @DisplayName("should return false when conversation not found")
        void shouldReturnFalseWhenNotFound() {
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.empty());

            assertThat(serviceApprovalService.approveServices("conv-1", Set.of("gmail"))).isFalse();
        }

        @Test
        @DisplayName("should approve multiple services and save")
        void shouldApproveMultipleServices() {
            Conversation conv = new Conversation();
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));
            when(conversationRepository.save(any(Conversation.class))).thenReturn(conv);

            boolean result = serviceApprovalService.approveServices("conv-1", Set.of("gmail", "slack"));

            assertThat(result).isTrue();
            verify(conversationRepository).save(conv);
        }
    }

    @Nested
    @DisplayName("revokeServiceApproval")
    class RevokeServiceApproval {

        @Test
        @DisplayName("should return false for null conversationId")
        void shouldReturnFalseForNullConversationId() {
            assertThat(serviceApprovalService.revokeServiceApproval(null, "gmail")).isFalse();
        }

        @Test
        @DisplayName("should return false for null serviceType")
        void shouldReturnFalseForNullServiceType() {
            assertThat(serviceApprovalService.revokeServiceApproval("conv-1", null)).isFalse();
        }

        @Test
        @DisplayName("should return false when conversation not found")
        void shouldReturnFalseWhenNotFound() {
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.empty());

            assertThat(serviceApprovalService.revokeServiceApproval("conv-1", "gmail")).isFalse();
        }

        @Test
        @DisplayName("should revoke service and save")
        void shouldRevokeAndSave() {
            Conversation conv = new Conversation();
            conv.approveService("gmail");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));
            when(conversationRepository.save(any(Conversation.class))).thenReturn(conv);

            boolean result = serviceApprovalService.revokeServiceApproval("conv-1", "gmail");

            assertThat(result).isTrue();
            verify(conversationRepository).save(conv);
        }
    }

    @Nested
    @DisplayName("clearAllApprovals")
    class ClearAllApprovals {

        @Test
        @DisplayName("should do nothing for null conversationId")
        void shouldDoNothingForNull() {
            serviceApprovalService.clearAllApprovals(null);
            verify(conversationRepository, never()).findById(any());
        }

        @Test
        @DisplayName("should clear approvals when conversation found")
        void shouldClearApprovals() {
            Conversation conv = new Conversation();
            conv.approveService("gmail");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));
            when(conversationRepository.save(any(Conversation.class))).thenReturn(conv);

            serviceApprovalService.clearAllApprovals("conv-1");

            verify(conversationRepository).save(conv);
        }

        @Test
        @DisplayName("should do nothing when conversation not found")
        void shouldDoNothingWhenNotFound() {
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.empty());

            serviceApprovalService.clearAllApprovals("conv-1");

            verify(conversationRepository, never()).save(any());
        }
    }
}
