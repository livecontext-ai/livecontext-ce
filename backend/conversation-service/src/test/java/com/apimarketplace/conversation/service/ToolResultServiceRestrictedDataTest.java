package com.apimarketplace.conversation.service;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.conversation.entity.ToolResult;
import com.apimarketplace.conversation.repository.ToolResultRepository;
import com.apimarketplace.conversation.service.ai.ConversationAgentServiceTurnSensitivityAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LC-066: a chat tool result from a Google restricted-scope integration is persisted RESTRICTED,
 * which excludes it from search, bounds its retention and marks the conversation for the
 * provider allow-list.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolResultService restricted-data classification")
class ToolResultServiceRestrictedDataTest {

    @Mock private ToolResultRepository repository;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private ConversationQueryService conversationQueryService;

    private ToolResultService service;

    @BeforeEach
    void setUp() {
        service = new ToolResultService(repository, breakdownService, conversationQueryService);
        org.mockito.Mockito.lenient().when(repository.save(any(ToolResult.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ToolResult saved() {
        ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a Gmail result (iconSlug in the metadata) is tagged RESTRICTED")
    void gmailResultIsRestricted() {
        service.save("conv-1", "tenant-1", "catalog", "call-1", true, 10L,
                "{\"snippet\":\"wire 45000 EUR\"}", null, Map.of("iconSlug", "gmail"));

        assertThat(saved().getDataSensitivity()).isEqualTo("RESTRICTED");
    }

    @Test
    @DisplayName("any other result, or one with no metadata, stays NORMAL")
    void otherResultsStayNormal() {
        service.save("conv-1", "tenant-1", "web_search", "call-2", true, 10L, "{}", null,
                Map.of("iconSlug", "github"));

        assertThat(saved().getDataSensitivity()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("the assistant message of a turn is RESTRICTED as soon as one of its tool results is")
    void turnSensitivity() {
        assertThat(ConversationAgentServiceTurnSensitivityAccess.turnSensitivity(List.of(
                Map.of("metadata", Map.of("iconSlug", "slack")),
                Map.of("metadata", Map.of("iconSlug", "googledrive"))))).isEqualTo("RESTRICTED");
        assertThat(ConversationAgentServiceTurnSensitivityAccess.turnSensitivity(List.of(
                Map.of("metadata", Map.of("iconSlug", "slack"))))).isEqualTo("NORMAL");
        assertThat(ConversationAgentServiceTurnSensitivityAccess.turnSensitivity(null)).isEqualTo("NORMAL");
    }
}
