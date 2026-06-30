package com.apimarketplace.agent.service.execution;

import java.util.List;
import java.util.Map;

/**
 * Builds the per-resource allow-list and access-mode credential keys from an agent's
 * {@code toolsConfig}. This is the WRITE-side counterpart of
 * {@link com.apimarketplace.agent.config.ToolAccessControl#getAllowedIds} (the read side):
 * given a {@code toolsConfig} map it emits {@code allowed<Resource>Ids} + {@code <resource>AccessMode}
 * credential keys that the downstream tool modules enforce.
 *
 * <p><b>Single source of truth for agent-service.</b> Shared by
 * {@link SubAgentExecutionHandler} (sub-agent cascade) and
 * {@code CliAgentService} (top-level CLI-bridge session), so every agent-service
 * execution path turns a {@code toolsConfig} into enforcement credentials identically.
 * Mirrors the conversation path {@code AgentContextBuilder.applyToolsConfigCredentials}
 * (conversation-service); the two are intentionally kept in lock-step so a restricted
 * agent enforces the SAME resource scope whether it runs direct-API or via the bridge.
 *
 * <p>{@link #apply} covers the 5 internal resources + files + access modes (shared by both
 * callers); the catalog {@code tools}/{@code mode} axis lives in {@link #applyCatalogToolsMode},
 * which only a parent-less top-level session needs (sub-agents inherit it from the parent).
 *
 * <p>Pure function on maps - unit-testable without Spring.
 */
public final class AgentToolsConfigCredentials {

    private AgentToolsConfigCredentials() {}

    /**
     * Emit the allow-list + access-mode credentials for {@code toolsConfig} into {@code credentials}.
     *
     * <p><b>null toolsConfig = deny-all for the 5 internal resources</b> (a legacy/empty config must
     * not fall through to "no restriction" → full tenant access). A caller that wants "no config =
     * unrestricted" (the conversation/top-level-agent semantics) must guard {@code toolsConfig != null}
     * BEFORE calling this - see {@code CliAgentService}.
     */
    public static void apply(Map<String, Object> credentials, Map<String, Object> toolsConfig) {
        Map<String, Object> tc = toolsConfig != null ? toolsConfig : Map.of();
        passAllowedIds(credentials, tc, "tables", "allowedTableIds");
        passAllowedIds(credentials, tc, "interfaces", "allowedInterfaceIds");
        passAllowedIds(credentials, tc, "agents", "allowedAgentIds");
        passAllowedIds(credentials, tc, "workflows", "allowedWorkflowIds");
        passAllowedIds(credentials, tc, "applications", "allowedApplicationIds");
        // Files are OPT-IN - an empty/absent list means FULL org file access, the inverse of
        // the 5 internal resources above where [] = deny-all. So we must NOT write
        // allowedFileIds=[] for a config-less child: the empty plain key would shadow the
        // parent's forwarded __allowedFileIds__ (plain wins in ToolAccessControl.getAllowedIds)
        // and silently WIDEN the sub-agent to every org file. Only a non-empty child list
        // scopes; otherwise the child inherits the parent's forwarded scope (or stays unrestricted).
        if (tc.get("files") instanceof List<?> fileIds && !fileIds.isEmpty()) {
            // Stringify for parity with passAllowedIds (file IDs are UUIDs → no-op,
            // but keeps the credential map uniformly List<String>).
            credentials.put("allowedFileIds", fileIds.stream().map(String::valueOf).toList());
        }
        passAccessMode(credentials, tc, "tableAccessMode");
        passAccessMode(credentials, tc, "workflowAccessMode");
        passAccessMode(credentials, tc, "interfaceAccessMode");
        passAccessMode(credentials, tc, "agentAccessMode");
        passAccessMode(credentials, tc, "applicationAccessMode");
        passAccessMode(credentials, tc, "skillAccessMode");
        passAccessMode(credentials, tc, "fileAccessMode");
    }

    /**
     * Emit the catalog {@code tools} allow-list ({@code allowedToolIds}) from the agent's top-level
     * {@code mode} - the axis that {@code AgentContextBuilder.applyToolsConfigCredentials} also writes
     * but {@link #apply} does NOT. Kept separate from {@link #apply} because the sub-agent cascade
     * inherits its catalog scope from the parent (forwarded {@code __allowedToolIds__}), whereas a
     * PARENT-LESS top-level session (the CLI bridge) must resolve it from the bound agent's own
     * {@code mode}: {@code none} → {@code []} (deny all catalog tools), {@code custom} → the configured
     * tool list, {@code all}/absent → omitted (unrestricted). Mirrors {@code AgentContextBuilder} so the
     * bridge path's catalog scope matches the direct-API path.
     */
    public static void applyCatalogToolsMode(Map<String, Object> credentials, Map<String, Object> toolsConfig) {
        if (toolsConfig == null) {
            return; // no config → unrestricted (matches apply()'s caller-side null guard)
        }
        Object mode = toolsConfig.get("mode");
        if ("none".equals(mode)) {
            credentials.put("allowedToolIds", List.of());
        } else if ("custom".equals(mode)) {
            Object tools = toolsConfig.get("tools");
            credentials.put("allowedToolIds", tools instanceof List<?> toolIds
                    ? toolIds.stream().map(String::valueOf).toList() : List.of());
        }
    }

    private static void passAllowedIds(Map<String, Object> credentials, Map<String, Object> toolsConfig,
                                       String configKey, String credentialKey) {
        // GRANT sentinel - authoritative, DENY-BY-DEFAULT (mirrors AgentConfigProvider.isXNone
        // and the conversation path AgentContextBuilder.applyToolsConfigCredentials). Convention:
        // ABSENT __allowed<Family>Ids__ = unrestricted, [] = deny-all, [ids] = those ids. When a
        // <family>Grant is present it DRIVES the result: 'all' → OMIT (unrestricted) so a
        // grant:'all'+empty-list agent is NOT blocked as a sub-agent; 'custom' → emit the id list;
        // 'none' OR any UNRECOGNISED value → emit [] (deny). A none/unknown grant must never trust
        // a stale id list behind it - that is the fail-OPEN this guards against.
        Object grantObj = toolsConfig.get(configKey + "Grant");
        if (grantObj instanceof String grant) {
            if ("all".equals(grant)) {
                return;
            }
            if ("custom".equals(grant)) {
                // Stringify every id: allowlists created via MCP keep their native JSON type, so a
                // numeric-ID resource (tables:[209]) arrives as a List<Integer>; tool modules compare
                // with `.contains(String.valueOf(id))`, so a raw List<Integer> never matches → silent
                // "not in your approved list". UUID-based resources are already strings (no-op).
                Object value = toolsConfig.get(configKey);
                credentials.put(credentialKey, value instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList() : List.of());
            } else {
                credentials.put(credentialKey, List.of());
            }
            return;
        }
        // No grant axis on this key (a legacy config-less child) - emit the list as-is (stringified
        // like the custom branch); an absent key becomes [] so a missing list never bypasses the allowlist.
        Object value = toolsConfig.get(configKey);
        credentials.put(credentialKey, value instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of());
    }

    private static void passAccessMode(Map<String, Object> credentials, Map<String, Object> toolsConfig,
                                       String accessModeKey) {
        Object value = toolsConfig.get(accessModeKey);
        if (value != null) {
            credentials.put(accessModeKey, value);
        }
    }
}
