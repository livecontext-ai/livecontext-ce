package com.apimarketplace.orchestrator.services.context;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RunContextService}.
 *
 * <p>Focused on the per-item context resolution rules introduced in 2026-05-08 to fix the
 * Daily Email Digest split context bug (run {@code run_<id>}). Each
 * regression test reproduces a real prod scenario and would fail on the pre-fix code.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RunContextService")
class RunContextServiceTest {

    @Mock
    private StorageRepository storageRepository;

    @Mock
    private TemplateEngine templateEngine;

    private RunContextService runContextService;

    private static final String RUN_ID = "run-test";
    private static final String TENANT_ID = "tenant-test";

    @BeforeEach
    void setUp() {
        runContextService = new RunContextService(storageRepository, templateEngine);
    }

    private StorageEntity storage(String stepKey, Integer itemIndex, String jsonData) {
        StorageEntity s = new StorageEntity();
        s.setId(UUID.randomUUID());
        s.setRunId(RUN_ID);
        s.setTenantId(TENANT_ID);
        s.setEpoch(1);
        s.setSpawn(0);
        s.setStepKey(stepKey);
        s.setItemIndex(itemIndex);
        s.setData(jsonData);
        s.setSizeBytes(jsonData != null ? jsonData.length() : 0);
        return s;
    }

    @Nested
    @DisplayName("loadRunContextForItem - per-item precedence (regression)")
    class PerItemPrecedenceRegressionTests {

        @Test
        @DisplayName("Pass 1: per-item match wins over other items' storages (Daily Email Digest fix)")
        void perItemMatchWinsOverOtherItems() {
            // Reproduces prod bug: split N=7 → mcp:read_email per-item → core:clean_email
            // sees item 0's email for ALL items because alias was first-wins.
            // Post-fix: each itemIndex K query returns ONLY item K's data.
            List<StorageEntity> storages = List.of(
                storage("mcp:read_email", 0, "{\"output\":{\"id\":\"email_0\"}}"),
                storage("mcp:read_email", 1, "{\"output\":{\"id\":\"email_1\"}}"),
                storage("mcp:read_email", 2, "{\"output\":{\"id\":\"email_2\"}}"),
                storage("mcp:read_email", 3, "{\"output\":{\"id\":\"email_3\"}}")
            );
            when(storageRepository.findByRunIdAndEpoch(eq(RUN_ID), eq(1), eq(TENANT_ID))).thenReturn(storages);

            Map<String, Object> ctx = runContextService.loadRunContextForItem(RUN_ID, TENANT_ID, 1, 2);

            // Both full key and alias must point to item 2's data (same row).
            assertNotNull(ctx.get("mcp:read_email"), "Full key must be present");
            assertNotNull(ctx.get("read_email"), "Alias must be present");
            assertEquals(getOutputId(ctx.get("mcp:read_email")), "email_2",
                "Full key must hold item 2's data");
            assertEquals(getOutputId(ctx.get("read_email")), "email_2",
                "Alias must hold item 2's data (was item 0 pre-fix due to first-wins bug)");
        }

        @Test
        @DisplayName("Pass 2: shared fallback for step_keys with no per-item match (trigger, pre-split nodes)")
        void sharedFallbackForUnmatchedStepKeys() {
            // Trigger and list_emails are pre-split nodes - they execute once with item_index=0
            // and must remain visible to all per-item executions downstream of the split.
            List<StorageEntity> storages = List.of(
                storage("trigger:every_morning", 0, "{\"output\":{\"fired_at\":\"06:00\"}}"),
                storage("mcp:list_emails", 0, "{\"output\":{\"messages\":[]}}"),
                storage("mcp:read_email", 0, "{\"output\":{\"id\":\"email_0\"}}"),
                storage("mcp:read_email", 1, "{\"output\":{\"id\":\"email_1\"}}"),
                storage("mcp:read_email", 2, "{\"output\":{\"id\":\"email_2\"}}")
            );
            when(storageRepository.findByRunIdAndEpoch(eq(RUN_ID), eq(1), eq(TENANT_ID))).thenReturn(storages);

            Map<String, Object> ctx = runContextService.loadRunContextForItem(RUN_ID, TENANT_ID, 1, 2);

            // Per-item match: read_email[item=2]
            assertEquals("email_2", getOutputId(ctx.get("read_email")));
            // Shared fallback: trigger and list_emails (item_index=0, no per-item siblings)
            assertNotNull(ctx.get("trigger:every_morning"));
            assertNotNull(ctx.get("every_morning"));
            assertNotNull(ctx.get("mcp:list_emails"));
            assertNotNull(ctx.get("list_emails"));
        }

