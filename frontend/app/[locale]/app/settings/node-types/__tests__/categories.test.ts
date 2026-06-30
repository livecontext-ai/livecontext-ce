import { describe, expect, it } from "vitest";
import { nodeTypeCategory, countByCategory, NODE_CATEGORY_ORDER } from "../categories";

/**
 * The backend `category` column is inconsistent free text but carries the
 * meaningful granularity (control_flow vs data_manipulation are BOTH the `core`
 * prefix). So node types are classified by a normalized `category` first, with
 * the structural `variablePrefix` only as a fallback. These tests pin both
 * paths + the messy real values.
 */
describe("nodeTypeCategory", () => {
  it("normalizes the well-named categories into clean buckets", () => {
    expect(nodeTypeCategory({ variablePrefix: "core", category: "Control Flow" })).toBe("control_flow");
    expect(nodeTypeCategory({ variablePrefix: "core", category: "Data Manipulation" })).toBe("data");
    expect(nodeTypeCategory({ variablePrefix: "mcp", category: "Actions" })).toBe("action");
    expect(nodeTypeCategory({ variablePrefix: "agent", category: "AI" })).toBe("ai");
    expect(nodeTypeCategory({ variablePrefix: "trigger", category: "trigger" })).toBe("trigger");
  });

  it("the category wins over the structural prefix (preserves granularity)", () => {
    // core-prefixed nodes must split by category, not collapse into one bucket.
    expect(nodeTypeCategory({ variablePrefix: "core", category: "control_flow" })).toBe("control_flow");
    expect(nodeTypeCategory({ variablePrefix: "core", category: "data_manipulation" })).toBe("data");
    expect(nodeTypeCategory({ variablePrefix: "core", category: "utility" })).toBe("utility");
  });

  it("collapses the messy real categories the admin reported as bad filters", () => {
    expect(nodeTypeCategory({ variablePrefix: null, category: "agent" })).toBe("ai"); // agent → AI
    expect(nodeTypeCategory({ variablePrefix: null, category: "core" })).toBe("utility"); // leftover core
    expect(nodeTypeCategory({ variablePrefix: null, category: "table" })).toBe("data");
    expect(nodeTypeCategory({ variablePrefix: null, category: "node" })).toBe("interface"); // the interface node
  });

  it("falls back to variablePrefix when the category is empty/unknown", () => {
    expect(nodeTypeCategory({ variablePrefix: "trigger", category: "" })).toBe("trigger");
    expect(nodeTypeCategory({ variablePrefix: "mcp", category: "" })).toBe("action");
    expect(nodeTypeCategory({ variablePrefix: "agent", category: "" })).toBe("ai");
    expect(nodeTypeCategory({ variablePrefix: "table", category: "" })).toBe("data");
    expect(nodeTypeCategory({ variablePrefix: "interface", category: "" })).toBe("interface");
    expect(nodeTypeCategory({ variablePrefix: "core", category: "" })).toBe("utility"); // core too coarse → utility
  });

  it("defaults unknown values to utility", () => {
    expect(nodeTypeCategory({ variablePrefix: "weird", category: "totally-unknown" })).toBe("utility");
    expect(nodeTypeCategory({ variablePrefix: "", category: "" })).toBe("utility");
    expect(nodeTypeCategory({ variablePrefix: null as unknown as string, category: null as unknown as string })).toBe("utility");
  });

  it("normalizes casing, trailing colons and whitespace before matching", () => {
    expect(nodeTypeCategory({ variablePrefix: "TRIGGER", category: "" })).toBe("trigger");
    expect(nodeTypeCategory({ variablePrefix: "core:", category: "" })).toBe("utility");
    expect(nodeTypeCategory({ variablePrefix: "", category: "CONTROL_FLOW" })).toBe("control_flow");
    expect(nodeTypeCategory({ variablePrefix: null as unknown as string, category: "Data Manipulation" })).toBe("data");
  });

  it("always resolves to one of the 7 ordered buckets", () => {
    expect(NODE_CATEGORY_ORDER).toContain(nodeTypeCategory({ variablePrefix: "x", category: "x" }));
  });
});

describe("countByCategory", () => {
  it("aggregates counts per clean bucket", () => {
    const counts = countByCategory([
      { variablePrefix: "trigger", category: "trigger" },
      { variablePrefix: "trigger", category: "trigger" },
      { variablePrefix: "agent", category: "ai" },
      { variablePrefix: null, category: "agent" }, // also AI via normalization
      { variablePrefix: "core", category: "Control Flow" },
      { variablePrefix: null, category: "node" }, // interface
    ]);
    expect(counts.trigger).toBe(2);
    expect(counts.ai).toBe(2);
    expect(counts.control_flow).toBe(1);
    expect(counts.interface).toBe(1);
    expect(counts.utility).toBeUndefined();
  });
});
