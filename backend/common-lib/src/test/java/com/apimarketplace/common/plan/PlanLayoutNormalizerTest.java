package com.apimarketplace.common.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Node coordinates must not count as a plan change: the save path used to mint a
 * new version for a two-pixel nudge, and the next execution then keyed its run on
 * that new version and started a SECOND run instead of accumulating an epoch into
 * the live one.
 *
 * <p>The other half of the contract matters just as much: {@code position} is a
 * real third-party tool parameter (ClickUp task position, Notion/Miro block
 * position, playlist position, ...). Stripping it inside a node's params would
 * make "move this task from 3 to 7" look like canvas drift, which skips the new
 * version AND overwrites the current one - silent loss of a real edit.
 */
@DisplayName("PlanLayoutNormalizer")
class PlanLayoutNormalizerTest {

    private static Map<String, Object> coreNode(String id, int x, int y) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", "aggregate");
        n.put("position", new LinkedHashMap<>(Map.of("x", x, "y", y)));
        return n;
    }

    private static Map<String, Object> planWithCoreAt(int x, int y) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", "wf-1");
        p.put("cores", new ArrayList<>(List.of(coreNode("aggregate-1", x, y))));
        return p;
    }

    /** An mcp tool node whose PARAMS carry a tool-level `position` (e.g. ClickUp). */
    private static Map<String, Object> planWithToolPosition(int toolPosition) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "mcp:clickup_update_task");
        node.put("position", new LinkedHashMap<>(Map.of("x", 10, "y", 20)));
        node.put("params", new LinkedHashMap<>(Map.of("task_id", "abc", "position", toolPosition)));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", "wf-1");
        p.put("mcps", new ArrayList<>(List.of(node)));
        return p;
    }

    @Test
    @DisplayName("Two plans differing only by node coordinates normalize to the same content")
    void stripsNodePositions() {
        assertThat(PlanLayoutNormalizer.withoutLayout(planWithCoreAt(288, -29)))
                .isEqualTo(PlanLayoutNormalizer.withoutLayout(planWithCoreAt(290, 0)));
    }

    @Test
    @DisplayName("Strips node layout across ALL SEVEN node families of a plan")
    void stripsEveryNodeList() {
        // Literal list on purpose, mirroring planGeneratorContext's plan shape:
        // deriving it from the implementation would make this test structurally
        // unable to notice a family the implementation forgot - which is exactly
        // how `tables` was missed (workflows with a CRUD node kept minting a
        // version per node move, i.e. the bug this class exists to fix).
        for (String listKey : List.of("triggers", "mcps", "agents", "tables", "cores", "notes", "interfaces")) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put(listKey, List.of(coreNode("n1", 1, 1)));
            Map<String, Object> b = new LinkedHashMap<>();
            b.put(listKey, List.of(coreNode("n1", 99, 99)));

            assertThat(PlanLayoutNormalizer.withoutLayout(a))
                    .as("layout should be stripped from '%s'", listKey)
                    .isEqualTo(PlanLayoutNormalizer.withoutLayout(b));
        }
    }

    @Test
    @DisplayName("Strips the BOX a resize handle writes, on every family that has one")
    void stripsNodeSizes() {
        // Dragging a note corner, an interface preview corner or a data-input
        // corner is canvas work exactly like moving the node - it minted a version
        // per drag while only `position` was stripped.
        record SizeCase(String listKey, String widthKey, String heightKey) {}
        for (SizeCase c : List.of(
                new SizeCase("notes", "width", "height"),
                new SizeCase("interfaces", "previewWidth", "previewHeight"),
                new SizeCase("cores", "dataInputWidth", "dataInputHeight"))) {
            Map<String, Object> small = coreNode("n1", 0, 0);
            small.put(c.widthKey(), 250);
            small.put(c.heightKey(), 100);
            Map<String, Object> large = coreNode("n1", 0, 0);
            large.put(c.widthKey(), 480);
            large.put(c.heightKey(), 320);

            Map<String, Object> a = new LinkedHashMap<>();
            a.put(c.listKey(), List.of(small));
            Map<String, Object> b = new LinkedHashMap<>();
            b.put(c.listKey(), List.of(large));

            assertThat(PlanLayoutNormalizer.withoutLayout(a))
                    .as("resizing in '%s' should not read as a plan change", c.listKey())
                    .isEqualTo(PlanLayoutNormalizer.withoutLayout(b));
        }
    }

    @Test
    @DisplayName("KEEPS a width/height that is a tool PARAM - image size is a real edit")
    void keepsToolParameterNamedWidth() {
        // `width` is one of the most common tool parameters there is (image
        // generation, video render). Only a node entry's OWN field is layout.
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "mcp:generate_image");
        node.put("position", new LinkedHashMap<>(Map.of("x", 10, "y", 20)));
        node.put("params", new LinkedHashMap<>(Map.of("width", 512, "height", 512)));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("mcps", new ArrayList<>(List.of(node)));

        Map<String, Object> normalized = PlanLayoutNormalizer.withoutLayout(plan);

        @SuppressWarnings("unchecked")
        Map<String, Object> outNode = (Map<String, Object>) ((List<Object>) normalized.get("mcps")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) outNode.get("params");
        assertThat(params).containsEntry("width", 512).containsEntry("height", 512);
    }

    @Test
    @DisplayName("Keeps a node-level width that is not a number - only geometry is layout")
    void keepsNonNumericWidth() {
        Map<String, Object> node = coreNode("n1", 0, 0);
        node.put("width", "full");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("cores", new ArrayList<>(List.of(node)));

        @SuppressWarnings("unchecked")
        Map<String, Object> outNode =
                (Map<String, Object>) ((List<Object>) PlanLayoutNormalizer.withoutLayout(plan).get("cores")).get(0);
        assertThat(outNode).containsEntry("width", "full");
    }

    @Test
    @DisplayName("KEEPS a tool parameter named position - it is a real edit, not canvas drift")
    void keepsToolParameterNamedPosition() {
        Map<String, Object> before = PlanLayoutNormalizer.withoutLayout(planWithToolPosition(3));
        Map<String, Object> after = PlanLayoutNormalizer.withoutLayout(planWithToolPosition(7));

        assertThat(before).isNotEqualTo(after);
        assertThat(before.toString()).contains("position=3");
    }

    @Test
    @DisplayName("Strips the node's own coordinates while keeping its params intact")
    void stripsNodeLayoutButKeepsParams() {
        Map<String, Object> normalized = PlanLayoutNormalizer.withoutLayout(planWithToolPosition(3));

        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) ((List<Object>) normalized.get("mcps")).get(0);
        assertThat(node).doesNotContainKey("position");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) node.get("params");
        assertThat(params).containsEntry("position", 3).containsEntry("task_id", "abc");
    }

    @Test
    @DisplayName("Keeps every non-layout field - a real edit still reads as different")
    void keepsSemanticFields() {
        Map<String, Object> a = planWithCoreAt(0, 0);
        Map<String, Object> b = planWithCoreAt(0, 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> bNode = (Map<String, Object>) ((List<Object>) b.get("cores")).get(0);
        bNode.put("label", "renamed");

        assertThat(PlanLayoutNormalizer.withoutLayout(a))
                .isNotEqualTo(PlanLayoutNormalizer.withoutLayout(b));
    }

    @Test
    @DisplayName("Does not mutate the caller's plan - the coordinates stay saved")
    void doesNotMutateInput() {
        Map<String, Object> original = planWithCoreAt(288, -29);

        PlanLayoutNormalizer.withoutLayout(original);

        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) ((List<Object>) original.get("cores")).get(0);
        assertThat(node).containsKey("position");
        assertThat(node.get("position")).isEqualTo(Map.of("x", 288, "y", -29));
    }

    @Test
    @DisplayName("Leaves a top-level key named position alone - only node entries are layout")
    void ignoresNonNodeListKeys() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("position", "top-level-value");
        plan.put("cores", List.of(coreNode("n1", 5, 5)));

        assertThat(PlanLayoutNormalizer.withoutLayout(plan)).containsEntry("position", "top-level-value");
    }

    @Test
    @DisplayName("Keeps a node-level 'position' that is NOT a coordinate pair")
    void keepsNonCoordinatePositionOnANodeEntry() {
        // Shape, not name, decides. A number or a provider-specific object at the
        // entry root is data, not canvas geometry.
        Map<String, Object> numeric = new LinkedHashMap<>();
        numeric.put("mcps", List.of(new LinkedHashMap<>(Map.of("id", "n", "position", 4))));
        assertThat(PlanLayoutNormalizer.withoutLayout(numeric).toString()).contains("position=4");

        Map<String, Object> objectish = new LinkedHashMap<>();
        objectish.put("mcps", List.of(new LinkedHashMap<>(Map.of("id", "n", "position", Map.of("after", "block-7")))));
        assertThat(PlanLayoutNormalizer.withoutLayout(objectish).toString()).contains("after=block-7");
    }

    @Test
    @DisplayName("Treats the plan's reading direction as layout: flipping it re-lays the nodes and changes nothing the workflow does")
    void stripsLayoutDirection() {
        Map<String, Object> horizontal = planWithCoreAt(290, 0);
        horizontal.put("layoutDirection", "horizontal");
        Map<String, Object> vertical = planWithCoreAt(0, 290);
        vertical.put("layoutDirection", "vertical");

        assertThat(PlanLayoutNormalizer.withoutLayout(vertical))
                .doesNotContainKey("layoutDirection")
                .isEqualTo(PlanLayoutNormalizer.withoutLayout(horizontal));
        assertThat(PlanLayoutNormalizer.withoutLayout(planWithCoreAt(290, 0)))
                .isEqualTo(PlanLayoutNormalizer.withoutLayout(horizontal));
        // The caller's plan is only read.
        assertThat(vertical).containsEntry("layoutDirection", "vertical");
    }

    @Test
    @DisplayName("Leaves edges untouched - they carry no layout")
    void leavesEdgesAlone() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("edges", List.of(Map.of("from", "a", "to", "b")));

        assertThat(PlanLayoutNormalizer.withoutLayout(plan))
                .containsEntry("edges", List.of(Map.of("from", "a", "to", "b")));
    }

    @Test
    @DisplayName("Null-safe, and tolerant of an empty plan or a malformed node list")
    void nullSafe() {
        assertThat(PlanLayoutNormalizer.withoutLayout(null)).isNull();
        assertThat(PlanLayoutNormalizer.withoutLayout(new HashMap<>())).isEmpty();

        Map<String, Object> malformed = new LinkedHashMap<>();
        malformed.put("cores", List.of("not-a-node", 42));
        assertThat(PlanLayoutNormalizer.withoutLayout(malformed).get("cores"))
                .isEqualTo(List.of("not-a-node", 42));

        Map<String, Object> notAList = new LinkedHashMap<>();
        notAList.put("cores", "unexpected");
        assertThat(PlanLayoutNormalizer.withoutLayout(notAList)).containsEntry("cores", "unexpected");
    }
}
