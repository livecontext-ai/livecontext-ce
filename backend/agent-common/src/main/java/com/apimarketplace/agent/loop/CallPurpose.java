package com.apimarketplace.agent.loop;

/**
 * Classifies an LLM call so the centralized context-optimization pipeline
 * knows whether to apply its full logic (MAIN) or bypass to a fast path
 * (CLASSIFY, GUARDRAIL).
 *
 * <p>MAIN covers the four primary callers: agent, sub-agent, workflow
 * agent node, and bridge task. CLASSIFY, GUARDRAIL and JSON_COMPLETION are
 * short, single-iteration, tool-less calls that must remain latency-light
 * and untouched by zone tracking, cache layering, or summarization.
 * JSON_COMPLETION is the bare {@code (system, user) -> JSON} call behind the
 * json-completion endpoint, i.e. the COLD-summary compaction itself: it must
 * never be compacted in turn.
 *
 * <p>Conservative default: a null or absent purpose is treated as MAIN,
 * so a newly added caller routes through the centralized pipeline
 * unless it explicitly opts out.
 */
public enum CallPurpose {
    MAIN,
    CLASSIFY,
    GUARDRAIL,
    JSON_COMPLETION;

    public static CallPurpose orDefault(CallPurpose value) {
        return value != null ? value : MAIN;
    }
}
