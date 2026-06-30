package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.service.ResponseShaper.Action;
import com.apimarketplace.catalog.service.ResponseShaper.Mode;
import com.apimarketplace.catalog.service.ResponseShaper.ShapingResult;
import com.apimarketplace.catalog.service.ResponseShaper.TruncationPattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ResponseShaper}.
 *
 * <p>Covers per-leaf truncation (4 KB cap, regression vs old 2 KB), array
 * digest under total budget, max_items cap (top-level arrays only),
 * pass-1.5 fallback for wide-object flooding, oversize skeleton fallback,
 * pattern aggregation, FileRef opacity, expand wildcard semantics, root-list
 * preservation, and workflow-mode contract.
 */
@DisplayName("ResponseShaper")
class ResponseShaperTest {

    private ResponseShaper shaper;

    @BeforeEach
    void setUp() {
        shaper = new ResponseShaper();
    }

    // ========================================================================
    // Per-leaf truncation (Pass 1)
    // ========================================================================

    @Nested
    @DisplayName("Per-leaf truncation")
    class PerLeafTests {

        @Test
        @DisplayName("perLeafTruncationFiresAt4KBNotBefore - 5 KB clipped, 3 KB preserved")
        void perLeafTruncationFiresAt4KBNotBefore() {
            String fiveKB = "This is a sentence. ".repeat(280); // ~5.6 KB
            String threeKB = "This is a sentence. ".repeat(150); // ~3 KB

            Map<String, Object> input = Map.of("big", fiveKB, "small", threeKB);
            ShapingResult result = shaper.shape(input, null, null, Mode.AGENT);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertTrue(((String) data.get("big")).contains("[TRUNCATED:"), "5 KB string must be clipped");
            assertEquals(threeKB, data.get("small"), "3 KB string must NOT be clipped under 4 KB cap");
            assertEquals(1, result.truncatedPatterns().size());
            assertEquals("big", result.truncatedPatterns().get(0).path());
        }

        @Test
        @DisplayName("expandWildcardStillWorks - `data[].b64_json` regression guarded")
        void expandWildcardStillWorks() {
            String largeBase64 = "A".repeat(5000);
            Map<String, Object> imageItem = Map.of("b64_json", largeBase64, "revised_prompt", "x");
            Map<String, Object> input = Map.of("data", List.of(imageItem));

            ShapingResult result = shaper.shape(input, List.of("data[].b64_json"), null, Mode.AGENT);

            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) result.data();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> dataList = (List<Map<String, Object>>) root.get("data");
            assertEquals(largeBase64, dataList.get(0).get("b64_json"));
            assertFalse(result.hasTruncatedPatterns());
        }

