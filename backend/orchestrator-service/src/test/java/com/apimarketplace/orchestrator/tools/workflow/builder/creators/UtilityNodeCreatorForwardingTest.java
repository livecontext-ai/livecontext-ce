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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for two build-params the creator used to drop silently, even
 * though the runtime node requires/supports them:
 * <ul>
 *   <li>{@code limit.input} - LimitNode fails at runtime ("Input expression is
 *       required") when it is absent, so a limit node built without it always
 *       failed. The creator now forwards it into the limit config.</li>
 *   <li>{@code respond_to_webhook.headers} - RespondToWebhookNode applies these
 *       custom response headers; the creator now forwards them.</li>
 * </ul>
 * Pre-fix, the config maps carried no {@code input} / {@code headers} key.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UtilityNodeCreator - build-param forwarding (limit.input, respond_to_webhook.headers)")
class UtilityNodeCreatorForwardingTest {

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
            .sessionId("s").tenantId("t").workflowName("w")
            .createdAt(Instant.now()).updatedAt(Instant.now())
            .build();
        Map<String, Object> trig = new LinkedHashMap<>();
        trig.put("label", "Start");
        trig.put("id", "trigger:start");
        trig.put("type", "webhook");
        session.getTriggers().add(trig);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstConfig(String configKey) {
        Map<String, Object> node = session.getCores().get(0);
        return (Map<String, Object>) node.get(configKey);
    }

    @Test
    @DisplayName("limit forwards the required 'input' expression into the limit config")
    void limitForwardsInput() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Top 5");
        p.put("count", 5);
        p.put("from", "first");
        p.put("input", "{{core:sort.output.items}}");

        ToolExecutionResult r = creator.executeAddLimit(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstConfig("limit").get("input")).isEqualTo("{{core:sort.output.items}}");
    }

    @Test
    @DisplayName("limit accepts the 'items' alias for input")
    void limitAcceptsItemsAlias() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Top 3");
        p.put("count", 3);
        p.put("items", "{{core:fetch.output.items}}");

        creator.executeAddLimit(session, p);

        assertThat(firstConfig("limit").get("input")).isEqualTo("{{core:fetch.output.items}}");
    }

    @Test
    @DisplayName("limit omits 'input' from config when not provided (no empty key)")
    void limitOmitsInputWhenAbsent() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "No Input");
        p.put("count", 5);

        creator.executeAddLimit(session, p);

        assertThat(firstConfig("limit")).doesNotContainKey("input");
    }

    @Test
    @DisplayName("respond_to_webhook forwards custom 'headers' into the config")
    void respondToWebhookForwardsHeaders() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Send Response");
        p.put("statusCode", 200);
        p.put("body", "ok");
        p.put("headers", Map.of("X-Custom", "value"));

        ToolExecutionResult r = creator.executeAddRespondToWebhook(session, p);

        assertThat(r.success()).isTrue();
        Object headers = firstConfig("respondToWebhook").get("headers");
        assertThat(headers).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) headers).containsEntry("X-Custom", "value");
    }

    @Test
    @DisplayName("respond_to_webhook omits 'headers' when not provided or empty")
    void respondToWebhookOmitsEmptyHeaders() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "No Headers");
        p.put("statusCode", 200);
        p.put("headers", Map.of());

        creator.executeAddRespondToWebhook(session, p);

        assertThat(firstConfig("respondToWebhook")).doesNotContainKey("headers");
    }
}
