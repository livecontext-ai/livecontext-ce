package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.service.AgentTaskService;
import com.apimarketplace.agent.service.ScheduledTaskPromptBuilder;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CASA LC-066: orchestrator asks which delegated tasks are RESTRICTED to scrub their titles out of
 * notifications stored before titles were withheld. Ids in, ids out.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAgentTaskController - restricted task ids (LC-066)")
class InternalAgentTaskControllerRestrictedIdsTest {

    @Mock private ScheduledTaskPromptBuilder scheduledPromptBuilder;
    @Mock private AgentTaskService taskService;
    @Mock private TenantResolver tenantResolver;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;

    private InternalAgentTaskController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAgentTaskController(
                scheduledPromptBuilder, taskService, tenantResolver,
                agentRepository, taskRepository, noteRepository);
    }

    @Test
    @DisplayName("answers the RESTRICTED ids among those asked; malformed and duplicate ids are dropped before the query")
    @SuppressWarnings("unchecked")
    void answersRestrictedIds() {
        UUID restricted = UUID.randomUUID();
        UUID normal = UUID.randomUUID();
        when(taskRepository.findRestrictedIdsAmong(List.of(restricted, normal))).thenReturn(List.of(restricted));

        ResponseEntity<?> response = controller.restrictedTaskIds(Map.of("ids",
                List.of(restricted.toString(), normal.toString(), "not-a-uuid", restricted.toString())));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(((Map<String, Object>) response.getBody()).get("restrictedIds")).isEqualTo(List.of(restricted));
    }

    @Test
    @DisplayName("no ids, an empty list, or too many ids: refused or answered without a query")
    void boundsTheRequest() {
        assertThat(controller.restrictedTaskIds(null).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.restrictedTaskIds(Map.of("ids", "x")).getStatusCode().value()).isEqualTo(400);

        List<String> tooMany = new ArrayList<>(Collections.nCopies(
                InternalAgentTaskController.RESTRICTED_IDS_MAX + 1, UUID.randomUUID().toString()));
        assertThat(controller.restrictedTaskIds(Map.of("ids", tooMany)).getStatusCode().value()).isEqualTo(400);

        assertThat(controller.restrictedTaskIds(Map.of("ids", List.of())).getStatusCode().is2xxSuccessful()).isTrue();
        verify(taskRepository, never()).findRestrictedIdsAmong(any());
    }
}