        @Test
        @DisplayName("fileRefMapNotRecursed - _type:file is opaque regardless of nominal size")
        void fileRefMapNotRecursed() {
            Map<String, Object> fileRef = new LinkedHashMap<>();
            fileRef.put("_type", "file");
            fileRef.put("path", "minio://bucket/file.png");
            // Add a fake "huge" string inside the FileRef. Must NOT trigger truncation.
            fileRef.put("hidden_label", "X".repeat(10_000));

            Map<String, Object> input = Map.of("attachment", fileRef);
            ShapingResult result = shaper.shape(input, null, null, Mode.AGENT);

            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) result.data();
            @SuppressWarnings("unchecked")
            Map<String, Object> ref = (Map<String, Object>) root.get("attachment");
            assertEquals("X".repeat(10_000), ref.get("hidden_label"),
                    "FileRef Map content must be preserved verbatim");
            assertFalse(result.hasTruncatedPatterns());
        }

        @Test
        @DisplayName("base64DetectionTriggersBase64Marker - pure base64 chars produce [BASE64_CONTENT]")
        void base64DetectionTriggersBase64Marker() {
            String pureB64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".repeat(100);
            Map<String, Object> input = Map.of("image", pureB64);
            ShapingResult result = shaper.shape(input, null, null, Mode.AGENT);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertTrue(((String) data.get("image")).contains("[BASE64_CONTENT:"));
        }
    }

    // ========================================================================
    // Total budget (Pass 2 - array digest)
    // ========================================================================

    @Nested
    @DisplayName("Array digest (total budget)")
    class ArrayDigestTests {

        @Test
        @DisplayName("arrayDigestKicksInWhenTotalOverBudget - 10 items × 8 KB → digest with 3 preview items")
        void arrayDigestKicksInWhenTotalOverBudget() {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", "user_" + i);
                // ~8 KB per item via medium-size strings (each under 4 KB cap so
                // pass-1 doesn't trim them - this is what forces pass-2 to digest).
                item.put("about", "Something. ".repeat(350));      // ~3.85 KB
                item.put("experience", "Something. ".repeat(350)); // ~3.85 KB
                items.add(item);
            }
            Map<String, Object> input = Map.of("items", items);

            ShapingResult result = shaper.shape(input, null, null, Mode.AGENT);

            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) result.data();
            @SuppressWarnings("unchecked")
            Map<String, Object> digest = (Map<String, Object>) root.get("items");
            assertEquals("array_digest", digest.get("_shape"));
            assertEquals(10, digest.get("total_items"));
            assertEquals(3, digest.get("preview_items"));
            assertEquals(3, digest.get("skipped_from"));
            assertEquals(9, digest.get("skipped_to"));
            assertEquals(Action.ARRAY_DIGESTED, result.action());
        }

        @Test
        @DisplayName("arrayDigestPreservesShapeAtRoot - list-typed root → returns [<digest_map>]")
        void arrayDigestPreservesShapeAtRoot() {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", "u" + i);
                // Two ~3.5 KB strings per item → 7 KB/item → 70 KB total > 64 KB
                // (each leaf stays under the 4 KB pass-1 cap so pass-2 must digest).
                item.put("about", "Some content. ".repeat(250));
                item.put("experience", "Other content. ".repeat(230));
                items.add(item);
            }

            ShapingResult result = shaper.shape(items, null, null, Mode.AGENT);

            assertTrue(result.data() instanceof List, "root preserved as List");
            @SuppressWarnings("unchecked")
            List<Object> rootList = (List<Object>) result.data();
            assertEquals(1, rootList.size(), "root list wraps a single digest map");
            @SuppressWarnings("unchecked")
            Map<String, Object> digest = (Map<String, Object>) rootList.get(0);
            assertEquals("array_digest", digest.get("_shape"));
        }

        @Test
        @DisplayName("arrayDigestEmitsIntegerSkippedRange - skipped_from/skipped_to are ints")
        void arrayDigestEmitsIntegerSkippedRange() {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("about", "Some content. ".repeat(250));
                item.put("experience", "Other content. ".repeat(230));
                items.add(item);
            }
            Map<String, Object> input = Map.of("items", items);

            ShapingResult result = shaper.shape(input, null, null, Mode.AGENT);

            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) result.data();
            @SuppressWarnings("unchecked")
            Map<String, Object> digest = (Map<String, Object>) root.get("items");
            assertEquals("array_digest", digest.get("_shape"), "items array must have been digested");
            assertTrue(digest.get("skipped_from") instanceof Integer);
            assertTrue(digest.get("skipped_to") instanceof Integer);
        }

        @Test
        @DisplayName("noNestedDigest - preview items keep their inner arrays untouched")
        void noNestedDigest() {
            List<Map<String, Object>> outer = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Map<String, Object> item = new LinkedHashMap<>();
                List<String> inner = new ArrayList<>();
                for (int j = 0; j < 3; j++) inner.add("v" + j);
                item.put("inner", inner);
                item.put("about", "Some content. ".repeat(250));
                item.put("experience", "Other content. ".repeat(230));
                outer.add(item);
            }
            Map<String, Object> input = Map.of("items", outer);

            ShapingResult result = shaper.shape(input, null, null, Mode.AGENT);
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) result.data();
            @SuppressWarnings("unchecked")
            Map<String, Object> digest = (Map<String, Object>) root.get("items");
            assertEquals("array_digest", digest.get("_shape"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> previews = (List<Map<String, Object>>) digest.get("items");
            for (Map<String, Object> p : previews) {
                Object inner = p.get("inner");
                assertTrue(inner instanceof List, "inner array must remain a List, not a digest map");
            }
        }
    }

    // ========================================================================
    // max_items (top-level cap)
    // ========================================================================

    @Nested
    @DisplayName("max_items")
    class MaxItemsTests {

        @Test
        @DisplayName("maxItemsCapsTopLevelArrays - max_items=2 on 10-item array → digest with 2 preview items")
        void maxItemsCapsTopLevelArrays() {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                items.add(Map.of("idx", i));
            }
            Map<String, Object> input = Map.of("items", items);

            ShapingResult result = shaper.shape(input, null, 2, Mode.AGENT);

            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) result.data();
            @SuppressWarnings("unchecked")
            Map<String, Object> digest = (Map<String, Object>) root.get("items");
            assertEquals("array_digest", digest.get("_shape"));
            assertEquals(10, digest.get("total_items"));
            assertEquals(2, digest.get("preview_items"));
            assertEquals(2, digest.get("skipped_from"));
            assertEquals(9, digest.get("skipped_to"));
        }

        @Test
        @DisplayName("maxItemsZeroEmitsStructureOnly - preview_items:0, skipped fields present")
        void maxItemsZeroEmitsStructureOnly() {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < 5; i++) items.add(Map.of("idx", i));
            Map<String, Object> input = Map.of("items", items);

            ShapingResult result = shaper.shape(input, null, 0, Mode.AGENT);

            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) result.data();
            @SuppressWarnings("unchecked")
            Map<String, Object> digest = (Map<String, Object>) root.get("items");
            assertEquals(0, digest.get("preview_items"));
            assertEquals(0, ((List<?>) digest.get("items")).size());
            assertEquals(0, digest.get("skipped_from"));
            assertEquals(4, digest.get("skipped_to"));
        }

        @Test
        @DisplayName("maxItemsLargerThanArrayIsNoOp - max_items=20 on 5-item array → no digest")
        void maxItemsLargerThanArrayIsNoOp() {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < 5; i++) items.add(Map.of("idx", i));
            Map<String, Object> input = Map.of("items", items);

            ShapingResult result = shaper.shape(input, null, 20, Mode.AGENT);

            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) result.data();
            assertTrue(root.get("items") instanceof List, "no-op cap must keep the array as List");
            assertEquals(5, ((List<?>) root.get("items")).size());
        }

        @Test
        @DisplayName("maxItemsDoesNotRecurseIntoNestedArrays - only top-level arrays capped")
        void maxItemsDoesNotRecurseIntoNestedArrays() {
            List<Map<String, Object>> outerUsers = new ArrayList<>();
            for (int u = 0; u < 3; u++) {
                List<Map<String, Object>> inner = new ArrayList<>();
                for (int p = 0; p < 10; p++) inner.add(Map.of("post", p));
                outerUsers.add(Map.of("posts", inner));
            }
            Map<String, Object> input = Map.of("users", outerUsers);

            ShapingResult result = shaper.shape(input, null, 2, Mode.AGENT);

            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) result.data();
            @SuppressWarnings("unchecked")
            Map<String, Object> digest = (Map<String, Object>) root.get("users");
            // users is at top level: capped.
            assertEquals("array_digest", digest.get("_shape"));
            // Each preview user's inner posts: NOT capped.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> users = (List<Map<String, Object>>) digest.get("items");
            for (Map<String, Object> u : users) {
                assertTrue(u.get("posts") instanceof List, "nested arrays remain Lists");
                assertEquals(10, ((List<?>) u.get("posts")).size());
            }
        }
    }

    // ========================================================================
    // Pass 1.5 (wide-object fallback)
    // ========================================================================

    @Nested
    @DisplayName("Pass 1.5 fallback")
    class PassOneFiveTests {

        @Test
        @DisplayName("wideObjectNoArrayJumpsToReclip - flat Map, no array → re-clipped at 1 KB and fits")
        void wideObjectNoArrayJumpsToReclip() {
            // 25 fields × 3.5 KB strings (each under 4 KB cap so pass-1 doesn't
            // touch them, but total > 64 KB and there's no array to digest).
            Map<String, Object> input = new LinkedHashMap<>();
            for (int i = 0; i < 25; i++) {
                input.put("f" + i, "x".repeat(3500));
            }

            ShapingResult result = shaper.shape(input, null, null, Mode.AGENT);

            assertEquals(Action.OVERSIZE_FALLBACK, result.action(),
                    "no array to digest → must hit pass-1.5 / oversize fallback");
            // Total size must be at or under budget after fallback.
            assertTrue(result.shapedBytes() <= ResponseShaper.MAX_TOTAL_RESPONSE_SIZE,
                    "shaped size " + result.shapedBytes() + " > " + ResponseShaper.MAX_TOTAL_RESPONSE_SIZE);
        }

        @Test
        @DisplayName("extremeOverflowFallsBackToSkeleton - 350 wide non-b64 fields → _shape:oversize")
        void extremeOverflowFallsBackToSkeleton() {
            // Goal: post-pass-1 tree must STILL exceed the 64 KB budget AND have
            // no array to digest, so pass-1.5 fires (re-clip at 1 KB), and even
            // that doesn't fit → skeleton fallback. Use a non-base64-looking
            // text so the truncator emits a 200-char text preview (not the
            // ~24-char `[BASE64_CONTENT: ...]` marker which would fit under
            // budget). 350 × ~230 chars ≈ 80 KB ≫ 64 KB budget.
            String wideText = "Sentence with words and spaces. ".repeat(180); // ~5.7 KB
            Map<String, Object> input = new LinkedHashMap<>();
            for (int i = 0; i < 350; i++) {
                input.put("f" + i, wideText);
            }

            ShapingResult result = shaper.shape(input, null, null, Mode.AGENT);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertEquals("oversize", data.get("_shape"));
            assertNotNull(data.get("skeleton"));
            assertEquals(Action.OVERSIZE_FALLBACK, result.action());
        }
    }

    // ========================================================================
    // Pattern aggregation
    // ========================================================================

    @Nested
    @DisplayName("Pattern aggregation")
    class PatternAggregationTests {

        @Test
        @DisplayName("truncatedFieldsAggregated - 10 truncated `items[].about` collapse to ONE entry")
        void truncatedFieldsAggregated() {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Map<String, Object> item = new LinkedHashMap<>();
                // Each `about` field is OVER the 4 KB cap → 10 leaves all
                // canonicalise to `items[].about`.
                int sizePadding = i; // varies the bytes per leaf
                item.put("about", "z".repeat(5000 + sizePadding));
                items.add(item);
            }
            Map<String, Object> input = Map.of("items", items);

            ShapingResult result = shaper.shape(input, null, null, Mode.AGENT);

            assertEquals(1, result.truncatedPatterns().size(),
                    "10 leaves canonicalised to one pattern entry");
            TruncationPattern p = result.truncatedPatterns().get(0);
            assertEquals("items[].about", p.path());
            assertEquals(10, p.count());
            assertEquals(5009, p.bytes(), "max bytes is the largest of the 10");
        }
    }

    // ========================================================================
    // PathPattern util
    // ========================================================================

    @Nested
    @DisplayName("PathPattern")
    class PathPatternTests {

        @Test
        @DisplayName("pathPatternUtilCanonicalisesIndices - items[3].about → items[].about")
        void pathPatternUtilCanonicalisesIndices() {
            assertEquals("items[].about", PathPattern.canonicalize("items[3].about"));
            assertEquals("a[].b[].c", PathPattern.canonicalize("a[12].b[7].c"));
            assertEquals("plain.path", PathPattern.canonicalize("plain.path"));
            assertEquals("", PathPattern.canonicalize(""));
            assertNull(PathPattern.canonicalize(null));
        }
    }

    // ========================================================================
    // Workflow vs Agent mode
    // ========================================================================

    @Nested
    @DisplayName("Workflow vs Agent mode")
    class ModeDiscriminationTests {

        @Test
        @DisplayName("workflowModePreservesArrayShape - no digest, no _shape markers")
        void workflowModePreservesArrayShape() {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("body", "x".repeat(3500));
                items.add(item);
            }
            Map<String, Object> input = Map.of("items", items);

            ShapingResult result = shaper.shape(input, null, null, Mode.WORKFLOW);

            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) result.data();
            assertTrue(root.get("items") instanceof List, "workflow mode keeps the array as List");
            assertEquals(10, ((List<?>) root.get("items")).size());
            // Even if very large, no digest.
            assertNotEquals(Action.ARRAY_DIGESTED, result.action());
            assertNotEquals(Action.OVERSIZE_FALLBACK, result.action());
        }

        @Test
        @DisplayName("workflowModeStillEmitsPatternedTruncatedFields - pattern shape used in both modes")
        void workflowModeStillEmitsPatternedTruncatedFields() {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                items.add(Map.of("about", "z".repeat(5000)));
            }
            Map<String, Object> input = Map.of("items", items);

            ShapingResult result = shaper.shape(input, null, null, Mode.WORKFLOW);

            assertEquals(1, result.truncatedPatterns().size());
            assertEquals("items[].about", result.truncatedPatterns().get(0).path());
            assertEquals(5, result.truncatedPatterns().get(0).count());
        }
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("nullInputReturnsNull")
        void nullInputReturnsNull() {
            ShapingResult result = shaper.shape(null, null, null, Mode.AGENT);
            assertNull(result.data());
            assertEquals(Action.UNTOUCHED, result.action());
            assertFalse(result.hasTruncatedPatterns());
        }

        @Test
        @DisplayName("smallResponseUnchanged - no log, no markers, no patterns")
        void smallResponseUnchanged() {
            Map<String, Object> input = Map.of("a", 1, "b", "hello", "c", List.of(1, 2, 3));
            ShapingResult result = shaper.shape(input, null, null, Mode.AGENT);
            assertEquals(Action.UNTOUCHED, result.action());
            assertEquals(input, result.data());
        }

        @Test
        @DisplayName("primitiveRoot - passed through as-is")
        void primitiveRoot() {
            ShapingResult result = shaper.shape(42, null, null, Mode.AGENT);
            assertEquals(42, result.data());
            assertEquals(Action.UNTOUCHED, result.action());
        }

        @Test
        @DisplayName("deeplyNestedStructure - terminates without overflow")
        void deeplyNestedStructure() {
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("value", "leaf");
            for (int i = 0; i < 60; i++) {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("nested", current);
                current = wrapper;
            }
            ShapingResult result = shaper.shape(current, null, null, Mode.AGENT);
            assertNotNull(result.data());
        }
    }

    // ========================================================================
    // Regression - Apify LinkedIn 147 KB
    // ========================================================================

    @Nested
    @DisplayName("Regression")
    class RegressionTests {

        @Test
        @DisplayName("regressionApifyLinkedIn - 10 profiles × multiple ~3.5 KB cells → digest fires, root preserved")
        void regressionApifyLinkedIn() {
            // Synthetic 10-profile payload mimicking the 2026-05-04 prod incident.
            // Each cell stays UNDER the 4 KB pass-1 cap (so individual strings
            // pass through unclipped - same as real LinkedIn `about`/`experience`
            // when the user has a typical-length profile). Total payload then
            // exceeds 64 KB → pass-2 must digest the root array.
            //
            // Then we add a SINGLE oversize field per profile (above 4 KB) so
            // we also check that pattern aggregation still surfaces a per-leaf
            // truncation pattern alongside the digest.
            List<Map<String, Object>> profiles = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", "Engineer #" + i);
                p.put("headline", "CTO at Company " + i);
                p.put("about", "About paragraph. ".repeat(220));       // ~3.7 KB (under cap)
                p.put("experience", "Worked at place. ".repeat(220));  // ~3.7 KB (under cap)
                p.put("manifesto", "X".repeat(5000));                  // > 4 KB → clipped, surfaces pattern
                profiles.add(p);
            }

            ShapingResult result = shaper.shape(profiles, null, null, Mode.AGENT);

            // Result fits under budget.
            assertTrue(result.shapedBytes() <= ResponseShaper.MAX_TOTAL_RESPONSE_SIZE,
                    "shaped " + result.shapedBytes() + "B exceeds 64 KB budget");
            // Root preserved as List with single digest entry.
            assertTrue(result.data() instanceof List);
            @SuppressWarnings("unchecked")
            List<Object> rootList = (List<Object>) result.data();
            assertEquals(1, rootList.size());
            @SuppressWarnings("unchecked")
            Map<String, Object> digest = (Map<String, Object>) rootList.get(0);
            assertEquals("array_digest", digest.get("_shape"));
            assertEquals(10, digest.get("total_items"));
            // Pattern aggregation surfaces the manifesto leaf truncation.
            assertTrue(result.hasTruncatedPatterns());
            boolean hasManifesto = result.truncatedPatterns().stream()
                    .anyMatch(p -> p.path().endsWith(".manifesto"));
            assertTrue(hasManifesto, "manifesto leaf truncation must surface as pattern");
        }
    }
}
