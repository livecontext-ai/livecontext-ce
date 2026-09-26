package com.apimarketplace.orchestrator.execution.v2.nodes;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Regression (prod 2026-09-25): when a provider refused the credential (HTTP 401/403), StepNode
 * re-logged at ERROR a refusal the catalogue had already logged at its source, at the level that
 * fits who owns the credential (21 duplicate ERROR lines in a week for one expired OAuth token).
 * The step still FAILS exactly as before; only the duplicate log line changes level. Any other
 * failure keeps ERROR.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepNode - a provider credential refusal is not re-logged at ERROR")
class StepNodeProviderRefusalLogLevelTest {

    @Mock private WorkflowPlan plan;
    @Mock private ToolsGateway toolsGateway;

    private final Logger logger = (Logger) LoggerFactory.getLogger(StepNode.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
        context = ExecutionContext.create("run-1", "workflow-run-1", "tenant-1", "item-1", 0,
                new HashMap<>(), plan);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private NodeExecutionResult runFailingStep(Map<String, Object> output, String error) {
        StepNode node = new StepNode("mcp:gmail_send", new Step("tool-1", "mcp", "Gmail Send",
                null, Map.of(), null, null, null));
        node.setToolsGateway(toolsGateway);
        when(toolsGateway.executeTool(any(ToolRef.class), any(), eq("tenant-1"), any()))
                .thenReturn(new ExecutionResult(false, output, List.of(Map.of("message", error)), List.of()));
        return node.execute(context);
    }

    @Test
    @DisplayName("provider 403 (e.g. insufficient scopes): step fails, logged WARN, no ERROR")
    void forbiddenIsWarn() {
        NodeExecutionResult result = runFailingStep(Map.of("http_status", 403),
                "Access forbidden for gmail: Request had insufficient authentication scopes.");

        assertThat(result.isFailure()).isTrue();
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("insufficient authentication scopes"));
    }

    @Test
    @DisplayName("provider 401 (expired token): step fails, no ERROR")
    void unauthorizedIsWarn() {
        NodeExecutionResult result = runFailingStep(Map.of("http_status", 401),
                "OAuth access token has expired. Re-authenticate to continue.");

        assertThat(result.isFailure()).isTrue();
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
    }

    @Test
    @DisplayName("any other failure (provider 500, no status) keeps ERROR")
    void otherFailuresStayError() {
        runFailingStep(Map.of("http_status", 500), "Internal Server Error");
        runFailingStep(Map.of(), "Tool error");

        assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("isProviderCredentialRefusal reads only a numeric 401/403 http_status")
    void classifierShape() {
        assertThat(StepNode.isProviderCredentialRefusal(Map.of("http_status", 401))).isTrue();
        assertThat(StepNode.isProviderCredentialRefusal(Map.of("http_status", 403))).isTrue();
        assertThat(StepNode.isProviderCredentialRefusal(Map.of("http_status", 404))).isFalse();
        assertThat(StepNode.isProviderCredentialRefusal(Map.of("http_status", "403"))).isFalse();
        assertThat(StepNode.isProviderCredentialRefusal(Map.of())).isFalse();
        assertThat(StepNode.isProviderCredentialRefusal(null)).isFalse();
    }
}