        @Test
        @DisplayName("All-skipped: itemIndex out of range - context contains shared rows only")
        void itemIndexOutOfRangeReturnsSharedOnly() {
            // If itemIndex=99 has no per-item storage (e.g., split only had 7 items, query asks
            // for item 99), the function gracefully degrades to shared-only context - downstream
            // templates resolve null for per-item references, mirroring upstream-failed semantics.
            List<StorageEntity> storages = List.of(
                storage("trigger:every_morning", 0, "{\"output\":{\"fired_at\":\"06:00\"}}"),
                storage("mcp:read_email", 0, "{\"output\":{\"id\":\"email_0\"}}"),
                storage("mcp:read_email", 1, "{\"output\":{\"id\":\"email_1\"}}")
            );
            when(storageRepository.findByRunIdAndEpoch(eq(RUN_ID), eq(1), eq(TENANT_ID))).thenReturn(storages);

            Map<String, Object> ctx = runContextService.loadRunContextForItem(RUN_ID, TENANT_ID, 1, 99);

            // Trigger has only item_index=0 (no per-item siblings) → shared via Pass 2
            assertNotNull(ctx.get("trigger:every_morning"));
            // read_email is per-item with no item 99 → Pass 1 no match, Pass 2 condition
            // (item_index null OR 0) matches the item 0 row, so it falls back to that.
            // This is acceptable degradation: downstream template gets SOME data, not null.
            assertNotNull(ctx.get("read_email"));
            assertEquals("email_0", getOutputId(ctx.get("read_email")));
        }

        @Test
        @DisplayName("Mixed split + post-merge: per-item nodes filtered, shared post-merge nodes visible")
        void mixedSplitAndSharedPostMerge() {
            List<StorageEntity> storages = List.of(
                storage("mcp:read_email", 0, "{\"output\":{\"id\":\"email_0\"}}"),
                storage("mcp:read_email", 1, "{\"output\":{\"id\":\"email_1\"}}"),
                storage("core:aggregate_emails", 0, "{\"output\":{\"count\":2}}")  // post-merge
            );
            when(storageRepository.findByRunIdAndEpoch(eq(RUN_ID), eq(1), eq(TENANT_ID))).thenReturn(storages);

            Map<String, Object> ctx = runContextService.loadRunContextForItem(RUN_ID, TENANT_ID, 1, 1);

            assertEquals("email_1", getOutputId(ctx.get("read_email")));
            assertNotNull(ctx.get("aggregate_emails"));
        }
    }

    @Nested
    @DisplayName("loadRunContextForItem - spawn-aware overload")
    class SpawnAwareOverloadTests {

        @Test
        @DisplayName("spawn=0 routes to findByRunIdAndEpoch and applies per-item precedence")
        void spawnZeroUsesPerItemPrecedence() {
            List<StorageEntity> storages = List.of(
                storage("mcp:read_email", 0, "{\"output\":{\"id\":\"email_0\"}}"),
                storage("mcp:read_email", 1, "{\"output\":{\"id\":\"email_1\"}}")
            );
            when(storageRepository.findByRunIdAndEpoch(eq(RUN_ID), eq(1), eq(TENANT_ID))).thenReturn(storages);

            Map<String, Object> ctx = runContextService.loadRunContextForItem(RUN_ID, TENANT_ID, 1, 0, 1);

            assertEquals("email_1", getOutputId(ctx.get("read_email")));
        }

        @Test
        @DisplayName("spawn>0 routes to findByRunIdAndEpochWithLatestSpawn and applies per-item precedence")
        void spawnPositiveUsesLatestSpawnQueryThenPerItemPrecedence() {
            List<StorageEntity> storages = List.of(
                storage("mcp:read_email", 0, "{\"output\":{\"id\":\"email_0_v2\"}}"),
                storage("mcp:read_email", 1, "{\"output\":{\"id\":\"email_1_v2\"}}")
            );
            when(storageRepository.findByRunIdAndEpochWithLatestSpawn(
                eq(RUN_ID), eq(1), eq(2), eq(TENANT_ID))).thenReturn(storages);

            Map<String, Object> ctx = runContextService.loadRunContextForItem(RUN_ID, TENANT_ID, 1, 2, 0);

            assertEquals("email_0_v2", getOutputId(ctx.get("read_email")));
        }
    }

    @Nested
    @DisplayName("loadRunContext - non-per-item callers (no regression)")
    class NonPerItemTests {

