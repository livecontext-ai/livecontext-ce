package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.service.AgentTaskService;
import com.apimarketplace.agent.service.ScheduledTaskPromptBuilder;
import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.common.web.TenantResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * LC-066 (review finding r5-1, workflow sink): the workflow task node copies what these internal
 * endpoints return into its step output. A RESTRICTED task read through them must either carry
 * the restricted tag (one task: the node lifts it, the run becomes restricted) or be listed
 * without its text (a list, which is never tagged).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAgentTaskController - RESTRICTED tasks read by a workflow (LC-066)")
class InternalAgentTaskControllerRestrictedTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-A";
    private static final String MAIL_TITLE = "Forward Carol's payslip to accounting";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Mock private ScheduledTaskPromptBuilder scheduledPromptBuilder;
    @Mock private AgentTaskService taskService;
    @Mock private TenantResolver tenantResolver;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;

    private InternalAgentTaskController controller;
    private MockHttpServletRequest req;

    @BeforeEach
    void setUp() {
        controller = new InternalAgentTaskController(
                scheduledPromptBuilder, taskService, tenantResolver,
                agentRepository, taskRepository, noteRepository);
        req = new MockHttpServletRequest();
        when(tenantResolver.resolve(req)).thenReturn(TENANT);
        when(tenantResolver.resolveOrgId(req)).thenReturn(ORG);
    }

    private static AgentTaskEntity task(boolean restricted) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(UUID.randomUUID());
        t.setTenantId(TENANT);
        t.setOrganizationId(ORG);
        t.setTitle(MAIL_TITLE);
        t.setInstructions("salary 5200 EUR");
        t.setStatus(AgentTaskEntity.STATUS_PENDING);
        t.setPriority(AgentTaskEntity.PRIORITY_NORMAL);
        t.setCreatedAt(Instant.now());
        if (restricted) {
            t.setDataSensitivity(DataSensitivity.RESTRICTED.name());
        }
        return t;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asJson(Object body) throws Exception {
        return json.readValue(json.writeValueAsString(body), Map.class);
    }

    @Test
    @DisplayName("get of a RESTRICTED task: its fields plus the restricted tag at the top level")
    void getRestrictedTaskCarriesTheTag() throws Exception {
        AgentTaskEntity t = task(true);
        when(taskRepository.findByIdAndOrganizationIdStrict(t.getId(), ORG)).thenReturn(Optional.of(t));
        when(noteRepository.findByTaskIdOrderByCreatedAtAsc(t.getId())).thenReturn(List.of());

        ResponseEntity<?> resp = controller.getTaskInternal(t.getId(), req);

        Map<String, Object> body = asJson(resp.getBody());
        assertThat(body).containsEntry(DataSensitivity.CREDENTIAL_KEY, "RESTRICTED")
                .containsEntry("id", t.getId().toString())
                .containsEntry("title", MAIL_TITLE);
    }

    @Test
    @DisplayName("get of a NORMAL task: unchanged, no tag")
    void getNormalTaskHasNoTag() throws Exception {
        AgentTaskEntity t = task(false);
        when(taskRepository.findByIdAndOrganizationIdStrict(t.getId(), ORG)).thenReturn(Optional.of(t));
        when(noteRepository.findByTaskIdOrderByCreatedAtAsc(t.getId())).thenReturn(List.of());

        ResponseEntity<?> resp = controller.getTaskInternal(t.getId(), req);

        Map<String, Object> body = asJson(resp.getBody());
        assertThat(body).doesNotContainKey(DataSensitivity.CREDENTIAL_KEY).containsEntry("title", MAIL_TITLE);
    }

    @Test
    @DisplayName("list: a RESTRICTED task is listed without its title or instructions, the list itself untagged")
    @SuppressWarnings("unchecked")
    void listWithholdsRestrictedTaskText() throws Exception {
        AgentTaskEntity restricted = task(true);
        AgentTaskEntity normal = task(false);
        normal.setTitle("Weekly report");
        normal.setInstructions("Summarise the week");
        when(taskRepository.findAllFilteredByOrganizationIdStrict(
                eq(ORG), any(), anyBoolean(), any(), any(), any(), any(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(restricted, normal));
        when(taskRepository.countAllFilteredByOrganizationIdStrict(
                eq(ORG), any(), anyBoolean(), any(), any(), any(), any(), any())).thenReturn(2L);

        ResponseEntity<?> resp = controller.listTasksInternal(null, null, null, null, 50, 0, req);

        String raw = json.writeValueAsString(resp.getBody());
        assertThat(raw).doesNotContain(MAIL_TITLE).doesNotContain("5200 EUR")
                .doesNotContain(DataSensitivity.CREDENTIAL_KEY);
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) asJson(resp.getBody()).get("tasks");
        assertThat(tasks.get(0)).containsEntry("restricted", true).containsEntry("id", restricted.getId().toString());
        assertThat((String) tasks.get(0).get("note")).contains("get_task").contains(restricted.getId().toString());
        assertThat(tasks.get(1)).containsEntry("title", "Weekly report");
    }
}
