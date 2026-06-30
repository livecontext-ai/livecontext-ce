package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.mapper.ConversationMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ConversationQueryService - firstMessagePreview enrichment")
@ExtendWith(MockitoExtension.class)
class ConversationQueryServiceFirstMessagePreviewTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private WorkflowContextProvider workflowContextProvider;

    private ConversationQueryService service;

    @BeforeEach
    void setUp() {
        service = new ConversationQueryService(
                conversationRepository, messageRepository, conversationMapper, workflowContextProvider);
    }

    private Conversation buildConversation(String id, String orgId) {
        Conversation conv = new Conversation();
        conv.setId(id);
        conv.setOrganizationId(orgId);
        conv.setActive(true);
        conv.setMessages(Collections.emptyList());
        return conv;
    }

    private ConversationDto buildDto(String id) {
        ConversationDto dto = new ConversationDto();
        dto.setId(id);
        return dto;
    }

    private List<Object[]> previewRows(Object[]... rows) {
        List<Object[]> list = new ArrayList<>();
        Collections.addAll(list, rows);
        return list;
    }

    @Nested
    @DisplayName("getConversationsByUserId")
    class GetConversationsByUserId {

        @Test
        @DisplayName("Populates firstMessagePreview from batch query results")
        void populatesPreviewFromBatchQuery() {
            String orgId = "org-1";
            Conversation conv = buildConversation("c-1", orgId);
            Page<Conversation> page = new PageImpl<>(List.of(conv));

            when(conversationRepository.findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(eq(orgId), any(Pageable.class)))
                    .thenReturn(page);

            ConversationDto dto = buildDto("c-1");
            when(conversationMapper.toDto(conv)).thenReturn(dto);

            when(messageRepository.findFirstUserMessagePreviewBatch(List.of("c-1")))
                    .thenReturn(previewRows(new Object[]{"c-1", "Hello, can you help me?"}));

            Page<ConversationDto> result = service.getConversationsByUserId("user-1", orgId, 0, 50, false);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getFirstMessagePreview()).isEqualTo("Hello, can you help me?");
        }

        @Test
        @DisplayName("Preview is null when conversation has no user messages")
        void previewNullWhenNoUserMessages() {
            String orgId = "org-1";
            Conversation conv = buildConversation("c-1", orgId);
            Page<Conversation> page = new PageImpl<>(List.of(conv));

            when(conversationRepository.findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(eq(orgId), any(Pageable.class)))
                    .thenReturn(page);
            when(conversationMapper.toDto(conv)).thenReturn(buildDto("c-1"));
            when(messageRepository.findFirstUserMessagePreviewBatch(List.of("c-1")))
                    .thenReturn(Collections.emptyList());

            Page<ConversationDto> result = service.getConversationsByUserId("user-1", orgId, 0, 50, false);

            assertThat(result.getContent().get(0).getFirstMessagePreview()).isNull();
        }

        @Test
        @DisplayName("Blank preview content is excluded")
        void blankPreviewExcluded() {
            String orgId = "org-1";
            Conversation conv = buildConversation("c-1", orgId);
            Page<Conversation> page = new PageImpl<>(List.of(conv));

            when(conversationRepository.findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(eq(orgId), any(Pageable.class)))
                    .thenReturn(page);
            when(conversationMapper.toDto(conv)).thenReturn(buildDto("c-1"));
            when(messageRepository.findFirstUserMessagePreviewBatch(List.of("c-1")))
                    .thenReturn(previewRows(new Object[]{"c-1", "   "}));

            Page<ConversationDto> result = service.getConversationsByUserId("user-1", orgId, 0, 50, false);

            assertThat(result.getContent().get(0).getFirstMessagePreview()).isNull();
        }
    }

    @Nested
    @DisplayName("getConversationById")
    class GetConversationById {

        @Test
        @DisplayName("Populates firstMessagePreview for single conversation")
        void populatesPreviewForSingleConv() {
            String orgId = "org-1";
            Conversation conv = buildConversation("c-1", orgId);

            when(conversationRepository.findByIdAndOrganizationIdStrict("c-1", orgId))
                    .thenReturn(Optional.of(conv));
            when(conversationMapper.toDto(conv)).thenReturn(buildDto("c-1"));
            when(messageRepository.findFirstUserMessagePreviewBatch(List.of("c-1")))
                    .thenReturn(previewRows(new Object[]{"c-1", "What is the weather?"}));

            Optional<ConversationDto> result = service.getConversationById("c-1", "user-1", orgId);

            assertThat(result).isPresent();
            assertThat(result.get().getFirstMessagePreview()).isEqualTo("What is the weather?");
        }
    }

    @Nested
    @DisplayName("searchConversationsByTitle")
    class SearchByTitle {

        @Test
        @DisplayName("Populates firstMessagePreview in title search results")
        void populatesPreviewInTitleSearch() {
            String orgId = "org-1";
            Conversation conv = buildConversation("c-1", orgId);
            Page<Conversation> page = new PageImpl<>(List.of(conv));

            when(conversationRepository.findByOrganizationIdStrictAndTitleContainingIgnoreCase(eq(orgId), eq("test"), any(Pageable.class)))
                    .thenReturn(page);
            when(conversationMapper.toDto(conv)).thenReturn(buildDto("c-1"));
            when(messageRepository.findFirstUserMessagePreviewBatch(List.of("c-1")))
                    .thenReturn(previewRows(new Object[]{"c-1", "Search result preview"}));

            Page<ConversationDto> result = service.searchConversationsByTitle("user-1", orgId, "test", 0, 50);

            assertThat(result.getContent().get(0).getFirstMessagePreview()).isEqualTo("Search result preview");
        }
    }

    @Nested
    @DisplayName("Exception handling")
    class ExceptionHandling {

        @Test
        @DisplayName("Repository exception is swallowed gracefully - preview stays null")
        void repositoryExceptionSwallowed() {
            String orgId = "org-1";
            Conversation conv = buildConversation("c-1", orgId);
            Page<Conversation> page = new PageImpl<>(List.of(conv));

            when(conversationRepository.findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(eq(orgId), any(Pageable.class)))
                    .thenReturn(page);
            when(conversationMapper.toDto(conv)).thenReturn(buildDto("c-1"));
            when(messageRepository.findFirstUserMessagePreviewBatch(any()))
                    .thenThrow(new RuntimeException("DB unavailable"));

            Page<ConversationDto> result = service.getConversationsByUserId("user-1", orgId, 0, 50, false);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getFirstMessagePreview()).isNull();
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Empty page produces no batch query")
        void emptyPageSkipsBatchQuery() {
            String orgId = "org-1";
            Page<Conversation> page = new PageImpl<>(Collections.emptyList());

            when(conversationRepository.findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(eq(orgId), any(Pageable.class)))
                    .thenReturn(page);

            Page<ConversationDto> result = service.getConversationsByUserId("user-1", orgId, 0, 50, false);

            assertThat(result.getContent()).isEmpty();
            verify(messageRepository, never()).findFirstUserMessagePreviewBatch(any());
        }

        @Test
        @DisplayName("Multiple conversations each get their own preview")
        void multipleConversationsGetOwnPreviews() {
            String orgId = "org-1";
            Conversation conv1 = buildConversation("c-1", orgId);
            Conversation conv2 = buildConversation("c-2", orgId);
            Page<Conversation> page = new PageImpl<>(List.of(conv1, conv2));

            when(conversationRepository.findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(eq(orgId), any(Pageable.class)))
                    .thenReturn(page);
            when(conversationMapper.toDto(conv1)).thenReturn(buildDto("c-1"));
            when(conversationMapper.toDto(conv2)).thenReturn(buildDto("c-2"));
            when(messageRepository.findFirstUserMessagePreviewBatch(anyList()))
                    .thenReturn(previewRows(
                            new Object[]{"c-1", "First conv message"},
                            new Object[]{"c-2", "Second conv message"}
                    ));

            Page<ConversationDto> result = service.getConversationsByUserId("user-1", orgId, 0, 50, false);

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent().get(0).getFirstMessagePreview()).isEqualTo("First conv message");
            assertThat(result.getContent().get(1).getFirstMessagePreview()).isEqualTo("Second conv message");
        }
    }
}