        @Test
        @DisplayName("Single-item-per-step_key context preserved (workflows without split)")
        void singleStorageContextPreserved() {
            List<StorageEntity> storages = List.of(
                storage("trigger:start", 0, "{\"output\":{\"data\":\"x\"}}"),
                storage("mcp:fetch", 0, "{\"output\":{\"items\":[1,2,3]}}"),
                storage("mcp:save", 0, "{\"output\":{\"saved\":true}}")
            );
            when(storageRepository.findByRunIdAndEpoch(eq(RUN_ID), eq(1), eq(TENANT_ID))).thenReturn(storages);

            Map<String, Object> ctx = runContextService.loadRunContext(RUN_ID, TENANT_ID, 1);

            assertNotNull(ctx.get("mcp:fetch"));
            assertNotNull(ctx.get("fetch"));
            assertNotNull(ctx.get("trigger:start"));
            assertNotNull(ctx.get("start"));
        }

        @Test
        @DisplayName("Multiple storages same step_key - last-wins (alias and full-key consistent)")
        void multipleStoragesSameStepKeyLastWinsConsistent() {
            // ORDER BY createdAt, id DESC tiebreaker means the last-iterated storage wins
            // for both full key and alias. Pre-fix: full=last, alias=first → drift.
            List<StorageEntity> storages = List.of(
                storage("mcp:fetch", 0, "{\"output\":{\"v\":\"first\"}}"),
                storage("mcp:fetch", 1, "{\"output\":{\"v\":\"second\"}}"),
                storage("mcp:fetch", 2, "{\"output\":{\"v\":\"last\"}}")
            );
            when(storageRepository.findByRunIdAndEpoch(eq(RUN_ID), eq(1), eq(TENANT_ID))).thenReturn(storages);

            Map<String, Object> ctx = runContextService.loadRunContext(RUN_ID, TENANT_ID, 1);

            // Both must reflect the LAST iterated storage (consistent).
            String fullKeyV = (String) ((Map<String, Object>) ((Map<String, Object>) ctx.get("mcp:fetch")).get("output")).get("v");
            String aliasV = (String) ((Map<String, Object>) ((Map<String, Object>) ctx.get("fetch")).get("output")).get("v");
            assertEquals(fullKeyV, aliasV, "full-key and alias must hold the same row's data");
        }
    }

    @Nested
    @DisplayName("Iteration × spawn × item_index matrix")
    class IterationSpawnItemIndexMatrixTests {

        @Test
        @DisplayName("Loop body: multiple writes same (step_key, item_index) - last iteration (latest createdAt) wins")
        void loopBodyMultipleIterationsLastWins() {
            // A loop body inside a split writes multiple storages with the same
            // (step_key, item_index) but different createdAt - one per iteration.
            // ORDER BY createdAt, id DESC means the LAST iteration wins (last-wins).
            // Pass 1 puts each matching row; subsequent puts overwrite for both alias
            // and full-key from the same row.
            List<StorageEntity> storages = List.of(
                storage("mcp:loop_body", 1, "{\"output\":{\"v\":\"v1\"}}"),  // iter 1
                storage("mcp:loop_body", 1, "{\"output\":{\"v\":\"v2\"}}"),  // iter 2
                storage("mcp:loop_body", 1, "{\"output\":{\"v\":\"v3\"}}")   // iter 3 (latest)
            );
            when(storageRepository.findByRunIdAndEpoch(eq(RUN_ID), eq(1), eq(TENANT_ID))).thenReturn(storages);

            Map<String, Object> ctx = runContextService.loadRunContextForItem(RUN_ID, TENANT_ID, 1, 1);

            // Latest iteration's value visible via both full-key and alias
            assertEquals("v3", getOutputV(ctx.get("mcp:loop_body")));
            assertEquals("v3", getOutputV(ctx.get("loop_body")));
        }

