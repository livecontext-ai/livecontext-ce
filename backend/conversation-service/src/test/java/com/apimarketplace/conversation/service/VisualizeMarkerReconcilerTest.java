package com.apimarketplace.conversation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link VisualizeMarkerReconciler}.
 *
 * <p>The reconciler exists to defeat one concrete failure mode: an LLM that types a
 * {@code [visualize:type:id]} marker into its reply but confabulates the 36-char id
 * (or, for run cards, the runId). The authoritative reference lives in the tool
 * result's {@code visualization} metadata, persisted into the {@code tool_calls} JSON.
 * These tests pin BOTH that the mistake gets corrected AND that the reconciler never
 * "corrects" a marker it cannot prove wrong (the dangerous direction - silently
 * repointing a valid card). Tool-call JSON fixtures use the REAL metadata shapes the
 * producing tools emit (verified against WorkflowBuilderProvider, ApplicationExecuteModule,
 * and the datasource ToolParameterUtils).
 */
@DisplayName("VisualizeMarkerReconciler")
class VisualizeMarkerReconcilerTest {

    private static final String CORRECT = "837a75d4-9d83-4037-973e-9d8e0b6db56f";
    private static final String HALLUCINATED = "837a75d4-9d83-4047-b2e9-d3c00e46eb3a";

    /** One authoritative workflow visualization (no runId - workflow cards have none). */
    private static String workflowToolCalls(String id) {
        return "[{\"toolName\":\"workflow\",\"id\":\"toolu_1\","
                + "\"visualization\":{\"title\":\"Photo Post Publisher (via chat)\","
                + "\"type\":\"workflow\",\"id\":\"" + id + "\"}},"
                + "{\"toolName\":\"_meta\",\"reasoningDurationMs\":5}]";
    }

    /** Real workflow_run shape emitted by WorkflowBuilderProvider on execute. */
    private static String workflowRunToolCalls(String id, String runId) {
        return "[{\"toolName\":\"workflow\",\"id\":\"toolu_1\","
                + "\"visualization\":{\"type\":\"workflow_run\",\"id\":\"" + id + "\","
                + "\"title\":\"WF\",\"runId\":\"" + runId + "\"}}]";
    }

    @Nested
    @DisplayName("corrects a provably-wrong marker")
    class Corrects {

        @Test
        @DisplayName("regression: prod 2026-06-05 - hallucinated workflow id rewritten to the authoritative one")
        void hallucinatedWorkflowId_isRewritten() {
            String content = "✅ Corrigé et sauvegardé.\n\n[visualize:workflow:" + HALLUCINATED + "]";

            String result = VisualizeMarkerReconciler.reconcile(content, workflowToolCalls(CORRECT));

            assertThat(result)
                    .contains("[visualize:workflow:" + CORRECT + "]")
                    .doesNotContain(HALLUCINATED);
        }

        @Test
        @DisplayName("preserves the trailing title segment (and a colon inside it) while fixing the id")
        void preservesTitleSegment() {
            String content = "[visualize:workflow:" + HALLUCINATED + ":Photo: Post Publisher (via chat)]";

            String result = VisualizeMarkerReconciler.reconcile(content, workflowToolCalls(CORRECT));

            assertThat(result).isEqualTo(
                    "[visualize:workflow:" + CORRECT + ":Photo: Post Publisher (via chat)]");
        }

        @Test
        @DisplayName("fixes every occurrence of the same mistyped marker in the text")
        void fixesAllOccurrences() {
            String content = "see [visualize:workflow:" + HALLUCINATED + "] ... again [visualize:workflow:"
                    + HALLUCINATED + "]";

            String result = VisualizeMarkerReconciler.reconcile(content, workflowToolCalls(CORRECT));

            assertThat(result).doesNotContain(HALLUCINATED);
            assertThat(countOccurrences(result, "[visualize:workflow:" + CORRECT + "]")).isEqualTo(2);
        }

        @Test
        @DisplayName("datasource marker is matched via the table↔datasource synonym and its id corrected")
        void datasourceMarker_matchedViaTableSynonym() {
            // Real datasource tool: marker says 'datasource', metadata says 'table'
            String toolCalls = "[{\"toolName\":\"table\",\"id\":\"t1\","
                    + "\"visualization\":{\"type\":\"table\",\"id\":\"42\",\"title\":\"My Table\"}}]";
            String content = "[visualize:datasource:99]"; // wrong id

            String result = VisualizeMarkerReconciler.reconcile(content, toolCalls);

            // type token preserved as 'datasource', id corrected to the authoritative 42
            assertThat(result).isEqualTo("[visualize:datasource:42]");
        }

