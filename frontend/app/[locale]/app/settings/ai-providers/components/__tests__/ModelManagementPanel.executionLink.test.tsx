// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react';

/**
 * One-click execution link from the admin Models panel: a billed model whose provider
 * has a CLI that routes it (`cliBridgeProvider`, stamped by the backend) gets a button
 * that routes it to that CLI on EVERY surface in a single click, and once routed the
 * badge opens a popover over the surfaces.
 *
 * The behaviour these pin, beyond the payloads: the popover must mirror how the backend
 * RESOLVES a route (exact surface first, else the ALL row), so while ALL is on the other
 * surfaces are shown as covered by it and are NOT switches - there is no write that
 * turns one surface off, and offering one would be a no-op an admin reads as a setting.
 * Plus the warnings that decide whether the click is a good idea at all (CLI missing
 * from the bridge host, ALL also covering calls a CLI cannot serve), and that a failing
 * links endpoint never costs the admin the model list.
 */

const mocks = vi.hoisted(() => ({
  getEffectiveModels: vi.fn(),
  saveOverride: vi.fn(),
  setCategoryEnabled: vi.fn(),
  bulkUpdateRankings: vi.fn(),
  deleteOverride: vi.fn(),
  resetAll: vi.fn(),
  clearModelsCache: vi.fn(),
  listExecutionLinks: vi.fn(),
  saveExecutionLink: vi.fn(),
  deleteExecutionLink: vi.fn(),
}));

vi.mock('@/lib/api/model-config.service', () => ({
  modelConfigService: {
    getEffectiveModels: mocks.getEffectiveModels,
    saveOverride: mocks.saveOverride,
    setCategoryEnabled: mocks.setCategoryEnabled,
    bulkUpdateRankings: mocks.bulkUpdateRankings,
    deleteOverride: mocks.deleteOverride,
    resetAll: mocks.resetAll,
    listExecutionLinks: mocks.listExecutionLinks,
    saveExecutionLink: mocks.saveExecutionLink,
    deleteExecutionLink: mocks.deleteExecutionLink,
  },
}));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: mocks.clearModelsCache }));
vi.mock('../AddModelDialog', () => ({ default: () => null }));
// Values are serialised into the rendered string so a test can assert WHICH target and
// WHICH surfaces a message names, not merely that some message was rendered.
vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (k: string, values?: Record<string, unknown>) => {
    const key = ns ? `${ns}.${k}` : k;
    return values ? `${key} ${JSON.stringify(values)}` : key;
  },
}));
// The control is cloud-only, so the edition has to be steerable per test.
const editionState = vi.hoisted(() => ({
  EDITION: 'cloud', IS_CE: false, IS_CLOUD: true, IS_MANAGED_CLOUD: true,
}));
vi.mock('@/lib/edition/edition', () => editionState);

import ModelManagementPanel from '../ModelManagementPanel';

const t = (k: string) => k;
const KEY = 'aiProviders.executionLinks';

function buildModel(over: Record<string, unknown> = {}) {
  return {
    id: 'claude-opus-4-7',
    name: 'Claude Opus 4.7',
    provider: 'anthropic',
    displayOrder: 1,
    enabled: true,
    tier: 'top',
    providerKind: 'cloud' as const,
    cliBridgeProvider: 'claude-code',
    ...over,
  };
}

function link(over: Record<string, unknown> = {}) {
  return {
    billedProvider: 'anthropic',
    billedModel: 'claude-opus-4-7',
    executionProvider: 'claude-code',
    executionModel: 'claude-opus-4-7',
    scope: 'ALL',
    enabled: true,
    ...over,
  };
}

/** The row control, whichever state it is in (dashed button or routed badge). */
const control = (provider = 'anthropic', id = 'claude-opus-4-7') =>
  screen.queryByTestId(`model-exec-link-${provider}-${id}`);

const surfaceRow = (labelKey: string) =>
  screen.getByText(`${KEY}.${labelKey}`).closest('button, div') as HTMLElement;

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  editionState.IS_CE = false;
  editionState.IS_CLOUD = true;
});

