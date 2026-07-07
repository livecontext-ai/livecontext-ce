package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Core control flow node in a workflow.
 *
 * Core types:
 * - "decision": If/elseif/else conditional branching
 * - "switch": Switch/case branching
 * - "loop": While loop with condition
 * - "split": Split parallel iteration
 * - "merge": AND merge (wait for all predecessors)
 * - "fork": Parallel fork (all branches execute)
 * - "transform": Data transformation mappings
 * - "wait": Delay/wait timer
 * - "exit": Ends execution along this branch (other parallel branches continue)
 * - "response": Sends a message response to chat
 * - "option": Multiple choice branching with expressions
 * - "aggregate": Collect N items into 1 (data transformation)
 * - "http_request": Make HTTP calls to external APIs
 * - "approval": User approval branching with approved/rejected/timeout ports
 *
 * IMPORTANT: Conditions are stored HERE in cores, NOT in edges.
 * Edges only reference cores via ports (e.g., "core:label:if", "core:label:else").
 *
 * @see <a href="the project docs">WorkflowPlan JSON Format</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Core(
    String id,
    String type,
    Map<String, Object> position,
    String label,
    // Decision-specific
    List<DecisionCondition> decisionConditions,
    // Switch-specific
    String switchExpression,
    List<SwitchCase> switchCases,
    // Loop-specific
    String loopCondition,
    Integer maxIterations,
    String strategy,
    // Split-specific
    String list,
    Integer maxItems,
    String splitStrategy,
    // Fork-specific
    List<ForkOutput> forkOutputs,
    // Transform-specific (JSON field is "transform", not "transformConfig")
    @JsonProperty("transform") TransformConfig transformConfig,
    // Wait-specific (JSON field is "wait", not "waitConfig")
    @JsonProperty("wait") WaitConfig waitConfig,
    // Download file-specific (JSON field is "download", not "downloadConfig")
    @JsonProperty("download") DownloadConfig downloadConfig,
    // Response-specific (JSON field is "response", not "responseConfig")
    @JsonProperty("response") ResponseConfig responseConfig,
    // Aggregate-specific (JSON field is "aggregate", not "aggregateConfig")
    @JsonProperty("aggregate") AggregateConfig aggregateConfig,
    // Option-specific (multiple choice branching)
    List<OptionChoice> optionChoices,
    // HTTP Request-specific (JSON field is "httpRequest", not "httpRequestConfig")
    @JsonProperty("httpRequest") HttpRequestConfig httpRequestConfig,
    // Approval-specific (JSON field is "approval", not "approvalConfig")
    @JsonProperty("approval") ApprovalConfig approvalConfig,
    // Data Input-specific (JSON field is "dataInput", not "dataInputConfig")
    @JsonProperty("dataInput") DataInputConfig dataInputConfig,
    // Filter-specific (JSON field is "filter", not "filterConfig")
    @JsonProperty("filter") FilterConfig filterConfig,
    // Sort-specific (JSON field is "sort", not "sortConfig")
    @JsonProperty("sort") SortConfig sortConfig,
    // Limit-specific (JSON field is "limit", not "limitConfig")
    @JsonProperty("limit") LimitConfig limitConfig,
    // RemoveDuplicates-specific (JSON field is "removeDuplicates", not "removeDuplicatesConfig")
    @JsonProperty("removeDuplicates") RemoveDuplicatesConfig removeDuplicatesConfig,
    // Summarize-specific (JSON field is "summarize", not "summarizeConfig")
    @JsonProperty("summarize") SummarizeConfig summarizeConfig,
    // DateTime-specific (JSON field is "dateTime", not "dateTimeConfig")
    @JsonProperty("dateTime") DateTimeConfig dateTimeConfig,
    // CryptoJWT-specific (JSON field is "cryptoJwt", not "cryptoJwtConfig")
    @JsonProperty("cryptoJwt") CryptoJwtConfig cryptoJwtConfig,
    // XML-specific (JSON field is "xml", not "xmlConfig")
    @JsonProperty("xml") XmlConfig xmlConfig,
    // Compression-specific (JSON field is "compression", not "compressionConfig")
    @JsonProperty("compression") CompressionConfig compressionConfig,
    // RSS-specific (JSON field is "rss", not "rssConfig")
    @JsonProperty("rss") RssConfig rssConfig,
    // ConvertToFile-specific (JSON field is "convertToFile", not "convertToFileConfig")
    @JsonProperty("convertToFile") ConvertToFileConfig convertToFileConfig,
    // ExtractFromFile-specific (JSON field is "extractFromFile", not "extractFromFileConfig")
    @JsonProperty("extractFromFile") ExtractFromFileConfig extractFromFileConfig,
    // CompareDatasets-specific (JSON field is "compareDatasets", not "compareDatasetsConfig")
    @JsonProperty("compareDatasets") CompareDatasetsConfig compareDatasetsConfig,
    // SubWorkflow-specific (JSON field is "subWorkflow", not "subWorkflowConfig")
    @JsonProperty("subWorkflow") SubWorkflowConfig subWorkflowConfig,
    // RespondToWebhook-specific (JSON field is "respondToWebhook", not "respondToWebhookConfig")
    @JsonProperty("respondToWebhook") RespondToWebhookConfig respondToWebhookConfig,
    // SendEmail-specific (JSON field is "sendEmail", not "sendEmailConfig")
    @JsonProperty("sendEmail") SendEmailConfig sendEmailConfig,
    // EmailInbox-specific (JSON field is "emailInbox", not "emailInboxConfig")
    @JsonProperty("emailInbox") EmailInboxConfig emailInboxConfig,
    // Code-specific (JSON field is "code", not "codeConfig")
    @JsonProperty("code") CodeConfig codeConfig,
    // Set-specific (JSON field is "set", not "setConfig")
    @JsonProperty("set") SetConfig setConfig,
    // HtmlExtract-specific (JSON field is "htmlExtract", not "htmlExtractConfig")
    @JsonProperty("htmlExtract") HtmlExtractConfig htmlExtractConfig,
    // Task-specific (JSON field is "task", not "taskConfig")
    @JsonProperty("task") TaskConfig taskConfig,
    // StopOnError-specific (JSON field is "stopOnError")
    @JsonProperty("stopOnError") StopOnErrorConfig stopOnErrorConfig,
    // SSH-specific (JSON field is "ssh")
    @JsonProperty("ssh") SshConfig sshConfig,
    // SFTP-specific (JSON field is "sftp")
    @JsonProperty("sftp") SftpConfig sftpConfig,
    // Database-specific (JSON field is "database")
    @JsonProperty("database") DatabaseConfig databaseConfig,
    // Common
    Map<String, Object> params,
    // React Flow node ID - passed from frontend plan for step↔node mapping
    String graphNodeId
) {

    // Valid core types
    private static final Set<String> VALID_TYPES = Set.of(
        "decision", "switch", "loop", "split", "merge", "fork", "transform", "wait", "download_file",
        "exit", "end", "response", "option", "aggregate", "http_request", "approval", "data_input",
        "filter", "sort", "limit", "remove_duplicates",
        "summarize", "date_time", "crypto_jwt",
        "xml", "compression", "rss",
        "convert_to_file", "extract_from_file", "compare_datasets",
        "sub_workflow", "respond_to_webhook", "send_email", "email_inbox", "code",
        "set", "html_extract", "task",
        "stop_on_error", "ssh", "sftp", "database"
    );

    /**
     * Decision condition (if/elseif/else).
     * Stored in cores[].decisionConditions[], NOT in edges.
     */
    public record DecisionCondition(String id, String type, String label, String expression) {
        public DecisionCondition {
            // type: "if", "elseif", "else"
            if (type == null || type.isBlank()) {
                type = "if";
            }
        }
    }

    /**
     * Switch case for switch nodes.
     */
    public record SwitchCase(String id, String type, String label, String value) {
        public SwitchCase {
            // type: "case" or "default"
            if (type == null || type.isBlank()) {
                type = "case";
            }
        }
    }

    /**
     * Fork output reference.
     */
    public record ForkOutput(String id, String label, String targetStep) {}

    /**
     * Transform configuration with mappings.
     */
    public record TransformConfig(List<TransformMapping> mappings) {
        public TransformConfig {
            mappings = mappings == null ? List.of() : List.copyOf(mappings);
        }
    }

    /**
     * Single transform mapping (label -> expression).
     */
    public record TransformMapping(String label, String expression) {}

    /**
     * Wait configuration.
     */
    public record WaitConfig(long duration) {}

    /**
     * Download file configuration.
     */
    public record DownloadConfig(String url, String filename, String mimeType) {}

    /**
     * Response configuration for sending messages to chat.
     */
    public record ResponseConfig(String message) {}

    /**
     * Aggregate configuration for collecting N items into 1.
     */
    public record AggregateConfig(List<AggregateField> fields) {
        public AggregateConfig {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    /**
     * Single aggregate field (label -> expression).
     */
    public record AggregateField(String label, String expression) {}

    /**
     * Option choice for option nodes (like decision but with N choices).
     * Each choice has an expression that is evaluated - first true wins.
     */
    public record OptionChoice(String id, String label, String expression) {
        public OptionChoice {
            if (id == null || id.isBlank()) {
                id = "choice_" + System.currentTimeMillis();
            }
        }
    }

    /**
     * HTTP Request configuration for making HTTP calls to external APIs.
     * Supports various authentication methods and request body types.
     */
    public record HttpRequestConfig(
        String method,           // GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS
        String url,              // URL expression (can contain SpEL templates)
        String authType,         // none, basic, bearer, api-key, custom-header
        HttpAuthConfig authConfig, // Authentication configuration
        List<HttpParam> queryParams, // Query parameters
        List<HttpParam> headers,     // Request headers
        String bodyType,         // none, json, form-data, x-www-form-urlencoded, raw
        String body,             // Request body expression
        String contentType,      // Content-Type header value
        Integer timeout          // Request timeout in milliseconds
    ) {
        public HttpRequestConfig {
            method = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
            authType = authType == null ? "none" : authType;
            bodyType = bodyType == null ? "none" : bodyType;
            queryParams = queryParams == null ? List.of() : List.copyOf(queryParams);
            headers = headers == null ? List.of() : List.copyOf(headers);
        }
    }

    /**
     * HTTP authentication configuration.
     */
    public record HttpAuthConfig(
        String username,         // For basic auth
        String password,         // For basic auth
        String bearerToken,      // For bearer auth
        String apiKeyName,       // For api-key auth
        String apiKeyValue,      // For api-key auth
        String apiKeyLocation,   // header or query
        String headerName,       // For custom-header auth
        String headerValue       // For custom-header auth
    ) {}

    /**
     * HTTP parameter (key-value pair with optional ID).
     */
    public record HttpParam(String id, String key, String value) {}

    /**
     * Approval configuration for user approval nodes.
     * Supports multi-level approval with required threshold.
     */
    public record ApprovalConfig(
        List<String> approverRoles,  // Required roles (e.g., ["manager"])
        int requiredApprovals,       // Threshold for multi-level (default 1)
        long timeoutMs,              // Timeout in ms (e.g., 86400000 = 24h)
        String contextTemplate,      // Template (literal + {{...}}) shown to the human approver
        ApprovalDelegation delegation // Optional external-channel delegation (null = in-app only)
    ) {
        public ApprovalConfig {
            approverRoles = approverRoles == null ? List.of() : List.copyOf(approverRoles);
            requiredApprovals = Math.max(1, requiredApprovals);
            contextTemplate = contextTemplate == null ? "" : contextTemplate;
        }
    }

    /**
     * External-channel delegation for a user approval node: the pending approval is
     * pushed to the channel (v1: a Telegram message with inline approve/reject
     * buttons) and the button click resolves the signal, in addition to the in-app
     * resolution paths. {@code chatId} and {@code messageTemplate} are
     * template-capable ({{...}}), resolved at yield time like {@code contextTemplate}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApprovalDelegation(
        String channel,              // Channel id (v1: "telegram")
        Long credentialId,           // User credential id of the channel bot (BYOK)
        String chatId,               // Destination chat id (template-capable)
        String messageTemplate,      // Optional message body; blank = resolved approval context
        List<String> allowedUserIds  // Optional allowlist of channel user ids; empty = anyone in chat
    ) {
        public ApprovalDelegation {
            channel = channel == null ? "" : channel.trim().toLowerCase(java.util.Locale.ROOT);
            chatId = chatId == null ? "" : chatId;
            messageTemplate = messageTemplate == null ? "" : messageTemplate;
            allowedUserIds = allowedUserIds == null ? List.of() : List.copyOf(allowedUserIds);
        }

        /** True when the author actually selected a channel (the section is optional). */
        public boolean isConfigured() {
            return !channel.isBlank();
        }
    }

    /**
     * Single item in a Data Input node (text or file).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DataInputItem(String id, String label, String type, String text, Map<String, Object> file) {
        public DataInputItem {
            id = id == null ? "item_" + System.currentTimeMillis() : id;
            label = label == null ? "input_1" : label;
            type = type == null ? "text" : type;
            text = text == null ? "" : text;
        }
    }

    /**
     * Data Input configuration with multiple labeled items (text and/or file).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DataInputConfig(List<DataInputItem> items) {
        public DataInputConfig {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public Core {
        id = normalizeMandatory(id, "core id");
        type = normalizeType(type);
        position = position == null ? Map.of() : Map.copyOf(position);
        label = normalizeOptional(label);
        decisionConditions = decisionConditions == null ? null : List.copyOf(decisionConditions);
        switchCases = switchCases == null ? null : List.copyOf(switchCases);
        forkOutputs = forkOutputs == null ? null : List.copyOf(forkOutputs);
        optionChoices = optionChoices == null ? null : List.copyOf(optionChoices);
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /**
     * Returns the normalized key for this core node.
     * Format: "core:normalized_label"
     *
     * Example: "Check Status" -> "core:check_status"
     */
    public String getNormalizedKey() {
        String base = (label != null && !label.isBlank()) ? label : id;
        String normalized = LabelNormalizer.normalizeLabel(base);
        return normalized != null ? "core:" + normalized : "core:" + base.toLowerCase(Locale.ROOT);
    }

    /**
     * Returns true if this is a decision node.
     */
    public boolean isDecision() {
        return "decision".equals(type);
    }

    /**
     * Returns true if this is a switch node.
     */
    public boolean isSwitch() {
        return "switch".equals(type);
    }

    /**
     * Returns true if this is a loop node.
     */
    public boolean isLoop() {
        return "loop".equals(type);
    }

    /**
     * Returns true if this is a split node.
     */
    public boolean isSplit() {
        return "split".equals(type);
    }

    /**
     * Returns true if this is a merge node.
     */
    public boolean isMerge() {
        return "merge".equals(type);
    }

    /**
     * Returns true if this is a fork node.
     */
    public boolean isFork() {
        return "fork".equals(type);
    }

    /**
     * Returns true if this is a transform node.
     */
    public boolean isTransform() {
        return "transform".equals(type);
    }

    /**
     * Returns true if this is a wait node.
     */
    public boolean isWait() {
        return "wait".equals(type);
    }

    /**
     * Returns true if this is a download file node.
     */
    public boolean isDownloadFile() {
        return "download_file".equals(type);
    }

    /**
     * Returns true if this is an exit node.
     */
    public boolean isExit() {
        return "exit".equals(type);
    }

    /**
     * Returns true if this is a response node.
     */
    public boolean isResponse() {
        return "response".equals(type);
    }

    /**
     * Returns true if this is an option node.
     */
    public boolean isOption() {
        return "option".equals(type);
    }

    /**
     * Returns true if this is an aggregate node.
     */
    public boolean isAggregate() {
        return "aggregate".equals(type);
    }

    /**
     * Returns true if this is an HTTP request node.
     */
    public boolean isHttpRequest() {
        return "http_request".equals(type);
    }

    /**
     * Returns true if this is an approval node.
     */
    public boolean isApproval() {
        return "approval".equals(type);
    }

    /**
     * Returns true if this is a data input node.
     */
    public boolean isDataInput() {
        return "data_input".equals(type);
    }

    /**
     * Returns true if this is a filter node.
     */
    public boolean isFilter() {
        return "filter".equals(type);
    }

    /**
     * Returns true if this is a sort node.
     */
    public boolean isSort() {
        return "sort".equals(type);
    }

    /**
     * Returns true if this is a limit node.
     */
    public boolean isLimit() {
        return "limit".equals(type);
    }

    /**
     * Returns true if this is a remove duplicates node.
     */
    public boolean isRemoveDuplicates() {
        return "remove_duplicates".equals(type);
    }

    /**
     * Returns true if this is a summarize node.
     */
    public boolean isSummarize() {
        return "summarize".equals(type);
    }

    /**
     * Returns true if this is a date_time node.
     */
    public boolean isDateTime() {
        return "date_time".equals(type);
    }

    /**
     * Returns true if this is a crypto_jwt node.
     */
    public boolean isCryptoJwt() {
        return "crypto_jwt".equals(type);
    }

    /**
     * Returns true if this is an XML node.
     */
    public boolean isXml() {
        return "xml".equals(type);
    }

    /**
     * Returns true if this is a compression node.
     */
    public boolean isCompression() {
        return "compression".equals(type);
    }

    /**
     * Returns true if this is an RSS node.
     */
    public boolean isRss() {
        return "rss".equals(type);
    }

    /**
     * Returns true if this is a convert-to-file node.
     */
    public boolean isConvertToFile() {
        return "convert_to_file".equals(type);
    }

    /**
     * Returns true if this is an extract-from-file node.
     */
    public boolean isExtractFromFile() {
        return "extract_from_file".equals(type);
    }

    /**
     * Returns true if this is a compare-datasets node.
     */
    public boolean isCompareDatasets() {
        return "compare_datasets".equals(type);
    }

    /**
     * Returns true if this is a sub-workflow node.
     */
    public boolean isSubWorkflow() {
        return "sub_workflow".equals(type);
    }

    /**
     * Returns true if this is a respond-to-webhook node.
     */
    public boolean isRespondToWebhook() {
        return "respond_to_webhook".equals(type);
    }

    /**
     * Returns true if this is a send email node.
     */
    public boolean isSendEmail() {
        return "send_email".equals(type);
    }

    /**
     * Returns true if this is an email inbox node (IMAP read + mailbox actions).
     */
    public boolean isEmailInbox() {
        return "email_inbox".equals(type);
    }

    /**
     * Returns true if this is a code node.
     */
    public boolean isCode() {
        return "code".equals(type);
    }

    /**
     * Returns true if this is a task CRUD node.
     */
    public boolean isTask() {
        return "task".equals(type);
    }

    /**
     * Returns true if this is a branching node (decision, switch, option, or approval).
     */
    public boolean isBranching() {
        return isDecision() || isSwitch() || isOption() || isApproval();
    }

    /**
     * Returns true if this is a looping node (loop or split).
     */
    public boolean isLooping() {
        return isLoop() || isSplit();
    }

    /**
     * Returns the number of branches for decision/switch nodes.
     */
    public int getBranchCount() {
        if (isDecision() && decisionConditions != null) {
            return decisionConditions.size();
        }
        if (isSwitch() && switchCases != null) {
            return switchCases.size();
        }
        return 0;
    }

    /**
     * Gets the port names for decision branches.
     * Returns: ["if", "elseif_0", "elseif_1", ..., "else"]
     */
    public List<String> getDecisionPorts() {
        if (!isDecision() || decisionConditions == null) {
            return List.of();
        }
        return decisionConditions.stream()
            .map(c -> {
                if ("if".equals(c.type())) return "if";
                if ("else".equals(c.type())) return "else";
                // elseif_0, elseif_1, etc.
                int idx = decisionConditions.indexOf(c) - 1; // -1 because first is "if"
                return "elseif_" + Math.max(0, idx);
            })
            .toList();
    }

    /**
     * Gets the port names for switch cases.
     * Returns: ["case_0", "case_1", ..., "default"]
     */
    public List<String> getSwitchPorts() {
        if (!isSwitch() || switchCases == null) {
            return List.of();
        }
        return switchCases.stream()
            .map(c -> {
                if ("default".equals(c.type())) return "default";
                int idx = switchCases.indexOf(c);
                return "case_" + idx;
            })
            .toList();
    }

    /**
     * Gets the port names for fork outputs.
     * Returns: ["branch_0", "branch_1", ...]
     */
    public List<String> getForkPorts() {
        if (!isFork() || forkOutputs == null) {
            return List.of();
        }
        List<String> ports = new java.util.ArrayList<>();
        for (int i = 0; i < forkOutputs.size(); i++) {
            ports.add("branch_" + i);
        }
        return ports;
    }

    /**
     * Gets the port names for split node.
     *
     * NOTE: Split does NOT actually use ports in edges!
     * It uses internal parallel spawning - items are passed via execution context
     * ({{item}}, {{item.field}}), not via edge ports.
     *
     * This method returns empty list because Split edges are simple:
     * { from: "core:split_label", to: "mcp:next_step" } - no port suffix.
     *
     * @return Empty list (Split doesn't use edge ports)
     */
    public List<String> getSplitPorts() {
        // Split uses internal parallel spawning, not edge ports
        return List.of();
    }

    /**
     * Gets the port names for loop node.
     * Returns: ["body", "exit"]
     */
    public List<String> getLoopPorts() {
        if (!isLoop()) {
            return List.of();
        }
        return List.of("body", "exit");
    }

    /**
     * Gets the port names for option choices.
     * Returns: ["choice_0", "choice_1", ...]
     */
    public List<String> getOptionPorts() {
        if (!isOption() || optionChoices == null) {
            return List.of();
        }
        List<String> ports = new java.util.ArrayList<>();
        for (int i = 0; i < optionChoices.size(); i++) {
            ports.add("choice_" + i);
        }
        return ports;
    }

    /**
     * Gets the port names for approval nodes.
     * Returns: ["approved", "rejected", "timeout"]
     */
    public List<String> getApprovalPorts() {
        if (!isApproval()) {
            return List.of();
        }
        return List.of("approved", "rejected", "timeout");
    }

    /**
     * Gets all port names for this core node based on its type.
     * Only returns ports for nodes that actually use edge ports.
     *
     * Nodes with ports: Decision, Switch, Fork, Loop, Option, Approval
     * Nodes WITHOUT ports: Split (uses internal parallel spawning), Merge, Transform, Wait, Stop, Response
     */
    public List<String> getAllPorts() {
        if (isDecision()) return getDecisionPorts();
        if (isSwitch()) return getSwitchPorts();
        if (isFork()) return getForkPorts();
        if (isLoop()) return getLoopPorts();
        if (isOption()) return getOptionPorts();
        if (isApproval()) return getApprovalPorts();
        // Split, Merge, Transform, Wait, Stop, Response don't use edge ports
        return List.of();
    }

    // ==================== Filter Config ====================

    /**
     * Filter configuration - keep only items matching conditions.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FilterConfig(List<FilterCondition> conditions, String mode, String input) {
        public FilterConfig {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
            mode = mode == null ? "and" : mode; // "and" or "or"
            // input: optional SpEL/template expression that resolves to items list
        }
    }

    /**
     * Single filter condition (field + operator + value).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FilterCondition(String field, String operator, String value) {
        public FilterCondition {
            operator = operator == null ? "equals" : operator;
        }
    }

    // ==================== Sort Config ====================

    /**
     * Sort configuration - reorder items by one or more fields.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SortConfig(List<SortField> fields, String input) {
        public SortConfig {
            fields = fields == null ? List.of() : List.copyOf(fields);
            // input: optional SpEL/template expression that resolves to items list
        }
    }

    /**
     * Single sort field (field name + direction).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SortField(String field, String direction) {
        public SortField {
            direction = direction == null ? "asc" : direction; // "asc" or "desc"
        }
    }

    // ==================== Limit Config ====================

    /**
     * Limit configuration - pass through only first/last N items.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LimitConfig(int count, String from, int offset, String input) {
        public LimitConfig {
            count = Math.max(1, count);
            from = from == null ? "first" : from; // "first" or "last"
            offset = Math.max(0, offset);
            // input: optional SpEL/template expression that resolves to items list
        }
    }

    // ==================== RemoveDuplicates Config ====================

    /**
     * RemoveDuplicates configuration - deduplicate items by fields.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoveDuplicatesConfig(List<String> fields, String keep, String input) {
        public RemoveDuplicatesConfig {
            fields = fields == null ? List.of() : List.copyOf(fields);
            keep = keep == null ? "first" : keep; // "first" or "last"
            // input: optional SpEL/template expression that resolves to items list
        }
    }

    // ==================== Summarize Config ====================

    /**
     * Summarize configuration - aggregate data with operations like sum, avg, count, min, max.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SummarizeConfig(List<SummarizeAggregation> aggregations, List<String> groupBy, String input) {
        public SummarizeConfig {
            aggregations = aggregations == null ? List.of() : List.copyOf(aggregations);
            groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
            // input: optional SpEL/template expression that resolves to items list
        }
    }

    /**
     * Single summarize aggregation (operation on a field).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SummarizeAggregation(String field, String operation, String alias) {
        public SummarizeAggregation {
            operation = operation == null ? "count" : operation;
            // valid: sum, avg, count, min, max, countDistinct, concatenate
        }
    }

    // ==================== DateTime Config ====================

    /**
     * DateTime configuration - parse, format, convert, manipulate dates.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DateTimeConfig(String operation, String value, String inputFormat, String outputFormat,
                                  String timezone, String targetTimezone, String durationUnit,
                                  long durationAmount, String secondValue, String extractPart) {
        public DateTimeConfig {
            operation = operation == null ? "format" : operation;
            // valid: parse, format, convertTimezone, add, subtract, difference, extract, now
            durationUnit = durationUnit == null ? "days" : durationUnit;
            // valid: years, months, days, hours, minutes, seconds
        }
    }

    // ==================== CryptoJWT Config ====================

    /**
     * CryptoJWT configuration - hash, HMAC, encrypt, JWT, base64.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CryptoJwtConfig(String operation, String algorithm, String value, String key,
                                   String secret, String token, Map<String, Object> payload,
                                   String encoding) {
        public CryptoJwtConfig {
            operation = operation == null ? "hash" : operation;
            // valid: hash, hmacSign, hmacVerify, encrypt, decrypt, jwtCreate, jwtDecode, jwtVerify,
            //        base64Encode, base64Decode, generateUuid, generateSecret
            algorithm = algorithm == null ? "SHA-256" : algorithm;
            // hash: MD5, SHA-1, SHA-256, SHA-512
            // hmac: HmacSHA256, HmacSHA512
            // encrypt/decrypt: AES
            // jwt: HS256, HS512, RS256
            encoding = encoding == null ? "hex" : encoding;
        }
    }

    // ==================== XML Config ====================

    /**
     * XML configuration - parse XML to JSON or convert JSON to XML.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record XmlConfig(String operation, String value, String rootElement, boolean preserveAttributes) {
        public XmlConfig {
            operation = operation == null ? "xmlToJson" : operation;
            // valid: xmlToJson, jsonToXml
        }
    }

    // ==================== Compression Config ====================

    /**
     * Compression configuration - compress/decompress data.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompressionConfig(String operation, String format, String value, String filename) {
        public CompressionConfig {
            operation = operation == null ? "compress" : operation;
            // valid: compress, decompress
            format = format == null ? "gzip" : format;
            // valid: gzip, zip, base64
        }
    }

    // ==================== RSS Config ====================

    /**
     * RSS configuration - fetch and parse RSS/Atom feeds.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RssConfig(String url, int maxItems) {
        public RssConfig {
            maxItems = maxItems <= 0 ? 20 : maxItems;
        }
    }

    // ==================== ConvertToFile Config ====================

    /**
     * ConvertToFile configuration - export JSON data to file formats.
     *
     * @param format Output format: "csv", "xlsx", "json", "txt"
     * @param value SpEL expression referencing the input data (list of maps)
     * @param filename Output filename (without extension, auto-appended)
     * @param delimiter CSV delimiter (default: ",")
     * @param includeHeaders Whether to include headers in CSV/XLSX (default: "yes")
     */
    public record ConvertToFileConfig(String format, String value, String filename,
                                       String delimiter, String includeHeaders) {
        public ConvertToFileConfig {
            format = format == null ? "csv" : format.toLowerCase(Locale.ROOT);
            filename = filename == null ? "export" : filename;
            delimiter = delimiter == null ? "," : delimiter;
            includeHeaders = includeHeaders == null ? "yes" : includeHeaders;
        }

        public boolean hasHeaders() {
            return "yes".equalsIgnoreCase(includeHeaders) || "true".equalsIgnoreCase(includeHeaders);
        }
    }

    // ==================== ExtractFromFile Config ====================

    /**
     * ExtractFromFile configuration - parse files into JSON items.
     *
     * Modes:
     * - "structured" (default): parse CSV/XLSX/JSON into structured rows
     * - "text": extract raw text from PDF/HTML/DOCX/TXT, optionally chunk for RAG
     *
     * @param format Input format: "csv", "xlsx", "json" (structured) or "pdf", "html", "docx", "txt" (text)
     * @param value SpEL expression referencing the file content (base64 or URL)
     * @param delimiter CSV delimiter for parsing (default: ",")
     * @param sheetName XLSX sheet name (default: first sheet)
     * @param hasHeaders Whether first row is headers (default: "yes")
     * @param mode Extraction mode: "structured" (default) or "text"
     * @param chunking Whether to split text into chunks (default: false, text mode only)
     * @param chunkSize Target size per chunk, measured in chunkUnit (default: 500, text mode only)
     * @param overlap Overlap between consecutive chunks, measured in chunkUnit (default: 50, text mode only)
     * @param chunkingStrategy Chunking strategy: "fixed_size", "recursive", "separator" (default: "fixed_size")
     * @param separator Custom separator for "separator" strategy (default: "\n\n")
     * @param chunkUnit Unit chunkSize/overlap are measured in: "char" (default) or "token" (cl100k_base)
     */
    public record ExtractFromFileConfig(String format, String value, String delimiter,
                                         String sheetName, String hasHeaders,
                                         String mode, Boolean chunking, Integer chunkSize,
                                         Integer overlap, String chunkingStrategy,
                                         String separator, String chunkUnit) {
        public ExtractFromFileConfig {
            format = format == null ? "csv" : format.toLowerCase(Locale.ROOT);
            delimiter = delimiter == null ? "," : delimiter;
            hasHeaders = hasHeaders == null ? "yes" : hasHeaders;
            mode = mode == null ? "structured" : mode.toLowerCase(Locale.ROOT);
            chunkSize = (chunkSize == null || chunkSize < 1) ? 500 : chunkSize;
            overlap = (overlap == null || overlap < 0) ? 50 : overlap;
            chunkingStrategy = chunkingStrategy == null ? "fixed_size" : chunkingStrategy.toLowerCase(Locale.ROOT);
            separator = separator == null ? "\n\n" : separator;
            chunkUnit = chunkUnit == null ? "char" : chunkUnit.toLowerCase(Locale.ROOT);
            if (!chunkUnit.equals("token")) {
                chunkUnit = "char"; // any unknown value falls back to char
            }
        }

        /**
         * Backward-compatible 11-argument constructor (pre-chunkUnit). Delegates to the
         * canonical constructor with chunkUnit defaulted to "char". Keeps existing positional
         * call sites compiling unchanged.
         */
        public ExtractFromFileConfig(String format, String value, String delimiter,
                                     String sheetName, String hasHeaders,
                                     String mode, Boolean chunking, Integer chunkSize,
                                     Integer overlap, String chunkingStrategy,
                                     String separator) {
            this(format, value, delimiter, sheetName, hasHeaders, mode, chunking,
                 chunkSize, overlap, chunkingStrategy, separator, null);
        }

        public boolean includeHeaders() {
            return "yes".equalsIgnoreCase(hasHeaders) || "true".equalsIgnoreCase(hasHeaders);
        }

        public boolean isTextMode() {
            return "text".equals(mode);
        }

        public boolean isChunkingEnabled() {
            return Boolean.TRUE.equals(chunking);
        }

        public boolean isTokenUnit() {
            return "token".equals(chunkUnit);
        }
    }

    // ==================== CompareDatasets Config ====================

    /**
     * CompareDatasets configuration - compare two datasets and find differences.
     *
     * @param inputA SpEL expression referencing dataset A (list of maps)
     * @param inputB SpEL expression referencing dataset B (list of maps)
     * @param matchFields List of field names to match on (key fields)
     * @param returnMatched Whether to return items in both (default: true)
     * @param returnOnlyA Whether to return items only in A (default: true)
     * @param returnOnlyB Whether to return items only in B (default: true)
     */
    public record CompareDatasetsConfig(String inputA, String inputB, List<String> matchFields,
                                         boolean returnMatched, boolean returnOnlyA, boolean returnOnlyB) {
        public CompareDatasetsConfig {
            matchFields = matchFields == null ? List.of() : matchFields;
        }
    }

    // ==================== SubWorkflow Config ====================

    /**
     * SubWorkflow configuration - execute another workflow by firing its trigger.
     *
     * @param workflowId UUID of the workflow to execute
     * @param inputMapping SpEL expression for input data to pass to the sub-workflow
     * @param timeoutSeconds Maximum wait time for sub-workflow completion (default: 300)
     * @param maxDepth Maximum recursion depth to prevent infinite loops (default: 5)
     * @param triggerId Optional trigger ID to fire (defaults to first fireable trigger)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubWorkflowConfig(String workflowId, String inputMapping, int timeoutSeconds, int maxDepth,
                                     String triggerId) {
        public SubWorkflowConfig {
            timeoutSeconds = timeoutSeconds <= 0 ? 300 : timeoutSeconds;
            maxDepth = maxDepth <= 0 ? 5 : Math.min(maxDepth, 10);
        }

        /** Backward-compatible constructor without triggerId. */
        public SubWorkflowConfig(String workflowId, String inputMapping, int timeoutSeconds, int maxDepth) {
            this(workflowId, inputMapping, timeoutSeconds, maxDepth, null);
        }
    }

    // ==================== RespondToWebhook Config ====================

    /**
     * RespondToWebhook configuration - control the HTTP response to webhook caller.
     *
     * @param statusCode HTTP status code (default: 200)
     * @param body SpEL expression for response body
     * @param contentType Content-Type header (default: application/json)
     * @param headers Custom response headers as key-value pairs
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RespondToWebhookConfig(int statusCode, String body, String contentType,
                                          Map<String, String> headers) {
        public RespondToWebhookConfig {
            statusCode = statusCode <= 0 ? 200 : statusCode;
            contentType = contentType == null ? "application/json" : contentType;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    // ==================== SendEmail Config ====================

    /**
     * SendEmail configuration - send emails via SMTP with user-provided credentials.
     *
     * @param smtpHost SMTP server hostname
     * @param smtpPort SMTP server port (default: 587)
     * @param smtpUsername SMTP authentication username
     * @param smtpPassword SMTP authentication password
     * @param smtpUseTls Whether to use TLS (default: true for port 587/465)
     * @param fromEmail Sender email address
     * @param fromName Sender display name (optional)
     * @param toEmail Recipient email(s), comma-separated for multiple
     * @param ccEmail CC email(s), comma-separated (optional)
     * @param bccEmail BCC email(s), comma-separated (optional)
     * @param subject Email subject
     * @param body Email body (plain text or HTML)
     * @param isHtml Whether body is HTML (default: false)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendEmailConfig(
        String smtpHost,
        int smtpPort,
        String smtpUsername,
        String smtpPassword,
        boolean smtpUseTls,
        String fromEmail,
        String fromName,
        String toEmail,
        String ccEmail,
        String bccEmail,
        String subject,
        String body,
        boolean isHtml,
        Long credentialId,
        // Reply threading: set In-Reply-To / References headers so the sent mail threads under
        // an existing conversation. Pass the original message's messageId (from email_inbox output).
        String inReplyTo,
        String references
    ) {
        public SendEmailConfig {
            smtpPort = smtpPort <= 0 ? 587 : smtpPort;
            smtpUseTls = smtpPort == 587 || smtpPort == 465 || smtpUseTls;
        }
    }

    // ==================== EmailInbox Config ====================

    /**
     * EmailInbox configuration - read messages and act on a mailbox via IMAP, with
     * user-provided credentials. IMAP credentials (host, port, username, password,
     * use_ssl) live in the credential system (Settings &gt; Credentials &gt; IMAP),
     * distinct from the SMTP credential used by send_email. IMAP reads/acts on a
     * mailbox; it never SENDS mail - sending stays on the send_email (SMTP) node.
     *
     * <p>Two modes, selected by {@code action}:
     * <ul>
     *   <li>{@code action="none"} (default) - READ: list messages from {@code folder},
     *       optionally {@code unreadOnly}, capped at {@code limit}, optionally since
     *       {@code sinceDays}. Each message exposes its stable IMAP {@code uid}.</li>
     *   <li>{@code action} in mark_read|mark_unread|flag|unflag|move|delete - act on the
     *       single message identified by {@code messageUid} in {@code folder}
     *       ({@code targetFolder} required for move).</li>
     * </ul>
     *
     * @param credentialId Selected IMAP credential id (falls back to the default IMAP credential)
     * @param folder Mailbox folder to read/act on (default: INBOX)
     * @param unreadOnly Read only unseen messages (default: false)
     * @param limit Max messages to return in READ mode (1-100, default: 10)
     * @param markSeen In READ mode, mark fetched messages as seen (default: false)
     * @param sinceDays In READ mode, only messages received within the last N days (0 = no limit)
     * @param action none | mark_read | mark_unread | flag | unflag | move | delete (default: none)
     * @param messageUid IMAP UID of the message to act on (required when action != none); SpEL-resolved
     * @param targetFolder Destination folder for the move action
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmailInboxConfig(
        Long credentialId,
        String folder,
        boolean unreadOnly,
        int limit,
        boolean markSeen,
        int sinceDays,
        String action,
        String messageUid,
        String targetFolder,
        // ---- READ-mode search filters (combined with AND) ----
        String fromContains,
        String subjectContains,
        String bodyContains,
        boolean flaggedOnly,
        int beforeDays,
        // ---- attachments ----
        boolean downloadAttachments
    ) {
        // Actions needing a messageUid (single-message). 'none' READS, 'list_folders' lists folders.
        public static final Set<String> MESSAGE_ACTIONS = Set.of(
            "mark_read", "mark_unread", "flag", "unflag", "move", "delete");
        public static final Set<String> VALID_ACTIONS = Set.of(
            "none", "mark_read", "mark_unread", "flag", "unflag", "move", "delete", "list_folders");

        public EmailInboxConfig {
            folder = (folder == null || folder.isBlank()) ? "INBOX" : folder.trim();
            limit = limit <= 0 ? 10 : Math.min(limit, 100);
            sinceDays = Math.max(sinceDays, 0);
            beforeDays = Math.max(beforeDays, 0);
            action = (action == null || action.isBlank()) ? "none" : action.trim().toLowerCase(Locale.ROOT);
            if (!VALID_ACTIONS.contains(action)) {
                throw new IllegalArgumentException(
                    "Invalid email_inbox action: " + action + ". Valid actions: " + VALID_ACTIONS);
            }
        }

        /** True when this action operates on a single message identified by messageUid. */
        public boolean isMessageAction() { return MESSAGE_ACTIONS.contains(action); }
    }

    // ==================== Code Config ====================

    /**
     * Code configuration - execute user code in a sandboxed environment via Piston.
     *
     * @param language Programming language: javascript, python, typescript, bash (default: javascript)
     * @param code The user code to execute
     * @param timeoutSeconds Maximum execution time in seconds (1-120, default: 10)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CodeConfig(String language, String code, int timeoutSeconds) {
        public CodeConfig {
            language = language == null ? "javascript" : language.toLowerCase(Locale.ROOT);
            code = code == null ? "" : code;
            timeoutSeconds = timeoutSeconds <= 0 ? 10 : Math.min(timeoutSeconds, 120);
        }
    }

    private static String normalizeMandatory(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Missing " + field);
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Empty " + field);
        }
        return trimmed;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Core type is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!VALID_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Invalid core type: " + value + ". Valid types: " + VALID_TYPES);
        }
        return normalized;
    }

    // ==================== Set Config ====================

    /**
     * Set configuration - assign or transform fields on the input data.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SetConfig(List<SetFieldAssignment> assignments, boolean keepOnlySet, String input) {
        public SetConfig {
            assignments = assignments == null ? List.of() : List.copyOf(assignments);
        }
    }

    /**
     * Single field assignment for the Set node.
     * - name: output key
     * - value: template string (resolved via templateAdapter)
     * - type: "string" | "number" | "boolean" | "json" | "auto"
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SetFieldAssignment(String name, String value, String type) {
        public SetFieldAssignment {
            type = type == null ? "auto" : type;
        }
    }

    // ==================== HtmlExtract Config ====================

    /**
     * HtmlExtract configuration - parse HTML via CSS selectors using jsoup.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HtmlExtractConfig(
            String sourceHtml,
            String extractionMode,
            String rootSelector,
            List<HtmlExtractField> fields,
            boolean cleanWhitespace
    ) {
        public HtmlExtractConfig {
            extractionMode = extractionMode == null ? "single" : extractionMode;
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    /**
     * Single field extraction definition for the HtmlExtract node.
     * - selector: CSS selector
     * - attribute: "text" | "html" | attribute name (e.g. "href")
     * - transform: "none" | "trim" | "lowercase" | "uppercase" | "number"
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HtmlExtractField(
            String name,
            String selector,
            String attribute,
            String transform,
            boolean required,
            String defaultValue
    ) {
        public HtmlExtractField {
            attribute = attribute == null ? "text" : attribute;
            transform = transform == null ? "none" : transform;
        }
    }

    // ==================== Task Config ====================

    /**
     * Task CRUD configuration - create, get, update, delete, or list agent tasks
     * directly from a workflow, without going through an agent.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskConfig(
            String operation,       // create_task, get_task, update_task, delete_task, list_tasks
            String taskId,          // template - UUID of existing task (for get/update/delete)
            String title,           // template - task title (for create/update)
            String instructions,    // template - task instructions (for create/update)
            String priority,        // low, normal, high, urgent (for create/update/list filter)
            String agentId,         // template - UUID of agent to assign (for create/update)
            String reviewerAgentId, // template - UUID of reviewer agent (for create)
            String status,          // status filter (for list/update)
            String search,          // search term (for list)
            Integer limit,          // max results (for list)
            Map<String, Object> taskContext  // arbitrary context data (for create)
    ) {
        public TaskConfig {
            operation = operation == null ? "list_tasks" : operation.trim().toLowerCase(Locale.ROOT);
            taskContext = taskContext == null ? Map.of() : Map.copyOf(taskContext);
        }
    }

    // ==================== StopOnError Config ====================

    /**
     * StopOnError configuration - immediately fail the workflow with an error message.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StopOnErrorConfig(String errorMessage, String errorCode) {
        public StopOnErrorConfig {
            errorMessage = errorMessage == null ? "Workflow stopped due to error" : errorMessage;
        }
    }

    // ==================== SSH Config ====================

    /**
     * SSH configuration - execute commands on remote servers via SSH.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SshConfig(
        String host, Integer port, String username, String authMethod,
        String password, String privateKey, String command, Integer timeout,
        Long credentialId
    ) {
        public SshConfig {
            port = port == null ? 22 : port;
            authMethod = authMethod == null ? "password" : authMethod.trim().toLowerCase(Locale.ROOT);
            timeout = timeout == null ? 30000 : timeout;
        }
    }

    // ==================== SFTP Config ====================

    /**
     * SFTP configuration - file operations on remote servers via SFTP.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SftpConfig(
        String host, Integer port, String username, String authMethod,
        String password, String privateKey, String operation,
        String remotePath, String localContent, String newPath, Integer timeout,
        Long credentialId
    ) {
        public SftpConfig {
            port = port == null ? 22 : port;
            authMethod = authMethod == null ? "password" : authMethod.trim().toLowerCase(Locale.ROOT);
            operation = operation == null ? "list" : operation.trim().toLowerCase(Locale.ROOT);
            timeout = timeout == null ? 30000 : timeout;
        }
    }

    // ==================== Database Config ====================

    /**
     * Database configuration - execute SQL queries against databases (PostgreSQL, MySQL, MSSQL).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DatabaseConfig(
        String dbType, String host, Integer port, String databaseName,
        String username, String password, Boolean sslEnabled,
        String query, List<String> queryParams,
        String operation, Integer timeout,
        Long credentialId
    ) {
        public DatabaseConfig {
            dbType = dbType == null ? "postgresql" : dbType.trim().toLowerCase(Locale.ROOT);
            port = port == null ? 5432 : port;
            sslEnabled = sslEnabled == null ? false : sslEnabled;
            queryParams = queryParams == null ? List.of() : List.copyOf(queryParams);
            operation = operation == null ? "select" : operation.trim().toLowerCase(Locale.ROOT);
            timeout = timeout == null ? 30000 : timeout;
        }
    }
}