        @Test
        @DisplayName("only the wrong marker is touched when content mixes a correct datasource card and a wrong workflow card")
        void mixedMarkers_onlyWrongOneTouched() {
            String toolCalls = "[{\"toolName\":\"workflow\",\"id\":\"t1\","
                    + "\"visualization\":{\"type\":\"workflow\",\"id\":\"" + CORRECT + "\"}},"
                    + "{\"toolName\":\"table\",\"id\":\"t2\","
                    + "\"visualization\":{\"type\":\"table\",\"id\":\"42\"}}]";
            String content = "[visualize:datasource:42] and [visualize:workflow:" + HALLUCINATED + "]";

            String result = VisualizeMarkerReconciler.reconcile(content, toolCalls);

            assertThat(result).isEqualTo("[visualize:datasource:42] and [visualize:workflow:" + CORRECT + "]");
        }

        @Test
        @DisplayName("workflow_run: corrects the workflow id while preserving the (correct) runId")
        void workflowRun_idCorrected_runIdPreserved() {
            String content = "[visualize:workflow_run:" + HALLUCINATED + ":run-1]";

            String result = VisualizeMarkerReconciler.reconcile(content, workflowRunToolCalls(CORRECT, "run-1"));

            assertThat(result).isEqualTo("[visualize:workflow_run:" + CORRECT + ":run-1]");
        }

        @Test
        @DisplayName("workflow_run: corrects a mistyped runId against the authoritative runId")
        void workflowRun_runIdCorrected() {
            String content = "[visualize:workflow_run:" + CORRECT + ":run-WRONG]";

            String result = VisualizeMarkerReconciler.reconcile(content, workflowRunToolCalls(CORRECT, "run-1"));

            assertThat(result).isEqualTo("[visualize:workflow_run:" + CORRECT + ":run-1]");
        }

        @Test
        @DisplayName("application: id and runId both corrected from the authoritative reference")
        void applicationMarker_idAndRunIdCorrected() {
            String toolCalls = "[{\"toolName\":\"application\",\"id\":\"t1\","
                    + "\"visualization\":{\"type\":\"application\",\"id\":\"" + CORRECT + "\",\"runId\":\"run-9\"}}]";
            String content = "[visualize:application:" + HALLUCINATED + ":run-BAD]";

            String result = VisualizeMarkerReconciler.reconcile(content, toolCalls);

            assertThat(result).isEqualTo("[visualize:application:" + CORRECT + ":run-9]");
        }
    }

    @Nested
    @DisplayName("leaves the marker untouched when it cannot prove a mistake")
    class LeavesUntouched {

        @Test
        @DisplayName("already-correct id is not rewritten and content is returned unchanged")
        void correctId_unchanged() {
            String content = "done [visualize:workflow:" + CORRECT + "]";

            String result = VisualizeMarkerReconciler.reconcile(content, workflowToolCalls(CORRECT));

            assertThat(result).isEqualTo(content);
        }

        @Test
        @DisplayName("a title is never mistaken for a runId: correct id + title, no runId in metadata → unchanged")
        void titleNeverTreatedAsRunId() {
            String content = "[visualize:workflow:" + CORRECT + ":My Title]";

            String result = VisualizeMarkerReconciler.reconcile(content, workflowToolCalls(CORRECT));

            assertThat(result).isEqualTo(content);
        }

        @Test
        @DisplayName("a 3-field marker is never grown to a 4-field one even when metadata has a runId")
        void noRunIdGrowthOnThreeFieldMarker() {
            String toolCalls = "[{\"toolName\":\"application\",\"id\":\"t1\","
                    + "\"visualization\":{\"type\":\"application\",\"id\":\"" + CORRECT + "\",\"runId\":\"run-9\"}}]";
            String content = "[visualize:application:" + HALLUCINATED + "]"; // 3-field, wrong id

            String result = VisualizeMarkerReconciler.reconcile(content, toolCalls);

            // id corrected, but NOT expanded with :run-9 (would flip static → live-run card)
            assertThat(result).isEqualTo("[visualize:application:" + CORRECT + "]");
        }

