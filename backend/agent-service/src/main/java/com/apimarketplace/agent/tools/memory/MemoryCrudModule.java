package com.apimarketplace.agent.tools.memory;

import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.config.GuardOverrides;
import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentMemoryEntity;
import com.apimarketplace.agent.domain.AgentMemoryEntity.MemorySource;
import com.apimarketplace.agent.domain.AgentMemoryEntity.MemoryType;
import com.apimarketplace.agent.memory.MemoryAgentScope;
import com.apimarketplace.agent.memory.MemoryPromptSection;
import com.apimarketplace.agent.memory.MemoryService;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.AgentListEnvelope;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.agent.tools.common.ToolRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.apimarketplace.agent.tools.common.ToolParamUtils.*;

/**
 * The {@code memory} tool's data actions: save, get, list, search, delete.
 *
 * <p>Calls {@link MemoryService} directly - agent-service owns the table, so
 * there is no HTTP hop, which matters because the prompt-section build sits on
 * the hot path of every execution.
 *
 * <p><b>The agent addresses entries by slug, not by UUID.</b> A UUID in a tool
 * call is tokens the model pays for and cannot reason about; a slug it can
 * recognise in its own index block and reuse without a lookup. The UUID stays
 * the REST and UI handle, and {@code get} accepts either so a caller holding
 * one from the API is not stuck.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryCrudModule implements ToolModule {

    private static final Set<String> HANDLED_ACTIONS = Set.of("save", "get", "list", "search", "delete");

    private final MemoryService memoryService;
    private final MemoryPromptSection promptSection;
    private final AgentService agentService;
    private final AgentDefaultsConfig agentDefaults;

    private final ToolRateLimiter saveLimiter = new ToolRateLimiter();

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String action) {
        return HANDLED_ACTIONS.contains(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                 String tenantId, ToolExecutionContext context) {
        if (!canHandle(action)) return Optional.empty();

        var accessDenied = ToolAccessControl.checkWriteAccess(
            context != null ? context.credentials() : null, "memory", action);
        if (accessDenied.isPresent()) {
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, accessDenied.get()));
        }

        String orgId = context != null ? context.orgId() : null;
        if (orgId == null || orgId.isBlank()) {
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "No active workspace on this call, and memory belongs to a workspace. "
                + "This usually means the run started without workspace context; retry from a normal chat or agent run."));
        }

        return Optional.of(switch (action) {
            case "save" -> executeSave(parameters, tenantId, orgId, context);
            case "get" -> executeGet(parameters, orgId, context);
            case "list" -> executeList(parameters, orgId, context);
            case "search" -> executeSearch(parameters, orgId, context);
            case "delete" -> executeDelete(parameters, orgId, context);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown action: " + action);
        });
    }

    // ==================== Save ====================

    private ToolExecutionResult executeSave(Map<String, Object> parameters, String tenantId,
                                            String orgId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);

        String title = getStringParam(p, "title");
        String summary = getStringParam(p, "summary");
        if (title == null || title.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, """
                'title' is REQUIRED. It is the short label a person reads in the memory list.

                EXAMPLE:
                memory(action='save', title='Release cadence', \
                summary='The team ships on Thursdays and freezes on Wednesday afternoon.', \
                type='project')""");
        }
        if (summary == null || summary.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, """
                'summary' is REQUIRED. It is the single line injected into every agent's context in this \
                workspace, so it must carry the fact on its own, not point at it.

                GOOD: 'The user prefers answers under five lines with no preamble.'
                BAD:  'Notes about the user's preferences.'""");
        }

        // Per-turn cap, shared with the other resource creators. Prevents a run
        // that decides everything is memorable from filling the workspace in one turn.
        String turnId = context != null ? getTurnId(context.credentials()) : null;
        if (turnId != null) {
            int maxSaves = resolveMaxPerResourcePerTurn(context);
            var limited = saveLimiter.checkLimit(tenantId + ":memory:" + turnId, maxSaves,
                "LIMIT REACHED: you have already saved " + maxSaves + " memories while answering this message. "
                + "Memory is for facts that outlive the task; save the rest of what you learned in your reply instead.");
            if (limited.isPresent()) return limited.get();
        }

        MemoryType type = parseType(getStringParam(p, "type"));
        if (type == null && getStringParam(p, "type") != null) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE,
                "'type' must be one of: user, feedback, project, reference. "
                + "user = who the person is and what they prefer; feedback = a correction they gave you; "
                + "project = a durable fact about the work; reference = a pointer to an external resource.");
        }

        UUID scopeAgentId;
        String scope = getStringParam(p, "scope");
        if (p.get("scope") != null && (scope == null
                || !Set.of("workspace", "agent").contains(scope.trim().toLowerCase(Locale.ROOT)))) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE,
                "'scope' must be 'workspace' or 'agent'. Nothing was saved; choose the intended visibility explicitly.");
        }
        try {
            scopeAgentId = resolveWriteScopeAgentId(p, context);
        } catch (NoCallingAgentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "scope='agent' requires a calling agent, but this conversation has none. Nothing was saved. "
                + "Do not broaden the scope to work around this refusal; continue the task without storing the fact.");
        }

        try {
            MemoryService.SaveOutcome outcome = memoryService.save(new MemoryService.SaveRequest(
                tenantId, orgId, scopeAgentId,
                getStringParam(p, "slug"), title, summary, getStringParam(p, "content"),
                type, readTags(p), getBooleanParam(p, "pinned"),
                MemorySource.AGENT, callerAgentId(context)
            ), context != null ? context.orgRole() : null);
            AgentMemoryEntity saved = outcome.entity();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("slug", saved.getSlug());
            result.put("id", saved.getId().toString());
            result.put("title", saved.getTitle());
            result.put("type", saved.getType().name().toLowerCase(Locale.ROOT));
            result.put("scope", saved.getAgentId() == null ? "workspace" : "agent");
            result.put("pinned", saved.getPinned());
            // Reported because it can be FALSE on a successful save: writing onto an
            // entry a person switched off stores the text and leaves the entry off.
            // The help tells the agent to read this field, so it has to be here -
            // and without it the note below would be a plain lie in that case.
            boolean active = !Boolean.FALSE.equals(saved.getIsActive());
            result.put("is_active", active);
            // CREATED or REPLACED, never a flat "SAVED". The upsert key is derived from
            // the title, so a save can land on an entry the agent has never seen - one a
            // PERSON wrote - and replace its title, summary and body. Reported as "SAVED"
            // the agent cannot tell, so it cannot mention it, and the person learns their
            // note is gone by missing it later.
            result.put("status", outcome.created() ? "CREATED" : "REPLACED");
            if (!outcome.created()) {
                result.put("replaced_title", outcome.replacedTitle());
                result.put("replaced_source", outcome.replacedSource() == null
                    ? null : outcome.replacedSource().name().toLowerCase(Locale.ROOT));
            }
            result.put("note", buildSaveNote(outcome, active));
            return ToolExecutionResult.success(result, Map.of("label", saved.getTitle()));
        } catch (MemoryService.MemoryWriteForbiddenException e) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, e.getMessage());
        } catch (MemoryService.MemoryValidationException e) {
            // INVALID_PARAMETER_VALUE, not EXECUTION_FAILED: a length cap, a full
            // workspace or an injection refusal is something the CALLER has to
            // change. EXECUTION_FAILED reads as transient and invites the identical
            // retry, which will be refused identically.
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, e.getMessage());
        } catch (Exception e) {
            log.error("[MEMORY] save failed in org {}: {}", orgId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to save memory: " + e.getMessage());
        }
    }

    // ==================== Get ====================

    private ToolExecutionResult executeGet(Map<String, Object> parameters, String orgId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);
        String slug = getStringParam(p, "slug");
        UUID id = getUuidParam(p, "memory_id");

        if ((slug == null || slug.isBlank()) && id == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "Pass 'slug' (the handle shown on each line of your memory index) or 'memory_id'. "
                + "To see what is stored: memory(action='list').");
        }

        try {
            Optional<AgentMemoryEntity> found = id != null
                ? memoryService.getByIdVisibleToAgent(id, orgId, callerAgentId(context))
                : memoryService.getBySlugAndRecordRecall(orgId, callerAgentId(context), slug);

            if (found.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                    "No memory '" + (slug != null ? slug : id) + "' in this workspace. "
                    + "Use memory(action='search', query='...') to find one by wording, "
                    + "or memory(action='list') to see everything stored.");
            }
            return ToolExecutionResult.success(toDetailMap(found.get()),
                Map.of("label", found.get().getTitle()));
        } catch (MemoryService.MemoryValidationException e) {
            // Same reasoning as the writes: a parameter the caller has to change,
            // not a failure worth retrying identically.
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, e.getMessage());
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to read memory: " + e.getMessage());
        }
    }

    // ==================== List ====================

    private ToolExecutionResult executeList(Map<String, Object> parameters, String orgId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);
        String typeFilter = getStringParam(p, "type");
        String query = getStringParam(p, "query");

        // The bridge escape hatch. An external CLI agent (Claude Code, Codex)
        // driving this platform reads the tool list and never the system prompt,
        // so the injected block never reaches it. Asking for the block here is
        // the only way such a caller can discover the workspace has a memory.
        if (Boolean.TRUE.equals(getBooleanParam(p, "as_index"))) {
            String block = renderIndexBlock(orgId, callerAgentId(context));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("index", block);
            result.put("note", block.isEmpty()
                ? "This workspace has no memories yet. Save one with memory(action='save', ...)."
                : "This is the same block that is injected into an agent's context. Read one entry in full with "
                  + "memory(action='get', slug='...').");
            return ToolExecutionResult.success(result);
        }

        AgentListEnvelope.Spec spec = AgentListEnvelope.Spec.of(
                AgentListEnvelope.Caps.STANDARD, "memories", "memories", "memories")
            .withSuggestedFilters(List.of("type", "query"));

        AgentListEnvelope.Bounds bounds;
        try {
            Set<String> activeFilters = new java.util.LinkedHashSet<>();
            if (typeFilter != null && !typeFilter.isBlank()) activeFilters.add("type");
            if (hasQuery(query)) activeFilters.add("query");
            bounds = AgentListEnvelope.readBounds(p, spec, activeFilters);
        } catch (AgentListEnvelope.InvalidParamsException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.code + ": " + e.getMessage());
        }

        try {
            List<AgentMemoryEntity> entries = memoryService.listVisible(orgId, callerAgentId(context));

            MemoryType wanted = parseType(typeFilter);
            if (wanted == null && typeFilter != null && !typeFilter.isBlank()) {
                // A filter the tool cannot parse used to be dropped, so asking for
                // type='feedbacks' returned the WHOLE list and the agent read forty
                // unrelated rows as feedback. save refuses the same value; so does
                // this. No silent fallbacks.
                return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE,
                    "'type' must be one of: user, feedback, project, reference. Omit it to list "
                    + "every type.");
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AgentMemoryEntity entry : entries) {
                if (wanted != null && entry.getType() != wanted) continue;
                // Tags are matched too. The help calls them "for filtering", and until this
                // line that was simply untrue: an agent that dutifully tagged its entries
                // could not then find them by tag, and nothing said so. They are also
                // returned on the row below, so a filter the agent can apply is one it can
                // see the result of.
                if (hasQuery(query) && !matchesQuery(query, entry.getTitle(), entry.getSummary(),
                        entry.getSlug(), tagsAsText(entry))) continue;
                rows.add(toSummaryMap(entry));
            }
            return ToolExecutionResult.success(AgentListEnvelope.paginateInMemory(rows, bounds, spec));
        } catch (MemoryService.MemoryValidationException e) {
            // Same reasoning as the writes: a parameter the caller has to change,
            // not a failure worth retrying identically.
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, e.getMessage());
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to list memories: " + e.getMessage());
        }
    }

    // ==================== Search ====================

    private ToolExecutionResult executeSearch(Map<String, Object> parameters, String orgId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);
        String query = getStringParam(p, "query");
        if (query == null || query.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "'query' is REQUIRED for search. It is matched against every entry's title, summary AND body, "
                + "so search finds facts whose body mentions a word the one-line index does not.");
        }
        Integer limit = getIntParam(p, "limit", 10);

        try {
            List<AgentMemoryEntity> hits = memoryService.search(orgId, callerAgentId(context), query, limit);
            List<Map<String, Object>> rows = hits.stream().map(this::toSummaryMap).toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", query);
            result.put("count", rows.size());
            result.put("memories", rows);
            if (rows.isEmpty()) {
                result.put("note", "Nothing matched. Search is word-based, not semantic: try a different word, "
                    + "or memory(action='list') to see everything stored in this workspace.");
            } else {
                result.put("note", "Summaries only. Read one in full with memory(action='get', slug='...').");
            }
            return ToolExecutionResult.success(result);
        } catch (MemoryService.MemoryValidationException e) {
            // Same reasoning as the writes: a parameter the caller has to change,
            // not a failure worth retrying identically.
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, e.getMessage());
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to search memories: " + e.getMessage());
        }
    }

    // ==================== Delete ====================

    private ToolExecutionResult executeDelete(Map<String, Object> parameters, String orgId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);
        String slug = getStringParam(p, "slug");
        UUID id = getUuidParam(p, "memory_id");

        if ((slug == null || slug.isBlank()) && id == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "Pass 'slug' or 'memory_id' to say which memory to delete. memory(action='list') gives you "
            + "the slug of every entry; the id comes back from get and from save.");
        }

        try {
            UUID target = id;
            String deletedSlug = null;
            if (target == null) {
                Optional<AgentMemoryEntity> found =
                    memoryService.findBySlugVisibleToAgent(orgId, callerAgentId(context), slug);
                if (found.isEmpty()) {
                    return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                        "No memory '" + slug + "' in this workspace, so there is nothing to delete.");
                }
                target = found.get().getId();
                deletedSlug = found.get().getSlug();
            }
            memoryService.deleteVisibleToAgent(target, orgId,
                context != null ? context.orgRole() : null, callerAgentId(context));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "DELETED");
            // The STORED handle, not what the caller typed: answering
            // slug: "Release Cadence" when the index shows "release-cadence" invites
            // the agent to address the next call with a handle that does not exist.
            result.put("slug", deletedSlug != null ? deletedSlug : target.toString());
            result.put("note", "Removed from the index for every run started from now on.");
            return ToolExecutionResult.success(result);
        } catch (MemoryService.MemoryWriteForbiddenException e) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, e.getMessage());
        } catch (MemoryService.MemoryNotFoundException e) {
            // Includes a sibling agent's private entry addressed by memory_id: not
            // found is the whole point, and EXECUTION_FAILED would read as a bug.
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, e.getMessage());
        } catch (MemoryService.MemoryValidationException e) {
            // INVALID_PARAMETER_VALUE, not EXECUTION_FAILED: a length cap, a full
            // workspace or an injection refusal is something the CALLER has to
            // change. EXECUTION_FAILED reads as transient and invites the identical
            // retry, which will be refused identically.
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, e.getMessage());
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to delete memory: " + e.getMessage());
        }
    }

    // ==================== Helpers ====================

    /** Raised when {@code scope='agent'} is asked for on a call that has no calling agent. */
    private static final class NoCallingAgentException extends RuntimeException {
        NoCallingAgentException() { super("no calling agent"); }
    }

    /**
     * Which scope a WRITE lands in.
     *
     * <p>{@code scope='agent'} means the calling agent's private memory, resolved
     * from the execution credentials and never from a caller-supplied id: an agent
     * must not be able to write into a sibling's private memory by naming it.
     * Anything else, including the default, is workspace scope.
     *
     * <p>When {@code scope='agent'} is asked for and there is no calling agent (a
     * plain chat), this FAILS rather than falling back to workspace scope. The
     * fallback looks harmless and is the opposite: the caller asked for a private
     * entry and would have got one every agent in the workspace can read, with the
     * widening reported nowhere the caller is obliged to look.
     */
    private UUID resolveWriteScopeAgentId(Map<String, Object> p, ToolExecutionContext context) {
        String scope = getStringParam(p, "scope");
        if (scope != null && "agent".equalsIgnoreCase(scope.trim())) {
            UUID caller = callerAgentId(context);
            if (caller == null) {
                throw new NoCallingAgentException();
            }
            return caller;
        }
        return null;
    }

    /** The agent running this tool call, or null for a chat with none bound. */
    private UUID callerAgentId(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) return null;
        return MemoryAgentScope.agentIdOrNull(context.credentials().get("__agentId__"));
    }

    /**
     * Per-turn save cap: caller-agent override, then the conversation-scope
     * override, then the YAML default. Same ladder and same soft-fail as
     * {@code SkillCrudModule.resolveMaxPerResourcePerTurn}.
     */
    int resolveMaxPerResourcePerTurn(ToolExecutionContext context) {
        int fallback = agentDefaults.getMaxPerResourcePerTurn();
        Map<String, Object> credentials = context != null ? context.credentials() : null;
        Integer agentOverride = null;
        UUID callerId = callerAgentId(context);
        if (callerId != null) {
            try {
                agentOverride = agentService.findById(callerId)
                    .map(AgentEntity::getMaxPerResourcePerTurn).orElse(null);
            } catch (Exception e) {
                log.debug("[MEMORY] Could not resolve per-agent save cap, using default: {}", e.toString());
            }
        }
        return GuardOverrides.resolve(agentOverride, credentials,
            GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, fallback);
    }

    /**
     * What the agent is told about the write it just made.
     *
     * <p>Three things it has to know and cannot see: whether the entry is switched
     * off (stored but never recalled), whether this write REPLACED an existing
     * entry, and if so whether that entry was a person's. The switched-off case
     * wins when both apply, because it is the one that makes the write pointless.
     */
    private static String buildSaveNote(MemoryService.SaveOutcome outcome, boolean active) {
        AgentMemoryEntity saved = outcome.entity();
        if (!active) {
            return "Your text was stored, but this entry is switched off: someone deliberately turned this "
                + "fact off, so it will NOT be recalled and will not appear in your index. Saving it "
                + "again will not bring it back. Say what you recorded and that it is currently "
                + "switched off, and leave the decision to the person.";
        }

        String stored = "Stored now and readable immediately with memory(action='get', slug='"
            + saved.getSlug() + "'). It joins the memory index at the start of the NEXT run: the "
            + "context of the run in progress is deliberately frozen so the prompt stays cacheable.";
        if (outcome.created()) {
            return stored;
        }

        // A replacement is not a failure and is usually the point (a correction
        // arrives as a save on the same handle), so this reports rather than refuses.
        // What it must not do is stay silent when the previous text was a person's.
        String replaced = "This REPLACED the existing memory '" + outcome.replacedTitle()
            + "' at the same handle. Submitted fields were updated; omitted fields were kept. ";
        if (outcome.replacedSource() == MemorySource.USER) {
            replaced += "That entry had been written by a PERSON, so say in your reply what you "
                + "overwrote and what it now says, rather than reporting a plain save. ";
        } else {
            replaced += "It was an earlier agent entry, so this reads as a correction. ";
        }
        return replaced + stored;
    }

    /** Null for absent AND for unrecognized. The caller that cares tells them apart
     * on the raw value, because a tool answers a bad enum with a failure result
     * rather than an exception. The set of accepted names is the service's, so it
     * cannot drift from what the REST surface accepts.
     */
    private static MemoryType parseType(String raw) {
        return MemoryService.parseType(raw).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> readTags(Map<String, Object> p) {
        Object raw = p.get("tags");
        if (raw instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
        }
        if (raw instanceof String s && !s.isBlank()) {
            // Tolerate a comma-joined string: models emit one about as often as a
            // real array, and refusing it costs the caller a turn for nothing.
            return List.of(s.split("\\s*,\\s*"));
        }
        return null;
    }

    /** An entry's tags as one searchable string, or empty when it has none. */
    private static String tagsAsText(AgentMemoryEntity entry) {
        return entry.getTags() == null ? "" : String.join(" ", entry.getTags());
    }

    private Map<String, Object> toSummaryMap(AgentMemoryEntity entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("slug", entry.getSlug());
        map.put("title", entry.getTitle());
        map.put("summary", entry.getSummary());
        // On the LIST row, not only on get: the query filters by them, so an agent
        // that cannot see them cannot tell why a row matched or which tag to ask
        // for next. A handful of short words, so the cost is nil.
        map.put("tags", entry.getTags() == null ? List.of() : entry.getTags());
        map.put("type", entry.getType().name().toLowerCase(Locale.ROOT));
        map.put("scope", entry.getAgentId() == null ? "workspace" : "agent");
        map.put("pinned", entry.getPinned());
        map.put("has_body", entry.getContent() != null && !entry.getContent().isBlank());
        // Only ever false on the entry a save just returned: every other action
        // filters deactivated rows out. It is reported so a save onto an entry a
        // person switched off does not look like a no-op - the text was stored, it
        // just will not be recalled until someone turns the entry back on.
        map.put("is_active", !Boolean.FALSE.equals(entry.getIsActive()));
        return map;
    }

    private Map<String, Object> toDetailMap(AgentMemoryEntity entry) {
        Map<String, Object> map = toSummaryMap(entry);
        map.put("id", entry.getId().toString());
        map.put("content", entry.getContent());
        map.put("tags", entry.getTags());
        map.put("source", entry.getSource().name().toLowerCase(Locale.ROOT));
        map.put("updated_at", entry.getUpdatedAt() != null ? entry.getUpdatedAt().toString() : null);
        return map;
    }

    /**
     * The index block exactly as it is injected into a system prompt. Exposed
     * through {@code memory(action='list', as_index=true)} for the callers that
     * never receive a system prompt at all - an external Claude Code or Codex
     * process driving this platform over the CLI bridge reads the tool list and
     * ignores the prompt, so without this it would have no way to learn that the
     * workspace has a memory, let alone what is in it.
     */
    String renderIndexBlock(String orgId, UUID agentId) {
        return promptSection.render(orgId, agentId);
    }
}
