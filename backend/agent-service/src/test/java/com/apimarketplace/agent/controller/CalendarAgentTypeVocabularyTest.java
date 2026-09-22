package com.apimarketplace.agent.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The calendar's allow-list of {@code agent_type} values, pinned.
 *
 * <p>{@code CALENDAR_AGENT_TYPES} is a hand-maintained mirror of a column six services
 * write, and a mirror drifts. It already had: {@code browser_agent} appeared in the
 * column without anyone deciding whether a Chromium session belongs on a calendar, and
 * nothing said so out loud until a reviewer went looking.
 *
 * <p>This test cannot discover a value nobody told it about - no test can. What it does
 * is make the set a DECISION: adding or removing a type fails here, so the next change to
 * that list arrives with someone having thought about which of the two silent failures
 * they are choosing. A missing allow entry leaves a visible hole somebody reports; a
 * wrongly added one puts an internal LLM call on a user's calendar, which nobody reports
 * because it looks like a feature.
 *
 * <p>Same pattern as {@code WorkflowIconExtractorParityTest} and
 * {@code ParamAliasCreatorParityTest}: a canonical literal beside the reasoning.
 */
@DisplayName("Calendar agent-type allow-list")
class CalendarAgentTypeVocabularyTest {

    /**
     * What a user can be shown as a run of theirs.
     *
     * <ul>
     *   <li>{@code agent} - an ordinary agent turn, wherever it was launched from. A
     *       claude-code / codex / gemini-cli CHAT lands here too: the chat adapter stamps
     *       this type for every provider.</li>
     *   <li>{@code sub_agent} - one agent spawned by another.</li>
     * </ul>
     *
     * <p>Deliberately absent, each for its own reason:
     * {@code classify} and {@code guardrail} (routing nodes of a plan, not agents anyone
     * can open), {@code compaction_summary} and {@code cold_summary} (summarisation the
     * platform does for itself, launched by nobody), {@code browser_agent} (a session run
     * as a TOOL inside another run that already has its own chip), and {@code cli}, which
     * is the interesting one: it was in this list until a review asked what it returned.
     * Its only writer never sets {@code agent_entity_id}, so the query's INNER JOIN drops
     * every such row - on the install checked, all 778 of them. A dead entry that the
     * docs describe as live is worse than no entry, because the next reader counts it as
     * coverage.
     */
    private static final Set<String> CANONICAL = Set.of("agent", "sub_agent");

    @Test
    @DisplayName("holds exactly the run types that can actually resolve to a named agent")
    void holdsExactlyTheCanonicalSet() {
        assertThat(Set.copyOf(calendarAgentTypes()))
            .as("Changing this set changes what appears on every user's calendar. "
              + "Update the canonical set here and the javadoc on "
              + "AgentExecutionRepository.findWorkspaceRunsBetweenStrict, which lists why "
              + "each excluded type is excluded.")
            .isEqualTo(CANONICAL);
    }

    @Test
    @DisplayName("is lower-case, because the query lower-cases the column to compare")
    void isLowerCase() {
        // The writers disagree on case - `agent`, `CLI`, `SUB_AGENT` - so the query uses
        // LOWER(e.agentType). An upper-case entry here would match nothing, silently.
        for (String type : calendarAgentTypes()) {
            assertThat(type).isEqualTo(type.toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Test
    @DisplayName("names no internal type, however it is spelled")
    void excludesTheInternalTypes() {
        assertThat(Set.copyOf(calendarAgentTypes()))
            .doesNotContain("classify", "guardrail", "compaction_summary", "cold_summary",
                    "browser_agent", "cli");
    }

    @SuppressWarnings("unchecked")
    private static List<String> calendarAgentTypes() {
        try {
            Field f = InternalAgentController.class.getDeclaredField("CALENDAR_AGENT_TYPES");
            f.setAccessible(true);
            return (List<String>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                "CALENDAR_AGENT_TYPES was renamed or removed; this guard must follow it", e);
        }
    }
}
