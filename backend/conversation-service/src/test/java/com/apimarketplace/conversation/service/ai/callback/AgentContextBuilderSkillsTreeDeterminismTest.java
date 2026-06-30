package com.apimarketplace.conversation.service.ai.callback;

import com.apimarketplace.conversation.service.ai.AgentConfigProvider.AgentSkillsSummary;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.FolderSummary;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.SkillSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1a.2 sub-piece A - pin byte-stable rendering of the [SKILLS] block.
 *
 * <p>Anthropic prompt caching hashes the tools+system prefix; any per-pod
 * permutation of skills/folders (HashMap iteration order varies across JVMs
 * with randomized hash seeds) invalidates the cached prefix and bills the full
 * input instead of the 0.1× cache-read rate. These tests lock the sort so
 * every pod in the horizontal fleet emits the exact same bytes for a given
 * {@link AgentSkillsSummary}.
 */
@DisplayName("AgentContextBuilder.buildSkillsTreePrompt - byte-stable rendering (Stage 1a.2 A)")
class AgentContextBuilderSkillsTreeDeterminismTest {

    private final AgentContextBuilder builder = new AgentContextBuilder(null, null, null, null, null, null, null, null, null, null, null, null);

    @Test
    @DisplayName("shuffled skills produce byte-identical output across runs")
    void shuffledSkillsAreByteIdentical() {
        List<SkillSummary> skills = List.of(
                new SkillSummary("sk-1", "Summarize", "summarizer", null, true, false),
                new SkillSummary("sk-2", "Translate", "fr↔en", null, true, false),
                new SkillSummary("sk-3", "Extract",   "pdf extractor", null, true, false),
                new SkillSummary("sk-4", "Rewrite",   "copy editor",  null, true, false),
                new SkillSummary("sk-5", "Classify",  "multi-label",  null, true, false)
        );

        String baseline = builder.buildSkillsTreePrompt(new AgentSkillsSummary(skills, List.of()));

        Random rng = new Random(42);
        for (int i = 0; i < 20; i++) {
            List<SkillSummary> shuffled = new ArrayList<>(skills);
            Collections.shuffle(shuffled, rng);
            String out = builder.buildSkillsTreePrompt(new AgentSkillsSummary(shuffled, List.of()));
            assertThat(out).as("shuffle #%d", i).isEqualTo(baseline);
        }
    }

    @Test
    @DisplayName("shuffled folders and skills produce byte-identical output across runs")
    void shuffledFoldersAndSkillsAreByteIdentical() {
        List<FolderSummary> folders = List.of(
                new FolderSummary("f-root-a", "Marketing", null, false),
                new FolderSummary("f-root-b", "Engineering", null, false),
                new FolderSummary("f-child-1", "Copywriting", "f-root-a", false),
                new FolderSummary("f-child-2", "Backend", "f-root-b", false),
                new FolderSummary("f-child-3", "Frontend", "f-root-b", false)
        );
        List<SkillSummary> skills = List.of(
                new SkillSummary("sk-a", "Email draft", null, "f-child-1", true, false),
                new SkillSummary("sk-b", "SEO audit",   null, "f-root-a",  true, false),
                new SkillSummary("sk-c", "SQL review",  null, "f-child-2", true, false),
                new SkillSummary("sk-d", "React hooks", null, "f-child-3", true, false),
                new SkillSummary("sk-e", "Root skill",  null, null,        true, false)
        );

        AgentSkillsSummary canonical = new AgentSkillsSummary(skills, folders);
        String baseline = builder.buildSkillsTreePrompt(canonical);

        Random rng = new Random(7);
        for (int i = 0; i < 20; i++) {
            List<FolderSummary> shuffledFolders = new ArrayList<>(folders);
            List<SkillSummary> shuffledSkills = new ArrayList<>(skills);
            Collections.shuffle(shuffledFolders, rng);
            Collections.shuffle(shuffledSkills, rng);
            String out = builder.buildSkillsTreePrompt(new AgentSkillsSummary(shuffledSkills, shuffledFolders));
            assertThat(out).as("shuffle #%d", i).isEqualTo(baseline);
        }
    }

    @Test
    @DisplayName("siblings are rendered in id-ascending order inside each parent")
    void siblingsSortedById() {
        List<FolderSummary> folders = List.of(
                new FolderSummary("f-b", "B", null, false),
                new FolderSummary("f-a", "A", null, false),
                new FolderSummary("f-c", "C", null, false)
        );
        List<SkillSummary> skills = List.of(
                new SkillSummary("sk-z", "Zed", null, null, true, false),
                new SkillSummary("sk-m", "Mike", null, null, true, false),
                new SkillSummary("sk-a", "Alpha", null, null, true, false)
        );

        String out = builder.buildSkillsTreePrompt(new AgentSkillsSummary(skills, folders));

        // Root-level folders A, B, C come first (sorted by id asc), then root skills.
        int idxFolderA = out.indexOf("[folder:f-a]");
        int idxFolderB = out.indexOf("[folder:f-b]");
        int idxFolderC = out.indexOf("[folder:f-c]");
        int idxSkillA = out.indexOf("[sk-a]");
        int idxSkillM = out.indexOf("[sk-m]");
        int idxSkillZ = out.indexOf("[sk-z]");

        assertThat(idxFolderA).isLessThan(idxFolderB);
        assertThat(idxFolderB).isLessThan(idxFolderC);
        assertThat(idxSkillA).isLessThan(idxSkillM);
        assertThat(idxSkillM).isLessThan(idxSkillZ);
        assertThat(idxFolderC).isLessThan(idxSkillA);
    }

    @Test
    @DisplayName("empty summary renders header only, trailing whitespace stripped")
    void emptySummaryRendersHeader() {
        String out = builder.buildSkillsTreePrompt(new AgentSkillsSummary(List.of(), List.of()));

        assertThat(out).startsWith("[SKILLS]");
        assertThat(out).endsWith("skill_id='<id>')");
    }

    @Test
    @DisplayName("skills with null folderId attach to root and stay sorted")
    void rootSkillsSortedById() {
        List<SkillSummary> skills = List.of(
                new SkillSummary("sk-b", "Beta", null, null, true, false),
                new SkillSummary("sk-a", "Alpha", null, null, true, false)
        );

        String first = builder.buildSkillsTreePrompt(new AgentSkillsSummary(skills, List.of()));
        String second = builder.buildSkillsTreePrompt(new AgentSkillsSummary(
                List.of(skills.get(1), skills.get(0)), List.of()));

        assertThat(first).isEqualTo(second);
        assertThat(first.indexOf("[sk-a]")).isLessThan(first.indexOf("[sk-b]"));
    }

    @Test
    @DisplayName("null id tolerated and placed last without throwing")
    void nullIdDoesNotThrow() {
        List<SkillSummary> skills = new ArrayList<>();
        skills.add(new SkillSummary(null, "Nameless", null, null, true, false));
        skills.add(new SkillSummary("sk-a", "Alpha", null, null, true, false));

        String out = builder.buildSkillsTreePrompt(new AgentSkillsSummary(skills, List.of()));

        assertThat(out).contains("Alpha");
        assertThat(out).contains("Nameless");
        assertThat(out.indexOf("Alpha")).isLessThan(out.indexOf("Nameless"));
    }
}
