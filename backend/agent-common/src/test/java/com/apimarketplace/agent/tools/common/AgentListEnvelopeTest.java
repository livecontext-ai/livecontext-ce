package com.apimarketplace.agent.tools.common;

import com.apimarketplace.agent.tools.common.AgentListEnvelope.Bounds;
import com.apimarketplace.agent.tools.common.AgentListEnvelope.Caps;
import com.apimarketplace.agent.tools.common.AgentListEnvelope.InvalidParamsException;
import com.apimarketplace.agent.tools.common.AgentListEnvelope.Spec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AgentListEnvelope}.
 *
 * <p>The plan v0.3 audit (composite 9.33/10, 3 Opus auditors) gated these tests on:
 * property-based bound clamping, hint precedence, hard-refuse trigger/skip, dual-emit
 * for legacy keys, snapshot of the JSON envelope, equivalence between the in-memory
 * and DB-projection paths.
 */
@DisplayName("AgentListEnvelope")
class AgentListEnvelopeTest {

    private static Spec specWith(Caps caps) {
        return Spec.of(caps, "items", "items", "items");
    }

    private static List<String> seq(int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add("item-" + i);
        return out;
    }

    // ==================== records: invariants ====================

    @Nested
    @DisplayName("Bounds invariants")
    class BoundsInvariants {
        @Test @DisplayName("rejects limit < 1")
        void rejectsLimit() {
            assertThatThrownBy(() -> new Bounds(0, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Bounds(-5, 0)).isInstanceOf(IllegalArgumentException.class);
        }
        @Test @DisplayName("rejects offset < 0")
        void rejectsOffset() {
            assertThatThrownBy(() -> new Bounds(10, -1)).isInstanceOf(IllegalArgumentException.class);
        }
        @Test @DisplayName("accepts positive limit/offset")
        void accepts() {
            assertThat(new Bounds(10, 0).limit()).isEqualTo(10);
            assertThat(new Bounds(1, 999).offset()).isEqualTo(999);
        }
    }

