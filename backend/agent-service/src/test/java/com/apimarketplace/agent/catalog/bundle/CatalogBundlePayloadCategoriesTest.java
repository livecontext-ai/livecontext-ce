package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.ModelCategorySettingsEntity;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-shape coverage for V156 - the {@code categories} field on each model row.
 *
 * <p>Asserts:
 * <ul>
 *   <li>Categories are absent when no sidecar rows are passed (back-compat).</li>
 *   <li>Categories are sorted alphabetically (byte-determinism for the signer).</li>
 *   <li>Orphaned sidecar rows (no matching model id) are silently dropped.</li>
 *   <li>{@code rank=null} is omitted from the inner map; {@code enabled} always present.</li>
 *   <li>The byte stream is byte-identical for two calls with the same inputs (signer relies on this).</li>
 * </ul>
 */
@DisplayName("CatalogBundlePayload - V156 categories serialisation")
class CatalogBundlePayloadCategoriesTest {

    private static final Instant TS = Instant.parse("2026-04-30T00:00:00.000000Z");

    @Test
    @DisplayName("No sidecar rows → no `categories` field on any row (back-compat with v1 readers)")
    void absentWhenSidecarEmpty() {
        ModelConfigOverrideEntity m = entity(10L, "openai", "gpt-5");

        String json = new String(CatalogBundlePayload.canonicalBytes(
                1L, 2, "cloud", TS, List.of(m), List.of()));

        assertThat(json).doesNotContain("categories");
    }

    @Test
    @DisplayName("Categories sort alphabetically + rank omitted when null + enabled always present")
    void categoriesShape() {
        ModelConfigOverrideEntity m = entity(10L, "openai", "gpt-5");
        // Insert in non-alphabetical order to confirm sorting kicks in.
        List<ModelCategorySettingsEntity> sidecar = List.of(
                sidecar(10L, "image_generation", null, false),
                sidecar(10L, "browser_agent", 7, true),
                sidecar(10L, "chat", 1, true));

        String json = new String(CatalogBundlePayload.canonicalBytes(
                1L, 2, "cloud", TS, List.of(m), sidecar));

        // Alphabetical ordering of category keys inside the per-row map.
        int browser = json.indexOf("\"browser_agent\"");
        int chat    = json.indexOf("\"chat\"");
        int image   = json.indexOf("\"image_generation\"");
        assertThat(browser).isPositive();
        assertThat(chat).isGreaterThan(browser);
        assertThat(image).isGreaterThan(chat);

        // rank omitted for image_generation (null), present for the others.
        assertThat(json).contains("\"image_generation\":{\"enabled\":false}");
        assertThat(json).contains("\"chat\":{\"enabled\":true,\"rank\":1}");
        assertThat(json).contains("\"browser_agent\":{\"enabled\":true,\"rank\":7}");
    }

    @Test
    @DisplayName("Orphaned sidecar rows (model_config_id with no matching model) are dropped")
    void orphansDropped() {
        ModelConfigOverrideEntity m = entity(10L, "openai", "gpt-5");
        // 99L doesn't match any model in the list.
        List<ModelCategorySettingsEntity> sidecar = List.of(
                sidecar(10L, "chat", 1, true),
                sidecar(99L, "chat", 1, true));

        String json = new String(CatalogBundlePayload.canonicalBytes(
                1L, 2, "cloud", TS, List.of(m), sidecar));

        // Exactly one occurrence of the chat key - the orphan was filtered.
        long count = json.chars().filter(c -> c == '"').count();
        assertThat(json.split("\"chat\"")).hasSize(2); // one prefix + one suffix split
        // Sanity: count tokens (avoid false positives from substring matching).
        assertThat(count).isPositive();
    }

    @Test
    @DisplayName("Two calls with the same inputs produce byte-identical output (signer contract)")
    void deterministicBytes() {
        ModelConfigOverrideEntity m = entity(10L, "openai", "gpt-5");
        List<ModelCategorySettingsEntity> sidecar = List.of(
                sidecar(10L, "chat", 1, true),
                sidecar(10L, "browser_agent", 2, false));

        byte[] a = CatalogBundlePayload.canonicalBytes(1L, 2, "cloud", TS, List.of(m), sidecar);
        byte[] b = CatalogBundlePayload.canonicalBytes(1L, 2, "cloud", TS, List.of(m), sidecar);

        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("Per-row map omitted when sidecar list has no rows for that model id")
    void perRowMapOmittedWhenNoRowsForModel() {
        ModelConfigOverrideEntity gpt5 = entity(10L, "openai", "gpt-5");
        ModelConfigOverrideEntity claude = entity(20L, "anthropic", "claude-opus");
        // Sidecar covers gpt-5 (id=10L) only - claude (id=20L) gets none.
        List<ModelCategorySettingsEntity> sidecar = List.of(sidecar(10L, "chat", 1, true));

        String json = new String(CatalogBundlePayload.canonicalBytes(
                1L, 2, "cloud", TS, List.of(gpt5, claude), sidecar));

        // Exactly ONE row carries a `categories` block - the gpt-5 row. If the
        // orphan/no-rows guard regressed, claude's row would also emit one.
        int firstCategories = json.indexOf("\"categories\"");
        int lastCategories = json.lastIndexOf("\"categories\"");
        assertThat(firstCategories).isPositive();
        assertThat(lastCategories).isEqualTo(firstCategories);

        // Sanity-check the gpt-5 row's chat sidecar is what we wrote.
        assertThat(json).contains("\"categories\":{\"chat\":{\"enabled\":true,\"rank\":1}}");
    }

    private static ModelConfigOverrideEntity entity(Long id, String provider, String modelId) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setId(id);
        e.setProvider(provider);
        e.setModelId(modelId);
        e.setDisplayName(modelId);
        return e;
    }

    private static ModelCategorySettingsEntity sidecar(Long modelId, String category,
                                                       Integer rank, boolean enabled) {
        ModelCategorySettingsEntity s = new ModelCategorySettingsEntity();
        s.setModelConfigId(modelId);
        s.setCategory(category);
        s.setRank(rank);
        s.setEnabled(enabled);
        return s;
    }
}
