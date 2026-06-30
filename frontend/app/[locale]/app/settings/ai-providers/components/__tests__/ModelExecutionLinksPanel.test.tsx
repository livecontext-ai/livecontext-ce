// @vitest-environment jsdom
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, expect, vi, beforeEach } from "vitest";
import ModelExecutionLinksPanel from "../ModelExecutionLinksPanel";

// t(key) returns the key so assertions can target stable strings.
vi.mock("next-intl", () => ({
  useTranslations: () => (key: string) => key,
}));

// Catalog the dropdowns are built from: anthropic (billed), openrouter (a non-bridge
// execution provider) and codex (a CLI bridge) each serve one model.
vi.mock("@/hooks/useModels", () => ({
  useModels: () => ({
    models: [
      { id: "claude-opus-4-8", name: "Claude Opus 4.8", provider: "anthropic" },
      { id: "claude-sonnet-4-6", name: "Claude Sonnet 4.6", provider: "anthropic" },
      { id: "anthropic/claude-3.5-sonnet", name: "Claude 3.5 Sonnet (OR)", provider: "openrouter" },
      { id: "gpt-5.3-codex", name: "GPT-5.3 Codex", provider: "codex" },
    ],
  }),
}));

const listExecutionLinks = vi.fn();
const saveExecutionLink = vi.fn();
const deleteExecutionLink = vi.fn();

vi.mock("@/lib/api/model-config.service", () => ({
  modelConfigService: {
    listExecutionLinks: () => listExecutionLinks(),
    saveExecutionLink: (l: unknown) => saveExecutionLink(l),
    deleteExecutionLink: (a: string, b: string, c?: string) => deleteExecutionLink(a, b, c),
  },
}));

// [billedProvider, billedModel, executionProvider, executionModel, scope]
const combo = (i: number) => screen.getAllByRole("combobox")[i] as HTMLSelectElement;

