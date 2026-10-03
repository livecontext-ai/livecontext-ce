package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
            "APPROVAL_DELEGATION_INVALID_CREDENTIAL",
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
        @DisplayName("A picked destination (an id, or default) needs no channel and raises nothing")
        void pickedDestinationIsValid() {
            ValidationResult byId = validate(Map.of("delegation",
                Map.of("linkId", java.util.UUID.randomUUID().toString())));
            ValidationResult byDefault = validate(Map.of("delegation", Map.of("linkId", "default")));

            assertThat(byId.getErrors()).isEmpty();
            assertThat(byId.getWarnings()).noneMatch(w -> w.code().startsWith("APPROVAL_DELEGATION"));
            assertThat(byDefault.getErrors()).isEmpty();
            assertThat(byDefault.getWarnings()).noneMatch(w -> w.code().startsWith("APPROVAL_DELEGATION"));
        }

        @Test
        @DisplayName("A destination that is neither an id nor default is an ERROR, not silently the default")
        void malformedDestinationIsError() {
            ValidationResult result = validate(Map.of("delegation", Map.of("linkId", "finance chat")));

            assertThat(result.getErrors()).anyMatch(e -> e.code().equals("APPROVAL_DELEGATION_INVALID_DESTINATION"));
        }

        @Test
        @DisplayName("A picked destination with its own chatId is WARNED: the destination wins")
        void destinationOverridesChat() {
            ValidationResult result = validate(Map.of("delegation",
                Map.of("linkId", "default", "chatId", "-100123")));

            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getWarnings()).anyMatch(w -> w.code().equals("APPROVAL_DELEGATION_DESTINATION_OVERRIDES"));
        }

        @Test
        @DisplayName("A picked destination with several approvers is WARNED like any delegated approval")
        void destinationMultiApprovals() {
            ValidationResult result = validate(Map.of("requiredApprovals", 2,
                "delegation", Map.of("linkId", "default")));

            assertThat(result.getWarnings()).anyMatch(w -> w.code().equals("APPROVAL_DELEGATION_MULTI_APPROVALS"));
        }

        @Test
        @DisplayName("Unknown channel is an ERROR (the approval would silently never reach any channel)")
        void unknownChannelIsError() {
            ValidationResult result = validate(Map.of(
                "contextTemplate", "Approve?",
                "delegation", Map.of("channel", "carrier-pigeon", "credentialId", 42, "chatId", "123")));

            assertThat(result.getErrors()).anyMatch(e ->
                e.code().equals("APPROVAL_DELEGATION_UNKNOWN_CHANNEL"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"slack", "discord", "whatsapp", "teams"})
        @DisplayName("Every connector service is a known channel, clean with no chatId or credential (the connected destination is used)")
        void connectorChannelsAreKnownAndNeedNoDestination(String channel) {
            ValidationResult result = validate(Map.of(
                "contextTemplate", "Approve?",
                "delegation", Map.of("channel", channel)));

            assertThat(result.getErrors()).noneMatch(e -> DELEGATION_CODES.contains(e.code()));
            assertThat(result.getWarnings()).noneMatch(w -> DELEGATION_CODES.contains(w.code()));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"#ops", "@alice"})
        @DisplayName("a Slack chat given by name warns: a press comes back with the channel ID")
        void slackNameWarns(String named) {
            ValidationResult result = validate(Map.of(
                "contextTemplate", "Approve?",
                "delegation", Map.of("channel", "slack", "chatId", named)));

            assertThat(result.getWarnings()).anyMatch(w -> w.code().equals("APPROVAL_DELEGATION_CHAT_NAME"));
            assertThat(validate(Map.of("contextTemplate", "Approve?",
                "delegation", Map.of("channel", "slack", "chatId", "C0123"))).getWarnings())
                .noneMatch(w -> w.code().equals("APPROVAL_DELEGATION_CHAT_NAME"));
        }

        @Test
        @DisplayName("Teams with allowedUserIds warns: a link does not say who opened it, so every press would be refused")
        void teamsAllowListIsFlagged() {
            ValidationResult result = validate(Map.of(
                "contextTemplate", "Approve?",
                "delegation", Map.of("channel", "teams", "allowedUserIds", List.of("u1"))));

            assertThat(result.getWarnings()).anyMatch(w ->
                w.code().equals("APPROVAL_DELEGATION_ALLOWLIST_UNENFORCEABLE"));
        }

        @Test
        @DisplayName("A connector service with a non-numeric credentialId still warns")
        void connectorChannelWithBadCredentialWarns() {
            ValidationResult result = validate(Map.of(
                "contextTemplate", "Approve?",
                "delegation", Map.of("channel", "slack", "credentialId", "my-slack")));

            assertThat(result.getWarnings()).anyMatch(w ->
                w.code().equals("APPROVAL_DELEGATION_INVALID_CREDENTIAL"));
        }

        @Test
        @DisplayName("regression: Telegram WITHOUT a credentialId is clean (the send falls back to the user's own Telegram credential)")
        void missingCredentialIsClean() {
            // Pre-fix this warned NO_CREDENTIAL and the run silently sent nothing. The
            // agent-built shape almost never carries a numeric credential id; absent now
            // means default-credential fallback, so validation must not cry wolf.
            ValidationResult result = validate(Map.of(
                "contextTemplate", "Approve?",
                "delegation", Map.of("channel", "telegram", "chatId", "123")));

            assertThat(result.getErrors()).noneMatch(e -> DELEGATION_CODES.contains(e.code()));
            assertThat(result.getWarnings()).noneMatch(w -> DELEGATION_CODES.contains(w.code()));
        }

        @Test
        @DisplayName("Telegram without a chatId is clean: the workspace's connected Telegram destination is used")
        void missingChatIdIsClean() {
            ValidationResult result = validate(Map.of(
                "contextTemplate", "Approve?",
                "delegation", Map.of("channel", "telegram", "credentialId", 42)));

            assertThat(result.getWarnings()).noneMatch(w ->
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

        @Test
        @DisplayName("regression: a numeric-string credentialId (LLM-quoted \"40\") raises no credential finding")
        void numericStringCredentialIdDoesNotWarn() {
            // Pre-fix the instanceof Number check warned on "40" even though the
            // creator/parser coerce that shape, producing a misleading finding.
            ValidationResult result = validate(Map.of(
                "contextTemplate", "Approve?",
                "delegation", Map.of("channel", "telegram", "credentialId", "40", "chatId", "123")));

            assertThat(result.getWarnings()).noneMatch(w ->
                w.code().equals("APPROVAL_DELEGATION_INVALID_CREDENTIAL"));
        }

        @Test
        @DisplayName("A present-but-non-numeric credentialId warns INVALID_CREDENTIAL (it will be ignored at run time)")
        void nonNumericCredentialIdWarnsInvalid() {
            ValidationResult result = validate(Map.of(
                "contextTemplate", "Approve?",
                "delegation", Map.of("channel", "telegram", "credentialId", "not-a-number", "chatId", "123")));

            assertThat(result.getWarnings()).anyMatch(w ->
                w.code().equals("APPROVAL_DELEGATION_INVALID_CREDENTIAL"));
        }
    }

    @Nested
    @DisplayName("email_inbox actions")
    class EmailInboxActionTests {

        private ValidationResult validate(Map<String, Object> emailInbox) {
            Map<String, Object> core = Map.of(
                "type", "email_inbox", "label", "File Mail", "emailInbox", emailInbox);
            when(session.getCores()).thenReturn(List.of(core));
            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);
            return result;
        }

        @Test
        @DisplayName("create_folder requires targetFolder (it names the folder to create)")
        void createFolderRequiresTargetFolder() {
            ValidationResult result = validate(Map.of("action", "create_folder"));

            assertThat(result.getErrors()).anyMatch(e -> e.code().equals("INBOX_NO_TARGET_FOLDER"));
        }

        @Test
        @DisplayName("create_folder must NOT require messageUid: it is a mailbox-level action, not a per-message one")
        void createFolderDoesNotRequireMessageUid() {
            // create_folder ships with this change, so nothing regressed: this guards the branch.
            // The generic rule here demands a messageUid for every action but none/list_folders,
            // so without its own branch create_folder would be rejected with a nonsensical error.
            ValidationResult result = validate(Map.of(
                "action", "create_folder", "targetFolder", "INBOX.Clients"));

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("INBOX_NO_MESSAGE_UID"));
            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("INBOX_NO_TARGET_FOLDER"));
        }

        @Test
        @DisplayName("move still requires both messageUid and targetFolder")
        void moveStillRequiresUidAndTargetFolder() {
            ValidationResult result = validate(Map.of("action", "move"));

            assertThat(result.getErrors()).anyMatch(e -> e.code().equals("INBOX_NO_MESSAGE_UID"));
            assertThat(result.getErrors()).anyMatch(e -> e.code().equals("INBOX_NO_TARGET_FOLDER"));
        }

        @Test
        @DisplayName("delete still requires messageUid but not targetFolder")
        void deleteRequiresOnlyMessageUid() {
            ValidationResult result = validate(Map.of("action", "delete"));

            assertThat(result.getErrors()).anyMatch(e -> e.code().equals("INBOX_NO_MESSAGE_UID"));
            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("INBOX_NO_TARGET_FOLDER"));
        }

        @Test
        @DisplayName("list_folders needs neither messageUid nor targetFolder")
        void listFoldersNeedsNothing() {
            ValidationResult result = validate(Map.of("action", "list_folders"));

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("INBOX_NO_MESSAGE_UID"));
            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("INBOX_NO_TARGET_FOLDER"));
        }
    }

    /**
     * LC-018: the code body is the one parameter that becomes executable source. A {{...}}
     * expression there splices an upstream value into JavaScript, TypeScript, Python or Bash,
     * so a plan is refused while it is still being written and the author is pointed at the
     * input object, which carries the same data without ever being parsed as code.
     */
    @Nested
    @DisplayName("Code body must not carry a template expression")
    class CodeTemplateTests {

        private ValidationResult validate(Object codeField) {
            Map<String, Object> core = new HashMap<>();
            core.put("type", "code");
            core.put("label", "Process");
            core.put("code", codeField);
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);
            return result;
        }

        @Test
        @DisplayName("Should only WARN about a {{...}} expression inside a string literal (saved workflows keep validating)")
        void shouldWarnAboutTemplateInsideStringLiteral() {
            ValidationResult result = validate(Map.of(
                    "language", "javascript",
                    "code", "const subject = '{{mcp:fetch_mail.output.subject}}';"));

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("CODE_TEMPLATE_IN_BODY"));
            assertThat(result.getWarnings()).anyMatch(w -> w.code().equals("CODE_TEMPLATE_IN_BODY"));
        }

        @Test
        @DisplayName("Should WARN, not block, on a {{...}} expression at a code position: the run splices it as data, and existing workflows must keep saving")
        void shouldWarnOnTemplateAtCodePosition() {
            ValidationResult result = validate(Map.of(
                    "language", "javascript",
                    "code", "const n = {{mcp:fetch_mail.output.count}};"));

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("CODE_TEMPLATE_IN_BODY"));
            assertThat(result.getWarnings()).anyMatch(w -> w.code().equals("CODE_TEMPLATE_IN_BODY")
                    && w.message().contains("{{mcp:fetch_mail.output.count}}"));
        }

        @Test
        @DisplayName("Should name the input object so the author knows what to write instead")
        void shouldTellTheAuthorToUseTheInputObject() {
            ValidationResult result = validate(Map.of(
                    "language", "python",
                    "code", "subject = {{mcp:fetch_mail.output.subject}}"));

            String message = result.getWarnings().stream()
                    .filter(w -> w.code().equals("CODE_TEMPLATE_IN_BODY"))
                    .map(w -> w.message())
                    .findFirst()
                    .orElseThrow();
            assertThat(message).contains("$input").contains("_input").contains("INPUT");
            assertThat(message).contains("workflow(action='modify'");
        }

        @Test
        @DisplayName("Should warn about it on the flat code shape an exported plan carries too")
        void shouldWarnOnTemplateOnFlatCodeShape() {
            ValidationResult result = validate("$output = {{trigger:start.output.name}};");

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("CODE_TEMPLATE_IN_BODY"));
            assertThat(result.getWarnings()).anyMatch(w -> w.code().equals("CODE_TEMPLATE_IN_BODY"));
        }

        @Test
        @DisplayName("Should accept a code body that reads the input object")
        void shouldAcceptInputObjectRead() {
            ValidationResult result = validate(Map.of(
                    "language", "javascript",
                    "code", "$output = { subject: $input.fetch_mail.output.subject };"));

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("CODE_TEMPLATE_IN_BODY"));
            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("CODE_NO_CODE"));
        }

        @Test
        @DisplayName("Should not flag a lone brace pair that is not an expression")
        void shouldNotFlagPlainBraces() {
            ValidationResult result = validate(Map.of(
                    "language", "javascript",
                    "code", "const empty = {}; if (x) { doWork(); }"));

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("CODE_TEMPLATE_IN_BODY"));
        }

        @Test
        @DisplayName("Should still report a missing code body without a template error")
        void shouldStillReportMissingCode() {
            ValidationResult result = validate(Map.of("language", "javascript"));

            assertThat(result.getErrors()).anyMatch(e -> e.code().equals("CODE_NO_CODE"));
            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("CODE_TEMPLATE_IN_BODY"));
        }

        /**
         * The guard used to be a bare {@code contains("{{")}, which refused source the engine
         * never touches. It now matches with the engine's own expression pattern, so authoring
         * and execution agree on what a placeholder is. A doubled brace holding a nested closing
         * brace is not an expression: the run leaves it as written, so the save must too.
         */
        @Test
        @DisplayName("Should not refuse a doubled brace the template engine would never resolve")
        void shouldNotRefuseADoubledBraceThatIsNotAnExpression() {
            ValidationResult result = validate(Map.of(
                    "language", "javascript",
                    "code", "if (ok) {{ a } b }}\n$output = { ok };"));

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("CODE_TEMPLATE_IN_BODY"));
        }
    }

    /**
     * LC-018: WHERE a placeholder sits decides which warning the author gets. Outside any string
     * literal the run splices the value as a complete literal (data, never source), so the body
     * does not do what it reads like; inside one the value is escaped into the literal as text.
     *
     * <p>Regression review 2026-09-29: this used to be a plan-SAVE refusal, which blocked every
     * existing workflow holding such a node from being saved again. It is a builder warning now,
     * and these cases pin the scanner that decides the wording, per language.
     */
    @Nested
    @DisplayName("Outside-literal warning: where the placeholder sits, per language")
    class OutsideLiteralWarningTests {

        /** The outside-literal warning for this body, or null when the validator does not raise it. */
        private String outsideLiteralWarning(String language, Object codeField) {
            Map<String, Object> core = new HashMap<>();
            core.put("type", "code");
            core.put("label", "Process");
            core.put("code", language == null ? codeField : Map.of("language", language, "code", codeField));
            when(session.getCores()).thenReturn(List.of(core));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);
            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("CODE_TEMPLATE_IN_BODY"));
            return result.getWarnings().stream()
                    .filter(w -> w.code().equals("CODE_TEMPLATE_IN_BODY"))
                    .map(w -> w.message())
                    .filter(m -> m.contains("outside any string literal"))
                    .findFirst()
                    .orElse(null);
        }

        @Test
        @DisplayName("Warns about a placeholder in bare statement position and names it")
        void warnsOnBareStatementPlaceholder() {
            String message = outsideLiteralWarning(
                    "javascript", "const o = {{core:build.output.obj}};");

            assertThat(message).isNotNull();
            assertThat(message).contains("Process").contains("{{core:build.output.obj}}");
            assertThat(message).contains("$input").contains("_input").contains("INPUT");
        }

        @Test
        @DisplayName("No outside-literal warning for a placeholder inside the string literal the author wrote around it")
        void noOutsideWarningInsideAStringLiteral() {
            assertThat(outsideLiteralWarning(
                    "javascript", "const s = '{{trigger:start.output.name}}';"))
                    .isNull();
            assertThat(outsideLiteralWarning(
                    "javascript", "const s = \"{{trigger:start.output.name}}\";"))
                    .isNull();
            assertThat(outsideLiteralWarning(
                    "javascript", "const s = `{{trigger:start.output.name}}`;"))
                    .isNull();
        }

        @Test
        @DisplayName("Warns about a placeholder in a JavaScript interpolation, which is code again")
        void warnsOnPlaceholderInsideAnInterpolation() {
            assertThat(outsideLiteralWarning(
                    "javascript", "const s = `total ${ {{core:sum.output.n}} }`;"))
                    .isNotNull();
        }

        @Test
        @DisplayName("Warns about a placeholder in a comment, where nothing escapes a comment terminator")
        void warnsOnPlaceholderInAComment() {
            assertThat(outsideLiteralWarning(
                    "javascript", "/* see {{trigger:start.output.name}} */\n$output = {};"))
                    .isNotNull();
        }

        @Test
        @DisplayName("Bash: both quoted words are safe, a bare or substituted position is not")
        void bashQuotingDecidesTheVerdict() {
            assertThat(outsideLiteralWarning(
                    "bash", "SUBJECT='{{mcp:mail.output.subject}}'"))
                    .isNull();
            // A double-quoted word expands $, ` and \, but a backslash escapes each of them there,
            // so the value can be made data IN PLACE and the everyday echo "{{...}}" draws no warning.
            assertThat(outsideLiteralWarning(
                    "bash", "SUBJECT=\"{{mcp:mail.output.subject}}\""))
                    .isNull();
            // A bare word has no delimiters at all.
            assertThat(outsideLiteralWarning(
                    "bash", "SUBJECT={{mcp:mail.output.subject}}"))
                    .isNotNull();
            // Inside a command substitution the shell re-parses: escaping for the surrounding
            // quotes would leave the value as an argument of a command that still runs.
            assertThat(outsideLiteralWarning(
                    "bash", "echo \"$(cat {{mcp:mail.output.subject}})\""))
                    .isNotNull();
            assertThat(outsideLiteralWarning(
                    "bash", "echo \"${x:-{{mcp:mail.output.subject}}}\""))
                    .isNotNull();
            assertThat(outsideLiteralWarning(
                    "bash", "echo \"`cat {{mcp:mail.output.subject}}`\""))
                    .isNotNull();
        }

        @Test
        @DisplayName("Python: a triple-quoted literal is a literal")
        void pythonTripleQuotedIsALiteral() {
            assertThat(outsideLiteralWarning(
                    "python", "s = \"\"\"{{mcp:mail.output.subject}}\"\"\""))
                    .isNull();
            assertThat(outsideLiteralWarning(
                    "python", "s = {{mcp:mail.output.subject}}"))
                    .isNotNull();
        }

        @Test
        @DisplayName("Reads the flat code shape an exported plan carries, and defaults to javascript")
        void readsTheFlatShape() {
            assertThat(outsideLiteralWarning(null, "const o = {{core:build.output.obj}};")).isNotNull();
        }

        /**
         * The checked-in end-to-end fixture (buildCodeTaskWorkflowPlan) creates its workflow
         * through the plan-save API, and its code body templates four values. Every one of them
         * is written inside the quotes the author typed, so it must not draw the outside-literal
         * warning, which would tell the author their working code does not do what it reads like.
         */
        @Test
        @DisplayName("No outside-literal warning on the checked-in e2e fixture body, quote by quote")
        void acceptsTheCheckedInFixtureBody() {
            String fixtureBody = String.join("\n",
                    "console.log('processed {{item.name}}');",
                    "$output = {",
                    "  name: '{{item.name}}',",
                    "  marker: '{{trigger:start.output.marker}}',",
                    "  doubled: Number('{{item.score}}') * 2,",
                    "  itemIndex: Number('{{index}}')",
                    "};");

            assertThat(outsideLiteralWarning(
                    "javascript", fixtureBody)).isNull();
        }
    }
}
