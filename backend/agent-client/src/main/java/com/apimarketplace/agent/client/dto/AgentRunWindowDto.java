package com.apimarketplace.agent.client.dto;

import java.time.Instant;
import java.util.List;

/**
 * One window of agent runs, WITH what the reader needs to know about its completeness.
 *
 * <p>The first version of this call answered a bare {@code List} and let the caller infer
 * truncation from "did I get as many rows as I asked for". Two things broke that
 * inference, both of them silently:
 *
 * <ul>
 *   <li><b>The per-member deny-list runs after the cap.</b> A member restricted from one
 *       agent gets a filtered list, so a capped window comes back SHORT and reads as
 *       complete. Their calendar then draws empty days for a period it never covered.</li>
 *   <li><b>A failed call is an empty list.</b> agent-service down, or slower than the 3 s
 *       budget, and the page states a complete history with no agent runs in it.</li>
 * </ul>
 *
 * <p>So the server says it outright, from the raw row count before any filtering, and the
 * client says whether it managed to ask at all. Neither is inferable downstream.
 *
 * @param runs        the runs the caller may see, newest first, already filtered by the
 *                    per-member deny-list
 * @param truncated   whether the cap cut the scan short, measured BEFORE that filtering
 * @param coveredFrom the oldest run the scan reached when truncated, so a reader can say
 *                    where its history starts instead of drawing an unexplained gap; null
 *                    when the window is complete
 * @param available   false when the window could not be read at all. Distinct from an
 *                    empty {@code runs}: one means "no agent ran", the other "nobody
 *                    knows", and a calendar must not draw them the same way.
 */
public record AgentRunWindowDto(
        List<AgentRunFireDto> runs,
        boolean truncated,
        Instant coveredFrom,
        boolean available
) {

    /**
     * Normalises a missing {@code runs} only.
     *
     * <p>{@code available} is deliberately NOT defaulted: it is a primitive, so a body
     * that omitted it would bind to false, i.e. "unavailable". That is the safe
     * direction (a reader is told less rather than more), and the server always writes
     * the field, which {@code AgentRunWindowRoundTripTest} proves rather than assumes.
     */
    public AgentRunWindowDto {
        runs = runs != null ? runs : List.of();
    }

    public static AgentRunWindowDto of(List<AgentRunFireDto> runs, boolean truncated,
                                       Instant coveredFrom) {
        return new AgentRunWindowDto(runs, truncated, coveredFrom, true);
    }

    /** The call did not happen or did not come back. */
    public static AgentRunWindowDto unavailable() {
        return new AgentRunWindowDto(List.of(), false, null, false);
    }
}
