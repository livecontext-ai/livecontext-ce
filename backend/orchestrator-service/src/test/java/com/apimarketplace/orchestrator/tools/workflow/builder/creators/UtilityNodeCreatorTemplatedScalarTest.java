package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

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
 * add_node used to drop a {{...}} reference written in a numeric or boolean setting: the typed
 * getters return null for it, so the field was omitted or given its default and the reference
 * never reached the plan. The plan parser now keeps such a reference and the node resolves it at
 * run time, so the builder must store it as written.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UtilityNodeCreator - {{...}} in numeric / boolean settings is kept (add_node)")
class UtilityNodeCreatorTemplatedScalarTest {

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
        lenient().when(nodeLibraryService.findByType(anyString())).thenReturn(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastNode() {
        return session.getCores().get(session.getCores().size() - 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> configOf(String nestedKey) {
        return (Map<String, Object>) lastNode().get(nestedKey);
    }

    private Map<String, Object> base(String label) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", label);
        p.put("connect_after", "Start");
        return p;
    }

    @Test
    @DisplayName("limit.count written as a reference is stored as that reference, not the default 10")
    void limitCountKeepsTemplate() {
        Map<String, Object> p = base("Top N");
        p.put("input", "{{trigger:start.output.items}}");
        p.put("count", "{{core:settings.output.page_size}}");

        creator.executeAddLimit(session, p);

        assertThat(configOf("limit")).containsEntry("count", "{{core:settings.output.page_size}}");
    }

    @Test
    @DisplayName("code.timeoutSeconds written as a reference is stored as that reference")
    void codeTimeoutKeepsTemplate() {
        Map<String, Object> p = base("Script");
        p.put("code", "return 1;");
        p.put("timeoutSeconds", "{{core:settings.output.timeout}}");

        creator.executeAddCode(session, p);

        assertThat(configOf("code")).containsEntry("timeoutSeconds", "{{core:settings.output.timeout}}");
    }

    @Test
    @DisplayName("ssh.port written as a reference is stored as that reference")
    void sshPortKeepsTemplate() {
        Map<String, Object> p = base("Run Command");
        p.put("host", "server.example.com");
        p.put("command", "ls");
        p.put("port", "{{core:settings.output.port}}");

        creator.executeAddSsh(session, p);

        assertThat(configOf("ssh")).containsEntry("port", "{{core:settings.output.port}}");
    }

    @Test
    @DisplayName("emailInbox.limit written as a reference is stored as that reference")
    void emailInboxLimitKeepsTemplate() {
        Map<String, Object> p = base("Read Mail");
        p.put("limit", "{{core:settings.output.max}}");

        creator.executeAddEmailInbox(session, p);

        assertThat(configOf("emailInbox")).containsEntry("limit", "{{core:settings.output.max}}");
    }

    @Test
    @DisplayName("wait.duration written as a reference is stored as that reference instead of being refused")
    void waitDurationKeepsTemplate() {
        Map<String, Object> p = base("Pause");
        p.put("duration", "{{core:settings.output.delay_ms}}");

        creator.executeAddWait(session, p);

        assertThat(configOf("wait")).containsEntry("duration", "{{core:settings.output.delay_ms}}");
    }

    @Test
    @DisplayName("a numeric string is still coerced to a number")
    void numericStringStillCoerced() {
        Map<String, Object> p = base("Top N");
        p.put("input", "{{trigger:start.output.items}}");
        p.put("count", "25");

        creator.executeAddLimit(session, p);

        assertThat(configOf("limit")).containsEntry("count", 25);
    }

    @Test
    @DisplayName("a non-reference, non-numeric string is still ignored for the default")
    void nonTemplateStringStillDefaulted() {
        Map<String, Object> p = base("Top N");
        p.put("input", "{{trigger:start.output.items}}");
        p.put("count", "lots");

        creator.executeAddLimit(session, p);

        assertThat(configOf("limit")).containsEntry("count", 10);
    }
}
