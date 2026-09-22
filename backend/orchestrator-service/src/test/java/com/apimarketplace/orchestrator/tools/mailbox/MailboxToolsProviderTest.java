package com.apimarketplace.orchestrator.tools.mailbox;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionServiceInjector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.Optional;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.nodes.EmailInboxNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.SendEmailNode;

/**
 * The door an agent uses to reach a mailbox, and the four ways it refuses to open.
 *
 * <p>Everything past these guards is {@code EmailInboxNode} and {@code SendEmailNode}, which
 * have their own suites and their own IMAP and SMTP clients. Nothing is re-tested here, and
 * nothing is re-implemented there: the value of this tool is precisely that it adds no second
 * copy of 1200 lines of mail handling that would drift from the first.
 */
class MailboxToolsProviderTest {

    private MailboxToolsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MailboxToolsProvider(mock(ExecutionServiceInjector.class));
    }

    private ToolExecutionResult run(Map<String, Object> params) {
        return provider.execute("mailbox", params, ToolExecutionContext.of("tenant-1"));
    }

    @Test
    @DisplayName("an unknown action is refused with the list of the real ones")
    void refusesUnknownAction() {
        ToolExecutionResult result = run(Map.of("action", "archive_everything"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ENUM_VALUE);
        assertThat(result.error()).contains("read").contains("send").contains("folders");
    }

    @Test
    @DisplayName("a missing action says what the actions are rather than that one is needed")
    void refusesMissingAction() {
        ToolExecutionResult result = run(new LinkedHashMap<>());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        assertThat(result.error()).contains("read");
    }

    /**
     * Never guessed and never defaulted. A mailbox resolved under the wrong tenant is someone
     * else's mail, so the absence of an account is a refusal and not a fallback.
     */
    @Test
    @DisplayName("no tenant on the call means no mailbox is resolved at all")
    void refusesWithoutATenant() {
        ToolExecutionResult result = provider.execute("mailbox", Map.of("action", "read"),
                ToolExecutionContext.of(null));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.TENANT_NOT_FOUND);
        assertThat(result.error()).contains("no account");
    }

    /**
     * Acting on a message without having read one means inventing a uid. The refusal names
     * where a real one comes from, because an agent that is told only "message_uid is required"
     * will supply a plausible number.
     */
    @Test
    @DisplayName("a single-message action without message_uid is told to read the folder first")
    void refusesMessageActionWithoutUid() {
        for (String action : new String[]{"mark_read", "mark_unread", "flag", "unflag", "move", "delete"}) {
            ToolExecutionResult result = run(Map.of("action", action));

            assertThat(result.success()).as("%s acts on one message", action).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(result.error()).contains("message_uid").contains("action='read'");
        }
    }

    /**
     * The node supports creating a folder and the first version of this tool simply did not
     * offer it, so an agent asked to file mail into a new folder could move nothing anywhere.
     * Checked here as an ACTION, because the gap was an omission from the action list.
     */
    @Test
    @DisplayName("create_folder is offered, and it is the node's own action name")
    void offersFolderCreation() {
        assertThat(MailboxToolsProvider.validActions()).contains("create_folder");
        assertThat(MailboxToolsProvider.inboxConfig("create_folder", null,
                Map.of("target_folder", "Archive")).action())
                .isEqualTo("create_folder");
    }

    /**
     * Both actions take a folder NAME and neither can invent one. Refused here rather than at
     * the node, so the message can point at the action that lists what already exists.
     */
    @Test
    @DisplayName("create_folder and move refuse without target_folder, and say where to look")
    void refusesFolderActionsWithoutATarget() {
        for (Map<String, Object> call : java.util.List.<Map<String, Object>>of(
                Map.of("action", "create_folder"),
                Map.of("action", "move", "message_uid", "4711"))) {
            ToolExecutionResult result = run(call);

            assertThat(result.success()).as("%s", call).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(result.error()).contains("target_folder").contains("action='folders'");
        }
    }

    /**
     * A move with neither value is ONE mistake, not two. Answering them one at a time costs the
     * caller a round trip to be told the second half of what it could have been told at once,
     * and an agent that has to guess which half comes first will guess wrong.
     */
    @Test
    @DisplayName("a move missing both values names both in one refusal")
    void reportsEveryMissingValueAtOnce() {
        ToolExecutionResult result = run(Map.of("action", "move"));

        assertThat(result.error())
                .contains("message_uid")
                .contains("target_folder")
                .contains(" and ");
    }

    @Test
    @DisplayName("send refuses without a recipient or a subject, before any SMTP connection")
    void refusesIncompleteSend() {
        assertThat(run(Map.of("action", "send", "subject", "hi")).error()).contains("`to`");
        assertThat(run(Map.of("action", "send", "to", "a@example.com")).error()).contains("`subject`");
    }

    @Test
    @DisplayName("help answers without touching a mailbox")
    void helpNeedsNothing() {
        ToolExecutionResult result = run(Map.of("action", "help"));

        assertThat(result.success()).isTrue();
        assertThat(String.valueOf(result.data()))
                .as("the two credentials are separate and an agent that does not know that "
                        + "reports the wrong one as missing")
                .contains("IMAP")
                .contains("SMTP");
    }

    /**
     * The help describes what the NODES return, and it described three shapes that do not exist:
     * {@code message_id} (the node emits {@code messageId}), and an invented
     * {@code {action, uid, applied}} for every single-message action, where
     * {@code EmailInboxNode.applyAction} actually emits {@code {action, messageUid, folder}}.
     *
     * <p>That is not cosmetic. The help's own sequence told the agent to take a
     * {@code message_id} from a message and pass it to {@code send(in_reply_to=...)}; followed
     * literally it reads an absent key and threads nothing. It survived three review passes
     * because every test here mocks the injector, so no test ever saw a real node's output.
     *
     * <p>The expected names below are the keys the two nodes put on their result maps:
     * {@code toMessageMap}, {@code readMessages}, {@code applyAction}, {@code listFolders},
     * {@code createFolder} in EmailInboxNode, and {@code execute} in SendEmailNode.
     */
    @Test
    @DisplayName("the help names the fields the nodes really return, not invented ones")
    void helpMatchesTheNodesRealOutput() {
        String help = String.valueOf(MailboxToolDefinition.help());

        assertThat(help)
                .as("camelCase, and messageId is NOT message_uid: one threads a reply, "
                        + "the other addresses a message in a folder")
                .contains("messageId")
                .contains("messageUid")
                .contains("newMessageUid")
                .contains("bodyHtml")
                .contains("hasAttachments");
        assertThat(help)
                .as("the names the first version invented")
                .doesNotContain("message_id")
                .doesNotContain("applied");
    }

    /**
     * Naming a field is not enough: an agent reads the documented SHAPE and indexes into it.
     *
     * <p>{@code readMessages} and {@code applyAction} return only a handful of keys at the top
     * ({@code messages}/{@code count}/{@code folder}, and {@code action}/{@code messageUid}/
     * {@code folder}). Everything the caller SENT is echoed under {@code resolved_params}
     * instead, because {@code execute} nests it there. A help that listed {@code limit} or
     * {@code targetFolder} beside {@code count} would read as top-level, and
     * {@code output.targetFolder} is empty on every single move.
     */
    @Test
    @DisplayName("the help says which fields are nested under resolved_params rather than top-level")
    void helpDistinguishesTopLevelFromNested() {
        // Read the structured payload, not its toString: `limit` also appears in the PARAMS
        // line, where it is perfectly correct, and a substring search would pass on that and
        // never look at the returns shape, which is the half that was wrong.
        assertThat(returnsOf("read"))
                .as("the top-level keys readMessages actually puts")
                .contains("messages").contains("count")
                .as("and the filters it echoes back, which execute nests")
                .contains("resolved_params (limit, unreadOnly, flaggedOnly)");
        assertThat(returnsOf("message actions"))
                .contains("action").contains("messageUid").contains("newMessageUid")
                .as("targetFolder is echoed under resolved_params; documented flat, "
                        + "output.targetFolder is empty on every single move")
                .contains("resolved_params.targetFolder");
    }

    @SuppressWarnings("unchecked")
    private static String returnsOf(String action) {
        Map<String, Object> actions =
                (Map<String, Object>) MailboxToolDefinition.help().get("actions");
        Map<String, Object> entry = (Map<String, Object>) actions.get(action);
        assertThat(entry).as("the help documents the '%s' action", action).isNotNull();
        return String.valueOf(entry.get("returns"));
    }

    /**
     * Same claim, on the other surface. The prompt line is read before any call, so an agent
     * that never opens the help still learns the field name from it.
     */
    @Test
    @DisplayName("the prompt line names the same field as the help")
    void promptLineAgreesWithTheHelp() {
        String line = com.apimarketplace.agent.prompt.DefaultSystemPrompts.MAILBOX.promptSection();

        assertThat(line).contains("messageId");
        assertThat(line).doesNotContain("message_id");
    }

    @Test
    @DisplayName("the tool is declared with action required and every action it accepts")
    void declaresItself() {
        AgentToolDefinition definition = provider.getTools().get(0);

        assertThat(definition.name()).isEqualTo("mailbox");
        assertThat(definition.requiredParameters()).containsExactly("action");
        assertThat(definition.description())
                .as("a caller must not believe it can pass a host and a password")
                .contains("Neither is something you can supply in the call");
        assertThat(definition.parameters())
                .extracting(com.apimarketplace.agent.domain.ToolParameter::name)
                .contains("action", "folder", "message_uid", "to", "subject", "credential_id");
    }

    /**
     * The one failure a mocked collaborator can never show.
     *
     * <p>This provider is a {@code @Component}, so Spring resolves its constructor argument
     * from the context at startup. The first version asked for a {@code ServiceRegistry},
     * which is NOT a bean: it is built per injection by {@code ExecutionServiceInjector} out
     * of the beans of the moment. Every test above passed, because each one hands the
     * constructor a mock, and the whole orchestrator context would have failed to start.
     *
     * <p>So the assertion is about the TYPE, not about behaviour: whatever this provider is
     * constructed from has to be something Spring can supply.
     */
    @Test
    @DisplayName("every constructor argument is a type Spring can actually supply")
    void dependsOnlyOnSpringBeans() {
        var constructors = MailboxToolsProvider.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);

        for (Class<?> dependency : constructors[0].getParameterTypes()) {
            boolean isSpringManaged =
                    dependency.isAnnotationPresent(org.springframework.stereotype.Component.class)
                            || dependency.isAnnotationPresent(org.springframework.stereotype.Service.class)
                            || dependency.isAnnotationPresent(org.springframework.stereotype.Repository.class);
            assertThat(isSpringManaged)
                    .as("%s is injected into a @Component, so it must carry a stereotype or the "
                            + "orchestrator context fails to start", dependency.getName())
                    .isTrue();
        }
    }

    /**
     * Sixteen positional arguments, four of them ints and five booleans. Swapping limit with
     * sinceDays, or markSeen with unreadOnly, compiles and runs and reads the wrong mailbox
     * with nothing to show for it, so the mapping is pinned field by field.
     */
    @Test
    @DisplayName("every agent parameter lands on the node field of the same meaning")
    void mapsEveryParameterToTheRightField() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("folder", "Archive");
        params.put("limit", 7);
        params.put("unread_only", true);
        params.put("since_days", 3);
        params.put("before_days", 11);
        params.put("mark_seen", true);
        params.put("flagged_only", true);
        params.put("from_contains", "billing@");
        params.put("subject_contains", "invoice");
        params.put("body_contains", "overdue");
        params.put("target_folder", "Done");
        params.put("create_target_if_missing", true);
        params.put("credential_id", 41);

        var config = MailboxToolsProvider.inboxConfig("move", "4711", params);

        assertThat(config.folder()).isEqualTo("Archive");
        assertThat(config.limit()).isEqualTo(7);
        assertThat(config.sinceDays()).isEqualTo(3);
        assertThat(config.beforeDays()).isEqualTo(11);
        assertThat(config.unreadOnly()).isTrue();
        assertThat(config.markSeen()).isTrue();
        assertThat(config.flaggedOnly()).isTrue();
        assertThat(config.fromContains()).isEqualTo("billing@");
        assertThat(config.subjectContains()).isEqualTo("invoice");
        assertThat(config.bodyContains()).isEqualTo("overdue");
        assertThat(config.targetFolder()).isEqualTo("Done");
        assertThat(config.createTargetIfMissing()).isTrue();
        assertThat(config.credentialId()).isEqualTo(41L);
        assertThat(config.action()).isEqualTo("move");
        assertThat(config.messageUid()).isEqualTo("4711");
    }

    /**
     * Never true on this path. The node stores attachment bytes and hands back file references,
     * which is right for a workflow that goes on to use them and wrong for a chat turn that
     * asked to look at an inbox: it would pull megabytes per message into storage nobody asked
     * for. There is no parameter to turn it on, and that is the point.
     */
    @Test
    @DisplayName("attachment bytes are never downloaded, whatever the call says")
    void neverDownloadsAttachments() {
        Map<String, Object> asking = new LinkedHashMap<>();
        asking.put("downloadAttachments", true);
        asking.put("download_attachments", true);

        assertThat(MailboxToolsProvider.inboxConfig("none", null, asking).downloadAttachments())
                .isFalse();
    }

    /**
     * The node's vocabulary is a workflow author's. An agent reading {@code action='none'}
     * against a mailbox cannot tell what it would do, so the tool renames the two, and the
     * translation is what makes the node run the right mode.
     */
    @Test
    @DisplayName("read and folders translate to the node's own action names")
    void translatesTheReadActions() {
        assertThat(MailboxToolsProvider.inboxConfig("none", null, Map.of()).action())
                .isEqualTo("none");
        assertThat(MailboxToolsProvider.inboxConfig("list_folders", null, Map.of()).action())
                .isEqualTo("list_folders");
    }

    @Test
    @DisplayName("defaults match what the tool documents: INBOX, 10 messages, no filters")
    void appliesTheDocumentedDefaults() {
        var config = MailboxToolsProvider.inboxConfig("none", null, Map.of());

        assertThat(config.folder()).isEqualTo("INBOX");
        assertThat(config.limit()).isEqualTo(10);
        assertThat(config.sinceDays()).isZero();
        assertThat(config.unreadOnly()).isFalse();
        assertThat(config.markSeen()).isFalse();
        assertThat(config.credentialId()).isNull();
    }

    /** A context carrying the per-agent access mode and the workspace role. */
    private static ToolExecutionContext contextWith(String accessMode, String orgRole) {
        Map<String, Object> credentials = new LinkedHashMap<>();
        if (accessMode != null) {
            credentials.put("mailboxAccessMode", accessMode);
        }
        return new ToolExecutionContext("tenant-1", credentials, Map.of(), java.util.Set.of(),
                null, null, orgRole == null ? null : "org-1", orgRole);
    }

    /**
     * The per-agent axis, the one every comparable tool carries (files, memory, table...) and
     * the one mailbox shipped without. An inbox scanner configured read-only must keep scanning
     * and must not be able to send from the account it is reading.
     */
    @Test
    @DisplayName("mailboxAccessMode=read keeps the reads and refuses the writes")
    void honoursTheReadOnlyAccessMode() {
        ToolExecutionContext readOnly = contextWith("read", null);

        for (String write : new String[]{"send", "delete", "move", "flag", "unflag", "create_folder", "mark_unread"}) {
            ToolExecutionResult result = provider.execute("mailbox", Map.of("action", write), readOnly);
            assertThat(result.success()).as("%s writes", write).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        }
        // Reads short-circuit before the gate, so they fail later and for another reason.
        for (String read : new String[]{"read", "folders", "mark_read"}) {
            ToolExecutionResult result = provider.execute("mailbox", Map.of(
                    "action", read, "message_uid", "4711"), readOnly);
            assertThat(result.errorCode())
                    .as("%s is a read and must not be refused on permission", read)
                    .isNotEqualTo(ToolErrorCode.PERMISSION_DENIED);
        }
    }

    /**
     * mark_read is a READ on purpose although it sets a flag: a read-only scanner that cannot
     * mark what it processed re-reads the same messages forever, which is exactly the agent
     * this mode exists to allow.
     */
    @Test
    @DisplayName("an absent access mode is full access, as it is everywhere else")
    void absentAccessModeIsFullAccess() {
        ToolExecutionResult result = provider.execute("mailbox",
                Map.of("action", "send", "to", "a@example.com", "subject", "hi"),
                contextWith(null, null));

        assertThat(result.errorCode()).isNotEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    /**
     * A different axis, and not the other one's fallback: the access mode is per agent and set
     * by whoever configured it, the role is per person and set by the workspace. A VIEWER is
     * read-only everywhere else on this platform, and the workspace's shared SMTP account is
     * not the exception.
     */
    @Test
    @DisplayName("a VIEWER cannot send or delete, whatever the agent's access mode says")
    void viewerCannotWriteEvenWithFullAccessMode() {
        ToolExecutionContext viewer = contextWith("write", "VIEWER");

        ToolExecutionResult send = provider.execute("mailbox",
                Map.of("action", "send", "to", "a@example.com", "subject", "hi"), viewer);
        assertThat(send.success()).isFalse();
        assertThat(send.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(send.error())
                .as("the refusal has to say it is the person's role, not the agent's config, "
                        + "or the reader goes and edits the wrong setting")
                .contains("VIEWER")
                .contains("Reading it still works");

        assertThat(provider.execute("mailbox", Map.of("action", "read"), viewer).errorCode())
                .as("a VIEWER still reads")
                .isNotEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    /**
     * The in-process agent loop namespaces its credentials; the tool controllers do not. A gate
     * that honoured only the plain spelling would be enforced on the remote route and wide open
     * on the loop route, for the same agent and the same configured restriction.
     */
    @Test
    @DisplayName("the namespaced credential key gates exactly as the plain one does")
    void honoursTheNamespacedAccessModeKey() {
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("__mailboxAccessMode__", "read");
        ToolExecutionContext loop = new ToolExecutionContext("tenant-1", credentials, Map.of(),
                java.util.Set.of(), null, null, null, null);

        ToolExecutionResult send = provider.execute("mailbox",
                Map.of("action", "send", "to", "a@example.com", "subject", "hi"), loop);
        assertThat(send.success()).isFalse();
        assertThat(send.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);

        assertThat(provider.execute("mailbox", Map.of("action", "read"), loop).errorCode())
                .as("the read half must behave the same under either spelling too")
                .isNotEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    /**
     * A name in {@code SENSITIVE_ACTIONS} that this provider never dispatches is a card that
     * never appears, and it is invisible: the policy map is a plain string set, so a typo there
     * compiles, passes a test that hardcodes the same typo, and quietly un-gates the one action
     * that reaches a person. Cross-checking against the dispatcher is what makes it loud.
     */
    @Test
    @DisplayName("every gated action is an action this tool actually has")
    void gatedActionsExistInTheDispatcher() {
        java.util.Set<String> gated =
                com.apimarketplace.agent.tools.authz.ToolAuthorizationPolicy.SENSITIVE_ACTIONS.get("mailbox");

        assertThat(gated).isNotEmpty();
        assertThat(MailboxToolsProvider.validActions())
                .as("a gated action the dispatcher does not know is a gate on nothing")
                .containsAll(gated);
        assertThat(gated)
                .as("the two irreversible ones: send reaches a person, delete removes what only "
                        + "the provider still holds")
                .containsExactlyInAnyOrder("send", "delete");
    }

    @Test
    @DisplayName("a member role is not a VIEWER and keeps its writes")
    void memberKeepsItsWrites() {
        ToolExecutionResult result = provider.execute("mailbox",
                Map.of("action", "send", "to", "a@example.com", "subject", "hi"),
                contextWith(null, "MEMBER"));

        assertThat(result.errorCode()).isNotEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("another tool name is not handled by this provider")
    void handlesOnlyItsOwnTool() {
        ToolExecutionResult result = provider.execute("table", Map.of("action", "read"),
                ToolExecutionContext.of("tenant-1"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
    }
    private void verifyExplicitChoice(String action, Object credentialId, String integration) {
        CredentialClient credentials = mock(CredentialClient.class);
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.getCredentialClient()).thenReturn(credentials);
        when(credentials.getCredentialById(anyString(), anyLong())).thenReturn(Optional.empty());
        // Stop before any network connection, even if the wrong mailbox is requested.
        when(credentials.getDefaultCredential(anyString(), anyString())).thenReturn(Optional.empty());
        ExecutionServiceInjector injector = mock(ExecutionServiceInjector.class);
        doAnswer(invocation -> {
            Map<?, ?> nodes = invocation.getArgument(0);
            for (Object node : nodes.values()) {
                if (node instanceof EmailInboxNode inbox) inbox.acceptServices(registry);
                if (node instanceof SendEmailNode send) send.acceptServices(registry);
            }
            return null;
        }).when(injector).injectServices(anyMap());
        var result = new MailboxToolsProvider(injector).execute("mailbox", Map.of(
            "action", action, "credential_id", credentialId, "message_uid", "42",
            "to", "nobody@example.invalid", "subject", "Review probe"),
            ToolExecutionContext.of("review-tenant"));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("credential");
        if (credentialId instanceof Long id) {
            verify(credentials).getCredentialById("review-tenant", id);
        }
        verify(credentials, never()).getDefaultCredential("review-tenant", integration);
    }
    @Test @DisplayName("An explicit unavailable inbox never selects the default mailbox") void missingSelectedImapMustNotChooseAnotherMailbox() {
        verifyExplicitChoice("delete", 12L, "imap");
    }
    @Test @DisplayName("An explicit unavailable sender never selects the default account") void missingSelectedSmtpMustNotChooseAnotherSender() {
        verifyExplicitChoice("send", 12L, "smtp");
    }
    @Test @DisplayName("A malformed account selection never means the default mailbox") void malformedChoiceMustNotMeanDefaultMailbox() {
        verifyExplicitChoice("delete", "not-an-id", "imap");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "0", "-1", "1.5", "9223372036854775808", "NaN"})
    @DisplayName("Invalid credential identifiers are refused before executing a node")
    void rejectsInvalidCredentialIds(String value) {
        var result = run(Map.of("action", "read", "credential_id", value));
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
    }
}
