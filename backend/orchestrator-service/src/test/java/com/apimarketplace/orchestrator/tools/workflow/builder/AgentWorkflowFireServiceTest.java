package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.EditorRunResolver;
import com.apimarketplace.orchestrator.services.StepOutputService;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AgentWorkflowFireService}.
 *
 * Covers:
 * - createRun: editor-style run creation (always new, with planVersion, __editorRun__)
 * - resolveTrigger: selection by hint or first fireable
 * - validateFireable: allowed vs forbidden trigger types
 * - buildTriggerExecuteInfo: per-type schema generation from session maps
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentWorkflowFireService")
class AgentWorkflowFireServiceTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowExecutionService executionService;
    @Mock private ReusableTriggerService reusableTriggerService;
    @Mock private SignalWaitRepository signalWaitRepository;
    @Mock private EditorRunResolver editorRunResolver;
    @Mock private StepOutputService stepOutputService;
    @Mock private WorkflowStepDataRepository stepDataRepository;
    @Mock private WorkflowEpochService epochService;

    private AgentWorkflowFireService service;

    private static final UUID WORKFLOW_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String RUN_ID_PUBLIC = "run-abc-123";
    private static final String TENANT_ID = "user@test.com";

    @BeforeEach
    void setUp() {
        service = new AgentWorkflowFireService(
                runRepository, executionService, reusableTriggerService,
                signalWaitRepository, editorRunResolver, new ObjectMapper(),
                stepOutputService, stepDataRepository, epochService);
    }

    // ==================== createRun - editor-style run creation ====================

    @Nested
    @DisplayName("createRun - delegates to EditorRunResolver")
    class CreateRunTests {

        @Mock private WorkflowEntity workflow;
        @Mock private WorkflowPlan plan;
        @Mock private WorkflowRunEntity resolvedRun;

        @BeforeEach
        void initWorkflow() {
            lenient().when(workflow.getId()).thenReturn(WORKFLOW_ID);
            lenient().when(plan.getOriginalPlan()).thenReturn(Map.of("name", "test"));
        }

        @Test
        @DisplayName("Delegates to EditorRunResolver and returns the resolved run")
        void delegatesToResolver() {
            when(editorRunResolver.findOrCreateRun(eq(workflow), eq(plan), any(), eq(TENANT_ID), eq(ExecutionMode.AUTOMATIC)))
                    .thenReturn(new EditorRunResolver.Resolution(resolvedRun, null, 7, false));

            WorkflowRunEntity result = service.createRun(workflow, plan, Map.of(), TENANT_ID);

            assertThat(result).isSameAs(resolvedRun);
            verify(editorRunResolver).findOrCreateRun(eq(workflow), eq(plan), any(), eq(TENANT_ID), eq(ExecutionMode.AUTOMATIC));
        }

        @Test
        @DisplayName("Returns reused run when resolver finds WAITING_TRIGGER run")
        void returnsReusedRun() {
            when(editorRunResolver.findOrCreateRun(eq(workflow), eq(plan), any(), eq(TENANT_ID), eq(ExecutionMode.AUTOMATIC)))
                    .thenReturn(new EditorRunResolver.Resolution(resolvedRun, null, 7, true));

            WorkflowRunEntity result = service.createRun(workflow, plan, Map.of(), TENANT_ID);

            assertThat(result).isSameAs(resolvedRun);
        }

        @Test
        @DisplayName("Passes dataInputs to resolver")
        void passesDataInputs() {
            Map<String, Object> dataInputs = Map.of("key", "value");
            when(editorRunResolver.findOrCreateRun(eq(workflow), eq(plan), eq(dataInputs), eq(TENANT_ID), eq(ExecutionMode.AUTOMATIC)))
                    .thenReturn(new EditorRunResolver.Resolution(resolvedRun, null, 1, false));

            service.createRun(workflow, plan, dataInputs, TENANT_ID);

            verify(editorRunResolver).findOrCreateRun(eq(workflow), eq(plan), eq(dataInputs), eq(TENANT_ID), eq(ExecutionMode.AUTOMATIC));
        }
    }

    // ==================== resolveTrigger ====================

    @Nested
    @DisplayName("resolveTrigger - trigger selection by type and hint")
    class ResolveTriggerTests {

        @Mock private WorkflowPlan plan;

        /** Helper to build a minimal mock Trigger. */
        private Trigger trigger(String type, String label) {
            Trigger t = mock(Trigger.class);
            lenient().when(t.type()).thenReturn(type);
            lenient().when(t.getNormalizedKey()).thenReturn("trigger:" + label.toLowerCase().replace(" ", "_"));
            lenient().when(t.label()).thenReturn(label);
            return t;
        }

        @Test
        @DisplayName("No hint - returns first fireable trigger")
        void noHint_returnsFirstFireable() {
            Trigger webhook = trigger("webhook", "Webhook");
            when(plan.getTriggers()).thenReturn(List.of(webhook));

            assertThat(service.resolveTrigger(plan, null)).isSameAs(webhook);
        }

        @Test
        @DisplayName("No hint - skips non-fireable types, returns first fireable")
        void noHint_skipsWorkflowTrigger() {
            Trigger wf = trigger("workflow", "Parent");
            Trigger manual = trigger("manual", "Start");
            when(plan.getTriggers()).thenReturn(List.of(wf, manual));

            assertThat(service.resolveTrigger(plan, null)).isSameAs(manual);
        }

        @Test
        @DisplayName("Hint with prefix - returns matching trigger")
        void hintWithPrefix_returnsMatchingTrigger() {
            Trigger chat = trigger("chat", "chat");
            Trigger webhook = trigger("webhook", "webhook");
            when(plan.getTriggers()).thenReturn(List.of(chat, webhook));

            assertThat(service.resolveTrigger(plan, "trigger:webhook")).isSameAs(webhook);
        }

        @Test
        @DisplayName("Hint without prefix - auto-prefixes and matches")
        void hintWithoutPrefix_autoPrefix() {
            Trigger manual = trigger("manual", "manual");
            when(plan.getTriggers()).thenReturn(List.of(manual));

            assertThat(service.resolveTrigger(plan, "manual")).isSameAs(manual);
        }

        @Test
        @DisplayName("Unknown hint - throws with informative message")
        void unknownHint_throws() {
            Trigger webhook = trigger("webhook", "webhook");
            when(plan.getTriggers()).thenReturn(List.of(webhook));

            assertThatThrownBy(() -> service.resolveTrigger(plan, "trigger:nonexistent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Trigger not found: trigger:nonexistent");
        }

        @Test
        @DisplayName("No fireable triggers - throws with informative message")
        void noFireable_throws() {
            Trigger wfTrigger = trigger("workflow", "Parent");
            Trigger errTrigger = trigger("error", "Error");
            when(plan.getTriggers()).thenReturn(List.of(wfTrigger, errTrigger));

            assertThatThrownBy(() -> service.resolveTrigger(plan, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No agent-fireable trigger");
        }

        @Test
        @DisplayName("Empty trigger list - throws")
        void emptyTriggers_throws() {
            when(plan.getTriggers()).thenReturn(List.of());

            assertThatThrownBy(() -> service.resolveTrigger(plan, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no triggers");
        }

        @Test
        @DisplayName("Null trigger list - throws")
        void nullTriggers_throws() {
            when(plan.getTriggers()).thenReturn(null);

            assertThatThrownBy(() -> service.resolveTrigger(plan, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================== validateFireable ====================

    @Nested
    @DisplayName("validateFireable - agent-fireable trigger types")
    class ValidateFireableTests {

        @Test
        @DisplayName("MANUAL is fireable by agent")
        void manual_isFireable() {
            assertThatCode(() -> service.validateFireable(TriggerType.MANUAL)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("CHAT is fireable by agent")
        void chat_isFireable() {
            assertThatCode(() -> service.validateFireable(TriggerType.CHAT)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("WEBHOOK is fireable by agent")
        void webhook_isFireable() {
            assertThatCode(() -> service.validateFireable(TriggerType.WEBHOOK)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("FORM is fireable by agent")
        void form_isFireable() {
            assertThatCode(() -> service.validateFireable(TriggerType.FORM)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SCHEDULE is fireable by agent")
        void schedule_isFireable() {
            assertThatCode(() -> service.validateFireable(TriggerType.SCHEDULE)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("DATASOURCE is fireable by agent")
        void datasource_isFireable() {
            assertThatCode(() -> service.validateFireable(TriggerType.DATASOURCE)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("WORKFLOW is NOT fireable - throws with 'workflow' in message")
        void workflow_notFireable() {
            assertThatThrownBy(() -> service.validateFireable(TriggerType.WORKFLOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("workflow");
        }

        @Test
        @DisplayName("ERROR is NOT fireable - throws with 'error' in message")
        void error_notFireable() {
            assertThatThrownBy(() -> service.validateFireable(TriggerType.ERROR))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("error");
        }
    }

    // ==================== hasOnlyBootstrapTriggers ====================

    @Nested
    @DisplayName("hasOnlyBootstrapTriggers - detect bootstrap-only plans for execute short-circuit")
    class HasOnlyBootstrapTriggersTests {

        @Mock private WorkflowPlan plan;

        private Trigger trigger(String type) {
            Trigger t = mock(Trigger.class);
            lenient().when(t.type()).thenReturn(type);
            return t;
        }

        @Test
        @DisplayName("Null plan → false (caller emits its own error)")
        void nullPlan_false() {
            assertThat(service.hasOnlyBootstrapTriggers(null)).isFalse();
        }

        @Test
        @DisplayName("Plan with null trigger list → false")
        void nullTriggers_false() {
            when(plan.getTriggers()).thenReturn(null);
            assertThat(service.hasOnlyBootstrapTriggers(plan)).isFalse();
        }

        @Test
        @DisplayName("Empty trigger list → false (no bootstrap to seed)")
        void emptyTriggers_false() {
            when(plan.getTriggers()).thenReturn(List.of());
            assertThat(service.hasOnlyBootstrapTriggers(plan)).isFalse();
        }

        @Test
        @DisplayName("Single error trigger → true (the bootstrap case)")
        void singleErrorTrigger_true() {
            Trigger err = trigger("error");
            when(plan.getTriggers()).thenReturn(List.of(err));
            assertThat(service.hasOnlyBootstrapTriggers(plan)).isTrue();
        }

        @Test
        @DisplayName("Single workflow trigger → true (also seeds via bootstrap)")
        void singleWorkflowTrigger_true() {
            Trigger wf = trigger("workflow");
            when(plan.getTriggers()).thenReturn(List.of(wf));
            assertThat(service.hasOnlyBootstrapTriggers(plan)).isTrue();
        }

        @Test
        @DisplayName("Mixed workflow + error triggers (all non-fireable) → true")
        void allNonFireableMix_true() {
            Trigger wf = trigger("workflow");
            Trigger err1 = trigger("error");
            Trigger err2 = trigger("error");
            when(plan.getTriggers()).thenReturn(List.of(wf, err1, err2));
            assertThat(service.hasOnlyBootstrapTriggers(plan)).isTrue();
        }

        @Test
        @DisplayName("Manual + error mix → false (manual is fireable, normal fire path applies)")
        void manualPlusError_false() {
            Trigger manual = trigger("manual");
            Trigger err = trigger("error");
            when(plan.getTriggers()).thenReturn(List.of(manual, err));
            assertThat(service.hasOnlyBootstrapTriggers(plan)).isFalse();
        }

        @Test
        @DisplayName("Single manual trigger → false")
        void singleManual_false() {
            Trigger manual = trigger("manual");
            when(plan.getTriggers()).thenReturn(List.of(manual));
            assertThat(service.hasOnlyBootstrapTriggers(plan)).isFalse();
        }

        @Test
        @DisplayName("Webhook + workflow mix → false (webhook is fireable)")
        void webhookPlusWorkflow_false() {
            Trigger webhook = trigger("webhook");
            Trigger wf = trigger("workflow");
            when(plan.getTriggers()).thenReturn(List.of(webhook, wf));
            assertThat(service.hasOnlyBootstrapTriggers(plan)).isFalse();
        }
    }

    // ==================== buildTriggerExecuteInfo ====================

    @Nested
    @DisplayName("buildTriggerExecuteInfo - trigger schema generation from session maps")
    class BuildTriggerExecuteInfoTests {

        @Test
        @DisplayName("Returns null for null list")
        void nullList_returnsNull() {
            assertThat(service.buildTriggerExecuteInfo(null, "wf-id")).isNull();
        }

        @Test
        @DisplayName("Returns null for empty list")
        void emptyList_returnsNull() {
            assertThat(service.buildTriggerExecuteInfo(List.of(), "wf-id")).isNull();
        }

        @Test
        @DisplayName("Single trigger - hint includes workflow id")
        void singleTrigger_hintIncludesWorkflowId() {
            var info = service.buildTriggerExecuteInfo(
                    List.of(Map.of("type", "manual", "label", "Start")), "wf-abc");

            assertThat((String) info.get("hint")).contains("wf-abc");
        }

        @Test
        @DisplayName("Single trigger - hint always includes trigger_id")
        void singleTrigger_hintIncludesTriggerId() {
            var info = service.buildTriggerExecuteInfo(
                    List.of(Map.of("type", "manual", "label", "Start")), "wf-1");

            assertThat((String) info.get("hint")).contains("trigger_id");
        }

        @Test
        @DisplayName("Multiple triggers - hint includes trigger_id param")
        void multipleTriggers_hintHasTriggerId() {
            var info = service.buildTriggerExecuteInfo(List.of(
                    Map.of("type", "manual", "label", "Start"),
                    Map.of("type", "webhook", "label", "Hook")
            ), "wf-1");

            assertThat((String) info.get("hint")).contains("trigger_id");
        }

        @Test
        @DisplayName("trigger_id is normalized from label")
        void triggerId_isNormalizedFromLabel() {
            var info = service.buildTriggerExecuteInfo(
                    List.of(Map.of("type", "manual", "label", "My Workflow Start")), null);

            @SuppressWarnings("unchecked")
            var triggers = (List<Map<String, Object>>) info.get("triggers");
            assertThat(triggers.get(0).get("trigger_id")).isEqualTo("trigger:my_workflow_start");
        }

        @Test
        @DisplayName("MANUAL trigger - fireable, has required_data and example")
        void manualTrigger_schema() {
            var info = service.buildTriggerExecuteInfo(
                    List.of(Map.of("type", "manual", "label", "Start")), null);

            @SuppressWarnings("unchecked")
            var schema = ((List<Map<String, Object>>) info.get("triggers")).get(0);
            assertThat(schema.get("fireable")).isEqualTo(true);
            assertThat(schema).containsKey("required_data").containsKey("example");
        }

        @Test
        @DisplayName("CHAT trigger - requires 'message' field, marked required=true")
        void chatTrigger_requiresMessageField() {
            var info = service.buildTriggerExecuteInfo(
                    List.of(Map.of("type", "chat", "label", "Chat")), null);

            @SuppressWarnings("unchecked")
            var schema = ((List<Map<String, Object>>) info.get("triggers")).get(0);
            @SuppressWarnings("unchecked")
            var requiredData = (Map<String, Object>) schema.get("required_data");
            assertThat(requiredData).containsKey("message");

            @SuppressWarnings("unchecked")
            var messageField = (Map<String, Object>) requiredData.get("message");
            assertThat(messageField.get("required")).isEqualTo(true);
        }

        @Test
        @DisplayName("CHAT trigger - includes chat_match when present in map")
        void chatTrigger_includesChatMatch() {
            var trigger = new LinkedHashMap<String, Object>();
            trigger.put("type", "chat");
            trigger.put("label", "Chat");
            trigger.put("chatMatch", Map.of("type", "KEYWORD", "value", "start"));

            var info = service.buildTriggerExecuteInfo(List.of(trigger), null);

            @SuppressWarnings("unchecked")
            var schema = ((List<Map<String, Object>>) info.get("triggers")).get(0);
            assertThat(schema).containsKey("chat_match");

            @SuppressWarnings("unchecked")
            var chatMatch = (Map<String, Object>) schema.get("chat_match");
            assertThat(chatMatch.get("match_type")).isEqualTo("KEYWORD");
            assertThat(chatMatch.get("match_value")).isEqualTo("start");
        }

        @Test
        @DisplayName("FORM trigger - includes field definitions from params.fields")
        void formTrigger_includesFieldSchema() {
            Map<String, Object> trigger = Map.of(
                    "type", "form",
                    "label", "Contact",
                    "params", Map.of("fields", List.of(
                            Map.of("name", "email", "type", "email", "required", true),
                            Map.of("name", "message", "type", "string", "required", false)
                    ))
            );
            var info = service.buildTriggerExecuteInfo(List.of(trigger), null);

            @SuppressWarnings("unchecked")
            var schema = ((List<Map<String, Object>>) info.get("triggers")).get(0);
            @SuppressWarnings("unchecked")
            var requiredData = (Map<String, Object>) schema.get("required_data");

            assertThat(requiredData).containsKey("email").containsKey("message");

            @SuppressWarnings("unchecked")
            var emailField = (Map<String, Object>) requiredData.get("email");
            assertThat(emailField.get("required")).isEqualTo(true);
            assertThat(emailField.get("type")).isEqualTo("email");
        }

        @Test
        @DisplayName("FORM trigger - required='true' (String) treated as required in hint")
        void formTrigger_requiredStringTrue_treatedAsRequired() {
            Map<String, Object> trigger = Map.of(
                    "type", "form",
                    "label", "Contact",
                    "params", Map.of("fields", List.of(
                            Map.of("name", "email", "type", "email", "required", "true")
                    ))
            );
            var info = service.buildTriggerExecuteInfo(List.of(trigger), null);

            @SuppressWarnings("unchecked")
            var schema = ((List<Map<String, Object>>) info.get("triggers")).get(0);
            @SuppressWarnings("unchecked")
            var requiredData = (Map<String, Object>) schema.get("required_data");
            @SuppressWarnings("unchecked")
            var emailField = (Map<String, Object>) requiredData.get("email");
            assertThat(emailField.get("required")).isEqualTo(true);

            @SuppressWarnings("unchecked")
            var example = (Map<String, Object>) schema.get("example");
            assertThat(example.get("email")).isEqualTo("<required>");
        }

        @Test
        @DisplayName("FORM trigger - no fields defined: graceful fallback")
        void formTrigger_noFields_gracefulFallback() {
            Map<String, Object> trigger = Map.of("type", "form", "label", "Empty Form");
            var info = service.buildTriggerExecuteInfo(List.of(trigger), null);

            @SuppressWarnings("unchecked")
            var schema = ((List<Map<String, Object>>) info.get("triggers")).get(0);
            assertThat(schema).containsKey("required_data");
        }

        @Test
        @DisplayName("SCHEDULE trigger - includes cron_expression from params")
        void scheduleTrigger_includesCron() {
            Map<String, Object> trigger = Map.of(
                    "type", "schedule",
                    "label", "Daily Job",
                    "params", Map.of("cron", "0 9 * * *")
            );
            var info = service.buildTriggerExecuteInfo(List.of(trigger), null);

            @SuppressWarnings("unchecked")
            var schema = ((List<Map<String, Object>>) info.get("triggers")).get(0);
            assertThat(schema.get("cron_expression")).isEqualTo("0 9 * * *");
        }

        @Test
        @DisplayName("DATASOURCE trigger - includes datasource_id when present")
        void datasourceTrigger_includesDatasourceId() {
            Map<String, Object> trigger = Map.of(
                    "type", "datasource",
                    "label", "Table Watch",
                    "params", Map.of("datasourceId", "ds-42")
            );
            var info = service.buildTriggerExecuteInfo(List.of(trigger), null);

            @SuppressWarnings("unchecked")
            var schema = ((List<Map<String, Object>>) info.get("triggers")).get(0);
            assertThat(schema.get("datasource_id")).isEqualTo("ds-42");
        }

        @Test
        @DisplayName("WORKFLOW trigger - marked fireable=false with reason")
        void workflowTrigger_markedNotFireable() {
            var info = service.buildTriggerExecuteInfo(
                    List.of(Map.of("type", "workflow", "label", "Parent")), null);

            @SuppressWarnings("unchecked")
            var schema = ((List<Map<String, Object>>) info.get("triggers")).get(0);
            assertThat(schema.get("fireable")).isEqualTo(false);
            assertThat(schema.get("reason")).asString().contains("parent workflow completion");
        }

        @Test
        @DisplayName("ERROR trigger - marked fireable=false with reason")
        void errorTrigger_markedNotFireable() {
            var info = service.buildTriggerExecuteInfo(
                    List.of(Map.of("type", "error", "label", "On Fail")), null);

            @SuppressWarnings("unchecked")
            var schema = ((List<Map<String, Object>>) info.get("triggers")).get(0);
            assertThat(schema.get("fireable")).isEqualTo(false);
            assertThat(schema.get("reason")).asString().contains("failure");
        }

        @Test
        @DisplayName("Mixed fireable/non-fireable triggers - all included in output")
        void mixedTriggers_allIncluded() {
            var info = service.buildTriggerExecuteInfo(List.of(
                    Map.of("type", "manual", "label", "Start"),
                    Map.of("type", "workflow", "label", "Parent"),
                    Map.of("type", "webhook", "label", "Hook")
            ), null);

            @SuppressWarnings("unchecked")
            var triggers = (List<Map<String, Object>>) info.get("triggers");
            assertThat(triggers).hasSize(3);
        }

        @Test
        @DisplayName("Single trigger - hint always includes trigger_id")
        void singleTrigger_hintAlwaysHasTriggerId() {
            var info = service.buildTriggerExecuteInfo(
                    List.of(Map.of("type", "manual", "label", "Start")), "wf-1");

            assertThat((String) info.get("hint")).contains("trigger_id");
        }
    }

    // ==================== resolveTrigger - mandatory trigger_id for multi-trigger ====================

    @Nested
    @DisplayName("resolveTrigger - mandatory trigger_id when multiple fireable triggers")
    class ResolveTriggerMultiTriggerTests {

        @Mock private WorkflowPlan plan;

        private Trigger trigger(String type, String label) {
            Trigger t = mock(Trigger.class);
            lenient().when(t.type()).thenReturn(type);
            lenient().when(t.getNormalizedKey()).thenReturn("trigger:" + label.toLowerCase().replace(" ", "_"));
            lenient().when(t.label()).thenReturn(label);
            return t;
        }

        @Test
        @DisplayName("Multiple fireable triggers + no hint - throws with list of available triggers")
        void multipleFireable_noHint_throwsWithList() {
            Trigger manual = trigger("manual", "start");
            Trigger webhook = trigger("webhook", "hook");
            when(plan.getTriggers()).thenReturn(List.of(manual, webhook));

            assertThatThrownBy(() -> service.resolveTrigger(plan, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Multiple fireable triggers")
                    .hasMessageContaining("trigger_id")
                    .hasMessageContaining("trigger:start")
                    .hasMessageContaining("trigger:hook");
        }

        @Test
        @DisplayName("Multiple fireable triggers + blank hint - throws")
        void multipleFireable_blankHint_throws() {
            Trigger manual = trigger("manual", "start");
            Trigger chat = trigger("chat", "chat");
            when(plan.getTriggers()).thenReturn(List.of(manual, chat));

            assertThatThrownBy(() -> service.resolveTrigger(plan, "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Multiple fireable triggers");
        }

        @Test
        @DisplayName("Multiple fireable triggers + valid hint - resolves correctly")
        void multipleFireable_validHint_resolves() {
            Trigger manual = trigger("manual", "start");
            Trigger webhook = trigger("webhook", "hook");
            when(plan.getTriggers()).thenReturn(List.of(manual, webhook));

            assertThat(service.resolveTrigger(plan, "trigger:hook")).isSameAs(webhook);
        }

        @Test
        @DisplayName("Multiple triggers but only 1 fireable - auto-resolves without hint")
        void multipleTriggersOnlyOneFireable_autoResolves() {
            Trigger wf = trigger("workflow", "parent");
            Trigger err = trigger("error", "on_fail");
            Trigger manual = trigger("manual", "start");
            when(plan.getTriggers()).thenReturn(List.of(wf, err, manual));

            assertThat(service.resolveTrigger(plan, null)).isSameAs(manual);
        }

        @Test
        @DisplayName("Error message includes trigger type for disambiguation")
        void errorMessage_includesTriggerType() {
            Trigger manual = trigger("manual", "start");
            Trigger webhook = trigger("webhook", "hook");
            Trigger form = trigger("form", "contact");
            when(plan.getTriggers()).thenReturn(List.of(manual, webhook, form));

            assertThatThrownBy(() -> service.resolveTrigger(plan, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("(manual)")
                    .hasMessageContaining("(webhook)")
                    .hasMessageContaining("(form)");
        }

        @Test
        @DisplayName("Wrong hint on multi-trigger - error lists fireable triggers only")
        void wrongHint_listsFireableOnly() {
            Trigger manual = trigger("manual", "start");
            Trigger webhook = trigger("webhook", "hook");
            Trigger wf = trigger("workflow", "parent");
            when(plan.getTriggers()).thenReturn(List.of(manual, webhook, wf));

            assertThatThrownBy(() -> service.resolveTrigger(plan, "trigger:nonexistent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("trigger:start")
                    .hasMessageContaining("trigger:hook");
        }
    }

    // ==================== validatePayload - per-type payload validation ====================

    @Nested
    @DisplayName("validatePayload - agent trigger simulation validation")
    class ValidatePayloadTests {

        // ---- CHAT ----

        @Test
        @DisplayName("CHAT - valid message passes")
        void chat_validMessage_passes() {
            Trigger trigger = triggerOf("chat", "Chat");
            assertThatCode(() -> service.validatePayload(
                    TriggerType.CHAT, trigger, Map.of("message", "Hello")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("CHAT - missing message throws with example")
        void chat_missingMessage_throwsWithExample() {
            Trigger trigger = triggerOf("chat", "Chat");
            assertThatThrownBy(() -> service.validatePayload(
                    TriggerType.CHAT, trigger, Map.of("data", "test")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("message")
                    .hasMessageContaining("Example");
        }

        @Test
        @DisplayName("CHAT - empty message throws")
        void chat_emptyMessage_throws() {
            Trigger trigger = triggerOf("chat", "Chat");
            assertThatThrownBy(() -> service.validatePayload(
                    TriggerType.CHAT, trigger, Map.of("message", "  ")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("message");
        }

        @Test
        @DisplayName("CHAT - null payload throws with example")
        void chat_nullPayload_throws() {
            Trigger trigger = triggerOf("chat", "Chat");
            assertThatThrownBy(() -> service.validatePayload(
                    TriggerType.CHAT, trigger, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("message");
        }

        @Test
        @DisplayName("CHAT - message is not a string throws")
        void chat_messageNotString_throws() {
            Trigger trigger = triggerOf("chat", "Chat");
            assertThatThrownBy(() -> service.validatePayload(
                    TriggerType.CHAT, trigger, Map.of("message", 42)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("message");
        }

        @Test
        @DisplayName("CHAT - extra fields alongside message are allowed")
        void chat_extraFields_allowed() {
            Trigger trigger = triggerOf("chat", "Chat");
            assertThatCode(() -> service.validatePayload(
                    TriggerType.CHAT, trigger, Map.of("message", "Hi", "context", "test")))
                    .doesNotThrowAnyException();
        }

        // ---- FORM ----

        @Test
        @DisplayName("FORM - all required fields present passes")
        void form_allRequired_passes() {
            Trigger trigger = formTrigger(List.of(
                    formField("email", "email", true),
                    formField("name", "text", true)
            ));
            assertThatCode(() -> service.validatePayload(
                    TriggerType.FORM, trigger, Map.of("email", "a@b.com", "name", "John")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("FORM - missing required field throws with all field names")
        void form_missingRequired_throwsWithFieldNames() {
            Trigger trigger = formTrigger(List.of(
                    formField("email", "email", true),
                    formField("name", "text", true),
                    formField("note", "textarea", false)
            ));
            assertThatThrownBy(() -> service.validatePayload(
                    TriggerType.FORM, trigger, Map.of("email", "a@b.com")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("[name]")
                    .hasMessageContaining("email")
                    .hasMessageContaining("note")
                    .hasMessageContaining("Example");
        }

        @Test
        @DisplayName("FORM - no required fields, any payload passes")
        void form_noRequired_anyPayloadPasses() {
            Trigger trigger = formTrigger(List.of(
                    formField("note", "textarea", false)
            ));
            assertThatCode(() -> service.validatePayload(
                    TriggerType.FORM, trigger, Map.of()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("FORM - no fields defined, any payload passes")
        void form_noFieldsDefined_anyPayloadPasses() {
            Trigger trigger = triggerOf("form", "Contact");
            assertThatCode(() -> service.validatePayload(
                    TriggerType.FORM, trigger, Map.of("anything", "goes")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("FORM - null payload with required fields throws")
        void form_nullPayload_withRequired_throws() {
            Trigger trigger = formTrigger(List.of(
                    formField("email", "email", true)
            ));
            assertThatThrownBy(() -> service.validatePayload(
                    TriggerType.FORM, trigger, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("email");
        }

        @Test
        @DisplayName("FORM - agent can add extra fields not in schema")
        void form_extraFieldsAllowed() {
            Trigger trigger = formTrigger(List.of(
                    formField("email", "email", true)
            ));
            assertThatCode(() -> service.validatePayload(
                    TriggerType.FORM, trigger,
                    Map.of("email", "a@b.com", "custom_field", "extra")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("FORM - multiple missing required fields all listed")
        void form_multipleMissing_allListed() {
            Trigger trigger = formTrigger(List.of(
                    formField("email", "email", true),
                    formField("name", "text", true),
                    formField("phone", "tel", true)
            ));
            assertThatThrownBy(() -> service.validatePayload(
                    TriggerType.FORM, trigger, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("email")
                    .hasMessageContaining("name")
                    .hasMessageContaining("phone");
        }

        // ---- WEBHOOK ----

        @Test
        @DisplayName("WEBHOOK - any JSON payload passes")
        void webhook_anyPayload_passes() {
            Trigger trigger = triggerOf("webhook", "Hook");
            assertThatCode(() -> service.validatePayload(
                    TriggerType.WEBHOOK, trigger,
                    Map.of("event", "user.created", "data", Map.of("id", "123"))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("WEBHOOK - empty payload passes")
        void webhook_emptyPayload_passes() {
            Trigger trigger = triggerOf("webhook", "Hook");
            assertThatCode(() -> service.validatePayload(
                    TriggerType.WEBHOOK, trigger, Map.of()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("WEBHOOK - null payload passes")
        void webhook_nullPayload_passes() {
            Trigger trigger = triggerOf("webhook", "Hook");
            assertThatCode(() -> service.validatePayload(
                    TriggerType.WEBHOOK, trigger, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("WEBHOOK - agent can simulate any HTTP body structure")
        void webhook_complexPayload_passes() {
            Trigger trigger = triggerOf("webhook", "Hook");
            Map<String, Object> payload = Map.of(
                    "headers", Map.of("X-Custom", "value"),
                    "body", Map.of("users", List.of("alice", "bob")),
                    "query", Map.of("page", "1")
            );
            assertThatCode(() -> service.validatePayload(
                    TriggerType.WEBHOOK, trigger, payload))
                    .doesNotThrowAnyException();
        }

        // ---- MANUAL ----

        @Test
        @DisplayName("MANUAL - any payload passes")
        void manual_anyPayload_passes() {
            Trigger trigger = triggerOf("manual", "Start");
            assertThatCode(() -> service.validatePayload(
                    TriggerType.MANUAL, trigger, Map.of("key", "value")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("MANUAL - empty payload passes")
        void manual_emptyPayload_passes() {
            Trigger trigger = triggerOf("manual", "Start");
            assertThatCode(() -> service.validatePayload(
                    TriggerType.MANUAL, trigger, Map.of()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("MANUAL - null payload passes")
        void manual_nullPayload_passes() {
            Trigger trigger = triggerOf("manual", "Start");
            assertThatCode(() -> service.validatePayload(
                    TriggerType.MANUAL, trigger, null))
                    .doesNotThrowAnyException();
        }

        // ---- SCHEDULE ----

        @Test
        @DisplayName("SCHEDULE - agent can simulate schedule fire with custom data")
        void schedule_customPayload_passes() {
            Trigger trigger = triggerOf("schedule", "Daily");
            assertThatCode(() -> service.validatePayload(
                    TriggerType.SCHEDULE, trigger, Map.of("run_date", "2026-04-03")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SCHEDULE - empty payload passes (cron ignored when agent fires)")
        void schedule_emptyPayload_passes() {
            Trigger trigger = triggerOf("schedule", "Daily");
            assertThatCode(() -> service.validatePayload(
                    TriggerType.SCHEDULE, trigger, Map.of()))
                    .doesNotThrowAnyException();
        }

        // ---- DATASOURCE ----

        @Test
        @DisplayName("DATASOURCE - any filter payload passes")
        void datasource_anyPayload_passes() {
            Trigger trigger = triggerOf("datasource", "Table");
            assertThatCode(() -> service.validatePayload(
                    TriggerType.DATASOURCE, trigger, Map.of("filter", "active")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("DATASOURCE - null payload passes (data loaded automatically)")
        void datasource_nullPayload_passes() {
            Trigger trigger = triggerOf("datasource", "Table");
            assertThatCode(() -> service.validatePayload(
                    TriggerType.DATASOURCE, trigger, null))
                    .doesNotThrowAnyException();
        }

        // ---- Helpers ----

        private Trigger triggerOf(String type, String label) {
            return new Trigger("test-id", label, "single", type, Map.of());
        }

        private Trigger formTrigger(List<Map<String, Object>> fields) {
            return new Trigger("test-id", "Form", "single", "form", Map.of("fields", fields));
        }

        private Map<String, Object> formField(String name, String type, boolean required) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", name);
            field.put("type", type);
            field.put("required", required);
            return field;
        }

    }

    // ==================== Audit edge cases ====================

    @Nested
    @DisplayName("Edge cases - audit findings")
    class AuditEdgeCaseTests {

        @Mock private WorkflowPlan plan;

        private Trigger trigger(String type, String label) {
            Trigger t = mock(Trigger.class);
            lenient().when(t.type()).thenReturn(type);
            lenient().when(t.getNormalizedKey()).thenReturn("trigger:" + label.toLowerCase().replace(" ", "_"));
            lenient().when(t.label()).thenReturn(label);
            return t;
        }

        // ---- C1: Hint matching non-fireable trigger ----

        @Test
        @DisplayName("C1: Hint matching WORKFLOW trigger - rejects with specific message")
        void hintMatchingWorkflowTrigger_rejectsWithMessage() {
            Trigger manual = trigger("manual", "start");
            Trigger wf = trigger("workflow", "parent");
            when(plan.getTriggers()).thenReturn(List.of(manual, wf));

            assertThatThrownBy(() -> service.resolveTrigger(plan, "trigger:parent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not agent-fireable")
                    .hasMessageContaining("system-only")
                    .hasMessageContaining("trigger:start"); // shows alternatives
        }

        @Test
        @DisplayName("C1: Hint matching ERROR trigger - rejects with specific message")
        void hintMatchingErrorTrigger_rejectsWithMessage() {
            Trigger webhook = trigger("webhook", "hook");
            Trigger err = trigger("error", "on_fail");
            when(plan.getTriggers()).thenReturn(List.of(webhook, err));

            assertThatThrownBy(() -> service.resolveTrigger(plan, "trigger:on_fail"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not agent-fireable")
                    .hasMessageContaining("Fireable triggers");
        }

        @Test
        @DisplayName("C1: Wrong hint - error says 'not found', not 'not fireable'")
        void wrongHint_saysNotFound() {
            Trigger manual = trigger("manual", "start");
            when(plan.getTriggers()).thenReturn(List.of(manual));

            assertThatThrownBy(() -> service.resolveTrigger(plan, "trigger:nonexistent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found")
                    .hasMessageNotContaining("not agent-fireable");
        }

        // ---- I3: Label normalization with prefix ----

        @Test
        @DisplayName("I3: Hint 'trigger:My Webhook' normalizes to match 'trigger:my_webhook'")
        void hintWithPrefixAndUnnormalizedLabel_normalizes() {
            Trigger webhook = trigger("webhook", "my_webhook");
            when(plan.getTriggers()).thenReturn(List.of(webhook));

            // "trigger:My Webhook" should normalize to "trigger:my_webhook"
            assertThat(service.resolveTrigger(plan, "trigger:My Webhook")).isSameAs(webhook);
        }

        @Test
        @DisplayName("I3: Hint without prefix with spaces normalizes correctly")
        void hintWithoutPrefixWithSpaces_normalizes() {
            Trigger form = trigger("form", "contact_form");
            when(plan.getTriggers()).thenReturn(List.of(form));

            assertThat(service.resolveTrigger(plan, "Contact Form")).isSameAs(form);
        }

        // ---- C2: Form required as String "true" ----

        @Test
        @DisplayName("C2: Form field required='true' (String) - treated as required")
        void formField_requiredAsString_treatedAsRequired() {
            Trigger trigger = new Trigger("test-id", "Form", "single", "form", Map.of("fields", List.of(
                    Map.of("name", "email", "type", "email", "required", "true")
            )));

            assertThatThrownBy(() -> service.validatePayload(
                    TriggerType.FORM, trigger, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("email");
        }

        @Test
        @DisplayName("C2: Form field required='false' (String) - treated as optional")
        void formField_requiredFalseString_treatedAsOptional() {
            Trigger trigger = new Trigger("test-id", "Form", "single", "form", Map.of("fields", List.of(
                    Map.of("name", "note", "type", "text", "required", "false")
            )));

            assertThatCode(() -> service.validatePayload(
                    TriggerType.FORM, trigger, Map.of()))
                    .doesNotThrowAnyException();
        }

        // ---- I4: Required field with null/empty values ----

        @Test
        @DisplayName("I4: Required field present but empty string - treated as missing")
        void requiredField_emptyString_treatedAsMissing() {
            Trigger trigger = new Trigger("test-id", "Form", "single", "form", Map.of("fields", List.of(
                    Map.of("name", "email", "type", "email", "required", true)
            )));

            assertThatThrownBy(() -> service.validatePayload(
                    TriggerType.FORM, trigger, Map.of("email", "")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("email");
        }

        @Test
        @DisplayName("I4: Required field present but blank string - treated as missing")
        void requiredField_blankString_treatedAsMissing() {
            Trigger trigger = new Trigger("test-id", "Form", "single", "form", Map.of("fields", List.of(
                    Map.of("name", "name", "type", "text", "required", true)
            )));

            assertThatThrownBy(() -> service.validatePayload(
                    TriggerType.FORM, trigger, Map.of("name", "   ")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name");
        }

        @Test
        @DisplayName("I4: Required field present with null value (HashMap) - treated as missing")
        void requiredField_nullValue_treatedAsMissing() {
            Trigger trigger = new Trigger("test-id", "Form", "single", "form", Map.of("fields", List.of(
                    Map.of("name", "email", "type", "email", "required", true)
            )));

            Map<String, Object> payload = new HashMap<>();
            payload.put("email", null);

            assertThatThrownBy(() -> service.validatePayload(
                    TriggerType.FORM, trigger, payload))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("email");
        }

        @Test
        @DisplayName("I4: Required field with non-string value (integer) - passes")
        void requiredField_integerValue_passes() {
            Trigger trigger = new Trigger("test-id", "Form", "single", "form", Map.of("fields", List.of(
                    Map.of("name", "age", "type", "number", "required", true)
            )));

            assertThatCode(() -> service.validatePayload(
                    TriggerType.FORM, trigger, Map.of("age", 25)))
                    .doesNotThrowAnyException();
        }

        // ---- M6: Form field without "name" key ----

        @Test
        @DisplayName("M6: Form field without name key - silently skipped, no crash")
        void formField_withoutName_skipped() {
            Trigger trigger = new Trigger("test-id", "Form", "single", "form", Map.of("fields", List.of(
                    Map.of("type", "text", "required", true),  // no "name" key
                    Map.of("name", "email", "type", "email", "required", true)
            )));

            // Only "email" should be required, the nameless field is skipped
            assertThatThrownBy(() -> service.validatePayload(
                    TriggerType.FORM, trigger, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("email")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("null"));
        }

        @Test
        @DisplayName("M6: All form fields without name - no required fields, passes")
        void allFormFields_withoutName_passes() {
            Trigger trigger = new Trigger("test-id", "Form", "single", "form", Map.of("fields", List.of(
                    Map.of("type", "text", "required", true)
            )));

            assertThatCode(() -> service.validatePayload(
                    TriggerType.FORM, trigger, Map.of()))
                    .doesNotThrowAnyException();
        }
    }

    // ==================== F1: resolveTrigger with real Trigger objects (normalization) ====================

    @Nested
    @DisplayName("resolveTrigger - real Trigger objects (LabelNormalizer integration)")
    class ResolveTriggerRealTriggerTests {

        @Test
        @DisplayName("Hint matches real trigger with accented label")
        void hintMatchesAccentedLabel() {
            WorkflowPlan plan = planWith(
                    triggerMap("t1", "Requête API", "webhook"));

            Trigger resolved = service.resolveTrigger(plan, "Requête API");
            assertThat(resolved.getNormalizedKey()).isEqualTo("trigger:requete_api");
        }

        @Test
        @DisplayName("Hint with prefix matches real trigger")
        void hintWithPrefixMatchesReal() {
            WorkflowPlan plan = planWith(
                    triggerMap("t1", "My Webhook", "webhook"));

            Trigger resolved = service.resolveTrigger(plan, "trigger:My Webhook");
            assertThat(resolved.getNormalizedKey()).isEqualTo("trigger:my_webhook");
        }

        @Test
        @DisplayName("Hint matches normalized key directly")
        void hintMatchesNormalizedKeyDirectly() {
            WorkflowPlan plan = planWith(
                    triggerMap("t1", "Contact Form", "form"));

            Trigger resolved = service.resolveTrigger(plan, "trigger:contact_form");
            assertThat(resolved.getNormalizedKey()).isEqualTo("trigger:contact_form");
        }

        @Test
        @DisplayName("Single fireable among mixed - auto-resolves with real triggers")
        void singleFireableAmongMixed_autoResolves() {
            WorkflowPlan plan = planWith(
                    triggerMap("t1", "On Complete", "workflow"),
                    triggerMap("t2", "Run It", "manual"));

            Trigger resolved = service.resolveTrigger(plan, null);
            assertThat(resolved.getNormalizedKey()).isEqualTo("trigger:run_it");
            assertThat(resolved.type()).isEqualTo("manual");
        }

        @Test
        @DisplayName("Multiple real fireable triggers without hint - throws with real keys")
        void multipleRealFireable_noHint_throws() {
            WorkflowPlan plan = planWith(
                    triggerMap("t1", "Support Chat", "chat"),
                    triggerMap("t2", "Feedback Form", "form"));

            assertThatThrownBy(() -> service.resolveTrigger(plan, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("trigger:support_chat")
                    .hasMessageContaining("trigger:feedback_form");
        }

        @Test
        @DisplayName("Hint for non-fireable real trigger - specific error")
        void hintForNonFireableReal_specificError() {
            WorkflowPlan plan = planWith(
                    triggerMap("t1", "Start", "manual"),
                    triggerMap("t2", "Parent Done", "workflow"));

            assertThatThrownBy(() -> service.resolveTrigger(plan, "Parent Done"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not agent-fireable")
                    .hasMessageContaining("trigger:start");
        }

        @Test
        @DisplayName("Empty string hint - treated as absent")
        void emptyStringHint_treatedAsAbsent() {
            WorkflowPlan plan = planWith(
                    triggerMap("t1", "Start", "manual"));

            Trigger resolved = service.resolveTrigger(plan, "");
            assertThat(resolved.getNormalizedKey()).isEqualTo("trigger:start");
        }

        private Map<String, Object> triggerMap(String id, String label, String type) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("label", label);
            m.put("type", type);
            m.put("strategy", "single");
            return m;
        }

        private WorkflowPlan planWith(Map<String, Object>... triggers) {
            return WorkflowPlan.fromMap(Map.of(
                    "triggers", List.of(triggers),
                    "mcps", List.of(),
                    "edges", List.of()
            ), "wf-test", "tenant-test");
        }
    }
}
