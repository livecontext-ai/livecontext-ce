package com.apimarketplace.orchestrator.tools.mailbox;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionServiceInjector;
import com.apimarketplace.orchestrator.execution.v2.nodes.EmailInboxNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.SendEmailNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reading and sending mail, for an agent that has no workflow.
 *
 * <p>IMAP and SMTP have been reachable from a workflow for a long time ({@code email_inbox}
 * and {@code send_email}), and reachable no other way. An agent in a chat could therefore
 * only get at a mailbox by building and running a whole workflow, which is why a scheduled
 * agent on this platform spent six days reporting "no executable IMAP read tool exists"
 * every thirty minutes while a perfectly good IMAP credential sat in its account. This tool
 * is that missing door, and nothing else: same protocol code, same credential, same
 * behaviour.
 *
 * <p><b>It duplicates nothing.</b> The IMAP client is 845 lines and the SMTP one 400, all of
 * it inside {@link EmailInboxNode} and {@link SendEmailNode}, and a second copy here would
 * have drifted from the first within a release. So this provider BUILDS those nodes and runs
 * them, handing them a synthetic {@link ExecutionContext} that carries the caller's tenant and
 * an empty plan. The nodes need the tenant to resolve their credential and the plan only to
 * resolve {@code {{templates}}}; an agent sends literal values, so an empty plan resolves them
 * to themselves. Every fix to mail handling therefore lands in both surfaces at once, which is
 * the property worth having here.
 *
 * <p><b>Credentials come from exactly where the workflow gets them.</b> The nodes call
 * {@code credentialClient.getDefaultCredential(tenantId, "imap" | "smtp")}, so a mailbox
 * connected once in Settings serves the chat agent and the workflow with no second setup, and
 * an account that is missing produces the node's own refusal rather than a variant of it.
 */
@Slf4j
@Component
public class MailboxToolsProvider implements ToolsProvider {

    static final String TOOL_NAME = "mailbox";

    /** Actions that act on ONE message and therefore need {@code message_uid}. */
    private static final Set<String> MESSAGE_ACTIONS =
            Core.EmailInboxConfig.MESSAGE_ACTIONS;

    private static final Set<String> VALID_ACTIONS = Set.of(
            "read", "folders", "create_folder", "send", "mark_read", "mark_unread",
            "flag", "unflag", "move", "delete", "help");

