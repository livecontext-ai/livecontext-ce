// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AgentsShowcase from '../AgentsShowcase';

/** Agent cards are the div[role=button]s carrying aria-pressed (star buttons
 *  carry aria-pressed too, hence the div qualifier); DOM order = display order.
 *  The agent name is the card footer's medium span (the avatar tool badge is
 *  also a span, so target the name by its typography class). */
function cards(): HTMLElement[] {
  return Array.from(document.querySelectorAll('div[aria-pressed]'));
}

function cardNames(): string[] {
  return cards().map((card) => card.querySelector('span.font-medium')!.textContent!.trim());
}

describe('AgentsShowcase', () => {
  beforeEach(() => {
    // The recolored preset avatar (Scout) fetches its base SVG to swap gradient stops.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        text: () => Promise.resolve('<svg><stop stop-color="#3B82F6"/><stop stop-color="#1D4ED8"/></svg>'),
      }),
    );
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('renders the full demo team with the real card fields (name, description, model)', () => {
    render(<AgentsShowcase />);

    // A full roster, not a handful of cards: the section sells "your whole
    // agent team, on one screen".
    expect(cardNames()).toHaveLength(12);
    expect(screen.getByText('Nova')).toBeInTheDocument();
    expect(screen.getByText('Support triage, answers or escalates')).toBeInTheDocument();
    expect(screen.getAllByText('anthropic/claude-sonnet-5').length).toBeGreaterThan(0);
    // App chrome: no browser URL bar (bare app frame), and the four real tabs
    // (the sidebar rail also has an Agents entry, hence getAllByText).
    expect(screen.queryByText('livecontext.ai/app/agent')).not.toBeInTheDocument();
    for (const tab of ['Agents', 'Skills', 'Fleet', 'Metrics']) {
      expect(screen.getAllByText(tab).length).toBeGreaterThan(0);
    }
  });

  it('gives every agent a distinct avatar preset and a labelled tool badge', () => {
    render(<AgentsShowcase />);

    // Each card renders through the production avatar pipeline: one <img>
    // alt-titled with the agent name, plus a tool badge whose aria-label is the
    // marketing role (not the terse in-app icon name).
    for (const name of cardNames()) {
      expect(screen.getByAltText(name)).toBeInTheDocument();
    }
    for (const role of ['Customer support', 'Research', 'Testing', 'Email', 'Finance', 'Web browsing', 'Compliance', 'Design']) {
      expect(screen.getByLabelText(role)).toBeInTheDocument();
    }
    // No badge falls back to the raw tool id because TOOL_LABELS is missing it.
    for (const rawToolId of ['flask', 'palette', 'megaphone']) {
      expect(screen.queryByLabelText(rawToolId)).not.toBeInTheDocument();
    }
  });

  it('lays the roster out as a compact multi-column grid', () => {
    render(<AgentsShowcase />);

    const grid = cards()[0].parentElement!;
    // 3 columns on phones, 4 from md: the cards shrink instead of the roster.
    expect(grid.className).toContain('grid-cols-3');
    expect(grid.className).toContain('md:grid-cols-4');
  });

  it('renders the sidebar rail mirroring the real AppSidebar nav, Agents active', () => {
    render(<AgentsShowcase />);

    // Same entries and order as AppSidebar's chatNavItems. The rail is
    // aria-hidden (decorative), so target the title tooltips.
    for (const label of ['Marketplace', 'Board', 'Agents', 'Applications', 'Workflows', 'Interfaces', 'Tables', 'Files']) {
      expect(screen.getByTitle(label)).toBeInTheDocument();
    }
    expect(screen.getByTitle('Agents')).toHaveAttribute('data-active', 'true');
    expect(screen.getByTitle('Marketplace')).not.toHaveAttribute('data-active');
    expect(screen.getByTitle('Account')).toBeInTheDocument();
    // There is deliberately no search toolbar in the landing replica.
    expect(screen.queryByLabelText('Search agents')).not.toBeInTheDocument();
  });

  it('recolors the custom-color avatar through the production svg pipeline', async () => {
    render(<AgentsShowcase />);

    // Scout is 'preset:blue?c1=..&c2=..': its base preset svg is fetched (via
    // avatarColors' module-level svgTextCache, so the network call itself only
    // happens on the first render in the suite), the gradient stops are
    // swapped, and the img flips to the recolored data URI.
    await waitFor(() =>
      expect(screen.getByAltText('Scout')).toHaveAttribute(
        'src',
        expect.stringContaining('data:image/svg+xml'),
      ),
    );
    // The swap actually happened: the encoded svg carries the custom stops.
    const src = screen.getByAltText('Scout').getAttribute('src')!;
    expect(decodeURIComponent(src)).toContain('#0EA5E9');
  });

  it('starring an agent floats it above non-favorites (after the default favorite Nova)', () => {
    render(<AgentsShowcase />);
    expect(cardNames()[0]).toBe('Nova'); // default favorite

    fireEvent.click(screen.getByRole('button', { name: 'Star Sol' }));
    expect(cardNames().slice(0, 2)).toEqual(['Nova', 'Sol']);

    // Unstar puts it back at the tail of the base order.
    fireEvent.click(screen.getByRole('button', { name: 'Unstar Sol' }));
    expect(cardNames()[1]).toBe('Atlas');
  });

  it('clicking cards toggles selection on and off, multiple cards at once', () => {
    render(<AgentsShowcase />);
    const atlasCard = screen.getByText('Atlas').closest('div[aria-pressed]')! as HTMLElement;
    const solCard = screen.getByText('Sol').closest('div[aria-pressed]')! as HTMLElement;

    fireEvent.click(atlasCard);
    fireEvent.click(solCard);
    expect(atlasCard).toHaveAttribute('aria-pressed', 'true');
    expect(solCard).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(atlasCard);
    expect(atlasCard).toHaveAttribute('aria-pressed', 'false');
    expect(solCard).toHaveAttribute('aria-pressed', 'true');
  });

  it('the checkbox also toggles selection, exactly once (stopPropagation vs the card click)', () => {
    render(<AgentsShowcase />);
    const atlasCard = screen.getByText('Atlas').closest('div[aria-pressed]')! as HTMLElement;
    const checkbox = screen.getByLabelText('Select Atlas');

    // If the checkbox click also bubbled into the card's toggle, the two
    // toggles would cancel out and selection would be a no-op.
    fireEvent.click(checkbox);
    expect(atlasCard).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(checkbox);
    expect(atlasCard).toHaveAttribute('aria-pressed', 'false');
  });

  it('cards toggle selection from the keyboard (Enter and Space)', () => {
    render(<AgentsShowcase />);
    const atlasCard = screen.getByText('Atlas').closest('div[aria-pressed]')! as HTMLElement;

    fireEvent.keyDown(atlasCard, { key: 'Enter' });
    expect(atlasCard).toHaveAttribute('aria-pressed', 'true');

    fireEvent.keyDown(atlasCard, { key: ' ' });
    expect(atlasCard).toHaveAttribute('aria-pressed', 'false');

    fireEvent.keyDown(atlasCard, { key: 'a' }); // unrelated keys are ignored
    expect(atlasCard).toHaveAttribute('aria-pressed', 'false');
  });

  it('starring never selects (stopPropagation guards the card click)', () => {
    render(<AgentsShowcase />);

    fireEvent.click(screen.getByRole('button', { name: 'Star Atlas' }));
    const atlasCard = screen.getByText('Atlas').closest('div[aria-pressed]')! as HTMLElement;
    expect(atlasCard).toHaveAttribute('aria-pressed', 'false');
  });
});
