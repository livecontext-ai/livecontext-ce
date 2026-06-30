package com.apimarketplace.agent.service.credentials;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read/write helper for the {@code __callerChain__} reserved key on the agent-service
 * credentials map (see §4.2 of AGENT_BUDGET_HIERARCHY.md).
 *
 * <p>Credentials in agent-service are a raw {@code Map<String,Object>}; rather than introducing
 * a typed wrapper we store the cascading caller chain as an ordered list of ancestor agent
 * ids under a reserved key. The chain is <b>nearest-first</b>: element 0 is the immediate
 * parent, element 1 is the grandparent, and so on. Root invocations (REST, CLI, orchestrator
 * AgentNode) leave the key absent - {@link #get(Map)} returns an empty list and the cascade
 * reservation short-circuits to a no-op.
 *
 * <p>Example: when agent P spawns agent C while running,
 * {@code forChild(pCreds, pId)} returns {@code [P]}. When C in turn spawns G,
 * {@code forChild(cCreds, cId)} returns {@code [C, P]}.
 */
public final class CredentialsCallerChain {

    /** Reserved credentials key. */
    public static final String KEY = "__callerChain__";

    private CredentialsCallerChain() {}

    /**
     * Read the chain from a credentials map. Returns an empty, immutable list when the key
     * is absent or when the stored value is not a list (defensive - a corrupted chain
     * collapses to root semantics rather than crashing the spawn).
     */
    @SuppressWarnings("unchecked")
    public static List<UUID> get(Map<String, Object> credentials) {
        if (credentials == null) {
            return List.of();
        }
        Object raw = credentials.get(KEY);
        if (!(raw instanceof List)) {
            return List.of();
        }
        return List.copyOf((List<UUID>) raw);
    }

    /**
     * Build the chain to inject into a child agent's credentials. Prepends
     * {@code parentAgentId} to the existing chain (nearest-first ordering) and returns an
     * immutable list.
     *
     * @param parentCreds   the parent agent's credentials map (may be null)
     * @param parentAgentId the id of the agent doing the spawning - must not be null, since
     *                      the cascade reservation requires at least this one ancestor to
     *                      debit when the child starts
     */
    public static List<UUID> forChild(Map<String, Object> parentCreds, UUID parentAgentId) {
        List<UUID> existing = get(parentCreds);
        List<UUID> chain = new ArrayList<>(existing.size() + 1);
        chain.add(parentAgentId);
        chain.addAll(existing);
        return List.copyOf(chain);
    }
}