    @Nested
    @DisplayName("Caps invariants")
    class CapsInvariants {
        @Test @DisplayName("rejects defaultLimit > maxLimit")
        void defaultExceedsMax() {
            assertThatThrownBy(() -> new Caps(100, 25, 50)).isInstanceOf(IllegalArgumentException.class);
        }
        @Test @DisplayName("rejects maxLimit > 1000 (sanity ceiling)")
        void maxExceedsCeiling() {
            assertThatThrownBy(() -> new Caps(25, 1001, 50)).isInstanceOf(IllegalArgumentException.class);
        }
        @Test @DisplayName("rejects non-positive defaultLimit / hintThreshold")
        void rejectsZeroes() {
            assertThatThrownBy(() -> new Caps(0, 25, 50)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Caps(10, 25, 0)).isInstanceOf(IllegalArgumentException.class);
        }
        @Test @DisplayName("three named presets are valid")
        void presets() {
            assertThat(Caps.SMALL.defaultLimit()).isEqualTo(10);
            assertThat(Caps.STANDARD.maxLimit()).isEqualTo(50);
            assertThat(Caps.LARGE.hintThreshold()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Spec invariants")
    class SpecInvariants {
        @Test @DisplayName("hardRefuseOffset must exceed hintThreshold so refine fires before the wall")
        void invariant() {
            assertThatThrownBy(() -> new Spec(Caps.STANDARD, "k", "k", "k",
                    Map.of(), 50, Set.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Spec(Caps.STANDARD, "k", "k", "k",
                    Map.of(), 100, Set.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        @Test @DisplayName("of() picks max(200, hintThreshold*4) for hardRefuse")
        void ofDefaults() {
            assertThat(Spec.of(Caps.SMALL, "k", "k", "k").hardRefuseOffset()).isEqualTo(200);
            assertThat(Spec.of(Caps.LARGE, "k", "k", "k").hardRefuseOffset()).isEqualTo(800);
        }
        @Test @DisplayName("rejects null/blank kind/itemsKey/label")
        void rejectsBlanks() {
            assertThatThrownBy(() -> Spec.of(Caps.STANDARD, "", "k", "k")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Spec.of(Caps.STANDARD, "k", null, "k")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================== readBounds ====================

    @Nested
    @DisplayName("readBounds - clamp + defaults + hard refuse")
    class ReadBoundsTests {
        Spec spec = specWith(Caps.STANDARD);  // default=25, max=50, hintThreshold=100, hardRefuse=400

        @Test @DisplayName("missing params → defaults from caps")
        void defaults() {
            Bounds b = AgentListEnvelope.readBounds(Map.of(), spec, Set.of());
            assertThat(b.limit()).isEqualTo(25);
            assertThat(b.offset()).isEqualTo(0);
        }

        @Test @DisplayName("limit above max is clamped silently")
        void clampLimit() {
            Bounds b = AgentListEnvelope.readBounds(Map.of("limit", 999), spec, Set.of());
            assertThat(b.limit()).isEqualTo(50);
        }

        @Test @DisplayName("limit below 1 is clamped to 1")
        void clampLowLimit() {
            assertThat(AgentListEnvelope.readBounds(Map.of("limit", 0), spec, Set.of()).limit()).isEqualTo(1);
            assertThat(AgentListEnvelope.readBounds(Map.of("limit", -5), spec, Set.of()).limit()).isEqualTo(1);
        }

        @Test @DisplayName("negative offset is clamped to 0")
        void clampOffset() {
            Bounds b = AgentListEnvelope.readBounds(Map.of("offset", -10), spec, Set.of());
            assertThat(b.offset()).isEqualTo(0);
        }

        @Test @DisplayName("non-numeric limit/offset silently falls back to defaults")
        void nonNumeric() {
            Bounds b = AgentListEnvelope.readBounds(Map.of("limit", "abc", "offset", "xyz"), spec, Set.of());
            assertThat(b.limit()).isEqualTo(25);
            assertThat(b.offset()).isEqualTo(0);
        }

        @Test @DisplayName("HARD REFUSE - offset past hardRefuseOffset without filter throws INVALID_PARAMS")
        void hardRefuseWithoutFilter() {
            assertThatThrownBy(() ->
                    AgentListEnvelope.readBounds(Map.of("offset", 500), spec, Set.of())
            ).isInstanceOfSatisfying(InvalidParamsException.class, e -> {
                assertThat(e.code).isEqualTo("PAGINATION_LIMIT_WITHOUT_FILTER");
                assertThat(e.getMessage()).contains("offset=400");
            });
        }

        @Test @DisplayName("HARD REFUSE skipped when an active filter is present")
        void hardRefuseSkippedWithFilter() {
            Bounds b = AgentListEnvelope.readBounds(
                    Map.of("offset", 500), spec, Set.of("query"));
            assertThat(b.offset()).isEqualTo(500);
        }

        @Test @DisplayName("MAX_VALUE limit clamps cleanly (no overflow)")
        void maxValue() {
            Bounds b = AgentListEnvelope.readBounds(Map.of("limit", Integer.MAX_VALUE), spec, Set.of());
            assertThat(b.limit()).isEqualTo(50);
        }
    }

    // ==================== hint emission (precedence) ====================

    @Nested
    @DisplayName("hint emission per action - precedence top→bottom")
    class HintEmission {

        @Test @DisplayName("refine wins at offset=0 when total>hintThreshold and hasMore - even with hasMore (next_page does NOT win)")
        @SuppressWarnings("unchecked")
        void refineBeatsNextPageAtOffsetZero() {
            // total=200 > hintThreshold=100, offset=0, hasMore=true (200>25)
            Map<String, Object> env = AgentListEnvelope.paginateProjection(
                    seq(25), new Bounds(25, 0), 200, specWith(Caps.STANDARD));
            Map<String, Object> hint = (Map<String, Object>) env.get("hint");
            assertThat(hint.get("action")).isEqualTo("refine");
            assertThat(hint.get("reason")).isEqualTo("large_result_set");
            assertThat((List<String>) hint.get("suggestedFilters"))
                    .containsExactlyInAnyOrder("query", "category");
        }

        @Test @DisplayName("next_page fires from page 2 onwards even when total>hintThreshold")
        @SuppressWarnings("unchecked")
        void nextPageFromPage2() {
            Map<String, Object> env = AgentListEnvelope.paginateProjection(
                    seq(25), new Bounds(25, 25), 200, specWith(Caps.STANDARD));
            Map<String, Object> hint = (Map<String, Object>) env.get("hint");
            assertThat(hint.get("action")).isEqualTo("next_page");
            assertThat(hint.get("nextOffset")).isEqualTo(50);
        }

        @Test @DisplayName("reset_offset fires on overshoot (offset>0 && count==0 && total>0)")
        @SuppressWarnings("unchecked")
        void resetOffset() {
            Map<String, Object> env = AgentListEnvelope.paginateProjection(
                    List.of(), new Bounds(25, 50), 30, specWith(Caps.STANDARD));
            Map<String, Object> hint = (Map<String, Object>) env.get("hint");
            assertThat(hint.get("action")).isEqualTo("reset_offset");
            assertThat(hint.get("suggestedOffset")).isEqualTo(0);
        }

        @Test @DisplayName("broaden fires on empty result at offset 0")
        @SuppressWarnings("unchecked")
        void broaden() {
            Map<String, Object> env = AgentListEnvelope.paginateProjection(
                    List.of(), new Bounds(25, 0), 0, specWith(Caps.STANDARD));
            Map<String, Object> hint = (Map<String, Object>) env.get("hint");
            assertThat(hint.get("action")).isEqualTo("broaden");
            assertThat(hint.get("reason")).isEqualTo("no_results");
        }

        @Test @DisplayName("no hint when total fits in one page (small, complete result)")
        void noHintForCompleteSmallResult() {
            Map<String, Object> env = AgentListEnvelope.paginateProjection(
                    seq(5), new Bounds(25, 0), 5, specWith(Caps.STANDARD));
            assertThat(env).doesNotContainKey("hint");
        }

        @Test @DisplayName("no hint when total <= hintThreshold at offset 0 with hasMore")
        void noRefineBelowThreshold() {
            // total=50 <= hintThreshold=100 → no refine. hasMore=true → next_page fires.
            @SuppressWarnings("unchecked")
            Map<String, Object> hint = (Map<String, Object>) AgentListEnvelope.paginateProjection(
                    seq(25), new Bounds(25, 0), 50, specWith(Caps.STANDARD)).get("hint");
            assertThat(hint.get("action")).isEqualTo("next_page");
        }

        @Test @DisplayName("refine SUPPRESSED when spec has no suggestedFilters - falls through to next_page")
        @SuppressWarnings("unchecked")
        void refineSuppressedWhenNoFilters() {
            // Resource where refinement is not meaningful (e.g. workflow.runs scoped by
            // workflow_id). Without suggestedFilters the agent would get a refine hint
            // it cannot act on - fall through to next_page instead.
            Spec spec = Spec.of(Caps.STANDARD, "runs", "runs", "runs")
                            .withSuggestedFilters(List.of());
            Map<String, Object> env = AgentListEnvelope.paginateProjection(
                    seq(25), new Bounds(25, 0), 1000, spec);
            Map<String, Object> hint = (Map<String, Object>) env.get("hint");
            assertThat(hint.get("action")).isEqualTo("next_page");
        }

        @Test @DisplayName("refine uses Spec.suggestedFilters verbatim - per-resource customization")
        @SuppressWarnings("unchecked")
        void refineUsesSpecFilters() {
            Spec spec = Spec.of(Caps.STANDARD, "items", "items", "items")
                            .withSuggestedFilters(List.of("status", "date_range"));
            Map<String, Object> env = AgentListEnvelope.paginateProjection(
                    seq(25), new Bounds(25, 0), 1000, spec);
            Map<String, Object> hint = (Map<String, Object>) env.get("hint");
            assertThat(hint.get("action")).isEqualTo("refine");
            assertThat((List<String>) hint.get("suggestedFilters"))
                    .containsExactly("status", "date_range");
        }
    }

    // ==================== envelope shape (snapshot) ====================

    @Nested
    @DisplayName("envelope shape - keys + types + observability")
    class EnvelopeShape {

        @Test @DisplayName("canonical envelope keys are emitted in the canonical order")
        @SuppressWarnings("unchecked")
        void canonicalShape() {
            Map<String, Object> env = AgentListEnvelope.paginateProjection(
                    seq(10), new Bounds(10, 20), 137,
                    Spec.of(Caps.STANDARD, "workflows", "workflows", "workflows"));
            // Iteration order = insertion order (LinkedHashMap)
            assertThat(env.keySet()).containsExactly(
                    "status", "kind", "workflows", "count", "total",
                    "offset", "limit", "hasMore", "hint");
            assertThat(env.get("status")).isEqualTo("OK");
            assertThat(env.get("kind")).isEqualTo("workflows");
            assertThat(env.get("count")).isEqualTo(10);
            assertThat(env.get("total")).isEqualTo(137L);
            assertThat(env.get("hasMore")).isEqualTo(true);
            assertThat((List<?>) env.get("workflows")).hasSize(10);
        }

        @Test @DisplayName("NEXT_OPTIONS emitted only when non-empty in spec")
        void nextOptionsEmittedWhenPresent() {
            Spec spec = Spec.of(Caps.STANDARD, "applications", "applications", "applications")
                    .withNext(Map.of("execute", "application(action='execute', application_id='<id>')"));
            Map<String, Object> env = AgentListEnvelope.paginateProjection(
                    seq(5), new Bounds(10, 0), 5, spec);
            assertThat(env).containsKey("NEXT_OPTIONS");
        }
    }

    // ==================== legacy dual-emit ====================

    @Nested
    @DisplayName("withLegacyKeys - application.search transition dual-emit")
    class LegacyKeys {

        @Test @DisplayName("emits totalItems and totalPages on opt-in")
        void emitsLegacy() {
            Spec spec = Spec.of(Caps.SMALL, "applications", "applications", "applications")
                    .withLegacyKeys(Set.of("totalItems", "totalPages"));
            Map<String, Object> env = AgentListEnvelope.paginateProjection(
                    seq(10), new Bounds(10, 0), 25, spec);
            assertThat(env.get("totalItems")).isEqualTo(25L);
            // 25/10 → 3 pages
            assertThat(env.get("totalPages")).isEqualTo(3);
        }

        @Test @DisplayName("totalPages uses effectiveLimit (post-clamp), not requested limit")
        void totalPagesPostClamp() {
            // If caller passed limit=999 it was clamped to 25 (SMALL.maxLimit). The
            // effectiveLimit used in totalPages math must be 25, not 999.
            Spec spec = Spec.of(Caps.SMALL, "applications", "applications", "applications")
                    .withLegacyKeys(Set.of("totalPages"));
            // readBounds would have clamped to 25; we simulate that here.
            Bounds clamped = AgentListEnvelope.readBounds(Map.of("limit", 999), spec, Set.of());
            assertThat(clamped.limit()).isEqualTo(25);
            Map<String, Object> env = AgentListEnvelope.paginateProjection(
                    seq(25), clamped, 100, spec);
            assertThat(env.get("totalPages")).isEqualTo(4);  // 100/25
        }

        @Test @DisplayName("absence of legacyKeys leaves envelope clean (no totalItems/totalPages)")
        void absentByDefault() {
            Map<String, Object> env = AgentListEnvelope.paginateProjection(
                    seq(10), new Bounds(10, 0), 25, specWith(Caps.SMALL));
            assertThat(env).doesNotContainKeys("totalItems", "totalPages");
        }
    }

    // ==================== in-memory ↔ projection equivalence ====================

    @Nested
    @DisplayName("paginateInMemory ↔ paginateProjection equivalence")
    class Equivalence {

        @Test @DisplayName("in-memory page == projection page for the same inputs")
        @SuppressWarnings("unchecked")
        void equivalence() {
            Spec spec = Spec.of(Caps.STANDARD, "workflows", "workflows", "workflows");
            List<String> all = seq(137);
            Bounds b = new Bounds(25, 50);

            Map<String, Object> inMem = AgentListEnvelope.paginateInMemory(all, b, spec);
            Map<String, Object> proj  = AgentListEnvelope.paginateProjection(
                    all.subList(50, 75), b, 137, spec);

            // Same canonical envelope, byte-identical except for the underlying List instance.
            assertThat(inMem.keySet()).isEqualTo(proj.keySet());
            assertThat(inMem.get("total")).isEqualTo(proj.get("total"));
            assertThat(inMem.get("count")).isEqualTo(proj.get("count"));
            assertThat(inMem.get("hasMore")).isEqualTo(proj.get("hasMore"));
            assertThat((List<String>) inMem.get("workflows"))
                    .containsExactlyElementsOf((List<String>) proj.get("workflows"));
        }
    }

    // ==================== projection caller-contract guard ====================

    @Nested
    @DisplayName("paginateProjection - caller-contract guards")
    class ProjectionGuards {

        @Test @DisplayName("rejects slice larger than bounds.limit (caller over-sliced)")
        void overSlice() {
            Spec spec = specWith(Caps.STANDARD);
            assertThatThrownBy(() ->
                    AgentListEnvelope.paginateProjection(seq(30), new Bounds(25, 0), 100, spec))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("over-sliced");
        }

        @Test @DisplayName("rejects negative total")
        void negativeTotal() {
            Spec spec = specWith(Caps.STANDARD);
            assertThatThrownBy(() ->
                    AgentListEnvelope.paginateProjection(List.of(), new Bounds(25, 0), -1, spec))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test @DisplayName("null slice is treated as empty (defensive - no NPE)")
        void nullSlice() {
            Spec spec = specWith(Caps.STANDARD);
            Map<String, Object> env = AgentListEnvelope.paginateProjection(null, new Bounds(25, 0), 0, spec);
            assertThat(env.get("count")).isEqualTo(0);
        }
    }

    // ==================== FlyFinder regression walk-through ====================

    @Nested
    @DisplayName("FlyFinder regression - agent on 1000-app marketplace without filter")
    class FlyFinderRegression {

        Spec searchSpec = Spec.of(Caps.SMALL, "applications", "applications", "applications");

        @Test @DisplayName("Step 1: agent calls search with no filter → refine hint at offset=0")
        @SuppressWarnings("unchecked")
        void step1RefineHintFires() {
            // SMALL: hintThreshold=50, hardRefuse=200
            // Server returns 10 of 1000, no filter
            Map<String, Object> env = AgentListEnvelope.paginateProjection(
                    seq(10), new Bounds(10, 0), 1000, searchSpec);
            Map<String, Object> hint = (Map<String, Object>) env.get("hint");
            assertThat(hint.get("action")).isEqualTo("refine");
            // Agent reading this should STOP paginating and apply a filter.
        }

        @Test @DisplayName("Step 2: agent ignores hint, paginates within bounds - next_page hint each call")
        @SuppressWarnings("unchecked")
        void step2NextPageHints() {
            // offset=50 (under hardRefuse=200), no filter - still allowed since past first page
            Bounds b = AgentListEnvelope.readBounds(
                    Map.of("offset", 50), searchSpec, Set.of());
            assertThat(b.offset()).isEqualTo(50);
            Map<String, Object> env = AgentListEnvelope.paginateProjection(
                    seq(10), b, 1000, searchSpec);
            Map<String, Object> hint = (Map<String, Object>) env.get("hint");
            assertThat(hint.get("action")).isEqualTo("next_page");
        }

        @Test @DisplayName("Step 3: agent paginates past hardRefuseOffset=200 without filter → HARD REFUSE")
        void step3HardRefuseStopsDeepWalk() {
            assertThatThrownBy(() ->
                    AgentListEnvelope.readBounds(
                            Map.of("offset", 250), searchSpec, Set.of()))
                    .isInstanceOfSatisfying(InvalidParamsException.class, e -> {
                        assertThat(e.code).isEqualTo("PAGINATION_LIMIT_WITHOUT_FILTER");
                    });
        }

        @Test @DisplayName("Same offset succeeds once a filter is applied - agent has a recovery path")
        void recoveryWithFilter() {
            Bounds b = AgentListEnvelope.readBounds(
                    Map.of("offset", 250), searchSpec, Set.of("query"));
            assertThat(b.offset()).isEqualTo(250);
        }
    }
}
