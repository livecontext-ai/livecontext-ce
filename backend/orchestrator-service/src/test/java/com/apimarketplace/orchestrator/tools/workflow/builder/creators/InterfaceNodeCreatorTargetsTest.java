package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link InterfaceNodeCreator#buildAvailableActionTargets(WorkflowBuilderSession)}.
 *
 * <p>This list is what the agent reads back after add_node - turning the value-token problem
 * from "recall/invent the grammar" into "pick one from this enumerated list". Regression target
 * for the field-rename hallucination bug (agent passed {trigger:..., mapping:{...}} object).
 */
@DisplayName("InterfaceNodeCreator.buildAvailableActionTargets")
class InterfaceNodeCreatorTargetsTest {

    private static WorkflowBuilderSession freshSession() {
        return WorkflowBuilderSession.create("tenant-1", "conv-1", "Test Workflow", "desc");
    }

    private static Map<String, Object> trigger(String label, String type) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("label", label);
        t.put("type", type);
        return t;
    }

    private static Map<String, Object> iface(String label) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("label", label);
        i.put("type", "interface");
        return i;
    }

    @Test
    @DisplayName("Form trigger maps to :submit suffix")
    void formTriggerMapsToSubmit() {
        WorkflowBuilderSession session = freshSession();
        session.getTriggers().add(trigger("Search Criteria", "form"));

        Map<String, Object> targets = InterfaceNodeCreator.buildAvailableActionTargets(session);

        @SuppressWarnings("unchecked")
        List<String> triggers = (List<String>) targets.get("triggers");
        assertThat(triggers).containsExactly("trigger:search_criteria:submit");
    }

    @Test
    @DisplayName("Chat trigger maps to :message suffix")
    void chatTriggerMapsToMessage() {
        WorkflowBuilderSession session = freshSession();
        session.getTriggers().add(trigger("My Chat", "chat"));

        Map<String, Object> targets = InterfaceNodeCreator.buildAvailableActionTargets(session);

        @SuppressWarnings("unchecked")
        List<String> triggers = (List<String>) targets.get("triggers");
        assertThat(triggers).containsExactly("trigger:my_chat:message");
    }

    @Test
    @DisplayName("Manual trigger maps to :click suffix")
    void manualTriggerMapsToClick() {
        WorkflowBuilderSession session = freshSession();
        session.getTriggers().add(trigger("Run Now", "manual"));

        Map<String, Object> targets = InterfaceNodeCreator.buildAvailableActionTargets(session);

        @SuppressWarnings("unchecked")
        List<String> triggers = (List<String>) targets.get("triggers");
        assertThat(triggers).containsExactly("trigger:run_now:click");
    }

    @Test
    @DisplayName("Interface labels emit :navigate tokens")
    void interfaceLabelsEmitNavigate() {
        WorkflowBuilderSession session = freshSession();
        session.getInterfaces().add(iface("Dashboard"));
        session.getInterfaces().add(iface("Settings Page"));

        Map<String, Object> targets = InterfaceNodeCreator.buildAvailableActionTargets(session);

        @SuppressWarnings("unchecked")
        List<String> interfaces = (List<String>) targets.get("interfaces");
        assertThat(interfaces).containsExactly(
            "interface:dashboard:navigate",
            "interface:settings_page:navigate"
        );
    }

    @Test
    @DisplayName("Control tokens always present and complete")
    void controlTokensAlwaysPresent() {
        WorkflowBuilderSession session = freshSession();

        Map<String, Object> targets = InterfaceNodeCreator.buildAvailableActionTargets(session);

        @SuppressWarnings("unchecked")
        List<String> control = (List<String>) targets.get("control");
        assertThat(control).containsExactly(
            "__continue",
            "__pagination:next",
            "__pagination:prev",
            "__pagination:first",
            "__pagination:last"
        );
    }

    @Test
    @DisplayName("Empty session: no triggers, no interfaces, only control tokens")
    void emptySessionHasOnlyControlTokens() {
        WorkflowBuilderSession session = freshSession();

        Map<String, Object> targets = InterfaceNodeCreator.buildAvailableActionTargets(session);

        @SuppressWarnings("unchecked")
        List<String> triggers = (List<String>) targets.get("triggers");
        @SuppressWarnings("unchecked")
        List<String> interfaces = (List<String>) targets.get("interfaces");
        assertThat(triggers).isEmpty();
        assertThat(interfaces).isEmpty();
        assertThat(targets).containsKey("control");
        assertThat(targets).containsKey("usage");
    }

    @Test
    @DisplayName("Usage hint is included and explains how to read the lists")
    void usageHintExplainsHowToRead() {
        WorkflowBuilderSession session = freshSession();

        Map<String, Object> targets = InterfaceNodeCreator.buildAvailableActionTargets(session);

        String usage = (String) targets.get("usage");
        assertThat(usage).isNotNull();
        assertThat(usage).contains("Pick ONE token");
        assertThat(usage).contains("Keys MUST start with '#'");
    }

    @Test
    @DisplayName("Triggers, interfaces, and control are returned together for the same workflow")
    void allThreeBucketsReturnedTogether() {
        WorkflowBuilderSession session = freshSession();
        session.getTriggers().add(trigger("Search", "form"));
        session.getInterfaces().add(iface("Results"));

        Map<String, Object> targets = InterfaceNodeCreator.buildAvailableActionTargets(session);

        @SuppressWarnings("unchecked")
        List<String> triggers = (List<String>) targets.get("triggers");
        @SuppressWarnings("unchecked")
        List<String> interfaces = (List<String>) targets.get("interfaces");
        @SuppressWarnings("unchecked")
        List<String> control = (List<String>) targets.get("control");
        assertThat(triggers).containsExactly("trigger:search:submit");
        assertThat(interfaces).containsExactly("interface:results:navigate");
        assertThat(control).contains("__continue", "__pagination:next");
    }

    @Test
    @DisplayName("Triggers without explicit type fall back to :submit (legacy)")
    void triggerWithoutTypeDefaultsToSubmit() {
        WorkflowBuilderSession session = freshSession();
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("label", "Legacy Trigger");
        // intentionally no "type" key
        session.getTriggers().add(t);

        Map<String, Object> targets = InterfaceNodeCreator.buildAvailableActionTargets(session);

        @SuppressWarnings("unchecked")
        List<String> triggers = (List<String>) targets.get("triggers");
        assertThat(triggers).containsExactly("trigger:legacy_trigger:submit");
    }
}
