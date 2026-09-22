package com.apimarketplace.agent.client.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One agent run, located in time, for a calendar-style history.
 *
 * <p>Deliberately the SMALLEST shape that answers "which agent ran, when, how it was
 * launched and how it ended". An {@code agent_executions} row also carries the system
 * prompt, the tool sequence and two JSONB snapshots; a month of them would be megabytes
 * over the wire to draw a row of chips. The repository query is a projection for exactly
 * that reason - never widen this record without asking what the reader draws with it.
 *
 * @param executionId  the {@code agent_executions} row. Carried so a reader can address
 *                     this exact run later; it is also what makes the calendar entry's id
 *                     stable across refetches.
 * @param agentId      the agent that ran. Never null: the query INNER JOINs the agent, so
 *                     a run whose agent has since been deleted is not reported at all -
 *                     it could be neither named nor opened.
 * @param agentName    the agent's current name, not the name it had when it ran. The
 *                     execution row does not store a name, and a rename is a rename.
 * @param startedAt    when the run began. Derived by the writer from the end minus the
 *                     measured duration, which is the only start instant it ever sees.
 * @param endedAt      when it finished, or null for a row that is somehow still open.
 * @param status       {@code COMPLETED}, {@code FAILED} or {@code CANCELLED}. The row
 *                     is written once, at the end of the run, so the column's
 *                     {@code RUNNING} default is not a state a finished row is
 *                     ever read in.
 * @param source       how the run was launched, verbatim from the execution row
 *                     ({@code CHAT}, {@code SCHEDULE}, {@code WEBHOOK}, {@code WORKFLOW},
 *                     {@code SUB_AGENT}, {@code TASK}, {@code TASK_REVIEW},
 *                     {@code WIDGET}, ...). Left as the raw string: the vocabulary belongs
 *                     to the writer, and a consumer that does not recognise a value must
 *                     be able to say "unknown" rather than be handed a wrong label.
 *                     Declared AFTER {@code status} and documented in that order: both are
 *                     Strings, so a reader who trusted a different order would swap them
 *                     and it would compile.
 * @param conversationId the conversation this run happened in, when it had one. Every
 *                     chat, schedule, webhook, widget and task run does; a workflow agent
 *                     node may not.
 */
public record AgentRunFireDto(
        UUID executionId,
        UUID agentId,
        String agentName,
        Instant startedAt,
        Instant endedAt,
        String status,
        String source,
        String conversationId
) {
}
