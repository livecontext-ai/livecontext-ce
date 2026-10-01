package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.service.ConversationSharingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The internal share-token checks. {@code /validate/{token}/in-scope} backs publication-service's
 * refusal to file a CONVERSATION share link naming a conversation the caller does not hold; the
 * scope rule itself is pinned in ConversationSharingServiceTest.FindSharedInScope.
 */
@DisplayName("InternalShareValidationController")
class InternalShareValidationControllerTest {

    private ConversationSharingService sharingService;
    private InternalShareValidationController controller;

    @BeforeEach
    void setUp() {
        sharingService = mock(ConversationSharingService.class);
        controller = new InternalShareValidationController(sharingService);
    }

    @Test
    @DisplayName("in-scope: answers the conversation id, asked with the caller's user and workspace")
    void inScopeAnswersConversationId() {
        Conversation conv = new Conversation();
        conv.setId("conv-1");
        when(sharingService.findSharedInScope("cs_abc", "user-1", "org-1")).thenReturn(Optional.of(conv));

        ResponseEntity<Map<String, String>> response = controller.validateInScope("cs_abc", "user-1", "org-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(Map.of("conversationId", "conv-1"));
        verify(sharingService).findSharedInScope("cs_abc", "user-1", "org-1");
    }

    @Test
    @DisplayName("in-scope: 404 when the token opens no conversation of the caller's workspace")
    void inScopeRefusesOutOfScope() {
        when(sharingService.findSharedInScope("cs_abc", "user-2", "org-2")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, String>> response = controller.validateInScope("cs_abc", "user-2", "org-2");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNull();
    }
}