describe('ModelManagementPanel - per-model execution link', () => {
  it('offers the one-click CLI button only when the backend stamped a counterpart', async () => {
    mocks.getEffectiveModels.mockResolvedValue([
      buildModel(),
      // Same provider family, but the Codex CLI cannot route it: the backend leaves it
      // unstamped and the row must offer nothing.
      buildModel({ id: 'gpt-5.6', name: 'GPT-5.6', provider: 'openai', cliBridgeProvider: undefined }),
    ]);
    mocks.listExecutionLinks.mockResolvedValue([]);

    render(<ModelManagementPanel t={t} />);

    await waitFor(() => expect(control()).toBeInTheDocument());
    expect(control()).toHaveAttribute('title', expect.stringContaining(`${KEY}.linkToCliHint`));
    expect(control('openai', 'gpt-5.6')).not.toBeInTheDocument();
  });

  it('one click routes the model to its CLI on every surface, then re-reads the links', async () => {
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([]);
    mocks.saveExecutionLink.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);

    await waitFor(() => expect(mocks.saveExecutionLink).toHaveBeenCalledTimes(1));
    expect(mocks.saveExecutionLink).toHaveBeenCalledWith({
      billedProvider: 'anthropic',
      billedModel: 'claude-opus-4-7',
      executionProvider: 'claude-code',
      executionModel: 'claude-opus-4-7',
      scope: 'ALL',
      enabled: true,
    });
    // Re-read so the badge reflects the server, not an optimistic guess.
    await waitFor(() => expect(mocks.listExecutionLinks).toHaveBeenCalledTimes(2));
  });

  it('shows the surfaces ALL covers as covered, not as switches that could turn them off', async () => {
    // The backend resolves exact-surface-first, else ALL. With ALL on, ticking a
    // surface writes a row that changes nothing, and unticking cannot switch that
    // surface off, so rendering eight checkboxes would state a control that does not
    // exist.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([link()]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);

    expect(screen.getByRole('checkbox', { name: new RegExp(`${KEY}\\.scopeAll`) }))
      .toHaveAttribute('aria-checked', 'true');
    // The other seven read as routed but not operable, and say why.
    const chat = screen.getByRole('checkbox', { name: new RegExp(`${KEY}\\.scopeChat`) });
    expect(chat).toHaveAttribute('aria-checked', 'true');
    expect(chat).toHaveAttribute('aria-disabled', 'true');
    expect(surfaceRow('scopeChat')).toHaveTextContent(`${KEY}.coveredByAll`);
    expect(screen.getByText(`${KEY}.perSurfaceHint`)).toBeInTheDocument();

    fireEvent.click(surfaceRow('scopeWorkflow'));
    expect(mocks.saveExecutionLink).not.toHaveBeenCalled();
    expect(mocks.deleteExecutionLink).not.toHaveBeenCalled();
  });

  it('routes surfaces one by one once ALL is off', async () => {
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([link({ scope: 'CHAT' })]);
    mocks.saveExecutionLink.mockResolvedValue({});
    mocks.deleteExecutionLink.mockResolvedValue(undefined);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);

    fireEvent.click(screen.getByRole('checkbox', { name: new RegExp(`${KEY}\\.scopeWorkflow`) }));
    await waitFor(() => expect(mocks.saveExecutionLink).toHaveBeenCalledTimes(1));
    expect(mocks.saveExecutionLink).toHaveBeenCalledWith({
      billedProvider: 'anthropic',
      billedModel: 'claude-opus-4-7',
      executionProvider: 'claude-code',
      executionModel: 'claude-opus-4-7',
      scope: 'WORKFLOW',
      enabled: true,
    });

    fireEvent.click(screen.getByRole('checkbox', { name: new RegExp(`${KEY}\\.scopeChat`) }));
    await waitFor(() => expect(mocks.deleteExecutionLink).toHaveBeenCalledTimes(1));
    expect(mocks.deleteExecutionLink).toHaveBeenCalledWith('anthropic', 'claude-opus-4-7', 'CHAT');
  });

  it('extends the target the model is ALREADY routed to, not the CLI counterpart', async () => {
    // Routed to OpenRouter by hand in the Execution links tab: adding a surface here
    // must keep that target (and its own execution model), or one model would run on
    // two different providers depending on the surface.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([
      link({ scope: 'CHAT', executionProvider: 'openrouter', executionModel: 'anthropic/claude-opus-4-7' }),
    ]);
    mocks.saveExecutionLink.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);
    fireEvent.click(screen.getByRole('checkbox', { name: new RegExp(`${KEY}\\.scopeWorkflow`) }));

    await waitFor(() => expect(mocks.saveExecutionLink).toHaveBeenCalledTimes(1));
    expect(mocks.saveExecutionLink).toHaveBeenCalledWith(
      expect.objectContaining({
        executionProvider: 'openrouter',
        executionModel: 'anthropic/claude-opus-4-7',
        scope: 'WORKFLOW',
      }),
    );
  });

  it('names the ALL row target on the badge, and only the surfaces that run on it', async () => {
    // Two traps in one fixture: links[0] would hand the badge to the CHAT row (the
    // list order is a backend ORDER BY on the scope name), and joining every scope
    // would claim Chat runs on Claude Code when it runs on OpenRouter.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([
      link({ scope: 'CHAT', executionProvider: 'openrouter' }),
      link({ scope: 'ALL' }),
    ]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());

    const title = control()!.getAttribute('title')!;
    expect(title).toContain('Claude Code');
    expect(title).not.toContain('OpenRouter');
    // Chat is overridden onto another target, so the tooltip names the target only
    // rather than claiming a surface list the ALL row no longer honours.
    expect(title).toContain(`${KEY}.routedViaTitle`);
    expect(title).not.toContain(`${KEY}.scopeChat`);
  });

  it('reads a DISABLED routing as off, and a click re-enables it instead of deleting it', async () => {
    // Disabling is a first-class state on the Execution links tab. Showing it as routed
    // would be a lie, and deleting the row on the obvious "turn it on" gesture would
    // destroy the target the admin configured there.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([link({ enabled: false })]);
    mocks.saveExecutionLink.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    expect(control()).toHaveAttribute('title', expect.stringContaining(`${KEY}.routedViaDisabled`));
    fireEvent.click(control()!);

    const allRow = screen.getByRole('checkbox', { name: new RegExp(`${KEY}\\.scopeAll`) });
    expect(allRow).toHaveAttribute('aria-checked', 'false');
    // With ALL off the other surfaces are switches again, and none is covered.
    expect(screen.getByRole('checkbox', { name: new RegExp(`${KEY}\\.scopeChat`) })).toBeInTheDocument();

    fireEvent.click(allRow);
    await waitFor(() => expect(mocks.saveExecutionLink).toHaveBeenCalledTimes(1));
    expect(mocks.saveExecutionLink).toHaveBeenCalledWith(
      expect.objectContaining({ scope: 'ALL', executionProvider: 'claude-code', enabled: true }),
    );
    expect(mocks.deleteExecutionLink).not.toHaveBeenCalled();
  });

  it('renders nothing and asks the backend for nothing on a self-hosted install', async () => {
    // CE does not load the execution-link controller, so the control would offer a
    // write that 404s. The edition gate is the only thing keeping it out.
    editionState.IS_CE = true;
    editionState.IS_CLOUD = false;
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([]);

    render(<ModelManagementPanel t={t} />);

    await waitFor(() => expect(screen.getByText('Claude Opus 4.7')).toBeInTheDocument());
    expect(control()).not.toBeInTheDocument();
    expect(mocks.listExecutionLinks).not.toHaveBeenCalled();
  });

  it('warns BEFORE the click when the target CLI cannot run on the bridge host', async () => {
    // An unusable CLI (absent, or present but logged out) is not a no-op: only an
    // unwired bridge transport falls back to the billed provider, so this link would
    // fail every run of the model.
    mocks.getEffectiveModels.mockResolvedValue([buildModel({ cliBridgeAvailable: false })]);
    mocks.listExecutionLinks.mockResolvedValue([]);

    render(<ModelManagementPanel t={t} />);

    await waitFor(() => expect(control()).toBeInTheDocument());
    expect(control()).toHaveAttribute('title', expect.stringContaining(`${KEY}.cliNotAvailable`));
  });

  it('does not blame the CLI when the model is routed somewhere else entirely', async () => {
    // cliBridgeAvailable describes the model's CLI counterpart. A model routed to
    // OpenRouter runs fine whatever the CLI's state, so the warning must stay quiet.
    mocks.getEffectiveModels.mockResolvedValue([buildModel({ cliBridgeAvailable: false })]);
    mocks.listExecutionLinks.mockResolvedValue([link({ executionProvider: 'openrouter' })]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());

    expect(control()).toHaveAttribute('title', expect.stringContaining(`${KEY}.routedVia`));
    expect(control()!.getAttribute('title')).not.toContain(`${KEY}.cliNotAvailable`);
  });

  it('warns that ALL also covers the calls a CLI cannot serve, and only for a CLI target', async () => {
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([link()]);

    const { unmount } = render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);
    expect(screen.getByText(new RegExp(`${KEY}\\.allScopeCliCaveat`))).toBeInTheDocument();
    unmount();

    // An API provider serves a single completion perfectly well: no caveat.
    mocks.listExecutionLinks.mockResolvedValue([link({ executionProvider: 'openrouter' })]);
    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);
    expect(screen.queryByText(new RegExp(`${KEY}\\.allScopeCliCaveat`))).toBeNull();
  });

  it('remove routing deletes every surface of that model', async () => {
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([link(), link({ scope: 'WORKFLOW' })]);
    mocks.deleteExecutionLink.mockResolvedValue(undefined);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);
    fireEvent.click(screen.getByText(`${KEY}.removeRouting`));

    await waitFor(() => expect(mocks.deleteExecutionLink).toHaveBeenCalledTimes(2));
    expect(mocks.deleteExecutionLink).toHaveBeenNthCalledWith(1, 'anthropic', 'claude-opus-4-7', 'ALL');
    expect(mocks.deleteExecutionLink).toHaveBeenNthCalledWith(2, 'anthropic', 'claude-opus-4-7', 'WORKFLOW');
  });

  it('re-reads the links when a multi-row removal fails halfway', async () => {
    // The first delete lands, the second does not. Without a re-read the popover would
    // keep listing a row the server no longer has.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([link(), link({ scope: 'WORKFLOW' })]);
    mocks.deleteExecutionLink
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error('boom'));
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);
    fireEvent.click(screen.getByText(`${KEY}.removeRouting`));

    await waitFor(() => expect(screen.getByText(`${KEY}.writeError`)).toBeInTheDocument());
    await waitFor(() => expect(mocks.listExecutionLinks).toHaveBeenCalledTimes(2));
    consoleError.mockRestore();
  });

  it('surfaces a failed write, naming the link update, then clears it once a write succeeds', async () => {
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([]);
    mocks.saveExecutionLink.mockRejectedValueOnce(new Error('boom')).mockResolvedValue({});
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);
    await waitFor(() => expect(screen.getByText(`${KEY}.writeError`)).toBeInTheDocument());
    // The button is released, so the admin can retry without reloading the page.
    await waitFor(() => expect(control()).not.toBeDisabled());

    fireEvent.click(control()!);
    // A stale "save failed" next to a routing that DID save reads as data loss.
    await waitFor(() => expect(screen.queryByText(`${KEY}.writeError`)).not.toBeInTheDocument());
    consoleError.mockRestore();
  });

  it('closes the surface popover on Escape and on an outside click', async () => {
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([link()]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());

    fireEvent.click(control()!);
    expect(screen.getByText(new RegExp(`${KEY}\\.routedViaTitle`))).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() =>
      expect(screen.queryByText(new RegExp(`${KEY}\\.routedViaTitle`))).toBeNull());

    fireEvent.click(control()!);
    expect(screen.getByText(new RegExp(`${KEY}\\.routedViaTitle`))).toBeInTheDocument();
    fireEvent.mouseDown(document.body);
    await waitFor(() =>
      expect(screen.queryByText(new RegExp(`${KEY}\\.routedViaTitle`))).toBeNull());
  });

  it('counts the surfaces once they are routed one by one', async () => {
    // Two explicit surfaces, no wildcard: here the number says something the icon alone
    // cannot. (With ALL on it would only count rows, which is why it is hidden then.)
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([
      link({ scope: 'CHAT' }),
      link({ scope: 'WORKFLOW' }),
    ]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    expect(control()).toHaveTextContent('2');
  });

  it('shows the routing of a model that has no CLI counterpart at all', async () => {
    // Routed to OpenRouter from the Execution links tab. The Models grid is where an
    // admin looks for "is this model routed"; answering only for CLI-capable models
    // would make the badge unreliable as a source of truth.
    mocks.getEffectiveModels.mockResolvedValue([
      buildModel({ id: 'gpt-4o', name: 'GPT-4o', provider: 'openai', cliBridgeProvider: undefined }),
    ]);
    mocks.listExecutionLinks.mockResolvedValue([
      link({ billedProvider: 'openai', billedModel: 'gpt-4o', executionProvider: 'openrouter' }),
    ]);

    render(<ModelManagementPanel t={t} />);

    await waitFor(() => expect(control('openai', 'gpt-4o')).toBeInTheDocument());
    expect(control('openai', 'gpt-4o')!.getAttribute('title')).toContain('OpenRouter');
  });

  it('keeps each model on its own links', async () => {
    mocks.getEffectiveModels.mockResolvedValue([
      buildModel(),
      buildModel({ id: 'claude-sonnet-4-6', name: 'Claude Sonnet 4.6', displayOrder: 2 }),
    ]);
    mocks.listExecutionLinks.mockResolvedValue([link()]);

    render(<ModelManagementPanel t={t} />);

    await waitFor(() => expect(control()).toBeInTheDocument());
    expect(control()).toHaveAttribute('title', expect.stringContaining(`${KEY}.routedVia`));
    // The unrouted sibling must still offer the create button, not inherit the badge.
    expect(control('anthropic', 'claude-sonnet-4-6'))
      .toHaveAttribute('title', expect.stringContaining(`${KEY}.linkToCliHint`));
  });

  it('offers no routing control at all while the link list is unknown', async () => {
    // The read failed, so nothing is known about this model's routing. Falling back to
    // the create button would be a lie AND a hazard: its PUT upserts on (pair, scope),
    // so one click would overwrite an existing ALL link - its target, its execution
    // model, its enabled flag - that the admin never saw. The list still renders, and
    // the failure is surfaced instead of being swallowed.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockRejectedValue(new Error('boom'));
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

    render(<ModelManagementPanel t={t} />);

    await waitFor(() => expect(screen.getByText('Claude Opus 4.7')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByText('executionLinks.loadError')).toBeInTheDocument());
    expect(control()).not.toBeInTheDocument();
    expect(mocks.saveExecutionLink).not.toHaveBeenCalled();
    consoleError.mockRestore();
  });

  it('keeps an unrelated error on screen when a link write succeeds', async () => {
    // Routing reports on its own line, so a successful routing cannot erase a failure
    // that belongs to another control.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([]);
    mocks.saveOverride.mockRejectedValue(new Error('tier save failed'));
    mocks.saveExecutionLink.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());

    // Fail an unrelated write first: the star toggle goes through saveOverride.
    fireEvent.click(screen.getByTitle('modelConfig.recommended'));
    await waitFor(() => expect(screen.getByText('modelConfig.saveError')).toBeInTheDocument());

    fireEvent.click(control()!);
    await waitFor(() => expect(mocks.saveExecutionLink).toHaveBeenCalledTimes(1));
    expect(screen.getByText('modelConfig.saveError')).toBeInTheDocument();
  });

  it('keeps a link error on screen when an unrelated write SUCCEEDS', async () => {
    // The symmetric case, and the one a shared slot could not survive: an unrelated
    // success refreshes the model list, which clears that slot, so "the routing failed
    // to save" used to vanish on a tier save the admin did next.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([]);
    mocks.saveExecutionLink.mockRejectedValue(new Error('boom'));
    mocks.saveOverride.mockResolvedValue({});
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);
    await waitFor(() => expect(screen.getByText(`${KEY}.writeError`)).toBeInTheDocument());

    fireEvent.click(screen.getByTitle('modelConfig.recommended'));
    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledTimes(1));
    expect(screen.getByText(`${KEY}.writeError`)).toBeInTheDocument();
    consoleError.mockRestore();
  });
  it('spells out a mixed setup: the execution model, a surface on another target, a disabled row', async () => {
    // ALL is off, so the surfaces are switches AND the popover has to say what each one
    // actually does. Rendering three surfaces identically here would hide the two facts
    // an admin needs before touching anything.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([
      link({ scope: 'WORKFLOW' }),
      link({ scope: 'CHAT', executionProvider: 'openrouter', executionModel: 'anthropic/claude-opus-4-7' }),
      link({ scope: 'TASK', enabled: false }),
    ]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);

    // The execution model the routing runs on, which can differ from the billed id. The row
    // itself now prints the billed id under the name whenever a model has been renamed, and
    // this fixture renames it, so the match has to EXCLUDE that line rather than accept any
    // element carrying the text: an earlier version of this assertion was a disjunction that
    // passed on the id line alone.
    const matches = screen.getAllByText('claude-opus-4-7')
      .filter((el) => !el.dataset.testid?.startsWith('model-id-'));
    expect(matches).toHaveLength(1);
    // A surface routed somewhere else names that target instead of pretending to match.
    expect(surfaceRow('scopeChat')).toHaveTextContent('OpenRouter');
    // A row that exists but is off reads as off, with its own label.
    expect(surfaceRow('scopeTask')).toHaveTextContent(`${KEY}.disabled`);
    // Matched through the row helper, not by role name: the accessible name of the
    // TASK row also carries its "disabled" chip, and a bare scopeTask regex would
    // match scopeTaskReview as well.
    expect(surfaceRow('scopeTask')).toHaveAttribute('aria-checked', 'false');
    // ...and nothing is claimed to be covered, because ALL routes nothing here.
    expect(screen.queryByText(`${KEY}.coveredByAll`)).toBeNull();
    expect(screen.queryByText(`${KEY}.perSurfaceHint`)).toBeNull();
  });

  it('stays quiet when the CLI is available, and when its state is unknown', async () => {
    for (const cliBridgeAvailable of [true, null]) {
      mocks.getEffectiveModels.mockResolvedValue([buildModel({ cliBridgeAvailable })]);
      mocks.listExecutionLinks.mockResolvedValue([]);
      const view = render(<ModelManagementPanel t={t} />);
      await waitFor(() => expect(control()).toBeInTheDocument());
      // Unknown (bridge unreachable) must not accuse a CLI of being unusable either.
      expect(control()!.getAttribute('title')).toContain(`${KEY}.linkToCliHint`);
      expect(control()!.getAttribute('title')).not.toContain(`${KEY}.cliNotAvailable`);
      view.unmount();
    }
  });

  it('does not warn about an unusable CLI while the routing to it is switched off', async () => {
    // Nothing runs through a disabled link, so there is nothing for the CLI's state to
    // break yet; shouting about it would train the admin to ignore the amber badge.
    mocks.getEffectiveModels.mockResolvedValue([buildModel({ cliBridgeAvailable: false })]);
    mocks.listExecutionLinks.mockResolvedValue([link({ enabled: false })]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());

    expect(control()!.getAttribute('title')).toContain(`${KEY}.routedViaDisabled`);
    expect(control()!.getAttribute('title')).not.toContain(`${KEY}.cliNotAvailable`);
  });
  it('warns about an unusable CLI even when it is only ONE surface override', async () => {
    // The badge's primary target is OpenRouter here, but Chat runs go to a CLI that
    // cannot run. Keying the warning on the primary target alone leaves this green
    // while every chat run of the model fails.
    mocks.getEffectiveModels.mockResolvedValue([buildModel({ cliBridgeAvailable: false })]);
    mocks.listExecutionLinks.mockResolvedValue([
      link({ scope: 'ALL', executionProvider: 'openrouter' }),
      link({ scope: 'CHAT', executionProvider: 'claude-code' }),
    ]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());

    const title = control()!.getAttribute('title')!;
    expect(title).toContain(`${KEY}.cliNotAvailable`);
    // It must name the CLI, not the badge's primary target: OpenRouter is healthy here,
    // and accusing it would send the admin after the wrong thing.
    expect(title).toContain('Claude Code');
    expect(title).not.toContain('OpenRouter');
  });

  it('counts and lists only the surfaces that run on the badge target', async () => {
    // A model split across two targets must not advertise the other target's surface
    // under this icon, in the count or in the tooltip.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([
      link({ scope: 'ALL' }),
      link({ scope: 'CHAT', executionProvider: 'openrouter' }),
    ]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());

    // One surface runs on Claude Code, so no count chip.
    expect(control()!.textContent).not.toMatch(/\d/);
    const title = control()!.getAttribute('title')!;
    expect(title).not.toContain(`${KEY}.scopeChat`);
    // And it must not claim "All surfaces" either: Chat is overridden onto OpenRouter,
    // so the tooltip drops the surface list and names the target only.
    expect(title).toContain(`${KEY}.routedViaTitle`);
    expect(title).not.toContain(`${KEY}.scopeAll`);
  });
  it('keeps the surface list when a single target serves every routed surface', async () => {
    // The mixed-target case drops the list; this is the case that must NOT, or the
    // badge would stop saying anything useful for the common setup.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([link({ scope: 'ALL' })]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());

    const title = control()!.getAttribute('title')!;
    expect(title).toContain(`${KEY}.routedVia`);
    expect(title).toContain(`${KEY}.scopeAll`);
    expect(title).toContain('Claude Code');
  });
  it('turning ALL off disables the wildcard, keeping the badge and the picker reachable', async () => {
    // Deleting it would drop the model's only link, so the badge would fall back to the
    // create button and the surface picker this click exists to unlock would be gone.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([link({ scope: 'ALL' })]);
    mocks.saveExecutionLink.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);
    fireEvent.click(screen.getByRole('checkbox', { name: new RegExp(`${KEY}\\.scopeAll`) }));

    await waitFor(() => expect(mocks.saveExecutionLink).toHaveBeenCalledTimes(1));
    expect(mocks.saveExecutionLink).toHaveBeenCalledWith(
      expect.objectContaining({ scope: 'ALL', executionProvider: 'claude-code', enabled: false }),
    );
    expect(mocks.deleteExecutionLink).not.toHaveBeenCalled();
  });

  it('switching a surface override off removes it, rather than parking a disabled row', async () => {
    // The asymmetry with ALL is deliberate: a leftover disabled row would still occupy
    // that scope's slot in the Execution links tab, where the pair is unique per scope.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([link({ scope: 'CHAT' })]);
    mocks.deleteExecutionLink.mockResolvedValue(undefined);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);
    fireEvent.click(screen.getByRole('checkbox', { name: new RegExp(`${KEY}\\.scopeChat`) }));

    await waitFor(() => expect(mocks.deleteExecutionLink).toHaveBeenCalledTimes(1));
    expect(mocks.deleteExecutionLink).toHaveBeenCalledWith('anthropic', 'claude-opus-4-7', 'CHAT');
    expect(mocks.saveExecutionLink).not.toHaveBeenCalled();
  });

  it('names the execution model of a row that RUNS, not of a disabled one', async () => {
    // Both rows share the target, and the disabled one sorts first. Naming its model
    // would describe something nothing uses, and a surface added from here inherits
    // this same row's model.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([
      link({ scope: 'ALL', executionModel: 'dead-model', enabled: false }),
      link({ scope: 'CHAT', executionModel: 'live-model' }),
    ]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);

    expect(screen.getByText('live-model')).toBeInTheDocument();
    expect(screen.queryByText('dead-model')).toBeNull();
  });

  it('remove routing closes the popover once the rows are gone', async () => {
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([link()]);
    mocks.deleteExecutionLink.mockResolvedValue(undefined);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);
    fireEvent.click(screen.getByText(`${KEY}.removeRouting`));

    await waitFor(() =>
      expect(screen.queryByText(new RegExp(`${KEY}\\.routedViaTitle`))).toBeNull());
  });
  it('tells the admin what the button does even while warning about the CLI', async () => {
    // The warning used to REPLACE the only text that explains the button, so an unlinked
    // model showed advice about removing a routing that does not exist yet.
    mocks.getEffectiveModels.mockResolvedValue([buildModel({ cliBridgeAvailable: false })]);
    mocks.listExecutionLinks.mockResolvedValue([]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());

    const title = control()!.getAttribute('title')!;
    expect(title).toContain(`${KEY}.linkToCliHint`);
    expect(title).toContain(`${KEY}.cliNotAvailable`);
  });

  it('drops the count chip while ALL routes everything, and calls a same-target row covered', async () => {
    // ALL plus a redundant same-target surface row is ONE routing, not two, and the
    // popover must not render that row as if it added something.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([link({ scope: 'ALL' }), link({ scope: 'WORKFLOW' })]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    expect(control()!.textContent).not.toMatch(/\d/);

    fireEvent.click(control()!);
    expect(surfaceRow('scopeWorkflow')).toHaveTextContent(`${KEY}.coveredByAll`);
  });
  it('closes the popover when the last surface is switched off', async () => {
    // Otherwise the popover keeps describing a routing that no longer exists: still
    // headed "Executed via Claude Code" for a model with zero links, every row unrouted,
    // and a Remove routing button with nothing left to remove.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValueOnce([link({ scope: 'CHAT' })]).mockResolvedValue([]);
    mocks.deleteExecutionLink.mockResolvedValue(undefined);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);
    expect(screen.getByText(new RegExp(`${KEY}\\.routedViaTitle`))).toBeInTheDocument();

    fireEvent.click(screen.getByRole('checkbox', { name: new RegExp(`${KEY}\.scopeChat`) }));

    await waitFor(() =>
      expect(screen.queryByText(new RegExp(`${KEY}\\.routedViaTitle`))).toBeNull());
    // The row falls back to the create button, which is now the truthful state.
    await waitFor(() =>
      expect(control()!.getAttribute('title')).toContain(`${KEY}.linkToCliHint`));
  });
  it('creating the wildcard from a surface-only setup keeps that target and model', async () => {
    // The one ALL-row write with no existing row: it falls through to the create branch,
    // where a wrong provider or scope would ship green because every other ALL test has
    // a row to re-enable or disable.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([
      link({ scope: 'CHAT', executionProvider: 'openrouter', executionModel: 'anthropic/claude-opus-4-7' }),
    ]);
    mocks.saveExecutionLink.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);
    fireEvent.click(screen.getByRole('checkbox', { name: new RegExp(`${KEY}\\.scopeAll`) }));

    await waitFor(() => expect(mocks.saveExecutionLink).toHaveBeenCalledTimes(1));
    expect(mocks.saveExecutionLink).toHaveBeenCalledWith({
      billedProvider: 'anthropic',
      billedModel: 'claude-opus-4-7',
      executionProvider: 'openrouter',
      executionModel: 'anthropic/claude-opus-4-7',
      scope: 'ALL',
      enabled: true,
    });
    expect(mocks.deleteExecutionLink).not.toHaveBeenCalled();
  });
  it('calls a same-provider row with a different model an override, not covered', async () => {
    // Resolution takes the exact row, so WORKFLOW runs haiku here while ALL runs opus.
    // Ticking it green and labelling it "via all surfaces" would show one state as
    // another; the row has to name the model that makes it different.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([
      link({ scope: 'ALL', executionModel: 'claude-opus-4-7' }),
      link({ scope: 'WORKFLOW', executionModel: 'claude-haiku-4-5' }),
    ]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);

    const workflow = surfaceRow('scopeWorkflow');
    expect(workflow).toHaveTextContent('claude-haiku-4-5');
    expect(workflow).not.toHaveTextContent(`${KEY}.coveredByAll`);
    // A row that really does agree with the wildcard still reads as covered.
    expect(surfaceRow('scopeChat')).toHaveTextContent(`${KEY}.coveredByAll`);
  });

  it('does not claim execution over a routing that is switched off', async () => {
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([link({ enabled: false })]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);

    // The header would otherwise say "Executed via Claude Code" above a Disabled chip.
    expect(screen.getByText(new RegExp(`${KEY}\\.routedViaDisabled`))).toBeInTheDocument();
    expect(screen.queryByText(new RegExp(`${KEY}\\.routedViaTitle`))).toBeNull();
  });

  it('says the CLI access policy is a second gate the badge cannot see', async () => {
    // The CLI access policy ships admin-only, so it lets THIS reader through and denies
    // everyone else on the same model: the one gate the badge cannot see, and the one an
    // admin is least likely to hit themselves.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([link()]);

    const { unmount } = render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);
    expect(screen.getByText(`${KEY}.accessPolicyCaveat`)).toBeInTheDocument();
    unmount();

    // A model routed to an API provider has no CLI policy to fall foul of.
    mocks.listExecutionLinks.mockResolvedValue([link({ executionProvider: 'openrouter' })]);
    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);
    expect(screen.queryByText(`${KEY}.accessPolicyCaveat`)).toBeNull();
  });
  it('closes on a second real click of the badge, mousedown included', async () => {
    // fireEvent.click alone never emits mousedown, which is what hid this: a real click
    // fires mousedown first, and with the outside-click ref scoped to the panel the
    // badge counted as outside, so the popover closed and the click reopened it.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([link()]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());

    fireEvent.mouseDown(control()!);
    fireEvent.click(control()!);
    expect(screen.getByText(new RegExp(`${KEY}\\.routedViaTitle`))).toBeInTheDocument();

    fireEvent.mouseDown(control()!);
    fireEvent.click(control()!);
    await waitFor(() =>
      expect(screen.queryByText(new RegExp(`${KEY}\\.routedViaTitle`))).toBeNull());
  });

  it('drops the access-policy caveat when only a DISABLED row points at the CLI', async () => {
    // Matches the amber warning's rule: a switched-off row gates nothing, so neither
    // predicate may claim a CLI is involved.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.listExecutionLinks.mockResolvedValue([
      link({ scope: 'ALL', executionProvider: 'openrouter' }),
      link({ scope: 'CHAT', executionProvider: 'claude-code', enabled: false }),
    ]);

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(control()).toBeInTheDocument());
    fireEvent.click(control()!);

    expect(screen.queryByText(`${KEY}.accessPolicyCaveat`)).toBeNull();
  });
});
