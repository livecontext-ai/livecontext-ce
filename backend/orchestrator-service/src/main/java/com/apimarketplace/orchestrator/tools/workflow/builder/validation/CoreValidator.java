package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

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
 * - Data processing nodes (filter, sort, limit, remove_duplicates, summarize) must have input
 * - XML, Compression, ConvertToFile, ExtractFromFile must have value/input
 * - CompareDatasets must have inputA and inputB
 * - RSS must have url
 * - Code must have code content
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

            // Code: code required
            if ("code".equals(type)) {
                if (!hasConfigField(cn, "code", "code")) {
                    result.addError("CODE_NO_CODE", nodeId,
                            "Code '" + label + "' requires code content.");
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
                    action = String.valueOf(m.get("action"));
                }
                if (action != null && !action.isBlank() && !"none".equals(action) && !"list_folders".equals(action)) {
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
    private void validateApprovalDelegation(Map<String, Object> cn, String nodeId, String label,
                                            ValidationResult result) {
        Object approval = cn.get("approval");
        if (!(approval instanceof Map<?, ?> approvalMap)) return;
        Object delegationObj = approvalMap.get("delegation");
        if (!(delegationObj instanceof Map<?, ?> delegation)) return;

        String channel = delegation.get("channel") instanceof String s ? s.trim().toLowerCase() : "";
        if (channel.isBlank()) return; // Section left unconfigured - nothing delegated, nothing to check.

        if (!"telegram".equals(channel)) {
            result.addError("APPROVAL_DELEGATION_UNKNOWN_CHANNEL", nodeId,
                    "Approval '" + label + "' delegates to unknown channel '" + channel + "'. " +
                    "Only 'telegram' is supported. Fix: workflow(action='modify', node='" + label + "', " +
                    "params={delegation: {channel: 'telegram', chatId: '<chat id>'}}).");
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
        if (!(delegation.get("chatId") instanceof String cid) || cid.isBlank()) {
            result.addWarning("APPROVAL_DELEGATION_NO_CHAT_ID", nodeId,
                    "Approval '" + label + "' delegates to Telegram without a chatId (destination chat, " +
                    "{{...}} templates allowed). Without it no Telegram message is sent; the approval " +
                    "stays resolvable in-app and via workflow(action='resolve_approval').");
        }
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
