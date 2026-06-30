// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import React from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

// Key-echo translator: assertions match raw keys (categories.trigger, ...).
vi.mock("next-intl", () => ({
  useTranslations: () => (key: string) => key,
}));

// Render the Radix Select primitives as a plain native <select> so the options
// are assertable in jsdom (Radix's portal + pointer capture is unreliable here).
vi.mock("@/components/ui/select", () => ({
  Select: ({ value, onValueChange, children }: any) => (
    <select data-testid="cat-select" value={value} onChange={(e) => onValueChange(e.target.value)}>
      {children}
    </select>
  ),
  SelectTrigger: () => null,
  SelectValue: () => null,
  SelectContent: ({ children }: any) => <>{children}</>,
  SelectItem: ({ value, children }: any) => <option value={value}>{children}</option>,
}));

import { NodeTypeCategorySelect } from "../components/NodeTypeCategorySelect";

const COUNTS = { trigger: 8, action: 5, ai: 3, control_flow: 15, data: 4, interface: 1 };
const TOTAL = 36;

afterEach(cleanup);

describe("NodeTypeCategorySelect", () => {
  it("offers an All option with the total and one option per non-empty bucket", () => {
    render(
      <NodeTypeCategorySelect counts={COUNTS} total={TOTAL} selectedCategory={null} onSelectCategory={() => {}} />,
    );
    expect(screen.getByRole("option", { name: "allCategory (36)" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "categories.trigger (8)" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "categories.ai (3)" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "categories.interface (1)" })).toBeInTheDocument();
  });

  it("hides buckets with no node types (utility count 0 → no option)", () => {
    render(
      <NodeTypeCategorySelect counts={COUNTS} total={TOTAL} selectedCategory={null} onSelectCategory={() => {}} />,
    );
    expect(screen.queryByRole("option", { name: /categories\.utility/ })).not.toBeInTheDocument();
  });

  it("emits the selected bucket id, and null for the All sentinel", () => {
    const onSelect = vi.fn();
    render(
      <NodeTypeCategorySelect counts={COUNTS} total={TOTAL} selectedCategory={null} onSelectCategory={onSelect} />,
    );
    const select = screen.getByTestId("cat-select");
    fireEvent.change(select, { target: { value: "ai" } });
    expect(onSelect).toHaveBeenCalledWith("ai");
    fireEvent.change(select, { target: { value: "__all__" } });
    expect(onSelect).toHaveBeenCalledWith(null);
  });
});
