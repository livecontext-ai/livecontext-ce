package com.apimarketplace.conversation.service.ai.schema;

import java.time.Instant;

/**
 * Stage 4a.4 - per-conversation record that a {@code (tool, action)} pair
 * received a help response at a specific turn. Consumed by the tools-prefix
 * builder to decide whether the LLM still has the action's full schema
 * loaded or whether we must re-inject it with a
 * {@link com.apimarketplace.agent.tools.validation.ValidationResult}-style
 * error.
 *
 * <p><b>Freshness is derived, not stored.</b> The original plan shipped a
 * {@code stillInHotOrWarm} boolean <em>and</em> recomputed it per turn -
 * a contradiction (R45 in the planning audit). Instead we persist only
 * {@link #lastSeenTurn} and derive freshness at read time by comparing
 * to the current turn against a configurable turn budget
 * ({@link HelpSeenProperties#getHotWarmTurnBudget()}).
 *
 * <p>{@link #seenAt} is kept for telemetry and debugging (wall-clock time
 * a help was observed) but is not used for freshness - turn count is the
 * authoritative clock because compaction is turn-driven.
 *
 * <p><b>Storage.</b> Serialised to JSON and stored in a Redis hash keyed by
 * conversationId (see {@link HelpSeenRegistry}). No direct DB persistence -
 * the record is ephemeral; on conversation resume it is rehydrated from
 * summary metadata with a stale {@link #lastSeenTurn} so the next call
 * forces a cheap re-help.
 *
 * @param toolAction   lowercased {@code "tool:action"} key, matching the
 *                     lookup convention in {@code SchemaSlimExclusionPolicy}
 * @param seenAt       wall-clock instant the help was observed (telemetry only)
 * @param lastSeenTurn turn index at which the help was last observed;
 *                     negative values are allowed (used by rehydration to
 *                     force fresh=false)
 */
public record HelpSeenState(
        String toolAction,
        Instant seenAt,
        int lastSeenTurn
) {
}