describe("ModelExecutionLinksPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listExecutionLinks.mockResolvedValue([]);
    saveExecutionLink.mockResolvedValue({});
    deleteExecutionLink.mockResolvedValue(undefined);
  });

  it("renders existing links returned by the service", async () => {
    listExecutionLinks.mockResolvedValue([
      { id: 1, billedProvider: "anthropic", billedModel: "claude-opus-4-8", executionProvider: "openrouter", executionModel: "anthropic/claude-3.5-sonnet", enabled: true },
    ]);

    render(<ModelExecutionLinksPanel />);

    expect(await screen.findByText("anthropic/claude-opus-4-8")).toBeInTheDocument();
  });

  it("validates required fields before calling the service", async () => {
    render(<ModelExecutionLinksPanel />);
    await waitFor(() => expect(listExecutionLinks).toHaveBeenCalled());

    fireEvent.click(screen.getByText("executionLinks.add"));

    expect(saveExecutionLink).not.toHaveBeenCalled();
    expect(screen.getByText("executionLinks.requiredError")).toBeInTheDocument();
  });

  it("renders the Add button via the themed Button component (visible), never the invalid bg-accent-primary class", async () => {
    // Regression: the Add button used `bg-accent-primary` - not a real Tailwind color,
    // so it painted NO background and the white label was invisible (white-on-white),
    // making "add a link" impossible. It must use the themed Button (default variant =
    // var(--accent-primary) bg + var(--accent-foreground) text).
    render(<ModelExecutionLinksPanel />);
    await waitFor(() => expect(listExecutionLinks).toHaveBeenCalled());

    const addButton = screen.getByText("executionLinks.add").closest("button");
    expect(addButton).not.toBeNull();
    expect(addButton!.className).not.toContain("bg-accent-primary");      // the invalid, invisible class is gone
    expect(addButton!.className).not.toContain("text-white");             // and the white-on-white label is gone
    expect(addButton!.className).toContain("bg-[var(--accent-primary)]"); // themed accent background is applied
    expect(addButton!.className).toContain("text-[var(--accent-foreground)]"); // contrasting label, never white-on-white
  });

  it("saves a link to a NON-bridge execution provider (openrouter) chosen entirely via dropdowns", async () => {
    render(<ModelExecutionLinksPanel />);
    await waitFor(() => expect(listExecutionLinks).toHaveBeenCalled());

    fireEvent.change(combo(0), { target: { value: "anthropic" } });        // billed provider
    fireEvent.change(combo(1), { target: { value: "claude-opus-4-8" } });  // billed model
    fireEvent.change(combo(2), { target: { value: "openrouter" } });       // execution provider (non-bridge)
    fireEvent.change(combo(3), { target: { value: "anthropic/claude-3.5-sonnet" } }); // execution model
    fireEvent.click(screen.getByText("executionLinks.add"));

    await waitFor(() => expect(saveExecutionLink).toHaveBeenCalledTimes(1));
    expect(saveExecutionLink).toHaveBeenCalledWith({
      billedProvider: "anthropic",
      billedModel: "claude-opus-4-8",
      executionProvider: "openrouter",
      executionModel: "anthropic/claude-3.5-sonnet",
      scope: "ALL", // default surface = everywhere
      enabled: true,
    });
  });

  it("saves the chosen surface scope (CHAT) selected via the 'applies to' dropdown", async () => {
    render(<ModelExecutionLinksPanel />);
    await waitFor(() => expect(listExecutionLinks).toHaveBeenCalled());

    fireEvent.change(combo(0), { target: { value: "anthropic" } });
    fireEvent.change(combo(1), { target: { value: "claude-opus-4-8" } });
    fireEvent.change(combo(2), { target: { value: "openrouter" } });
    fireEvent.change(combo(3), { target: { value: "anthropic/claude-3.5-sonnet" } });
    fireEvent.change(combo(4), { target: { value: "CHAT" } }); // applies-to = general chat
    fireEvent.click(screen.getByText("executionLinks.add"));

    await waitFor(() => expect(saveExecutionLink).toHaveBeenCalledTimes(1));
    expect(saveExecutionLink).toHaveBeenCalledWith(expect.objectContaining({ scope: "CHAT" }));
  });

  it("leaves the execution model null when '(same as billed)' is kept", async () => {
    render(<ModelExecutionLinksPanel />);
    await waitFor(() => expect(listExecutionLinks).toHaveBeenCalled());

    fireEvent.change(combo(0), { target: { value: "anthropic" } });
    fireEvent.change(combo(1), { target: { value: "claude-opus-4-8" } });
    fireEvent.change(combo(2), { target: { value: "codex" } }); // a CLI bridge, exec model left blank
    fireEvent.click(screen.getByText("executionLinks.add"));

    await waitFor(() => expect(saveExecutionLink).toHaveBeenCalledTimes(1));
    expect(saveExecutionLink).toHaveBeenCalledWith(expect.objectContaining({
      executionProvider: "codex",
      executionModel: null,
    }));
  });

  it("toggling an enabled link saves it with enabled flipped to false", async () => {
    listExecutionLinks.mockResolvedValue([
      { id: 1, billedProvider: "anthropic", billedModel: "claude-opus-4-8", executionProvider: "openrouter", executionModel: null, enabled: true },
    ]);
    render(<ModelExecutionLinksPanel />);
    await screen.findByText("anthropic/claude-opus-4-8");

    fireEvent.click(screen.getByText("executionLinks.enabled"));

    await waitFor(() => expect(saveExecutionLink).toHaveBeenCalledTimes(1));
    expect(saveExecutionLink).toHaveBeenCalledWith(expect.objectContaining({
      billedProvider: "anthropic",
      billedModel: "claude-opus-4-8",
      enabled: false,
    }));
  });

  it("deletes a link by its billed pair and scope (ALL when unscoped)", async () => {
    listExecutionLinks.mockResolvedValue([
      { id: 1, billedProvider: "anthropic", billedModel: "claude-opus-4-8", executionProvider: "codex", executionModel: null, enabled: true },
    ]);
    render(<ModelExecutionLinksPanel />);
    await screen.findByText("anthropic/claude-opus-4-8");

    fireEvent.click(screen.getByLabelText("executionLinks.delete"));

    await waitFor(() => expect(deleteExecutionLink).toHaveBeenCalledWith("anthropic", "claude-opus-4-8", "ALL"));
  });

  it("deletes a surface-scoped link with its scope and shows the scope badge", async () => {
    listExecutionLinks.mockResolvedValue([
      { id: 1, billedProvider: "anthropic", billedModel: "claude-opus-4-8", executionProvider: "codex", executionModel: null, scope: "WORKFLOW", enabled: true },
    ]);
    render(<ModelExecutionLinksPanel />);
    await screen.findByText("anthropic/claude-opus-4-8");

    // A non-ALL link renders its scope badge (a <span>, distinct from the dropdown <option>).
    expect(screen.getByText("executionLinks.scopeWorkflow", { selector: "span" })).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText("executionLinks.delete"));

    await waitFor(() => expect(deleteExecutionLink).toHaveBeenCalledWith("anthropic", "claude-opus-4-8", "WORKFLOW"));
  });

  it("renders an 'All surfaces' badge for an unscoped (ALL) link", async () => {
    listExecutionLinks.mockResolvedValue([
      { id: 1, billedProvider: "anthropic", billedModel: "claude-opus-4-8", executionProvider: "openrouter", executionModel: null, scope: "ALL", enabled: true },
    ]);
    render(<ModelExecutionLinksPanel />);
    await screen.findByText("anthropic/claude-opus-4-8");

    expect(screen.getByText("executionLinks.scopeAll", { selector: "span" })).toBeInTheDocument();
  });

  it("the 'Applies to' picker hides a surface already linked for the selected billed pair", async () => {
    // anthropic/claude-opus-4-8 already has a CHAT link, so CHAT must not be offered again.
    listExecutionLinks.mockResolvedValue([
      { id: 1, billedProvider: "anthropic", billedModel: "claude-opus-4-8", executionProvider: "codex", executionModel: null, scope: "CHAT", enabled: true },
    ]);
    render(<ModelExecutionLinksPanel />);
    await screen.findByText("anthropic/claude-opus-4-8");

    fireEvent.change(combo(0), { target: { value: "anthropic" } });
    fireEvent.change(combo(1), { target: { value: "claude-opus-4-8" } });

    const scopeOptions = Array.from(combo(4).options).map((o) => o.value);
    expect(scopeOptions).not.toContain("CHAT"); // already used for this pair
    expect(scopeOptions).toContain("ALL");
    expect(scopeOptions).toContain("WORKFLOW");
  });

  it("disables Add and shows a hint when every surface is already linked for the pair", async () => {
    const scopes = ["ALL", "CHAT", "WORKFLOW", "WEBHOOK", "WIDGET", "SCHEDULE", "TASK", "TASK_REVIEW"];
    listExecutionLinks.mockResolvedValue(
      scopes.map((scope, i) => ({
        id: i + 1,
        billedProvider: "anthropic",
        billedModel: "claude-opus-4-8",
        executionProvider: "codex",
        executionModel: null,
        scope,
        enabled: true,
      })),
    );
    render(<ModelExecutionLinksPanel />);
    await waitFor(() => expect(listExecutionLinks).toHaveBeenCalled());

    fireEvent.change(combo(0), { target: { value: "anthropic" } });
    fireEvent.change(combo(1), { target: { value: "claude-opus-4-8" } });

    expect(screen.getByText("executionLinks.allSurfacesUsed")).toBeInTheDocument();
    expect(screen.getByText("executionLinks.add").closest("button")).toBeDisabled();
  });

  it("snaps the scope off a now-taken surface when switching pairs, and Add posts the snapped (valid) scope", async () => {
    // claude-sonnet-4-6 already has a CHAT link; claude-opus-4-8 has none.
    listExecutionLinks.mockResolvedValue([
      { id: 1, billedProvider: "anthropic", billedModel: "claude-sonnet-4-6", executionProvider: "codex", executionModel: null, scope: "CHAT", enabled: true },
    ]);
    render(<ModelExecutionLinksPanel />);
    await waitFor(() => expect(listExecutionLinks).toHaveBeenCalled());

    // On a pair with no links, every surface is available: pick CHAT.
    fireEvent.change(combo(0), { target: { value: "anthropic" } });
    fireEvent.change(combo(1), { target: { value: "claude-opus-4-8" } });
    fireEvent.change(combo(4), { target: { value: "CHAT" } });
    expect(combo(4).value).toBe("CHAT");

    // Switch the model (same provider) to the one that already has a CHAT link:
    // CHAT is now taken, so the scope must snap to an available surface.
    fireEvent.change(combo(1), { target: { value: "claude-sonnet-4-6" } });
    await waitFor(() => expect(combo(4).value).not.toBe("CHAT"));

    // Add must post the SNAPPED scope, never the stale CHAT that would clobber the existing link.
    fireEvent.change(combo(2), { target: { value: "openrouter" } });
    fireEvent.click(screen.getByText("executionLinks.add"));
    await waitFor(() => expect(saveExecutionLink).toHaveBeenCalledTimes(1));
    const posted = saveExecutionLink.mock.calls[0][0];
    expect(posted.scope).not.toBe("CHAT");
    expect(posted.billedModel).toBe("claude-sonnet-4-6");
  });
});
