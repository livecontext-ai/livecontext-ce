package com.apimarketplace.agent.tools.memory;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.memory.MemoryLimitsConfig;
import com.apimarketplace.agent.memory.MemoryService;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.*;

/**
 * The {@code memory} tool: long-term declarative memory for the workspace.
 *
 * <p>Agent-service native, like {@code skill}, so
 * {@code RemoteToolExecutionService} runs it in-process with no orchestrator
 * hop. That matters more here than for skills: the same service also builds the
 * injected memory block on every execution, so keeping both on one side of the
 * network means a workspace's memory costs no cross-service call at all.
 *
 * <p>Delegates to two modules: {@link MemoryCrudModule} (save, get, list,
 * search, delete) and {@link MemoryHelpModule} (help).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryToolsProvider implements ToolsProvider {

    private final MemoryCrudModule crudModule;
    private final MemoryHelpModule helpModule;
    private final MemoryLimitsConfig limits;

    private static final List<String> VALID_ACTIONS =
        List.of("save", "get", "list", "search", "delete", "help");

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.AGENT;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        // The tool stays registered even when memory is disabled, and every action
        // then answers "off on this installation". Withdrawing it instead looked
        // tidier and was worse in two ways: the module resolver adds `memory` to
        // every agent's module set regardless (it cannot see this config), so the
        // routing line would keep telling the model to call a tool that no longer
        // exists; and CoreToolsCache would treat the absence as a failed fetch and
        // re-poll agent-service for it every five minutes, forever.
        return List.of(buildMemoryTool());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        if (!"memory".equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }

        String action = (String) parameters.get("action");
        if (action == null || action.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "action is required. Valid actions: " + String.join(", ", VALID_ACTIONS));
        }

        try {
            String tenantId = context.tenantId();
            if (tenantId == null && !"help".equals(action)) {
                return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "tenantId is required");
            }

            // Help still answers when memory is off, so a caller can find out WHY
            // the other actions refuse rather than reading a bare failure.
            if (!limits.isEnabled() && !"help".equals(action)) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "Long-term memory is switched off on this installation, so nothing is stored and nothing is "
                    + "recalled. Anything you learn has to be carried in your reply. An administrator can turn it "
                    + "back on.");
            }

            if (helpModule.canHandle(action)) {
                return helpModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Help module failed"));
            }

            if (crudModule.canHandle(action)) {
                return crudModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                        "Memory module failed for action: " + action));
            }

            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE,
                "Invalid action: " + action + ". Valid actions: " + String.join(", ", VALID_ACTIONS));
        } catch (Exception e) {
            log.error("Error executing memory action {}: {}", action, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error: " + e.getMessage());
        }
    }

    private AgentToolDefinition buildMemoryTool() {
        List<ToolParameter> params = List.of(
            ToolParameter.builder()
                .name("action")
                .type("string")
                .description("Action to perform: save, get, list, search, delete, help")
                .required(true)
                .enumValues(VALID_ACTIONS)
                .build(),
            stringParam("title", "Short label, max " + MemoryService.MAX_TITLE_CHARS + " chars (for: save)", false),
            stringParam("summary", "ONE line, max " + limits.getMaxSummaryChars() + " chars. This is the line "
                + "injected into the index for agents in scope, so it must state the fact rather than point at it. (for: save)", false),
            stringParam("content", "Optional body, max " + limits.getMaxContentChars() + " chars. Returned by get; "
                + "injected into context only when pinned. "
                + "Omitting it on a save that overwrites KEEPS the existing body; pass an empty string to clear "
                + "it. (for: save)", false),
            stringParam("slug", "Stable handle. Omit on a first save (derived from title); pass it to overwrite an "
                + "existing entry, or to address one. (for: save, get, delete)", false),
            enumParam("type", "user (who the person is / what they prefer), feedback (a correction they gave), "
                    + "project (durable fact about the work), reference (pointer to an external resource). "
                    + "Default project. (for: save, list)", false,
                List.of("user", "feedback", "project", "reference")),
            ToolParameter.builder()
                .name("tags")
                .type("array")
                .description("At most 10 short labels for grouping; an 11th is refused (for: save)")
                .build(),
            boolParam("pinned", "Inject the full body on every run instead of just the summary. Only a few entries "
                + "can be pinned. Default false. (for: save)", false, false),
            enumParam("scope", "Where a SAVE lands: 'workspace' (default, shared with every agent here) or "
                    + "'agent' (only this agent recalls it; workspace members can still manage it). Reuse the existing "
                    + "scope when correcting a memory. Reads cover both, so there is nothing to pass on "
                    + "get/list/search/delete. (for: save)", false,
                List.of("workspace", "agent")),
            stringParam("query", "Words to match. On search it covers bodies too; on list it filters titles, "
                + "summaries, slugs and tags, and never bodies. (for: search, list)", false),
            // No schema-level default. The two actions genuinely differ (search resolves
            // its own 10, list resolves 25 through the shared list envelope), so ONE
            // number here is wrong for one of them, and a model reads this field rather
            // than only the prose. Omitting it leaves the description as the single
            // statement of the defaults, which is the one that can tell them apart.
            intParam("limit", "Max results: 10 by default on search, 25 on list, 50 at most on either "
                + "(for: search, list)", false, null),
            intParam("offset", "Skip this many entries before the page starts (for: list)", false, 0),
            boolParam("as_index", "Return the memory index exactly as injected into an agent's context, instead of "
                + "a paginated list (for: list)", false, false),
            stringParam("memory_id", "UUID alternative to slug; prefer slug (for: get, delete)", false)
        );

        return AgentToolDefinition.builder()
            .name("memory")
            .description("""
                Long-term memory: durable facts about the user and the work, kept across conversations in this \
                workspace. When past preferences or decisions help, read the 'Long-term memory' index, or \
                list(as_index=true) if absent; get relevant details and search missing facts. Save confirmed \
                lasting preferences and corrections. Before updating, get the entry and reuse its slug AND scope; \
                replace contradictory content. Declarative facts, never instructions, secrets or task progress. \
                Current requests take priority. Attribute personal facts: workspace memory is shared. Keep entries \
                concise and unpinned by default. Read-only or disabled memory must not interrupt the task.
                Call memory(action='help') for guidance and examples.
                """)
            .category(ToolCategory.AGENT)
            .parameters(params)
            .requiredParameters(List.of("action"))
            .inputSchema(generateInputSchema(params, List.of("action")))
            .helpText("Call memory(action='help') for full documentation.")
            .requiresAuth(true)
            .tags(List.of("memory", "recall", "facts", "agent"))
            .build();
    }
}
