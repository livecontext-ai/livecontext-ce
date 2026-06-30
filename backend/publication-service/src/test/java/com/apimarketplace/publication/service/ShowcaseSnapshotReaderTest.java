package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Round-trips the JSONB shape produced by ShowcaseSnapshotBuilder back
 * through ShowcaseSnapshotReader to lock down the marketplace contract.
 * If a field name drifts on either side, one of these tests fails - far
 * cheaper than discovering it from the marketplace turning blank in prod.
 */
@DisplayName("ShowcaseSnapshotReader")
class ShowcaseSnapshotReaderTest {

    // Default rewriter passes items through unchanged so existing assertions
    // (which never inspect rewritten URLs) keep their original semantics.
    private final ShowcaseFileRefRewriter passthroughRewriter = passthroughRewriter();
    private final ShowcaseSnapshotReader reader = new ShowcaseSnapshotReader(passthroughRewriter);

    private static ShowcaseFileRefRewriter passthroughRewriter() {
        ShowcaseFileRefRewriter m = mock(ShowcaseFileRefRewriter.class);
        when(m.rewriteItems(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        return m;
    }

    @Nested
    @DisplayName("hasSnapshot")
    class HasSnapshot {
        @Test
        @DisplayName("Returns false when the entity carries no JSONB at all")
        void noJsonb() {
            assertThat(reader.hasSnapshot(new WorkflowPublicationEntity())).isFalse();
        }

        @Test
        @DisplayName("Returns false when the JSONB is an empty map")
        void emptyJsonb() {
            WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
            pub.setShowcaseSnapshot(Map.of());
            assertThat(reader.hasSnapshot(pub)).isFalse();
        }

        @Test
        @DisplayName("Returns true once at least one section is populated")
        void populated() {
            WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
            pub.setShowcaseSnapshot(Map.of("runState", Map.of("runId", "run_42")));
            assertThat(reader.hasSnapshot(pub)).isTrue();
        }
    }

    @Test
    @DisplayName("readRunState returns empty when no snapshot is present")
    void readRunStateMissing() {
        assertThat(reader.readRunState(new WorkflowPublicationEntity())).isEmpty();
    }

    @Test
    @DisplayName("readAggregatedSteps returns the global list when epoch is null")
    void readAggregatedStepsAll() {
        WorkflowPublicationEntity pub = pubWithSnapshot(Map.of(
                "aggregatedSteps", Map.of(
                        "all", List.of(Map.of("stepId", "trigger:start")),
                        "byEpoch", Map.of("0", List.of(Map.of("stepId", "trigger:start")))
                )
        ));
        Optional<List<Map<String, Object>>> result = reader.readAggregatedSteps(pub, null);
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0)).containsEntry("stepId", "trigger:start");
    }

    @Test
    @DisplayName("readAggregatedSteps with explicit epoch picks the byEpoch sub-list")
    void readAggregatedStepsPerEpoch() {
        WorkflowPublicationEntity pub = pubWithSnapshot(Map.of(
                "aggregatedSteps", Map.of(
                        "all", List.of(),
                        "byEpoch", Map.of(
                                "0", List.of(Map.of("stepId", "epoch_0_step")),
                                "1", List.of(Map.of("stepId", "epoch_1_step"))
                        )
                )
        ));
        assertThat(reader.readAggregatedSteps(pub, 1).orElseThrow().get(0))
                .containsEntry("stepId", "epoch_1_step");
    }

