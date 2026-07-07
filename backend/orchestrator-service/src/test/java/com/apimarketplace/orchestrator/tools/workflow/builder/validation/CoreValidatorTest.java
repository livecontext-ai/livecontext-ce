package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tests for CoreValidator.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CoreValidator")
class CoreValidatorTest {

    @Mock
    private WorkflowBuilderSession session;

    private CoreValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CoreValidator();
    }

    @Test
    @DisplayName("Should not add errors when no cores exist")
    void shouldNotAddErrorsWhenNoCores() {
        when(session.getCores()).thenReturn(List.of());

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    @DisplayName("Should add error when core has no label")
    void shouldAddErrorWhenNoLabel() {
        Map<String, Object> core = Map.of("type", "decision");
        when(session.getCores()).thenReturn(List.of(core));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).code()).isEqualTo("MISSING_LABEL");
    }

    @Test
    @DisplayName("Should add error when core label is blank")
    void shouldAddErrorWhenLabelBlank() {
        Map<String, Object> core = Map.of("type", "decision", "label", "  ");
        when(session.getCores()).thenReturn(List.of(core));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).code()).isEqualTo("MISSING_LABEL");
    }

    @Nested
    @DisplayName("Decision validation")
    class DecisionTests {

        @Test
        @DisplayName("Should add error when decision has no conditions")
        void shouldAddErrorWhenNoConditions() {
            Map<String, Object> core = Map.of("type", "decision", "label", "Check User");
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                e.code().equals("DECISION_NO_CONDITIONS"));
        }

        @Test
        @DisplayName("Should not add error when decision has conditions")
        void shouldNotAddErrorWhenHasConditions() {
            Map<String, Object> core = Map.of(
                "type", "decision",
                "label", "Check User",
                "decisionConditions", List.of(Map.of("expression", "#{isActive}"))
            );
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                e.code().equals("DECISION_NO_CONDITIONS"));
        }
    }

    @Nested
    @DisplayName("Loop validation")
    class LoopTests {

        @Test
        @DisplayName("Should add error when loop has no loopCondition")
        void shouldAddErrorWhenNoLoopCondition() {
            Map<String, Object> core = Map.of("type", "loop", "label", "Process Items");
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                e.code().equals("LOOP_NO_CONDITION"));
        }

        @Test
        @DisplayName("Should not add error when loop has loopCondition")
        void shouldNotAddErrorWhenHasCondition() {
            Map<String, Object> core = Map.of(
                "type", "loop",
                "label", "Process Items",
                "loopCondition", Map.of("type", "forEach", "collection", "#{items}")
            );
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                e.code().equals("LOOP_NO_CONDITION"));
        }

        @Test
        @DisplayName("Should not add LOOP_NO_CONDITION when only maxIterations set")
        void shouldNotAddErrorWhenHasMaxIterations() {
            Map<String, Object> core = Map.of(
                "type", "loop",
                "label", "Process Items",
                "maxIterations", 10
            );
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                e.code().equals("LOOP_NO_CONDITION"));
        }

        // #F3: Before the fix, getCoreId returned "loop:<label>" but edges are stored
        // with the actual prefix "core:<label>" (via NodeType.LOOP.buildNodeId / LabelNormalizer),
        // so the body-port edge check never matched and LOOP_NO_BODY was raised even when
        // a valid body was wired up.
        @Test
        @DisplayName("#F3 Should NOT raise LOOP_NO_BODY when body edge uses core: prefix (stored id)")
        void shouldNotRaiseNoBodyWhenStoredIdUsed() {
            Map<String, Object> core = new HashMap<>();
            core.put("id", "core:process_items");
            core.put("type", "loop");
            core.put("label", "Process Items");
            core.put("maxIterations", 10);
            when(session.getCores()).thenReturn(List.of(core));
            when(session.getEdges()).thenReturn(List.of(
                Map.of("from", "core:process_items:body", "to", "mcp:worker"),
                Map.of("from", "mcp:worker", "to", "core:process_items:iterate")
            ));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("LOOP_NO_BODY"));
            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("LOOP_NOT_CLOSED"));
        }

        @Test
        @DisplayName("#F3 Should NOT raise LOOP_NO_BODY when id is absent (fallback computes core: prefix)")
        void shouldNotRaiseNoBodyFallbackComputesCorePrefix() {
            Map<String, Object> core = new HashMap<>();
            // no "id" field - validator must fall back to "core:" + normalizeLabel(label)
            core.put("type", "loop");
            core.put("label", "Process Items");
            core.put("maxIterations", 10);
            when(session.getCores()).thenReturn(List.of(core));
            when(session.getEdges()).thenReturn(List.of(
                Map.of("from", "core:process_items:body", "to", "mcp:worker"),
                Map.of("from", "mcp:worker", "to", "core:process_items:iterate")
            ));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("LOOP_NO_BODY"));
            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("LOOP_NOT_CLOSED"));
        }

        @Test
        @DisplayName("Should raise LOOP_NO_BODY when no body-port edge exists")
        void shouldRaiseNoBodyWhenNoBodyEdge() {
            Map<String, Object> core = new HashMap<>();
            core.put("id", "core:process_items");
            core.put("type", "loop");
            core.put("label", "Process Items");
            core.put("maxIterations", 10);
            when(session.getCores()).thenReturn(List.of(core));
            when(session.getEdges()).thenReturn(List.of(
                Map.of("from", "trigger:start", "to", "core:process_items")
            ));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e -> e.code().equals("LOOP_NO_BODY"));
        }

        @Test
        @DisplayName("Should raise LOOP_NOT_CLOSED when body exists but no iterate edge")
        void shouldRaiseNotClosedWhenBodyButNoIterate() {
            Map<String, Object> core = new HashMap<>();
            core.put("id", "core:process_items");
            core.put("type", "loop");
            core.put("label", "Process Items");
            core.put("maxIterations", 10);
            when(session.getCores()).thenReturn(List.of(core));
            when(session.getEdges()).thenReturn(List.of(
                Map.of("from", "core:process_items:body", "to", "mcp:worker")
                // missing iterate back-edge
            ));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("LOOP_NO_BODY"));
            assertThat(result.getErrors()).anyMatch(e -> e.code().equals("LOOP_NOT_CLOSED"));
        }
    }

    @Nested
    @DisplayName("Data processing node input validation")
    class DataProcessingInputTests {

        @Test
        @DisplayName("Should add error when filter has no input")
        void shouldAddErrorWhenFilterNoInput() {
            Map<String, Object> core = Map.of("type", "filter", "label", "Active Items");
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                e.code().equals("DATA_NODE_MISSING_INPUT"));
        }

        @Test
        @DisplayName("Should add error when sort has no input")
        void shouldAddErrorWhenSortNoInput() {
            Map<String, Object> core = Map.of("type", "sort", "label", "Sort Items");
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                e.code().equals("DATA_NODE_MISSING_INPUT"));
        }

        @Test
        @DisplayName("Should add error when limit has no input")
        void shouldAddErrorWhenLimitNoInput() {
            Map<String, Object> core = Map.of("type", "limit", "label", "Take 5");
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                e.code().equals("DATA_NODE_MISSING_INPUT"));
        }

        @Test
        @DisplayName("Should add error when remove_duplicates has no input")
        void shouldAddErrorWhenDedupNoInput() {
            Map<String, Object> core = Map.of("type", "remove_duplicates", "label", "Dedup");
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                e.code().equals("DATA_NODE_MISSING_INPUT"));
        }

        @Test
        @DisplayName("Should add error when summarize has no input")
        void shouldAddErrorWhenSummarizeNoInput() {
            Map<String, Object> core = Map.of("type", "summarize", "label", "Stats");
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                e.code().equals("DATA_NODE_MISSING_INPUT"));
        }

        @Test
        @DisplayName("Should not add error when input is in params.input")
        void shouldNotAddErrorWhenInputInParams() {
            Map<String, Object> core = new HashMap<>();
            core.put("type", "filter");
            core.put("label", "Active Items");
            core.put("params", Map.of("input", "{{trigger:data.output.items}}"));
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                e.code().equals("DATA_NODE_MISSING_INPUT"));
        }

        @Test
        @DisplayName("Should not add error when input is in removeDuplicates config")
        void shouldNotAddErrorWhenInputInDedupConfig() {
            Map<String, Object> core = new HashMap<>();
            core.put("type", "remove_duplicates");
            core.put("label", "Dedup");
            core.put("removeDuplicates", Map.of("input", "{{core:limit.output.items}}", "fields", List.of("name")));
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                e.code().equals("DATA_NODE_MISSING_INPUT"));
        }

        @Test
        @DisplayName("Should not add error when input is in summarize config")
        void shouldNotAddErrorWhenInputInSummarizeConfig() {
            Map<String, Object> core = new HashMap<>();
            core.put("type", "summarize");
            core.put("label", "Stats");
            core.put("summarize", Map.of("input", "{{core:dedup.output.items}}", "aggregations", List.of()));
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                e.code().equals("DATA_NODE_MISSING_INPUT"));
        }

        @Test
        @DisplayName("Should not add error when input is at top-level")
        void shouldNotAddErrorWhenInputTopLevel() {
            Map<String, Object> core = new HashMap<>();
            core.put("type", "sort");
            core.put("label", "Sort Items");
            core.put("input", "{{core:filter.output.matched}}");
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                e.code().equals("DATA_NODE_MISSING_INPUT"));
        }

        @Test
        @DisplayName("Should add error when params.input is blank")
        void shouldAddErrorWhenInputBlank() {
            Map<String, Object> core = new HashMap<>();
            core.put("type", "filter");
            core.put("label", "Active Items");
            core.put("params", Map.of("input", "  "));
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                e.code().equals("DATA_NODE_MISSING_INPUT"));
        }
    }

    @Nested
    @DisplayName("Approval validation")
    class ApprovalTests {

        @Test
        @DisplayName("Should WARN (not error) when approval has no contextTemplate")
        void shouldWarnWhenNoContextTemplate() {
            Map<String, Object> core = Map.of("type", "approval", "label", "Manager Review");
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).anyMatch(w ->
                w.code().equals("APPROVAL_NO_CONTEXT_TEMPLATE"));
            // Non-blocking: the plan must remain creatable (no error raised for the missing template).
            assertThat(result.getErrors()).noneMatch(e ->
                e.code().equals("APPROVAL_NO_CONTEXT_TEMPLATE"));
        }

        @Test
        @DisplayName("Should NOT warn when approval has a contextTemplate")
        void shouldNotWarnWhenContextTemplatePresent() {
            Map<String, Object> core = Map.of(
                "type", "approval",
                "label", "Manager Review",
                "approval", Map.of("contextTemplate", "Approve {{trigger:form.output.amount}}?"));
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                w.code().equals("APPROVAL_NO_CONTEXT_TEMPLATE"));
        }

        @Test
        @DisplayName("Should WARN when contextTemplate is blank")
        void shouldWarnWhenContextTemplateBlank() {
            Map<String, Object> core = Map.of(
                "type", "approval",
                "label", "Manager Review",
                "approval", Map.of("contextTemplate", "   "));
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).anyMatch(w ->
                w.code().equals("APPROVAL_NO_CONTEXT_TEMPLATE"));
        }
    }

    @Nested
    @DisplayName("Approval delegation validation")
    class ApprovalDelegationTests {

        private static final List<String> DELEGATION_CODES = List.of(
            "APPROVAL_DELEGATION_UNKNOWN_CHANNEL",
            "APPROVAL_DELEGATION_NO_CREDENTIAL",
            "APPROVAL_DELEGATION_NO_CHAT_ID",
            "APPROVAL_DELEGATION_MULTI_APPROVALS");

        private ValidationResult validate(Map<String, Object> approvalConfig) {
            Map<String, Object> core = Map.of(
                "type", "approval",
                "label", "Manager Review",
                "approval", approvalConfig);
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);
            return result;
        }

        @Test
        @DisplayName("Unknown channel is an ERROR (the approval would silently never reach any channel)")
        void unknownChannelIsError() {
            ValidationResult result = validate(Map.of(
                "contextTemplate", "Approve?",
                "delegation", Map.of("channel", "slack", "credentialId", 42, "chatId", "123")));

            assertThat(result.getErrors()).anyMatch(e ->
                e.code().equals("APPROVAL_DELEGATION_UNKNOWN_CHANNEL"));
        }

        @Test
        @DisplayName("Telegram without a numeric credentialId is a WARNING (in-app resolution still works)")
        void missingCredentialIsWarning() {
            ValidationResult result = validate(Map.of(
                "contextTemplate", "Approve?",
                "delegation", Map.of("channel", "telegram", "chatId", "123")));

            assertThat(result.getWarnings()).anyMatch(w ->
                w.code().equals("APPROVAL_DELEGATION_NO_CREDENTIAL"));
            assertThat(result.getErrors()).noneMatch(e ->
                e.code().equals("APPROVAL_DELEGATION_NO_CREDENTIAL"));
        }

        @Test
        @DisplayName("Telegram without a chatId is a WARNING")
        void missingChatIdIsWarning() {
            ValidationResult result = validate(Map.of(
                "contextTemplate", "Approve?",
                "delegation", Map.of("channel", "telegram", "credentialId", 42)));

            assertThat(result.getWarnings()).anyMatch(w ->
                w.code().equals("APPROVAL_DELEGATION_NO_CHAT_ID"));
        }

        @Test
        @DisplayName("Delegation with requiredApprovals > 1 is a WARNING (a button tap is a single decision)")
        void multiApprovalsIsWarning() {
            ValidationResult result = validate(Map.of(
                "contextTemplate", "Approve?",
                "requiredApprovals", 2,
                "delegation", Map.of("channel", "telegram", "credentialId", 42, "chatId", "123")));

            assertThat(result.getWarnings()).anyMatch(w ->
                w.code().equals("APPROVAL_DELEGATION_MULTI_APPROVALS"));
        }

        @Test
        @DisplayName("Fully configured Telegram delegation raises no delegation finding")
        void fullyConfiguredDelegationIsClean() {
            ValidationResult result = validate(Map.of(
                "contextTemplate", "Approve?",
                "requiredApprovals", 1,
                "delegation", Map.of("channel", "telegram", "credentialId", 42, "chatId", "123")));

            assertThat(result.getErrors()).noneMatch(e -> DELEGATION_CODES.contains(e.code()));
            assertThat(result.getWarnings()).noneMatch(w -> DELEGATION_CODES.contains(w.code()));
        }

        @Test
        @DisplayName("regression: an approval WITHOUT a delegation block produces no delegation finding")
        void noDelegationProducesNoDelegationFinding() {
            ValidationResult result = validate(Map.of("contextTemplate", "Approve?"));

            assertThat(result.getErrors()).noneMatch(e -> DELEGATION_CODES.contains(e.code()));
            assertThat(result.getWarnings()).noneMatch(w -> DELEGATION_CODES.contains(w.code()));
        }

        @Test
        @DisplayName("A blank channel means the section was left unconfigured: no delegation finding")
        void blankChannelProducesNoDelegationFinding() {
            ValidationResult result = validate(Map.of(
                "contextTemplate", "Approve?",
                "delegation", Map.of("channel", "   ")));

            assertThat(result.getErrors()).noneMatch(e -> DELEGATION_CODES.contains(e.code()));
            assertThat(result.getWarnings()).noneMatch(w -> DELEGATION_CODES.contains(w.code()));
        }
    }
}
