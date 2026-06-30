package com.apimarketplace.conversation.service.ai.callback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("ColdSummaryContextRenderer")
class ColdSummaryContextRendererTest {

    @Nested
    @DisplayName("empty / null / no-op inputs")
    class EmptyInputs {

        @Test
        @DisplayName("null summary renders empty string")
        void nullSummary() {
            assertThat(ColdSummaryContextRenderer.render(null)).isEmpty();
        }

        @Test
        @DisplayName("empty map renders empty string")
        void emptyMap() {
            assertThat(ColdSummaryContextRenderer.render(Map.of())).isEmpty();
        }

        @Test
        @DisplayName("summary with only metadata (no content sections) renders empty string")
        void onlyMetadata() {
            Map<String, Object> cold = new LinkedHashMap<>();
            cold.put("generated_at", "2026-06-09T12:00:00Z");
            cold.put("model", "deepseek-chat");
            cold.put("cold_tokens_at_generation", 1200);
            cold.put("turns_covered", List.of(1, 2, 3));
            // No user_intents / decisions / ids_resolved / errors_resolved / helped_actions.
            assertThat(ColdSummaryContextRenderer.render(cold)).isEmpty();
        }

        @Test
        @DisplayName("all content sections empty renders empty string")
        void allSectionsEmpty() {
            Map<String, Object> cold = new LinkedHashMap<>();
            cold.put("user_intents", List.of());
            cold.put("decisions", List.of());
            cold.put("ids_resolved", Map.of());
            cold.put("errors_resolved", List.of());
            cold.put("helped_actions", List.of());
            assertThat(ColdSummaryContextRenderer.render(cold)).isEmpty();
        }

        @Test
        @DisplayName("blank / whitespace-only entries are skipped and render empty")
        void blankEntriesSkipped() {
            Map<String, Object> cold = new LinkedHashMap<>();
            cold.put("user_intents", List.of("", "   ", "\n"));
            assertThat(ColdSummaryContextRenderer.render(cold)).isEmpty();
        }
    }

    @Nested
    @DisplayName("rendering content")
    class Rendering {

        @Test
        @DisplayName("renders the durable-context header when any content is present")
        void rendersHeader() {
            Map<String, Object> cold = Map.of("user_intents", List.of("build an Airbnb search app"));
            String out = ColdSummaryContextRenderer.render(cold);
            assertThat(out).contains("[DURABLE CONTEXT - distilled summary of earlier turns]");
            assertThat(out).contains("build an Airbnb search app");
        }

        @Test
        @DisplayName("renders user intents under a labelled section")
        void rendersUserIntents() {
            Map<String, Object> cold = Map.of("user_intents", List.of("intent A", "intent B"));
            String out = ColdSummaryContextRenderer.render(cold);
            assertThat(out).contains("User intents:");
            assertThat(out).contains("- intent A");
            assertThat(out).contains("- intent B");
        }

        @Test
        @DisplayName("renders decisions with turn numbers")
        void rendersDecisionsWithTurn() {
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("turn", 3);
            decision.put("decision", "chose SerpAPI over scraping");
            Map<String, Object> cold = Map.of("decisions", List.of(decision));

            String out = ColdSummaryContextRenderer.render(cold);
            assertThat(out).contains("Key decisions:");
            assertThat(out).contains("(turn 3) chose SerpAPI over scraping");
        }

        @Test
        @DisplayName("renders a decision without a turn number gracefully")
        void rendersDecisionWithoutTurn() {
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("decision", "no turn here");
            Map<String, Object> cold = Map.of("decisions", List.of(decision));

            String out = ColdSummaryContextRenderer.render(cold);
            assertThat(out).contains("- no turn here");
            assertThat(out).doesNotContain("(turn");
        }

        @Test
        @DisplayName("renders resolved identifiers as name -> value")
        void rendersIds() {
            Map<String, Object> ids = new LinkedHashMap<>();
            ids.put("workflow_id", "wf-123");
            ids.put("interface_id", "if-456");
            Map<String, Object> cold = Map.of("ids_resolved", ids);

            String out = ColdSummaryContextRenderer.render(cold);
            assertThat(out).contains("Resolved identifiers");
            assertThat(out).contains("workflow_id: wf-123");
            assertThat(out).contains("interface_id: if-456");
        }

        @Test
        @DisplayName("renders resolved errors as error -> resolution")
        void rendersErrors() {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "401 from Gmail");
            err.put("resolution", "reconnected credential");
            Map<String, Object> cold = Map.of("errors_resolved", List.of(err));

            String out = ColdSummaryContextRenderer.render(cold);
            assertThat(out).contains("Resolved errors:");
            assertThat(out).contains("401 from Gmail → reconnected credential");
        }

