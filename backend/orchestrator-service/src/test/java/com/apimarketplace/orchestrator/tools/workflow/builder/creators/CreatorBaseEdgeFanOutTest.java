package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connect_after path ({@code add_node(..., connect_after='Decision:if')}) wires
 * edges through {@link CreatorBase#createSimpleEdge} rather than the connect action.
 * It must honour the same "one output port = one target node" rule: a second
 * connect_after onto an already-wired named branch port must NOT silently add a
 * second successor to that port. Port-less sources (trigger, plain step) keep their
 * implicit-fork fan-out.
 */
@DisplayName("CreatorBase.createSimpleEdge - one output port = one target (connect_after path)")
class CreatorBaseEdgeFanOutTest {

    /** Minimal concrete creator exposing the protected edge helper. */
    private static class TestCreator extends CreatorBase {
        void connect(WorkflowBuilderSession session, String from, String to) {
            createSimpleEdge(session, from, to);
        }
    }

    private TestCreator creator;

    @BeforeEach
    void setUp() {
        creator = new TestCreator();
    }

    private WorkflowBuilderSession session() {
        WorkflowBuilderSession s = WorkflowBuilderSession.builder()
                .sessionId("test").tenantId("t").workflowName("W")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        addMcp(s, "Step A");
        addMcp(s, "Step B");
        return s;
    }

    private void addMcp(WorkflowBuilderSession s, String label) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", "mcp:" + WorkflowBuilderSession.normalizeLabel(label));
        n.put("type", "mcp");
        n.put("label", label);
        s.getMcps().add(n);
    }

    private void addDecision(WorkflowBuilderSession s, String label) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", "core:" + WorkflowBuilderSession.normalizeLabel(label));
        n.put("type", "decision");
        n.put("label", label);
        Map<String, Object> ifC = new LinkedHashMap<>(); ifC.put("type", "if");
        Map<String, Object> elseC = new LinkedHashMap<>(); elseC.put("type", "else");
        n.put("decisionConditions", List.of(ifC, elseC));
        s.getCores().add(n);
    }

    private void addTrigger(WorkflowBuilderSession s, String label) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", "trigger:" + WorkflowBuilderSession.normalizeLabel(label));
        n.put("type", "manual");
        n.put("label", label);
        s.getTriggers().add(n);
    }

    private long edgesFrom(WorkflowBuilderSession s, String portedFrom) {
        return s.getEdges().stream().filter(e -> portedFrom.equals(e.get("from"))).count();
    }

    @Test
    @DisplayName("skips a 2nd connect_after onto an already-wired decision port")
    void skipsSecondConnectAfterOnSamePort() {
        WorkflowBuilderSession s = session();
        addDecision(s, "Check");

        creator.connect(s, "Check:if", "Step A");
        creator.connect(s, "Check:if", "Step B"); // same port → must be skipped

        assertThat(edgesFrom(s, "core:check:if"))
                .as("the if port keeps exactly one outgoing edge")
                .isEqualTo(1);
        assertThat(s.getEdges()).hasSize(1);
    }

    @Test
    @DisplayName("allows connect_after onto a different port of the same node (if then else)")
    void allowsDistinctPorts() {
        WorkflowBuilderSession s = session();
        addDecision(s, "Check");

        creator.connect(s, "Check:if", "Step A");
        creator.connect(s, "Check:else", "Step B");

        assertThat(edgesFrom(s, "core:check:if")).isEqualTo(1);
        assertThat(edgesFrom(s, "core:check:else")).isEqualTo(1);
        assertThat(s.getEdges()).hasSize(2);
    }

    @Test
    @DisplayName("preserves implicit fork from a port-less trigger (two connect_after edges allowed)")
    void allowsImplicitForkFromTrigger() {
        WorkflowBuilderSession s = session();
        addTrigger(s, "Start");

        creator.connect(s, "Start", "Step A");
        creator.connect(s, "Start", "Step B");

        assertThat(edgesFrom(s, "trigger:start"))
                .as("a port-less trigger keeps its implicit-fork fan-out")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("still skips an exact duplicate (same port → same target)")
    void skipsExactDuplicate() {
        WorkflowBuilderSession s = session();
        addDecision(s, "Check");

        creator.connect(s, "Check:if", "Step A");
        creator.connect(s, "Check:if", "Step A");

        assertThat(s.getEdges()).hasSize(1);
    }
}