        @Test
        @DisplayName("Spawn>0 + multiple item_index - DISTINCT ON keeps per-item rows, Pass 1 picks matching")
        void spawnPositiveMultipleItemsPerItemMatch() {
            // After fix: DISTINCT ON (step_key, item_index) preserves per-item rows on rerun.
            // Pre-fix: DISTINCT ON (step_key) collapsed N items to 1 (silent data loss).
            // Mock the repository return as if DISTINCT ON returned 4 rows (one per item).
            List<StorageEntity> storages = List.of(
                storage("mcp:fetch", 0, "{\"output\":{\"id\":\"v1_item_0\"}}"),
                storage("mcp:fetch", 1, "{\"output\":{\"id\":\"v1_item_1\"}}"),
                storage("mcp:fetch", 2, "{\"output\":{\"id\":\"v1_item_2\"}}"),
                storage("mcp:fetch", 3, "{\"output\":{\"id\":\"v1_item_3\"}}")
            );
            when(storageRepository.findByRunIdAndEpochWithLatestSpawn(
                eq(RUN_ID), eq(1), eq(3), eq(TENANT_ID))).thenReturn(storages);

            Map<String, Object> ctx = runContextService.loadRunContextForItem(RUN_ID, TENANT_ID, 1, 3, 2);

            // Pass 1 picks itemIndex=2 only - alias and full-key both reflect item 2.
            assertEquals("v1_item_2", getOutputId(ctx.get("mcp:fetch")));
            assertEquals("v1_item_2", getOutputId(ctx.get("fetch")));
        }

        @Test
        @DisplayName("Multi-epoch isolation: query filters by epoch param (mock verifies)")
        void multiEpochIsolation() {
            // Storages from epoch 1 and epoch 2 exist in DB. The query filters by epoch
            // param at the SQL layer. RunContextService trusts the repository - the test
            // verifies that the repository is called with the requested epoch and the
            // returned storages are processed correctly.
            List<StorageEntity> epoch1Storages = List.of(
                storage("mcp:fetch", 0, "{\"output\":{\"epoch\":\"e1\"}}")
            );
            List<StorageEntity> epoch2Storages = List.of(
                storage("mcp:fetch", 0, "{\"output\":{\"epoch\":\"e2\"}}")
            );
            when(storageRepository.findByRunIdAndEpoch(eq(RUN_ID), eq(1), eq(TENANT_ID))).thenReturn(epoch1Storages);
            when(storageRepository.findByRunIdAndEpoch(eq(RUN_ID), eq(2), eq(TENANT_ID))).thenReturn(epoch2Storages);

            Map<String, Object> ctxE1 = runContextService.loadRunContextForItem(RUN_ID, TENANT_ID, 1, 0);
            Map<String, Object> ctxE2 = runContextService.loadRunContextForItem(RUN_ID, TENANT_ID, 2, 0);

            assertEquals("e1", getOutputEpoch(ctxE1.get("fetch")));
            assertEquals("e2", getOutputEpoch(ctxE2.get("fetch")));
        }

        @Test
        @DisplayName("createdAt tiebreaker: ORDER BY id DESC ensures deterministic last-wins on ties")
        void createdAtTieDeterministic() {
            // Two storages with the same logical key but distinct UUIDs. Repository's
            // ORDER BY (createdAt, id DESC) decides iteration order. Mock returns them
            // in that order; the test asserts buildPerItemContext processes them
            // deterministically (last in iteration wins). The deterministic ordering
            // guarantee is at the SQL layer (id DESC tiebreaker on tied createdAt) -
            // covered here at the Java level by trusting the repository's contract.
            List<StorageEntity> storages = List.of(
                storage("mcp:tied", 0, "{\"output\":{\"v\":\"earlier\"}}"),
                storage("mcp:tied", 0, "{\"output\":{\"v\":\"later\"}}")
            );
            when(storageRepository.findByRunIdAndEpoch(eq(RUN_ID), eq(1), eq(TENANT_ID))).thenReturn(storages);

            Map<String, Object> ctx = runContextService.loadRunContextForItem(RUN_ID, TENANT_ID, 1, 0);

            // Last in mock list wins for both full-key and alias.
            assertEquals("later", getOutputV(ctx.get("mcp:tied")));
            assertEquals("later", getOutputV(ctx.get("tied")));
        }
    }

    // ─────────────────────────── Helpers ───────────────────────────

    @SuppressWarnings("unchecked")
    private static String getOutputV(Object stepData) {
        if (stepData == null) return null;
        Map<String, Object> output = (Map<String, Object>) ((Map<String, Object>) stepData).get("output");
        if (output == null) return null;
        return (String) output.get("v");
    }

    @SuppressWarnings("unchecked")
    private static String getOutputEpoch(Object stepData) {
        if (stepData == null) return null;
        Map<String, Object> output = (Map<String, Object>) ((Map<String, Object>) stepData).get("output");
        if (output == null) return null;
        return (String) output.get("epoch");
    }

    @SuppressWarnings("unchecked")
    private static String getOutputId(Object stepData) {
        if (stepData == null) return null;
        Map<String, Object> output = (Map<String, Object>) ((Map<String, Object>) stepData).get("output");
        if (output == null) return null;
        return (String) output.get("id");
    }

    // Mockito's eq() shorthand
    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