        @Test
        @DisplayName("renders helped actions section")
        void rendersHelpedActions() {
            Map<String, Object> cold = Map.of("helped_actions", List.of("created table users", "wired interface"));
            String out = ColdSummaryContextRenderer.render(cold);
            assertThat(out).contains("Actions already completed:");
            assertThat(out).contains("- created table users");
            assertThat(out).contains("- wired interface");
        }

        @Test
        @DisplayName("renders a turn-coverage range in the header")
        void rendersCoverageRange() {
            Map<String, Object> cold = new LinkedHashMap<>();
            cold.put("user_intents", List.of("x"));
            cold.put("turns_covered", List.of(1, 2, 5, 14));
            String out = ColdSummaryContextRenderer.render(cold);
            assertThat(out).contains("(covers turns 1-14)");
        }

        @Test
        @DisplayName("renders a single-turn coverage label")
        void rendersSingleTurnCoverage() {
            Map<String, Object> cold = new LinkedHashMap<>();
            cold.put("user_intents", List.of("x"));
            cold.put("turns_covered", List.of(7));
            String out = ColdSummaryContextRenderer.render(cold);
            assertThat(out).contains("(covers turn 7)");
        }
    }

    @Nested
    @DisplayName("robustness - never throws, defends against malformed rows")
    class Robustness {

        @Test
        @DisplayName("malformed types in every field never throw and still render valid entries")
        void malformedTypesNeverThrow() {
            Map<String, Object> cold = new LinkedHashMap<>();
            // user_intents should be a list - give it a string.
            cold.put("user_intents", "not a list");
            // decisions should be a list of maps - give mixed garbage + one valid.
            List<Object> decisions = new ArrayList<>();
            decisions.add("a bare string");
            decisions.add(42);
            Map<String, Object> goodDecision = new LinkedHashMap<>();
            goodDecision.put("turn", "not-a-number");
            goodDecision.put("decision", "still valid text");
            decisions.add(goodDecision);
            cold.put("decisions", decisions);
            // ids_resolved should be a map - give a list.
            cold.put("ids_resolved", List.of("nope"));
            // errors_resolved entries with null fields.
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", null);
            err.put("resolution", "partial");
            cold.put("errors_resolved", List.of(err));
            cold.put("turns_covered", "garbage");

            String out = ColdSummaryContextRenderer.render(cold);
            // The one salvageable decision survives; the garbage is dropped.
            assertThat(out).contains("still valid text");
            assertThat(out).contains("(unknown error) → partial");
            // turn was non-numeric -> no "(turn ...)" prefix on that decision.
            assertThat(out).doesNotContain("(turn not-a-number)");
        }

        @Test
        @DisplayName("a deeply malformed map never throws")
        void deeplyMalformedNeverThrows() {
            Map<String, Object> cold = new LinkedHashMap<>();
            cold.put("decisions", 12345);
            cold.put("ids_resolved", "string-not-map");
            cold.put("errors_resolved", Map.of("not", "a-list"));
            cold.put("user_intents", Map.of());
            cold.put("helped_actions", 3.14);
            assertThatCode(() -> ColdSummaryContextRenderer.render(cold)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("long entries are length-capped and newlines collapsed to one line")
        void longEntryCapped() {
            String huge = "x".repeat(5000);
            Map<String, Object> cold = Map.of("user_intents", List.of("line1\nline2\nline3 " + huge));
            String out = ColdSummaryContextRenderer.render(cold);
            // Multi-line entry was collapsed into a single bullet line.
            assertThat(out).contains("line1 line2 line3");
            // Ellipsis marks the cap.
            assertThat(out).contains("…");
        }

        @Test
        @DisplayName("total output is bounded by the hard ceiling and marks truncation")
        void totalOutputBounded() {
            // ids_resolved has a high per-section cap (40), each value long, so the
            // GLOBAL ceiling (not the per-section cap) is what bounds the output.
            Map<String, Object> ids = new LinkedHashMap<>();
            for (int i = 0; i < 40; i++) {
                ids.put("key_" + i, "v".repeat(250));
            }
            Map<String, Object> cold = Map.of("ids_resolved", ids);
            String out = ColdSummaryContextRenderer.render(cold);
            // Capped well under a runaway size (header + 6000 + truncation note).
            assertThat(out.length()).isLessThan(6500);
            // The truncation branch must announce itself so the model knows content was dropped.
            assertThat(out).endsWith("… (summary truncated)");
        }

        @Test
        @DisplayName("per-section entry cap limits ids_resolved to MAX_IDS (40) entries")
        void perSectionIdCapApplied() {
            Map<String, Object> ids = new LinkedHashMap<>();
            for (int i = 0; i < 100; i++) {
                ids.put("k" + i, "val" + i);
            }
            Map<String, Object> cold = Map.of("ids_resolved", ids);
            String out = ColdSummaryContextRenderer.render(cold);
            // Exactly 40 identifier bullet lines survive the cap; entries 0..39 kept, 40..99 dropped.
            long idLines = out.lines().filter(l -> l.matches("\\s*- k\\d+: val\\d+")).count();
            assertThat(idLines).isEqualTo(40);
            assertThat(out).contains("- k0: val0");
            assertThat(out).contains("- k39: val39");
            assertThat(out).doesNotContain("- k40: val40");
        }

        @Test
        @DisplayName("per-section entry cap limits decisions to MAX_DECISIONS (20) entries")
        void perSectionDecisionCapApplied() {
            List<Object> decisions = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("turn", i);
                d.put("decision", "decision-" + i);
                decisions.add(d);
            }
            Map<String, Object> cold = Map.of("decisions", decisions);
            String out = ColdSummaryContextRenderer.render(cold);
            assertThat(out).contains("decision-0");
            assertThat(out).contains("decision-19");
            assertThat(out).doesNotContain("decision-20");
        }

        @Test
        @DisplayName("an error entry with both error and resolution null is skipped entirely")
        void errorEntryFullyNullSkipped() {
            Map<String, Object> bothNull = new LinkedHashMap<>();
            bothNull.put("error", null);
            bothNull.put("resolution", null);
            Map<String, Object> valid = new LinkedHashMap<>();
            valid.put("error", "real error");
            valid.put("resolution", "real fix");
            Map<String, Object> cold = Map.of("errors_resolved", List.of(bothNull, valid));

            String out = ColdSummaryContextRenderer.render(cold);
            // Only the valid entry renders; the all-null entry produces no bullet.
            assertThat(out).contains("real error → real fix");
            long errorLines = out.lines().filter(l -> l.contains("→")).count();
            assertThat(errorLines).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("status-aware header (regression: status-blind recall)")
    class StatusAwareHeader {

        @Test
        @DisplayName("regression - status=stale renders the caution header, NOT the authoritative one")
        void staleStatusRendersCautionHeader() {
            // Pre-fix, a summary the user had explicitly walked back (or whose
            // covered turns no longer existed) was still injected with "trust
            // it … do NOT redo work" framing. The stale status must flip the
            // header to the caveated variant while keeping the content.
            Map<String, Object> cold = new LinkedHashMap<>();
            cold.put("user_intents", List.of("build CRM"));
            cold.put("status", "stale");

            String out = ColdSummaryContextRenderer.render(cold);

            assertThat(out).contains("MAY BE PARTIALLY OUTDATED");
            assertThat(out).contains("recent turns win");
            assertThat(out).doesNotContain("authoritative recollection");
            assertThat(out).doesNotContain("do NOT redo work");
            // Content is preserved - losing recall entirely would be worse.
            assertThat(out).contains("build CRM");
        }

        @Test
        @DisplayName("status=active renders the authoritative header")
        void activeStatusRendersAuthoritativeHeader() {
            Map<String, Object> cold = new LinkedHashMap<>();
            cold.put("user_intents", List.of("build CRM"));
            cold.put("status", "active");

            String out = ColdSummaryContextRenderer.render(cold);

            assertThat(out).contains("authoritative recollection");
            assertThat(out).doesNotContain("MAY BE PARTIALLY OUTDATED");
        }

        @Test
        @DisplayName("absent status (pre-field legacy rows) renders the authoritative header")
        void absentStatusRendersAuthoritativeHeader() {
            Map<String, Object> cold = Map.of("user_intents", List.of("build CRM"));

            String out = ColdSummaryContextRenderer.render(cold);

            assertThat(out).contains("authoritative recollection");
            assertThat(out).doesNotContain("MAY BE PARTIALLY OUTDATED");
        }

        @Test
        @DisplayName("non-string / unknown status values fall back to the authoritative header without throwing")
        void malformedStatusFallsBackSafely() {
            Map<String, Object> withNumber = new LinkedHashMap<>();
            withNumber.put("user_intents", List.of("x"));
            withNumber.put("status", 42);
            Map<String, Object> withUnknown = new LinkedHashMap<>();
            withUnknown.put("user_intents", List.of("x"));
            withUnknown.put("status", "half-baked");

            assertThatCode(() -> ColdSummaryContextRenderer.render(withNumber))
                    .doesNotThrowAnyException();
            assertThat(ColdSummaryContextRenderer.render(withNumber))
                    .contains("authoritative recollection");
            assertThat(ColdSummaryContextRenderer.render(withUnknown))
                    .contains("authoritative recollection");
        }

        @Test
        @DisplayName("stale header still respects the coverage suffix and total-size ceiling")
        void staleHeaderKeepsCoverageAndBounds() {
            Map<String, Object> cold = new LinkedHashMap<>();
            cold.put("user_intents", List.of("intent"));
            cold.put("turns_covered", List.of(1, 2, 3));
            cold.put("status", "stale");

            String out = ColdSummaryContextRenderer.render(cold);

            assertThat(out).contains("(covers turns 1-3)");
            assertThat(out.length()).isLessThanOrEqualTo(6000 + 32);
        }
    }
}