    /**
     * An empty plan: these nodes read it only to resolve templates, and there are none.
     *
     * <p>Built per call rather than held in a static. {@code WorkflowPlan} copies its lists
     * into mutable {@code ArrayList}s, so one shared instance would be mutable state visible
     * to every concurrent mailbox call on the pod. Nothing mutates it today, and an object
     * that costs nothing to build is not worth leaving as the thing that has to stay true.
     */
    private static WorkflowPlan emptyPlan() {
        return new WorkflowPlan(
                "mailbox-tool", null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), Map.of());
    }

    /**
     * The same injector the execution engine uses to service a node.
     *
     * <p>Not a {@code ServiceRegistry} directly: that is not a Spring bean, it is built per
     * injection by {@link ExecutionServiceInjector#injectServices(Map)} from the beans of the
     * moment. Asking for one here would have failed the whole orchestrator context at startup,
     * and a unit test that hands the provider a mock could never have shown it. Going through
     * the injector also means a mailbox opened from a chat is serviced identically to one
     * opened by a workflow step, which is the property that keeps the two from drifting.
     */
    private final ExecutionServiceInjector serviceInjector;

    public MailboxToolsProvider(ExecutionServiceInjector serviceInjector) {
        this.serviceInjector = serviceInjector;
    }

    /** Give a freshly built node the services the engine would give it. */
    private <T extends com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode> T serviced(T node) {
        serviceInjector.injectServices(Map.of(TOOL_NAME, node));
        return node;
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.UTILITY;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(MailboxToolDefinition.build());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters,
                                       ToolExecutionContext context) {
        if (!TOOL_NAME.equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }
        Map<String, Object> params = parameters == null ? Map.of() : parameters;
        String action = str(params, "action");
        if (action == null || action.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "action is required. Valid actions: " + sorted(VALID_ACTIONS));
        }
        action = action.trim().toLowerCase(Locale.ROOT);
        if (!VALID_ACTIONS.contains(action)) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE,
                    "Unknown action '" + action + "'. Valid actions: " + sorted(VALID_ACTIONS));
        }
        if ("help".equals(action)) {
            return ToolExecutionResult.success(MailboxToolDefinition.help());
        }

        String tenantId = context == null ? null : context.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            // Never guessed: a mailbox resolved under the wrong tenant would read someone
            // else's mail, so an absent tenant refuses instead of falling back.
            return ToolExecutionResult.failure(ToolErrorCode.TENANT_NOT_FOUND,
                    "This call carries no account, so no mailbox could be resolved and nothing ran.");
        }

        // Per-agent read/write axis, the same one files and memory carry. Reads short-circuit
        // inside checkWriteAccess, so a mailboxAccessMode='read' agent keeps scanning an inbox
        // and loses only send / move / delete / flag.
        var accessDenied = ToolAccessControl.checkWriteAccess(
                context.credentials(), TOOL_NAME, action);
        if (accessDenied.isPresent()) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, accessDenied.get());
        }

        // Workspace role, a DIFFERENT axis and not the other one's fallback: the access mode is
        // per agent and set by whoever configured it, the role is per person and set by the
        // workspace. A VIEWER is read-only everywhere else on this platform, and sending mail
        // from the workspace's shared account is not the exception.
        if (isViewer(context.orgRole()) && !ToolAccessControl.isReadAction(TOOL_NAME, action)) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                    "Your workspace role is read-only (VIEWER), so '" + action + "' is not allowed "
                            + "on this mailbox. Reading it still works. Only a workspace "
                            + "administrator can change that, and you cannot.");
        }

        try {
            return "send".equals(action)
                    ? send(params, tenantId)
                    : inbox(action, params, tenantId);
        } catch (IllegalArgumentException e) {
            // The node configs validate their own inputs and say what is wrong; that
            // sentence is better than anything this layer could write about it.
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, e.getMessage());
        } catch (Exception e) {
            log.error("mailbox({}) failed for tenant {}: {}", action, tenantId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "The mailbox call could not be completed: " + e.getMessage());
        }
    }

    /** READ and the single-message actions, both served by {@code email_inbox}. */
    private ToolExecutionResult inbox(String action, Map<String, Object> params, String tenantId) {
        // "read" and "folders" are this tool's names for what the node calls "none" and
        // "list_folders". The node's vocabulary is a workflow author's; an agent reading
        // action="none" against a mailbox cannot tell what it would do.
        String nodeAction = switch (action) {
            case "read" -> "none";
            case "folders" -> "list_folders";
            // create_folder keeps the node name: it says exactly what it does already.
            default -> action;
        };
        // Both missing values are reported TOGETHER. A move with neither uid nor target
        // folder is one mistake, not two, and answering them one at a time costs the caller
        // a round trip to be told the second half of what it could have been told at once.
        String messageUid = str(params, "message_uid");
        List<String> missing = new ArrayList<>();
        if (MESSAGE_ACTIONS.contains(nodeAction) && (messageUid == null || messageUid.isBlank())) {
            missing.add("message_uid, which is the `uid` of a message returned by action='read' "
                    + "(read the folder first)");
        }
        if (("create_folder".equals(nodeAction) || "move".equals(nodeAction))
                && str(params, "target_folder") == null) {
            missing.add("target_folder, the folder name to "
                    + ("move".equals(nodeAction) ? "move the message into" : "create")
                    + " (action='folders' lists what already exists)");
        }
        if (!missing.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "action='" + action + "' needs " + String.join(" and ", missing) + ".");
        }

        Core.EmailInboxConfig config = inboxConfig(nodeAction, messageUid, params);

        EmailInboxNode node = serviced(new EmailInboxNode(TOOL_NAME, config));
        return toToolResult(node.execute(syntheticContext(tenantId)), "mailbox(" + action + ")");
    }

    /**
     * The agent call, as the node config the engine understands.
     *
     * <p>Package-private and returned rather than built inline because the record takes SIXTEEN
     * positional arguments of which four are ints and five are booleans: swapping limit with
     * sinceDays, or markSeen with unreadOnly, compiles, runs, and reads the wrong mailbox
     * quietly. Nothing else in this class is worth a test as much as this mapping is.
     */
    static Core.EmailInboxConfig inboxConfig(String nodeAction, String messageUid,
                                             Map<String, Object> params) {
        return new Core.EmailInboxConfig(
                longOrNull(params, "credential_id"),
                str(params, "folder"),
                bool(params, "unread_only", false),
                intOr(params, "limit", 10),
                bool(params, "mark_seen", false),
                intOr(params, "since_days", 0),
                nodeAction,
                messageUid,
                str(params, "target_folder"),
                str(params, "from_contains"),
                str(params, "subject_contains"),
                str(params, "body_contains"),
                bool(params, "flagged_only", false),
                intOr(params, "before_days", 0),
                // Attachment BYTES are never downloaded on this path. The node stores them
                // and returns file references, which is right for a workflow that goes on to
                // use them and wrong for a chat turn that asked to look at an inbox: it would
                // pull megabytes per message into storage nobody asked for. Message metadata
                // still lists what is attached.
                false,
                bool(params, "create_target_if_missing", false));
    }

    /** SMTP send, served by {@code send_email}. */
    private ToolExecutionResult send(Map<String, Object> params, String tenantId) {
        String to = str(params, "to");
        if (to == null || to.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "action='send' needs `to`, the recipient address.");
        }
        String subject = str(params, "subject");
        if (subject == null || subject.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "action='send' needs `subject`.");
        }

        // Host, port, user and password are left null on purpose: SendEmailNode reads them
        // from the account's SMTP credential whenever they are absent, and an agent must not
        // be able to send through a server it names itself.
        Core.SendEmailConfig config = new Core.SendEmailConfig(
                null, 0, null, null, false,
                str(params, "from_email"),
                str(params, "from_name"),
                to,
                str(params, "cc"),
                str(params, "bcc"),
                subject,
                str(params, "body"),
                bool(params, "is_html", false),
                longOrNull(params, "credential_id"),
                str(params, "in_reply_to"),
                str(params, "references"),
                str(params, "reply_to"));

        SendEmailNode node = serviced(new SendEmailNode(TOOL_NAME, config));
        return toToolResult(node.execute(syntheticContext(tenantId)), "mailbox(send)");
    }

    /**
     * The context the nodes run in: the caller's tenant, and nothing else that matters.
     *
     * <p>The ids are fixed literals rather than random ones because nothing reads them here.
     * There is no run to correlate, no step output to store and no DAG to advance; the nodes
     * use them for log lines only. Giving them stable values keeps a mailbox call out of
     * anything that groups by run id.
     */
    private ExecutionContext syntheticContext(String tenantId) {
        return ExecutionContext.create(
                "mailbox-tool", "mailbox-tool", tenantId, "mailbox-tool", 0,
                new HashMap<>(), emptyPlan());
    }

    /**
     * A node result as a tool result.
     *
     * <p>A node failure carries its own sentence and it is the one to relay: it names the
     * mailbox, the folder or the missing credential, none of which this layer knows.
     */
    private ToolExecutionResult toToolResult(NodeExecutionResult result, String label) {
        if (result == null) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    label + " returned nothing, so nothing can be reported about it.");
        }
        if (result.isFailure()) {
            String error = result.errorMessage().orElse(label + " failed without saying why.");
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, error);
        }
        Map<String, Object> output = result.output() == null ? Map.of() : result.output();
        return ToolExecutionResult.success(new LinkedHashMap<>(output));
    }

    // ---- parameter readers, tolerant of the shapes different models emit ----

    private static String str(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean bool(Map<String, Object> params, String key, boolean fallback) {
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return fallback;
        String s = String.valueOf(v).trim();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        return fallback;
    }

    private static int intOr(Map<String, Object> params, String key, int fallback) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Long longOrNull(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        try {
            long id = new java.math.BigDecimal(String.valueOf(v).trim()).longValueExact();
            if (id <= 0) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new IllegalArgumentException(key + " must be a positive integer identifying the selected account.");
        }
    }

    private static String sorted(Set<String> values) {
        return String.join(", ", values.stream().sorted().toList());
    }

    /** The workspace's own read-only role, matched the way every other gate on the platform does. */
    private static boolean isViewer(String organizationRole) {
        return organizationRole != null && "VIEWER".equalsIgnoreCase(organizationRole.trim());
    }

    /** Exposed for the tool definition, which lists the same actions. */
    static Set<String> validActions() {
        return VALID_ACTIONS;
    }
}