    @Test
    @DisplayName("readEpochSignals synthesises empty list when epoch existed but had no signals")
    void readEpochSignalsKnownEpoch() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("runState", Map.of("epochTimestamps", List.of(Map.of("epoch", 0))));
        snap.put("epochSignals", Map.of()); // nothing recorded for epoch 0
        Optional<List<Map<String, Object>>> result = reader.readEpochSignals(pubWithSnapshot(snap), 0);
        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
    }

    @Test
    @DisplayName("readEpochSignals returns empty Optional for epochs the run never reached")
    void readEpochSignalsUnknownEpoch() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("runState", Map.of("epochTimestamps", List.of(Map.of("epoch", 0))));
        snap.put("epochSignals", Map.of());
        // Asking for epoch 99 must NOT silently return an empty list - that
        // would mask deletion / corruption. Caller should 404.
        assertThat(reader.readEpochSignals(pubWithSnapshot(snap), 99)).isEmpty();
    }

    @Test
    @DisplayName("readInterfaceRender slices items[] by page/size and computes pagination")
    void readInterfaceRenderPagination() {
        Map<String, Object> defaultRender = new LinkedHashMap<>();
        defaultRender.put("htmlTemplate", "<h1>{{title}}</h1>");
        defaultRender.put("cssTemplate", ".x{}");
        defaultRender.put("jsTemplate", "");
        defaultRender.put("actionMappings", Map.of());
        List<Map<String, Object>> items = List.of(
                Map.of("epoch", 0, "itemIndex", 0),
                Map.of("epoch", 0, "itemIndex", 1),
                Map.of("epoch", 0, "itemIndex", 2)
        );
        defaultRender.put("items", items);

        Map<String, Object> ifaceRenders = Map.of(
                "iface_a", Map.of("defaultRender", defaultRender, "byEpoch", Map.of())
        );
        WorkflowPublicationEntity pub = pubWithSnapshot(Map.of("interfaceRenders", ifaceRenders));

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface_a", 1, 2, null);
        assertThat(result).isPresent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sliced = (List<Map<String, Object>>) result.get().get("items");
        assertThat(sliced).hasSize(1); // page=1, size=2 → only item index 2 left
        assertThat(sliced.get(0)).containsEntry("itemIndex", 2);
        @SuppressWarnings("unchecked")
        Map<String, Object> pagination = (Map<String, Object>) result.get().get("pagination");
        assertThat(pagination).containsEntry("totalItems", 3).containsEntry("totalPages", 2);
    }

    @Test
    @DisplayName("readInterfaceRender invokes the FileRef rewriter on the sliced items - regression: anonymous marketplace card got <img src='{\"_type\":\"file\"...}'> when the rewriter wasn't wired")
    void readInterfaceRenderInvokesRewriter() {
        ShowcaseFileRefRewriter rewriter = mock(ShowcaseFileRefRewriter.class);
        when(rewriter.rewriteItems(any(), any())).thenAnswer(inv -> {
            return List.of(Map.of("rewritten", true));
        });
        ShowcaseSnapshotReader r = new ShowcaseSnapshotReader(rewriter);

        Map<String, Object> defaultRender = new LinkedHashMap<>();
        defaultRender.put("htmlTemplate", "<img src=\"{{x}}\">");
        defaultRender.put("items", List.of(Map.of("itemIndex", 0)));
        Map<String, Object> ifaceRenders = Map.of(
                "iface_a", Map.of("defaultRender", defaultRender, "byEpoch", Map.of()));
        WorkflowPublicationEntity pub = pubWithSnapshot(Map.of("interfaceRenders", ifaceRenders));

        Optional<Map<String, Object>> result = r.readInterfaceRender(pub, "iface_a", 0, 1, null);
        assertThat(result).isPresent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outItems = (List<Map<String, Object>>) result.get().get("items");
        assertThat(outItems).hasSize(1);
        assertThat(outItems.get(0)).containsEntry("rewritten", true);
    }

    @Test
    @DisplayName("readEpochState aggregates step status counts for the requested epoch")
    void readEpochStateBuildsCounts() {
        Map<String, Object> runState = new LinkedHashMap<>();
        runState.put("epochTimestamps", List.of(Map.of("epoch", 0), Map.of("epoch", 1)));
        runState.put("steps", List.of(
                Map.of("epoch", 0, "status", "COMPLETED"),
                Map.of("epoch", 0, "status", "COMPLETED"),
                Map.of("epoch", 0, "status", "FAILED"),
                Map.of("epoch", 1, "status", "COMPLETED")
        ));
        WorkflowPublicationEntity pub = pubWithSnapshot(Map.of("runState", runState));
        Optional<Map<String, Object>> resultOpt = reader.readEpochState(pub, 0);
        assertThat(resultOpt).isPresent();
        @SuppressWarnings("unchecked")
        Map<String, Integer> nodeCounts = (Map<String, Integer>) resultOpt.get().get("nodeCounts");
        assertThat(nodeCounts).containsEntry("COMPLETED", 2).containsEntry("FAILED", 1);
        assertThat(resultOpt.get()).containsEntry("epoch", 0);
    }

    // ─────────────────────────────────────────────────────────────────
    // V273 - showcase_chosen_epoch regression: reader honors the pin
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("V273: pub.showcaseChosenEpoch is null → reader uses defaultRender (legacy multi-epoch view preserved)")
    void chosenEpochNullFallsBackToDefaultRender() {
        Map<String, Object> defaultRender = renderFor("default-title", 0);
        Map<String, Object> epoch3Render = renderFor("epoch-3-title", 3);

        Map<String, Object> ifaceRenders = Map.of(
                "iface_a", Map.of(
                        "defaultRender", defaultRender,
                        "byEpoch", Map.of("3", epoch3Render)));
        WorkflowPublicationEntity pub = pubWithSnapshot(Map.of("interfaceRenders", ifaceRenders));
        // chosenEpoch left null → legacy publication, reader keeps the default render

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface_a", 0, 10, null);

        assertThat(result).isPresent();
        assertThat(result.get().get("htmlTemplate")).isEqualTo("<h1>default-title</h1>");
    }

    @Test
    @DisplayName("V273: pub.showcaseChosenEpoch set + caller passes no epoch → reader pins to the chosen epoch")
    void chosenEpochSetPinsToThatEpoch() {
        Map<String, Object> defaultRender = renderFor("default-title", 0);
        Map<String, Object> epoch3Render = renderFor("epoch-3-title", 3);

        Map<String, Object> ifaceRenders = Map.of(
                "iface_a", Map.of(
                        "defaultRender", defaultRender,
                        "byEpoch", Map.of("3", epoch3Render)));
        WorkflowPublicationEntity pub = pubWithSnapshot(Map.of("interfaceRenders", ifaceRenders));
        pub.setShowcaseChosenEpoch(3);

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface_a", 0, 10, null);

        assertThat(result).isPresent();
        assertThat(result.get().get("htmlTemplate"))
                .as("Pre-fix the reader returned defaultRender regardless of the pin; this pins "
                    + "the visitor to the publisher-chosen epoch - the whole point of V273.")
                .isEqualTo("<h1>epoch-3-title</h1>");
    }

    @Test
    @DisplayName("V273: caller's explicit epoch wins over the pin (publisher-side QA preview still browses)")
    void explicitEpochOverridesChosen() {
        Map<String, Object> defaultRender = renderFor("default-title", 0);
        Map<String, Object> epoch3Render = renderFor("epoch-3-title", 3);
        Map<String, Object> epoch5Render = renderFor("epoch-5-title", 5);

        Map<String, Object> ifaceRenders = Map.of(
                "iface_a", Map.of(
                        "defaultRender", defaultRender,
                        "byEpoch", Map.of("3", epoch3Render, "5", epoch5Render)));
        WorkflowPublicationEntity pub = pubWithSnapshot(Map.of("interfaceRenders", ifaceRenders));
        pub.setShowcaseChosenEpoch(3);

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface_a", 0, 10, 5);

        assertThat(result).isPresent();
        assertThat(result.get().get("htmlTemplate"))
                .as("When the caller explicitly asks for a specific epoch (publisher QA path), the "
                    + "pin is ignored - visitor-facing reads pass null epoch and get the pinned view.")
                .isEqualTo("<h1>epoch-5-title</h1>");
    }

    @Test
    @DisplayName("V273: pin points to an epoch missing from the snapshot → reader falls back to defaultRender (cards never blank out)")
    void chosenEpochMissingFallsBack() {
        Map<String, Object> defaultRender = renderFor("default-title", 0);

        Map<String, Object> ifaceRenders = Map.of(
                "iface_a", Map.of(
                        "defaultRender", defaultRender,
                        "byEpoch", Map.of("3", renderFor("epoch-3", 3))));
        WorkflowPublicationEntity pub = pubWithSnapshot(Map.of("interfaceRenders", ifaceRenders));
        pub.setShowcaseChosenEpoch(999); // pin out of sync with what's actually captured

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface_a", 0, 10, null);

        assertThat(result).isPresent();
        assertThat(result.get().get("htmlTemplate"))
                .as("Stale pin must not blank out the marketplace card - fall back to defaultRender.")
                .isEqualTo("<h1>default-title</h1>");
    }

    @Test
    @DisplayName("V273 contract: a publication entity stores and returns the chosen epoch verbatim (round-trip)")
    void entityRoundTripsChosenEpoch() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        assertThat(pub.getShowcaseChosenEpoch()).as("default is null - legacy multi-epoch view").isNull();

        pub.setShowcaseChosenEpoch(7);
        assertThat(pub.getShowcaseChosenEpoch()).isEqualTo(7);

        pub.setShowcaseChosenEpoch(null);
        assertThat(pub.getShowcaseChosenEpoch()).as("explicit null clears the pin").isNull();
    }

    @Test
    @DisplayName("V273: filtered snapshots preserve the source pin but read from renumbered epoch one")
    void sourceEpochPinReadsRenumberedSnapshotEpoch() {
        Map<String, Object> epochOneRender = renderFor("source-epoch-3-title", 1);
        Map<String, Object> ifaceRenders = Map.of(
                "iface_a", Map.of(
                        "defaultRender", epochOneRender,
                        "byEpoch", Map.of("1", epochOneRender)));
        WorkflowPublicationEntity pub = pubWithSnapshot(Map.of(
                "sourceEpoch", 3,
                "interfaceRenders", ifaceRenders));
        pub.setShowcaseChosenEpoch(3);

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface_a", 0, 10, null);

        assertThat(result).isPresent();
        assertThat(result.get().get("htmlTemplate")).isEqualTo("<h1>source-epoch-3-title</h1>");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("epoch", 1);
    }

    @Test
    @DisplayName("V273: explicit renumbered epoch reads filtered snapshot epoch one")
    void explicitRenumberedEpochReadsFilteredSnapshotEpochOne() {
        Map<String, Object> epochOneRender = renderFor("source-epoch-3-title", 1);
        Map<String, Object> ifaceRenders = Map.of(
                "iface_a", Map.of(
                        "defaultRender", epochOneRender,
                        "byEpoch", Map.of("1", epochOneRender)));
        WorkflowPublicationEntity pub = pubWithSnapshot(Map.of(
                "sourceEpoch", 3,
                "interfaceRenders", ifaceRenders));
        pub.setShowcaseChosenEpoch(3);

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface_a", 0, 10, 1);

        assertThat(result).isPresent();
        assertThat(result.get().get("htmlTemplate")).isEqualTo("<h1>source-epoch-3-title</h1>");
    }

    @Test
    @DisplayName("V273: explicit non-source epoch is rejected for filtered snapshots")
    void explicitNonSourceEpochIsRejectedForFilteredSnapshots() {
        Map<String, Object> epochOneRender = renderFor("source-epoch-3-title", 1);
        Map<String, Object> ifaceRenders = Map.of(
                "iface_a", Map.of(
                        "defaultRender", epochOneRender,
                        "byEpoch", Map.of("1", epochOneRender)));
        WorkflowPublicationEntity pub = pubWithSnapshot(Map.of(
                "sourceEpoch", 3,
                "interfaceRenders", ifaceRenders));
        pub.setShowcaseChosenEpoch(3);

        Optional<Map<String, Object>> result = reader.readInterfaceRender(pub, "iface_a", 0, 10, 2);

        assertThat(result).isEmpty();
    }

    private static Map<String, Object> renderFor(String title, int epoch) {
        Map<String, Object> render = new LinkedHashMap<>();
        render.put("htmlTemplate", "<h1>" + title + "</h1>");
        render.put("cssTemplate", "");
        render.put("jsTemplate", "");
        render.put("actionMappings", Map.of());
        render.put("items", List.of(Map.of("epoch", epoch, "itemIndex", 0)));
        return render;
    }

    private static WorkflowPublicationEntity pubWithSnapshot(Map<String, Object> snapshot) {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setShowcaseSnapshot(snapshot);
        return pub;
    }
}
