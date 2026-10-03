package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.execution.v2.nodes.CodeNode;
import com.apimarketplace.orchestrator.execution.v2.split.SplitNodeExecutor;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnectorRegistry;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates workflow control nodes (cores).
 *
 * Rules enforced:
 * - Label required for all control nodes
 * - Decision nodes must have at least one condition
 * - Loop/While nodes must have a loop condition
 * - Split nodes must have a list expression
 * - Split nodes must not declare a maxItems above the server fan-out ceiling
 * - Data processing nodes (filter, sort, limit, remove_duplicates, summarize) must have input
 * - XML, Compression, ConvertToFile, ExtractFromFile must have value/input
 * - CompareDatasets must have inputA and inputB
 * - RSS must have url
 * - Code must have code content; a {{...}} expression in the body is a warning (spliced as data, see CodeNode)
 * - HttpRequest must have url
 * - DownloadFile must have url
 * - SendEmail must have toEmail and subject
 * - RespondToWebhook must have body (optional but recommended)
 * - Response must have message
 * - Aggregate must have fields
 */
@Slf4j
@Component
public class CoreValidator implements WorkflowValidator {

    private static final Set<String> DATA_PROCESSING_TYPES = Set.of(
            "filter", "sort", "limit", "remove_duplicates", "summarize"
    );