        @Test
        @DisplayName("over-reach guard: a single ref + two distinct same-type markers → neither is repointed")
        void multipleDistinctMarkerIds_singleRef_notTouched() {
            // Turn acted on ONE workflow (CORRECT); the reply ALSO embeds a card for a
            // DIFFERENT, real workflow B the agent merely mentioned. The single ref must
            // not be allowed to repoint either marker - including the hallucinated one.
            String otherRealId = "11111111-2222-3333-4444-555555555555";
            String content = "[visualize:workflow:" + HALLUCINATED + "] vs [visualize:workflow:" + otherRealId + "]";

            String result = VisualizeMarkerReconciler.reconcile(content, workflowToolCalls(CORRECT));

            assertThat(result).isEqualTo(content);
        }

        @Test
        @DisplayName("ambiguous: two distinct authoritative refs of the same type → no correction")
        void multipleAuthoritativeSameType_notTouched() {
            String toolCalls = "[{\"toolName\":\"workflow\",\"id\":\"t1\","
                    + "\"visualization\":{\"type\":\"workflow\",\"id\":\"" + CORRECT + "\"}},"
                    + "{\"toolName\":\"workflow\",\"id\":\"t2\","
                    + "\"visualization\":{\"type\":\"workflow\",\"id\":\"11111111-2222-3333-4444-555555555555\"}}]";
            String content = "[visualize:workflow:" + HALLUCINATED + "]";

            String result = VisualizeMarkerReconciler.reconcile(content, toolCalls);

            assertThat(result).isEqualTo(content);
        }

        @Test
        @DisplayName("strict type match: a workflow_run marker is NOT corrected from a (plain) workflow visualization")
        void typeIsStrict_workflowRunVsWorkflow() {
            String content = "[visualize:workflow_run:" + HALLUCINATED + ":run-1]";

            String result = VisualizeMarkerReconciler.reconcile(content, workflowToolCalls(CORRECT));

            assertThat(result).isEqualTo(content);
        }

        @Test
        @DisplayName("no authoritative viz of that type this turn (marker references a prior turn) → untouched")
        void noAuthoritativeOfType_untouched() {
            String toolCalls = "[{\"toolName\":\"table\",\"id\":\"t1\","
                    + "\"visualization\":{\"type\":\"table\",\"id\":\"42\"}}]";
            String content = "[visualize:workflow:" + HALLUCINATED + "]";

            String result = VisualizeMarkerReconciler.reconcile(content, toolCalls);

            assertThat(result).isEqualTo(content);
        }

        @Test
        @DisplayName("tool_calls has no visualization objects at all → untouched")
        void noVisualizationEntries_untouched() {
            String toolCalls = "[{\"toolName\":\"workflow\",\"id\":\"t1\"},{\"toolName\":\"_meta\"}]";
            String content = "[visualize:workflow:" + HALLUCINATED + "]";

            String result = VisualizeMarkerReconciler.reconcile(content, toolCalls);

            assertThat(result).isEqualTo(content);
        }
    }

    @Nested
    @DisplayName("safe on degenerate input - never throws, never drops content")
    class Safe {

        @Test
        @DisplayName("null content → null")
        void nullContent() {
            assertThat(VisualizeMarkerReconciler.reconcile(null, workflowToolCalls(CORRECT))).isNull();
        }

        @Test
        @DisplayName("content without any marker → returned unchanged")
        void noMarker() {
            String content = "Just a normal reply with no card.";
            assertThat(VisualizeMarkerReconciler.reconcile(content, workflowToolCalls(CORRECT)))
                    .isEqualTo(content);
        }

        @Test
        @DisplayName("null tool_calls → content returned unchanged")
        void nullToolCalls() {
            String content = "[visualize:workflow:" + HALLUCINATED + "]";
            assertThat(VisualizeMarkerReconciler.reconcile(content, null)).isEqualTo(content);
        }

        @Test
        @DisplayName("blank tool_calls → content returned unchanged")
        void blankToolCalls() {
            String content = "[visualize:workflow:" + HALLUCINATED + "]";
            assertThat(VisualizeMarkerReconciler.reconcile(content, "   ")).isEqualTo(content);
        }

        @Test
        @DisplayName("malformed tool_calls JSON → content returned unchanged (non-fatal)")
        void malformedJson() {
            String content = "[visualize:workflow:" + HALLUCINATED + "]";
            assertThat(VisualizeMarkerReconciler.reconcile(content, "{not valid json")).isEqualTo(content);
        }

        @Test
        @DisplayName("tool_calls that is a JSON object, not an array → untouched")
        void jsonObjectNotArray() {
            String content = "[visualize:workflow:" + HALLUCINATED + "]";
            assertThat(VisualizeMarkerReconciler.reconcile(content, "{\"foo\":\"bar\"}")).isEqualTo(content);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
