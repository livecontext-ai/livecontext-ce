package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Regression tests for the taskContext ghost-field fix (2026-06-10): the
 * agent's {@code add_node type='task'} path built the task config from an
 * explicit whitelist that silently dropped {@code taskContext}, even though
 * the backend (Core.TaskConfig + TaskNode) supports it and the agent docs
 * advertise it. Pre-fix, the first test fails: the created core's task config
 * has no {@code taskContext} key.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UtilityNodeCreator - add_node task taskContext passthrough")
class UtilityNodeCreatorTaskContextTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private ResponseOptimizer responseOptimizer;
    @Mock private NodeLibraryService nodeLibraryService;
    @Mock private WorkflowRepository workflowRepository;

    private UtilityNodeCreator creator;
    private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        creator = new UtilityNodeCreator(sessionStore, responseOptimizer, nodeLibraryService, workflowRepository);
        session = WorkflowBuilderSession.builder()
            .sessionId("s")
            .tenantId("t")
            .workflowName("w")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        Map<String, Object> trig = new LinkedHashMap<>();
        trig.put("label", "Start");
        trig.put("id", "trigger:start");
        trig.put("type", "webhook");
        session.getTriggers().add(trig);

        lenient().when(nodeLibraryService.findByType(anyString())).thenReturn(Optional.empty());
    }

    private Map<String, Object> baseCreateParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Track Work");
        p.put("operation", "create_task");
        p.put("title", "Review doc");
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstCoreTaskConfig() {
        Map<String, Object> node = session.getCores().get(0);
        return (Map<String, Object>) node.get("task");
    }

    @Test
    @DisplayName("create_task: 'taskContext' map is preserved in the core's task config")
    void taskContextMapIsPreserved() {
        Map<String, Object> p = baseCreateParams();
        p.put("taskContext", Map.of("orderId", "12345", "url", "{{trigger:start.output.url}}"));

        ToolExecutionResult r = creator.executeAddTask(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstCoreTaskConfig().get("taskContext"))
            .isEqualTo(Map.of("orderId", "12345", "url", "{{trigger:start.output.url}}"));
    }

    @Test
    @DisplayName("create_task: snake_case 'task_context' alias is accepted")
    void taskContextSnakeCaseAlias() {
        Map<String, Object> p = baseCreateParams();
        p.put("task_context", Map.of("k", "v"));

        ToolExecutionResult r = creator.executeAddTask(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstCoreTaskConfig().get("taskContext")).isEqualTo(Map.of("k", "v"));
    }

    @Test
    @DisplayName("create_task: non-map or empty taskContext is ignored, node still created")
    void nonMapOrEmptyTaskContextIgnored() {
        Map<String, Object> p1 = baseCreateParams();
        p1.put("taskContext", "not a map");
        ToolExecutionResult r1 = creator.executeAddTask(session, p1);
        assertThat(r1.success()).isTrue();
        assertThat(firstCoreTaskConfig()).doesNotContainKey("taskContext");

        Map<String, Object> p2 = baseCreateParams();
        p2.put("label", "Track Other");
        p2.put("taskContext", Map.of());
        ToolExecutionResult r2 = creator.executeAddTask(session, p2);
        assertThat(r2.success()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> secondTaskConfig = (Map<String, Object>) session.getCores().get(1).get("task");
        assertThat(secondTaskConfig).doesNotContainKey("taskContext");
    }

    @Test
    @DisplayName("create_task: other whitelisted fields are unaffected by the taskContext extraction")
    void otherFieldsUnaffected() {
        Map<String, Object> p = baseCreateParams();
        p.put("priority", "high");
        p.put("taskContext", Map.of("k", "v"));

        ToolExecutionResult r = creator.executeAddTask(session, p);

        assertThat(r.success()).isTrue();
        Map<String, Object> cfg = firstCoreTaskConfig();
        assertThat(cfg.get("operation")).isEqualTo("create_task");
        assertThat(cfg.get("title")).isEqualTo("Review doc");
        assertThat(cfg.get("priority")).isEqualTo("high");
    }
}
