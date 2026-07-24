package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Facade for control node creation in the workflow builder.
 * Delegates to specialized creators for each node type:
 * <ul>
 *   <li>{@link DecisionNodeCreator} - Decision, Switch and Option nodes (branching)</li>
 *   <li>{@link ForkMergeNodeCreator} - Fork and Merge nodes (parallelism)</li>
 *   <li>{@link UtilityNodeCreator} - Transform, Wait, Download File, HTTP Request, Stop, Response, Aggregate, Split nodes (utilities)</li>
 *   <li>{@link InterfaceNodeCreator} - Interface nodes (display HTML templates)</li>
 * </ul>
 *
 * This class follows the Facade pattern to maintain backward compatibility
 * while delegating to smaller, focused creator classes.
 *
 * @see DecisionNodeCreator
 * @see ForkMergeNodeCreator
 * @see UtilityNodeCreator
 * @see InterfaceNodeCreator
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ControlNodeCreator extends CreatorBase {

    private final DecisionNodeCreator decisionNodeCreator;
    private final ForkMergeNodeCreator forkMergeNodeCreator;
    private final UtilityNodeCreator utilityNodeCreator;
    private final InterfaceNodeCreator interfaceNodeCreator;

    // ==================== Decision Nodes ====================

    /**
     * Execute add_decision action.
     * Creates an if/else branching node that evaluates conditions to choose ONE branch.
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     * @see DecisionNodeCreator#executeAddDecision(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddDecision(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return decisionNodeCreator.executeAddDecision(session, parameters);
    }

    /**
     * Execute add_switch action.
     * Creates a switch/case node for multi-way branching based on expression value.
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     * @see DecisionNodeCreator#executeAddSwitch(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddSwitch(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return decisionNodeCreator.executeAddSwitch(session, parameters);
    }

    /**
     * Execute add_option action.
     * Creates a multiple choice node where each choice has an expression.
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     * @see DecisionNodeCreator#executeAddOption(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddOption(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return decisionNodeCreator.executeAddOption(session, parameters);
    }

    // ==================== Loop Node ====================

    /**
     * Execute add_loop action.
     * Creates a loop node with body and exit ports.
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     * @see UtilityNodeCreator#executeAddLoop(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddLoop(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddLoop(session, parameters);
    }

    // ==================== Approval Node ====================

    /**
     * Execute add_approval action.
     * Creates an approval node with approved/rejected/timeout ports.
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     * @see DecisionNodeCreator#executeAddApproval(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddApproval(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return decisionNodeCreator.executeAddApproval(session, parameters);
    }

    // ==================== Data Input Node ====================

    /**
     * Execute add_data_input action.
     * Creates a data input node that provides text/file inputs to downstream nodes.
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     * @see UtilityNodeCreator#executeAddDataInput(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddDataInput(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddDataInput(session, parameters);
    }

    // ==================== Split Node ====================

    /**
     * Execute add_split action.
     * Creates a split node that iterates over a list with parallel execution.
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     */
    public ToolExecutionResult executeAddSplit(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddSplit(session, parameters);
    }

    // ==================== Fork/Merge Nodes ====================

    /**
     * Execute add_fork action.
     * Fork creates parallel branches - ALL branches execute simultaneously.
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     * @see ForkMergeNodeCreator#executeAddFork(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddFork(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return forkMergeNodeCreator.executeAddFork(session, parameters);
    }

    /**
     * Execute add_merge action.
     * Merge waits for ALL predecessors to complete before continuing (AND mode).
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     * @see ForkMergeNodeCreator#executeAddMerge(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddMerge(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return forkMergeNodeCreator.executeAddMerge(session, parameters);
    }

    // ==================== Utility Nodes ====================

    /**
     * Execute add_transform action.
     * Transform applies data mappings - stored in cores but behaves as a passthrough step at execution.
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     * @see UtilityNodeCreator#executeAddTransform(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddTransform(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddTransform(session, parameters);
    }

    /**
     * Execute add_wait action.
     * Wait adds a delay - stored in cores but behaves as a passthrough step at execution.
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     * @see UtilityNodeCreator#executeAddWait(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddWait(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddWait(session, parameters);
    }

    /**
     * Execute add_download_file action.
     * Downloads a file from URL and stores it for use in the workflow.
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     * @see UtilityNodeCreator#executeAddDownloadFile(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddDownloadFile(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddDownloadFile(session, parameters);
    }

    /**
     * Mints a public, time-limited signed URL for a stored file (FileRef).
     * Delegates to UtilityNodeCreator for the actual implementation.
     *
     * @see UtilityNodeCreator#executeAddPublicLink(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddPublicLink(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddPublicLink(session, parameters);
    }

    /**
     * Processes audio/video files on the optional renderer component
     * (probe, mux_audio, mix, extract_audio).
     * Delegates to UtilityNodeCreator for the actual implementation.
     *
     * @see UtilityNodeCreator#executeAddMedia(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddMedia(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddMedia(session, parameters);
    }

    /**
     * Execute add_stop action.
     * Exit ends execution along this branch - terminal node.
     * Other parallel branches (fork, split) continue normally.
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     * @see UtilityNodeCreator#executeAddExit(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddExit(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddExit(session, parameters);
    }

    /**
     * Execute add_response action.
     * Sends a message to the chat interface.
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     * @see UtilityNodeCreator#executeAddResponse(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddResponse(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddResponse(session, parameters);
    }

    /**
     * Execute add_aggregate action.
     * Aggregates data from parallel Split executions.
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     * @see UtilityNodeCreator#executeAddAggregate(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddAggregate(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddAggregate(session, parameters);
    }

    /**
     * Execute add_http_request action.
     * Makes HTTP requests to external APIs.
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     * @see UtilityNodeCreator#executeAddHttpRequest(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddHttpRequest(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddHttpRequest(session, parameters);
    }

    /**
     * Execute add_interface action.
     * Displays an HTML interface linked to workflow outputs.
     *
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     * @see InterfaceNodeCreator#executeAddInterface(WorkflowBuilderSession, Map)
     */
    public ToolExecutionResult executeAddInterface(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return interfaceNodeCreator.executeAddInterface(session, parameters);
    }

    // ==================== Data Manipulation Nodes ====================

    public ToolExecutionResult executeAddFilter(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddFilter(session, parameters);
    }

    public ToolExecutionResult executeAddSort(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddSort(session, parameters);
    }

    public ToolExecutionResult executeAddLimit(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddLimit(session, parameters);
    }

    public ToolExecutionResult executeAddRemoveDuplicates(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddRemoveDuplicates(session, parameters);
    }

    public ToolExecutionResult executeAddSummarize(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddSummarize(session, parameters);
    }

    public ToolExecutionResult executeAddDateTime(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddDateTime(session, parameters);
    }

    public ToolExecutionResult executeAddCryptoJwt(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddCryptoJwt(session, parameters);
    }

    public ToolExecutionResult executeAddXml(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddXml(session, parameters);
    }

    public ToolExecutionResult executeAddCompression(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddCompression(session, parameters);
    }

    public ToolExecutionResult executeAddRss(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddRss(session, parameters);
    }

    public ToolExecutionResult executeAddConvertToFile(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddConvertToFile(session, parameters);
    }

    public ToolExecutionResult executeAddExtractFromFile(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddExtractFromFile(session, parameters);
    }

    public ToolExecutionResult executeAddCompareDatasets(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddCompareDatasets(session, parameters);
    }

    public ToolExecutionResult executeAddSubWorkflow(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddSubWorkflow(session, parameters);
    }

    public ToolExecutionResult executeAddRespondToWebhook(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddRespondToWebhook(session, parameters);
    }

    public ToolExecutionResult executeAddSendEmail(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddSendEmail(session, parameters);
    }

    public ToolExecutionResult executeAddEmailInbox(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddEmailInbox(session, parameters);
    }

    public ToolExecutionResult executeAddCode(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddCode(session, parameters);
    }

    public ToolExecutionResult executeAddSet(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddSet(session, parameters);
    }

    public ToolExecutionResult executeAddHtmlExtract(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddHtmlExtract(session, parameters);
    }

    public ToolExecutionResult executeAddTask(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddTask(session, parameters);
    }

    public ToolExecutionResult executeAddStopOnError(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddStopOnError(session, parameters);
    }

    public ToolExecutionResult executeAddSsh(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddSsh(session, parameters);
    }

    public ToolExecutionResult executeAddSftp(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddSftp(session, parameters);
    }

    public ToolExecutionResult executeAddDatabase(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return utilityNodeCreator.executeAddDatabase(session, parameters);
    }

    // ==================== Factory Method ====================

    /**
     * Create a control node by type.
     * Convenience method that dispatches to the appropriate creator based on type.
     *
     * @param type The node type (decision, switch, split, fork, merge, transform, wait)
     * @param session The workflow builder session
     * @param parameters The action parameters
     * @return Tool execution result
     */
    public ToolExecutionResult create(String type, WorkflowBuilderSession session, Map<String, Object> parameters) {
        return switch (type.toLowerCase()) {
            case "decision" -> executeAddDecision(session, parameters);
            case "switch" -> executeAddSwitch(session, parameters);
            case "option" -> executeAddOption(session, parameters);
            case "split" -> executeAddSplit(session, parameters);
            case "fork" -> executeAddFork(session, parameters);
            case "merge" -> executeAddMerge(session, parameters);
            case "transform" -> executeAddTransform(session, parameters);
            case "wait" -> executeAddWait(session, parameters);
            case "download_file" -> executeAddDownloadFile(session, parameters);
            case "public_link" -> executeAddPublicLink(session, parameters);
            case "media" -> executeAddMedia(session, parameters);
            case "exit" -> executeAddExit(session, parameters);
            case "response" -> executeAddResponse(session, parameters);
            case "aggregate" -> executeAddAggregate(session, parameters);
            case "http_request" -> executeAddHttpRequest(session, parameters);
            case "loop" -> executeAddLoop(session, parameters);
            case "approval" -> executeAddApproval(session, parameters);
            case "data_input" -> executeAddDataInput(session, parameters);
            case "interface" -> executeAddInterface(session, parameters);
            case "filter" -> executeAddFilter(session, parameters);
            case "sort" -> executeAddSort(session, parameters);
            case "limit" -> executeAddLimit(session, parameters);
            case "remove_duplicates" -> executeAddRemoveDuplicates(session, parameters);
            case "summarize" -> executeAddSummarize(session, parameters);
            case "date_time" -> executeAddDateTime(session, parameters);
            case "crypto_jwt" -> executeAddCryptoJwt(session, parameters);
            case "xml" -> executeAddXml(session, parameters);
            case "compression" -> executeAddCompression(session, parameters);
            case "rss" -> executeAddRss(session, parameters);
            case "convert_to_file" -> executeAddConvertToFile(session, parameters);
            case "extract_from_file" -> executeAddExtractFromFile(session, parameters);
            case "compare_datasets" -> executeAddCompareDatasets(session, parameters);
            case "sub_workflow" -> executeAddSubWorkflow(session, parameters);
            case "respond_to_webhook" -> executeAddRespondToWebhook(session, parameters);
            case "send_email" -> executeAddSendEmail(session, parameters);
            case "email_inbox" -> executeAddEmailInbox(session, parameters);
            case "code" -> executeAddCode(session, parameters);
            case "set" -> executeAddSet(session, parameters);
            case "html_extract" -> executeAddHtmlExtract(session, parameters);
            case "task" -> executeAddTask(session, parameters);
            case "stop_on_error" -> executeAddStopOnError(session, parameters);
            case "ssh" -> executeAddSsh(session, parameters);
            case "sftp" -> executeAddSftp(session, parameters);
            case "database" -> executeAddDatabase(session, parameters);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown control node type: " + type +
                ". Valid types: decision, switch, split, fork, merge, transform, wait, download_file, public_link, media, exit, response, aggregate, http_request, loop, approval, data_input, interface, filter, sort, limit, remove_duplicates, summarize, date_time, crypto_jwt, xml, compression, rss, convert_to_file, extract_from_file, compare_datasets, sub_workflow, respond_to_webhook, send_email, email_inbox, code, task, stop_on_error, ssh, sftp, database");
        };
    }
}
