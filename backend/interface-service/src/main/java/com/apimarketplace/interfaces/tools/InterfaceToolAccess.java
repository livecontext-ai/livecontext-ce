package com.apimarketplace.interfaces.tools;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;

import java.util.List;
import java.util.Optional;

/**
 * Shared {@code allowedInterfaceIds} ("approved interface list") enforcement for the
 * interface-tool modules ({@link InterfaceCrudModule}, {@link InterfacePublishModule}).
 *
 * <p>This mirrors {@code TableToolAccess} in datasource-service, and it exists for the same
 * reason: the interface CRUD module used to read the list from {@code context.variables()}
 * while the create-grant appended to {@code context.credentials()}, so read and write
 * disagreed. Worse than for tables, one edition never populates {@code variables} at all,
 * which turned a scoped agent into an unscoped one with no error and no log line.
 *
 * <p>Semantics (from {@code getAllowedIds}): {@code null} = unrestricted (no allow-list
 * configured), {@code []} = explicit no-access, a populated list = scoped to those ids.
 */
final class InterfaceToolAccess {

    private InterfaceToolAccess() {}

    /** The agent's approved interface-id list, or {@code null} when unrestricted. */
    static List<String> allowedInterfaceIds(ToolExecutionContext context) {
        return ToolAccessControl.getAllowedIds(
                context != null ? context.credentials() : null, "interface");
    }

    /**
     * Returns a PERMISSION_DENIED failure when the agent has a restricted interface allow-list
     * that does NOT contain {@code interfaceId}; empty otherwise (unrestricted, or the id is
     * allowed, or the id is absent - a missing id is left for the caller's own
     * MISSING_PARAMETER handling).
     */
    static Optional<ToolExecutionResult> denyIfNotAllowed(ToolExecutionContext context, Object interfaceId) {
        List<String> allowed = allowedInterfaceIds(context);
        if (allowed != null && interfaceId != null && !allowed.contains(String.valueOf(interfaceId))) {
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                    "This interface is not in your approved interface list."));
        }
        return Optional.empty();
    }
}