    @Override
    public void validate(WorkflowBuilderSession session, ValidationResult result) {
        for (Map<String, Object> cn : session.getCores()) {
            String type = (String) cn.get("type");
            String label = (String) cn.get("label");
            String nodeId = getCoreId(cn);

            // Rule: Label required
            if (label == null || label.isBlank()) {
                result.addError("MISSING_LABEL", nodeId, type + " node must have a label.");
                continue;
            }

            // Decision validation
            if ("decision".equals(type)) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> conditions = (List<Map<String, Object>>) cn.get("decisionConditions");
                if (conditions == null || conditions.isEmpty()) {
                    result.addError("DECISION_NO_CONDITIONS", nodeId,
                            "Decision '" + label + "' must have at least one condition.");
                }
            }

            // Loop validation
            if ("loop".equals(type)) {
                // Accept either an explicit loopCondition or a maxIterations fallback
                // (docs declare condition non-required when max_iterations is set).
                Object loopCond = cn.get("loopCondition");
                boolean hasLoopCondition = loopCond != null
                        && !(loopCond instanceof String s && s.isBlank());
                boolean hasMaxIterations = cn.get("maxIterations") != null;
                if (!hasLoopCondition && !hasMaxIterations) {
                    result.addError("LOOP_NO_CONDITION", nodeId,
                            "Loop '" + label + "' must have a loopCondition or maxIterations. " +
                            "Fix: workflow(action='modify', node='" + label + "', params={maxIterations: 10}) " +
                            "or workflow(action='modify', node='" + label + "', params={loopCondition: '{{mcp:api.output.has_more}} == true'}). " +
                            "Snake_case aliases (condition, loop_condition, max_iterations) are also accepted.");
                }
                // Check that the loop has a body edge and an iterate back-edge
                boolean hasBody = session.getEdges().stream()
                    .anyMatch(e -> String.valueOf(e.get("from")).startsWith(nodeId + ":body"));
                boolean hasIterate = session.getEdges().stream()
                    .anyMatch(e -> String.valueOf(e.get("to")).equals(nodeId + ":iterate"));
                if (!hasBody) {
                    result.addError("LOOP_NO_BODY", nodeId,
                            "Loop '" + label + "' has no body. Add body nodes: " +
                            "workflow(action='add_node', type='...', label='...', connect_after='" + label + ":body')");
                }
                if (hasBody && !hasIterate) {
                    result.addError("LOOP_NOT_CLOSED", nodeId,
                            "Loop '" + label + "' body is not connected back. Close it: " +
                            "workflow(action='connect', from='<last body step>', to='" + label + ":iterate')");
                }
            }

            // Split validation
            if ("split".equals(type)) {
                if (!hasNonBlankString(cn, "list")) {
                    result.addError("SPLIT_NO_LIST", nodeId,
                            "Split '" + label + "' must have a list expression.");
                }
                String ceilingError = splitMaxItemsAboveCeilingOrNull(cn, label);
                if (ceilingError != null) {
                    result.addError("SPLIT_MAX_ITEMS_ABOVE_CEILING", nodeId, "maxItems",
                            ceilingError + " Fix: workflow(action='modify', node='" + label
                                    + "', params={maxItems: " + SplitNodeExecutor.SPLIT_HARD_CEILING
                                    + "}), or any value from 1 to "
                                    + SplitNodeExecutor.SPLIT_HARD_CEILING + ".");
                }
            }

            // Data processing nodes: input is required
            if (DATA_PROCESSING_TYPES.contains(type)) {
                if (!hasInput(cn, type)) {
                    result.addError("DATA_NODE_MISSING_INPUT", nodeId,
                            "'" + label + "' requires an input expression - specify the items to process.");
                }
            }

            // XML: value required
            if ("xml".equals(type)) {
                if (!hasConfigField(cn, "xml", "value")) {
                    result.addError("XML_NO_INPUT", nodeId,
                            "XML '" + label + "' requires input data (value).");
                }
            }

            // Compression: value required
            if ("compression".equals(type)) {
                if (!hasConfigField(cn, "compression", "value")) {
                    result.addError("COMPRESSION_NO_INPUT", nodeId,
                            "Compression '" + label + "' requires input data (value).");
                }
            }

            // ConvertToFile: value required
            if ("convert_to_file".equals(type)) {
                if (!hasConfigField(cn, "convertToFile", "value")) {
                    result.addError("CONVERT_NO_INPUT", nodeId,
                            "Convert to File '" + label + "' requires data source (value).");
                }
            }

            // ExtractFromFile: value required
            if ("extract_from_file".equals(type)) {
                if (!hasConfigField(cn, "extractFromFile", "value")) {
                    result.addError("EXTRACT_NO_INPUT", nodeId,
                            "Extract from File '" + label + "' requires file content or URL (value).");
                }
            }

            // CompareDatasets: inputA and inputB required
            if ("compare_datasets".equals(type)) {
                if (!hasConfigField(cn, "compareDatasets", "inputA")) {
                    result.addError("COMPARE_NO_INPUT_A", nodeId,
                            "Compare Datasets '" + label + "' requires Dataset A (inputA).");
                }
                if (!hasConfigField(cn, "compareDatasets", "inputB")) {
                    result.addError("COMPARE_NO_INPUT_B", nodeId,
                            "Compare Datasets '" + label + "' requires Dataset B (inputB).");
                }
            }

            // RSS: url required
            if ("rss".equals(type)) {
                if (!hasConfigField(cn, "rss", "url")) {
                    result.addError("RSS_NO_URL", nodeId,
                            "RSS '" + label + "' requires a feed URL.");
                }
            }

            // Code: code required, and the body must be code - never a template
            if ("code".equals(type)) {
                if (!hasConfigField(cn, "code", "code")) {
                    result.addError("CODE_NO_CODE", nodeId,
                            "Code '" + label + "' requires code content.");
                }
                // Checked on both shapes the plan can carry (nested config map, or the flat
                // string an exported plan uses), independently of the CODE_NO_CODE branch above
                // which only recognises the nested one. Matched with the engine's own expression
                // pattern rather than a bare "{{" so authoring and execution agree on what a
                // placeholder is: a body that only carries a doubled brace the engine would never
                // resolve (a nested JavaScript block, a Handlebars template being built as a
                // string) is not refused for something the run treats as inert text.
                // Two severities, keyed on WHERE the placeholder sits (LC-018). Outside any string
                // literal the run can no longer turn the value into source (it is spliced as a
                // complete literal), so the body does not do what it reads like. Inside a
                // literal the run escapes the value and it still works, which is what saved
                // workflows rely on, so it is only a warning steering the author to $input.
                String codeBody = codeBodyOf(cn);
                String unsafeExpression = CodeNode.firstPlaceholderOutsideStringLiteral(
                        codeBody, codeLanguageOf(cn));
                String inputAdvice = "Read upstream outputs from the input object instead - "
                        + "$input.<predecessor label>.<field> in javascript and typescript, "
                        + "_input['<predecessor label>']['<field>'] in python, INPUT (JSON) in bash - "
                        + "it already carries every predecessor output, keeps real types (numbers, "
                        + "objects, arrays) and needs no quoting. Fix: workflow(action='modify', node='"
                        + label + "', params={code: '<the same code with every {{...}} replaced by an "
                        + "$input read>'}).";
                if (unsafeExpression != null) {
                    result.addWarning("CODE_TEMPLATE_IN_BODY", nodeId,
                            "Code '" + label + "' has the expression " + unsafeExpression + " outside any "
                            + "string literal. Upstream values reach the code as DATA, not as source "
                            + "text: at run time that value is spliced as a complete literal (an object, a "
                            + "list or a text holding exactly one JSON object or array as a literal (JSON, or "
                            + "True/False/None in python), a number or boolean as itself, anything else "
                            + "as a quoted string), so it does not "
                            + "produce the code it reads like. " + inputAdvice);
                } else if (CodeNode.hasTemplateExpression(codeBody)) {
                    result.addWarning("CODE_TEMPLATE_IN_BODY", nodeId,
                            "Code '" + label + "' has a {{...}} expression inside a string literal. It "
                            + "still works (the resolved value is escaped into that literal as text), but "
                            + "it always arrives as a string. " + inputAdvice);
                }
            }

            // HttpRequest: url required
            if ("http_request".equals(type)) {
                if (!hasConfigField(cn, "httpRequest", "url")) {
                    result.addError("HTTP_NO_URL", nodeId,
                            "HTTP Request '" + label + "' requires a URL.");
                }
            }

            // DownloadFile: url required
            if ("download_file".equals(type)) {
                if (!hasConfigField(cn, "download", "url")) {
                    result.addError("DOWNLOAD_NO_URL", nodeId,
                            "Download File '" + label + "' requires a URL.");
                }
            }

            // SendEmail: toEmail and subject required
            if ("send_email".equals(type)) {
                if (!hasConfigField(cn, "sendEmail", "toEmail")) {
                    result.addError("EMAIL_NO_TO", nodeId,
                            "Send Email '" + label + "' requires a recipient (toEmail).");
                }
                if (!hasConfigField(cn, "sendEmail", "subject")) {
                    result.addError("EMAIL_NO_SUBJECT", nodeId,
                            "Send Email '" + label + "' requires a subject.");
                }
            }

            // EmailInbox: any action other than 'none' requires messageUid; move also requires targetFolder
            if ("email_inbox".equals(type)) {
                String action = null;
                if (cn.get("emailInbox") instanceof Map<?, ?> m && m.get("action") != null) {
                    // Normalized like Core.EmailInboxConfig does, so 'CREATE_FOLDER' is not
                    // mistaken for a per-message action and asked for a messageUid.
                    action = String.valueOf(m.get("action")).trim().toLowerCase(java.util.Locale.ROOT);
                }
                if ("create_folder".equals(action)) {
                    // Mailbox-level action: names the folder to create, never a message.
                    if (!hasConfigField(cn, "emailInbox", "targetFolder")) {
                        result.addError("INBOX_NO_TARGET_FOLDER", nodeId,
                                "Email Inbox '" + label + "' create_folder action requires targetFolder.");
                    }
                } else if (action != null && !action.isBlank() && !"none".equals(action) && !"list_folders".equals(action)) {
                    if (!hasConfigField(cn, "emailInbox", "messageUid")) {
                        result.addError("INBOX_NO_MESSAGE_UID", nodeId,
                                "Email Inbox '" + label + "' action '" + action + "' requires messageUid.");
                    }
                    if ("move".equals(action) && !hasConfigField(cn, "emailInbox", "targetFolder")) {
                        result.addError("INBOX_NO_TARGET_FOLDER", nodeId,
                                "Email Inbox '" + label + "' move action requires targetFolder.");
                    }
                }
            }

            // Response: message required
            if ("response".equals(type)) {
                if (!hasConfigField(cn, "response", "message")) {
                    result.addError("RESPONSE_NO_MESSAGE", nodeId,
                            "Response '" + label + "' requires a message.");
                }
            }

            // Aggregate: fields required
            if ("aggregate".equals(type)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> config = (Map<String, Object>) cn.get("aggregate");
                if (config == null || !(config.get("fields") instanceof List<?> fields) || fields.isEmpty()) {
                    result.addError("AGGREGATE_NO_FIELDS", nodeId,
                            "Aggregate '" + label + "' requires at least one aggregation field.");
                }
            }

            // Switch: expression required
            if ("switch".equals(type)) {
                if (!hasNonBlankString(cn, "switchExpression")) {
                    result.addError("SWITCH_NO_EXPRESSION", nodeId,
                            "Switch '" + label + "' must have a switchExpression.");
                }
            }

            // Approval: contextTemplate is required (WARNING, never blocking). Without it the
            // approver sees no description of WHAT they are approving. The run still proceeds.
            if ("approval".equals(type)) {
                if (!hasConfigField(cn, "approval", "contextTemplate")) {
                    result.addWarning("APPROVAL_NO_CONTEXT_TEMPLATE", nodeId,
                            "Approval '" + label + "' should set a contextTemplate so the approver sees what " +
                            "they are approving. Fix: workflow(action='modify', node='" + label + "', " +
                            "params={contextTemplate: 'Approve refund of {{trigger:form.output.amount}} for {{trigger:form.output.email}}?'}). " +
                            "Literal text plus {{...}} expressions; resolved at pause time and shown to the approver. " +
                            "The run still works without it.");
                }
                validateApprovalDelegation(cn, nodeId, label, result);
            }
        }
    }

    /**
     * Delegation block checks. Unknown channel is an ERROR (the approval would silently
     * never reach any external channel); missing credential/chatId and multi-approval
     * thresholds are WARNINGs (the run still proceeds, in-app resolution always works).
     */
    private static boolean isUuid(String value) {
        try {
            java.util.UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void validateApprovalDelegation(Map<String, Object> cn, String nodeId, String label,
                                            ValidationResult result) {
        Object approval = cn.get("approval");
        if (!(approval instanceof Map<?, ?> approvalMap)) return;
        Object delegationObj = approvalMap.get("delegation");
        if (!(delegationObj instanceof Map<?, ?> delegation)) return;

        String channel = delegation.get("channel") instanceof String s ? s.trim().toLowerCase() : "";
        String linkId = delegation.get("linkId") instanceof String l ? l.trim() : "";
        if (!linkId.isBlank()) {
            // A destination picked like a credential: it decides service, account and chat.
            if (!"default".equalsIgnoreCase(linkId) && !isUuid(linkId)) {
                result.addError("APPROVAL_DELEGATION_INVALID_DESTINATION", nodeId,
                        "Approval '" + label + "' names destination '" + linkId + "', which is not a destination "
                        + "id. Use a linkId from channel(action='list'), or 'default' for the workspace default. "
                        + "Fix: workflow(action='modify', node='" + label + "', params={delegation: {linkId: 'default'}}).");
                return;
            }
            boolean namesChat = delegation.get("chatId") instanceof String c && !c.isBlank();
            if (namesChat || delegation.get("credentialId") != null) {
                result.addWarning("APPROVAL_DELEGATION_DESTINATION_OVERRIDES", nodeId,
                        "Approval '" + label + "' picks a destination (linkId) and also gives a chatId or "
                        + "credentialId. The destination decides the account and the chat, so those are ignored. "
                        + "Remove them, or remove linkId to name the chat yourself.");
            }
            Object threshold = approvalMap.get("requiredApprovals");
            if (threshold instanceof Number t && t.intValue() > 1) {
                result.addWarning("APPROVAL_DELEGATION_MULTI_APPROVALS", nodeId,
                        "Approval '" + label + "' delegates to a chat destination with requiredApprovals > 1. A " +
                        "button press counts as a single decision; multi-approver thresholds are only tracked " +
                        "for in-app approvals. Consider requiredApprovals: 1 when delegating.");
            }
            return;
        }
        if (channel.isBlank()) return; // Section left unconfigured - nothing delegated, nothing to check.

        if (!ChatChannelConnectorRegistry.KNOWN_CHANNELS.contains(channel)) {
            result.addError("APPROVAL_DELEGATION_UNKNOWN_CHANNEL", nodeId,
                    "Approval '" + label + "' delegates to unknown channel '" + channel + "'. " +
                    "Supported: " + String.join(", ", ChatChannelConnectorRegistry.KNOWN_CHANNELS) + ". " +
                    "Fix: workflow(action='modify', node='" + label + "', " +
                    "params={delegation: {channel: 'slack'}}).");
            return;
        }
        if (!"telegram".equals(channel)) {
            // With no credential or destination given, the approval goes to the destination
            // connected on that provider, so neither is worth a warning. Only a malformed
            // credential id is.
            Object pinned = delegation.get("credentialId");
            if (pinned != null && !isNumericId(pinned)) {
                result.addWarning("APPROVAL_DELEGATION_INVALID_CREDENTIAL", nodeId,
                        "Approval '" + label + "' delegates to " + channel + " with a non-numeric credentialId ('" +
                        pinned + "'). Remove it to use the " + channel + " account connected in this workspace.");
            }
            if ("slack".equals(channel) && delegation.get("chatId") instanceof String named
                    && (named.trim().startsWith("#") || named.trim().startsWith("@"))) {
                result.addWarning("APPROVAL_DELEGATION_CHAT_NAME", nodeId,
                        "Approval '" + label + "' names a Slack channel by name ('" + named + "'). A press comes "
                        + "back with the channel ID, so give the ID instead (it starts with C, G or D; "
                        + "channel(action='list') shows the connected ones), or leave chatId empty to use the "
                        + "connected destination.");
            }
            if ("teams".equals(channel) && delegation.get("allowedUserIds") instanceof java.util.List<?> allowed
                    && !allowed.isEmpty()) {
                result.addWarning("APPROVAL_DELEGATION_ALLOWLIST_UNENFORCEABLE", nodeId,
                        "Approval '" + label + "' delegates to Teams with allowedUserIds. A Teams approval is " +
                        "decided from a link, which does not say who opened it, so every press would be " +
                        "refused. Fix: workflow(action='modify', node='" + label + "', " +
                        "params={delegation: {channel: 'teams', allowedUserIds: []}}).");
            }
            Object threshold = approvalMap.get("requiredApprovals");
            if (threshold instanceof Number n && n.intValue() > 1) {
                result.addWarning("APPROVAL_DELEGATION_MULTI_APPROVALS", nodeId,
                        "Approval '" + label + "' delegates to " + channel + " with requiredApprovals > 1. A channel " +
                        "button press counts as a single decision; multi-approver thresholds are only tracked " +
                        "for in-app approvals. Consider requiredApprovals: 1 when delegating.");
            }
            return;
        }
        // credentialId is OPTIONAL: absent means the send uses the user's own Telegram
        // credential automatically (same resolution as a telegram step with no explicit
        // credential). Only a PRESENT-but-non-numeric value is flagged: it will be
        // ignored at run time, which is almost never what the author meant.
        Object credentialId = delegation.get("credentialId");
        if (credentialId != null && !isNumericId(credentialId)) {
            result.addWarning("APPROVAL_DELEGATION_INVALID_CREDENTIAL", nodeId,
                    "Approval '" + label + "' delegates to Telegram with a non-numeric credentialId ('" +
                    credentialId + "'). The value will be ignored and the send falls back to the user's " +
                    "own Telegram credential. Set a numeric credential id to pin a specific bot, or " +
                    "remove the field to use the default.");
        }
        // No chatId is not flagged: the message then goes to the Telegram destination connected in the
        // workspace, exactly as on the other services. A workspace with none records the failure on
        // the delivery, and the approval stays decidable in-app.
        Object required = approvalMap.get("requiredApprovals");
        if (required instanceof Number n && n.intValue() > 1) {
            result.addWarning("APPROVAL_DELEGATION_MULTI_APPROVALS", nodeId,
                    "Approval '" + label + "' delegates to Telegram with requiredApprovals > 1. A channel " +
                    "button tap counts as a single decision; multi-approver thresholds are only tracked " +
                    "for in-app approvals. Consider requiredApprovals: 1 when delegating.");
        }
    }

    // ===== Helpers =====

    /**
     * True for a Number or a numeric string. LLMs routinely quote numeric ids
     * ("credentialId": "40"); the creator and plan parser coerce that shape, so
     * the validator must accept it too instead of raising a misleading warning.
     */
    private static boolean isNumericId(Object value) {
        if (value instanceof Number) return true;
        if (value instanceof String s && !s.isBlank()) {
            try {
                Long.parseLong(s.trim());
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean hasInput(Map<String, Object> cn, String type) {
        // Check params.input
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) cn.get("params");
        if (params != null && params.get("input") instanceof String s && !s.isBlank()) return true;

        // Check typed config input (nested config nodes store input inside their config object)
        String configKey = switch (type) {
            case "remove_duplicates" -> "removeDuplicates";
            case "summarize" -> "summarize";
            case "limit" -> "limit";
            case "filter" -> "filter";
            case "sort" -> "sort";
            default -> null;
        };
        if (configKey != null) {
            Object config = cn.get(configKey);
            if (config instanceof Map<?, ?> m && m.get("input") instanceof String s && !s.isBlank()) return true;
        }

        // Check top-level input (from frontend plan export)
        return cn.get("input") instanceof String s && !s.isBlank();
    }

    /**
     * The code body of a code node, or "" when it is absent. Reads both shapes a plan can carry:
     * the nested config map written by the builder ({@code code: {code: "..."}}) and the flat
     * string an exported plan can carry ({@code code: "..."}).
     */
    private static String codeBodyOf(Map<String, Object> cn) {
        Object code = cn.get("code");
        if (code instanceof String flat) {
            return flat;
        }
        if (code instanceof Map<?, ?> config && config.get("code") instanceof String body) {
            return body;
        }
        return "";
    }

    /**
     * The declared language of a code node, defaulting to javascript exactly as
     * {@code Core.CodeConfig} does, so the warning scans a body with the same literal
     * rules the run will apply to it.
     */
    private static String codeLanguageOf(Map<String, Object> cn) {
        Object code = cn.get("code");
        if (code instanceof Map<?, ?> config && config.get("language") instanceof String lang && !lang.isBlank()) {
            return lang;
        }
        if (cn.get("language") instanceof String flatLang && !flatLang.isBlank()) {
            return flatLang;
        }
        return "javascript";
    }

    @SuppressWarnings("unchecked")
    private boolean hasConfigField(Map<String, Object> cn, String configKey, String fieldName) {
        Object config = cn.get(configKey);
        if (config instanceof Map<?, ?> m) {
            Object val = m.get(fieldName);
            if (val instanceof String s) return !s.isBlank();
            return val != null;
        }
        return false;
    }

    /**
     * The plan-authoring half of the split fan-out ceiling (LC-064).
     *
     * <p>A run refuses a split whose list exceeds
     * {@link SplitNodeExecutor#SPLIT_HARD_CEILING} rather than truncating it, so a plan that
     * declares a higher {@code maxItems} promises a limit the run cannot honour. Without this the
     * author is told the plan is valid and only discovers the ceiling on the first oversized list,
     * in a failure that reads like a data problem. The bound is NOT applied when the DAG is built
     * from a saved plan: rewriting or refusing the number there would take down runs that never
     * fan out anywhere near the ceiling, which is a bigger outage than the one being fixed. This
     * fires while the plan is being written, where the author can still change it in one call.
     *
     * <p>Reads exactly what the plan parser reads: a {@code Number} under {@code maxItems}. Any
     * other shape (a string, a snake_case key) is ignored at run time and the split falls back to
     * its default of 100, so flagging it here would report an error for a value that changes
     * nothing.
     *
     * <p>Shared with the other plan-writing surfaces so the ceiling has one wording; each caller
     * appends the action it wants the agent to retry.
     *
     * @return the agent-facing message, or {@code null} when the declaration is within the ceiling
     */
    public static String splitMaxItemsAboveCeilingOrNull(Map<String, Object> coreNode, String label) {
        Object declared = coreNode.get("maxItems");
        if (!(declared instanceof Number n) || n.intValue() <= SplitNodeExecutor.SPLIT_HARD_CEILING) {
            return null;
        }
        String named = (label == null || label.isBlank()) ? "This split: " : "Split '" + label + "': ";
        return named + SplitNodeExecutor.maxItemsAboveCeilingReason(n.intValue());
    }

    private boolean hasNonBlankString(Map<String, Object> cn, String key) {
        Object val = cn.get(key);
        return val instanceof String s && !s.isBlank();
    }

    private String getCoreId(Map<String, Object> cn) {
        // #F3: prefer the stored id (creators always populate it as "core:<label>")
        // so edge lookups that key off this prefix - e.g. LOOP_NO_BODY detection
        // which expects "core:<label>:body" - match actual stored edges. The old
        // fallback produced "loop:<label>" and caused spurious LOOP_NO_BODY errors.
        Object idVal = cn.get("id");
        if (idVal instanceof String s && !s.isBlank()) return s;
        String label = (String) cn.get("label");
        return "core:" + WorkflowBuilderSession.normalizeLabel(label);
    }
}
