package com.apimarketplace.agent.loop;

import java.util.List;

/**
 * Enumerates the MAIN-purpose entry points that MUST route through the centralized
 * {@link AgentLoopExecutor#processIteration} chokepoint.
 *
 * <p>This is the authoritative list of callers whose context-optimization pipeline
 * (HOT/WARM/COLD zones, Anthropic cache breakpoints, Gemini cached content, token
 * budgeting) is mandated to be identical. When a new MAIN caller is added, it MUST
 * be registered here so reviewers notice the addition and the integration test
 * ({@code MainCallerCentralizationTest}) can replay it through the chokepoint.
 *
 * <p>CLASSIFY and GUARDRAIL are intentionally excluded - they bypass the MAIN
 * pipeline by design (single-shot, no streaming, no compaction).
 */
public final class MainCallerRegistry {

    /** Identifier for each MAIN-purpose entry point. Stable - used in logs and tests. */
    public enum Caller {
        /** Conversation agent (chat UI) - {@code AgentContextBuilder} in conversation-service. */
        CONVERSATION_AGENT,
        /** Sub-agent dispatch - {@code SubAgentExecutionHandler} in agent-service. */
        SUB_AGENT,
        /** Workflow agent node (orchestrator → agent-service HTTP) - {@code AgentRemoteExecutionService}. */
        WORKFLOW_AGENT_NODE,
        /** Bridge-task dispatch (CLI providers: claude-code, codex, gemini-cli, mistral-vibe) - {@code BridgeLoopDispatcher}. */
        BRIDGE_TASK
    }

    public static final List<Caller> MAIN_CALLERS = List.of(
        Caller.CONVERSATION_AGENT,
        Caller.SUB_AGENT,
        Caller.WORKFLOW_AGENT_NODE,
        Caller.BRIDGE_TASK
    );

    private MainCallerRegistry() {}
}
