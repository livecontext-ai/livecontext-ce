package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.service.AuthPricingSyncClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * V554: the unlisted flag reaches self-hosted (CE) installs through the signed bundle.
 *
 * <p>Unlisting a model the cloud had OFF turns its {@code enabled} back on, and {@code enabled}
 * is what the bundle ships. Without the flag beside it, every linked CE would receive the model
 * as an ordinary listed one. Both halves are pinned here: what the cloud emits, and what a CE
 * merge does with it, per source (a feed sync must never touch it).
 */
@DisplayName("V554 unlisted - bundle payload and CE merge")
class CatalogBundleUnlistedTransportTest {

    private static final Instant TS = Instant.parse("2026-09-30T10:00:00Z");

    private static ModelConfigOverrideEntity cloudRow(String modelId, Boolean enabled, boolean unlisted) {
        ModelConfigOverrideEntity m = new ModelConfigOverrideEntity();
        m.setProvider("openai");
        m.setModelId(modelId);
        m.setDisplayName(modelId);
        m.setPriceInput(new BigDecimal("2.50"));
        m.setEnabled(enabled);
        m.setUnlisted(unlisted);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shipped(ModelConfigOverrideEntity m) throws Exception {
        byte[] bytes = CatalogBundlePayload.canonicalBytes(1L, 1, "cloud", TS, List.of(m));
        Map<String, Object> root = new ObjectMapper().readValue(bytes, Map.class);
        return ((List<Map<String, Object>>) root.get("models")).get(0);
    }

    @Nested
    @DisplayName("cloud payload")
    class Payload {

        @Test
        @DisplayName("an unlisted model the bundle ships ON carries unlisted=true beside enabled")
        void unlistedShipsTheFlag() throws Exception {
            assertThat(shipped(cloudRow("gpt-4o", true, true)))
                    .containsEntry("enabled", true)
                    .containsEntry("unlisted", true);
        }

        @Test
        @DisplayName("a listed model carries no key at all: its signed bytes are what they were before V554")
        void listedRowBytesUnchanged() {
            ModelConfigOverrideEntity listed = cloudRow("gpt-5", true, false);
            String json = new String(CatalogBundlePayload.canonicalBytes(1L, 1, "cloud", TS, List.of(listed)),
                    StandardCharsets.UTF_8);

            assertThat(json).doesNotContain("unlisted");
        }

        @Test
        @DisplayName("an OFF model carries no flag, even one left set: off wins on both sides")
        void offRowCarriesNoFlag() throws Exception {
            assertThat(shipped(cloudRow("gpt-4", false, true))).doesNotContainKey("unlisted");
        }

        @Test
        @DisplayName("bundle_enabled decides what 'shipped on' means: forced on, the flag travels; forced off, it does not")
        void bundleEnabledDecides() throws Exception {
            ModelConfigOverrideEntity forcedOn = cloudRow("gpt-4o", false, true);
            forcedOn.setBundleEnabled(true);
            ModelConfigOverrideEntity forcedOff = cloudRow("gpt-4o-mini", true, true);
            forcedOff.setBundleEnabled(false);

            assertThat(shipped(forcedOn)).containsEntry("unlisted", true);
            assertThat(shipped(forcedOff)).doesNotContainKey("unlisted");
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("CE merge")
    class Merge {

        @Mock private ModelConfigOverrideRepository modelRepo;
        @Mock private AuthPricingSyncClient authPricingSyncClient;

        private CatalogMergeService merge;
        private ModelConfigOverrideEntity ceRow;

        @BeforeEach
        void setUp() {
            merge = new CatalogMergeService(modelRepo, null, authPricingSyncClient, null);
            when(modelRepo.findMaxRanking()).thenReturn(0);
            when(modelRepo.save(any())).thenAnswer(inv -> {
                ModelConfigOverrideEntity e = inv.getArgument(0);
                if (e.getId() == null) e.setId(99L);
                return e;
            });
            ceRow = new ModelConfigOverrideEntity();
            ceRow.setId(1L);
            ceRow.setProvider("openai");
            ceRow.setModelId("gpt-4o");
            ceRow.setDisplayName("gpt-4o");
            ceRow.setEnabled(true);
            when(modelRepo.findByProviderAndModelId("openai", "gpt-4o")).thenReturn(Optional.of(ceRow));
        }

        private Map<String, Object> payload(Boolean unlisted) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", "openai");
            m.put("modelId", "gpt-4o");
            m.put("displayName", "gpt-4o");
            m.put("enabled", true);
            if (unlisted != null) m.put("unlisted", unlisted);
            return m;
        }

        @Test
        @DisplayName("a bundle carrying unlisted=true unlists the CE row")
        void bundleUnlists() {
            merge.merge(List.of(payload(true)), MergeOptions.forBundle(7L));

            assertThat(ceRow.isUnlisted()).isTrue();
            assertThat(ceRow.getEnabled()).isTrue();
        }

        @Test
        @DisplayName("a bundle WITHOUT the key lists it again: the cloud admin listed it back")
        void bundleWithoutKeyLists() {
            ceRow.setUnlisted(true);

            merge.merge(List.of(payload(null)), MergeOptions.forBundle(8L));

            assertThat(ceRow.isUnlisted()).isFalse();
        }

        @Test
        @DisplayName("a CE admin's own choice wins over the bundle, in both directions")
        void ceAdminChoiceIsProtected() {
            ceRow.setUnlisted(true);
            ceRow.addUserModifiedField("unlisted");

            merge.merge(List.of(payload(null)), MergeOptions.forBundle(9L));
            assertThat(ceRow.isUnlisted()).isTrue();

            ceRow.setUnlisted(false);
            merge.merge(List.of(payload(true)), MergeOptions.forBundle(10L));
            assertThat(ceRow.isUnlisted()).isFalse();
        }

        @Test
        @DisplayName("a feed sync never touches it: its absent key must not list every unlisted model on the next refresh")
        void feedSyncNeverTouches() {
            ceRow.setUnlisted(true);
            Map<String, Object> feed = payload(null);
            feed.remove("enabled");

            merge.merge(List.of(feed), MergeOptions.forSync());

            assertThat(ceRow.isUnlisted()).isTrue();
        }

        @Test
        @DisplayName("the curated seed applies the key when it carries it, and leaves the row alone when it does not")
        void seedIsPartial() {
            ceRow.setUnlisted(true);
            merge.merge(List.of(payload(null)), MergeOptions.forSeed());
            assertThat(ceRow.isUnlisted()).as("absent key on the partial seed path").isTrue();

            merge.merge(List.of(payload(false)), MergeOptions.forSeed());
            assertThat(ceRow.isUnlisted()).isFalse();
        }

        @Test
        @DisplayName("a model a bundle introduces arrives unlisted when the cloud has it unlisted")
        void bundleInsertUnlists() {
            when(modelRepo.findByProviderAndModelId("openai", "gpt-4o")).thenReturn(Optional.empty());
            ModelConfigOverrideEntity[] saved = new ModelConfigOverrideEntity[1];
            doAnswer(inv -> {
                ModelConfigOverrideEntity e = inv.getArgument(0);
                e.setId(42L);
                saved[0] = e;
                return e;
            }).when(modelRepo).save(any());

            merge.merge(List.of(payload(true)), MergeOptions.forBundle(11L));

            assertThat(saved[0]).isNotNull();
            assertThat(saved[0].isUnlisted()).isTrue();
        }
    }
}
