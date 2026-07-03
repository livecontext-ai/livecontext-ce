package com.apimarketplace.agent.tools.skill;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the skill help payload after the 2026-07 intra-payload dedup: the
 * ADDITIVE/max-10 assign contract now has ONE authority (the skill_ids
 * parameter doc) plus short contextual reminders. These guards keep the
 * surviving copies from being lost in a later edit.
 */
@DisplayName("SkillHelpModule")
class SkillHelpModuleTest {

    private SkillHelpModule helpModule;

    @BeforeEach
    void setUp() {
        helpModule = new SkillHelpModule();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> help() {
        return (Map<String, Object>) helpModule
                .execute("help", Map.of(), "tenant-1", ToolExecutionContext.of("tenant-1"))
                .orElseThrow(() -> new AssertionError("help module returned empty"))
                .data();
    }

    @Test
    @DisplayName("parameters.skill_ids is the single authority for the assign contract (ADDITIVE, all-at-once, max 10)")
    @SuppressWarnings("unchecked")
    void skillIdsParamIsAssignAuthority() {
        Map<String, Object> params = (Map<String, Object>) help().get("parameters");
        Map<String, Object> skillIds = (Map<String, Object>) params.get("skill_ids");
        assertThat((String) skillIds.get("description"))
                .contains("ADDITIVE")
                .contains("Pass ALL skill IDs at once")
                .contains("Max 10");
    }

    @Test
    @DisplayName("the assign action doc points at the skill_ids authority instead of restating it")
    @SuppressWarnings("unchecked")
    void assignActionPointsAtAuthority() {
        Map<String, String> actions = (Map<String, String>) help().get("skill_actions");
        assertThat(actions.get("assign"))
                .contains("additive")
                .contains("skill_ids parameter");
    }

    @Test
    @DisplayName("tips keep ONE merged assign reminder (one call, additive, max 10) and the description/instructions split")
    @SuppressWarnings("unchecked")
    void tipsKeepMergedReminders() {
        List<String> tips = (List<String>) help().get("skill_tips");
        assertThat(tips.stream().filter(t -> t.contains("additive"))).hasSize(1);
        assertThat(tips).anySatisfy(t -> assertThat(t)
                .contains("ONE call")
                .contains("max 10 per agent"));
        assertThat(tips).anySatisfy(t -> assertThat(t)
                .contains("'description' short")
                .contains("'instructions'"));
    }
}
