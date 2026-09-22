// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import fr from '@/messages/fr.json';
import { PERSONA_KEYS, type PersonaKey } from '@/components/landing/personas/personas';

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

/**
 * The roster the persona pages actually pass: each persona's OWN twelve. Read straight from
 * the messages a page renders, so a team that drifts out of a locale fails here rather than
 * showing a visitor somebody else's job titles.
 */
function personaTeam(persona: PersonaKey) {
  return Object.values(fr.PersonaLanding.personas[persona].team);
}

describe('AgentsShowcase', () => {
  beforeEach(() => {
    // The recolored preset avatar (the research card) fetches its base SVG to swap stops.
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

  it('uses the supplied persona team with the existing selection and favorite controls', () => {
    render(<AgentsShowcase team={[
      { name: 'Content studio', description: 'Creates scripts and visuals' },
      { name: 'Publishing', description: 'Publishes approved content' },
    ]} />);
    expect(cardNames()).toEqual(['Content studio', 'Publishing']);
    expect(screen.queryByText('Support triage, answers or escalates')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Star Publishing' }));
    expect(cardNames()).toEqual(['Publishing', 'Content studio']);
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Content studio' }));
    expect(screen.getByRole('checkbox', { name: 'Select Content studio' })).toBeChecked();
  });

  it('localizes a generic team without replacing the original avatar roles', () => {
    const team = [
      { name: 'Assistance', description: 'Répond aux demandes client' },
      { name: 'Prospection', description: 'Prépare le contexte des prospects' },
    ];
    const labels = fr.PersonaLanding.agents;
    render(<NextIntlClientProvider locale="fr" messages={fr} onError={(error) => { throw error; }}>
      <AgentsShowcase team={team} locale="fr" />
    </NextIntlClientProvider>);
    expect(screen.getByText(fr.emptyState.agent.tabMetrics)).toBeInTheDocument();
    expect(within(cards()[0]).getByLabelText(fr.avatarPicker.tools.headset)).toBeInTheDocument();
    expect(within(cards()[1]).getByLabelText(fr.avatarPicker.tools.chart)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: labels.unstar.replace('{name}', team[0].name) })).toHaveAttribute('aria-pressed', 'true');
    const checkbox = screen.getByRole('checkbox', { name: labels.select.replace('{name}', team[1].name) });
    fireEvent.click(checkbox);
    expect(checkbox).toBeChecked();
    expect(screen.queryByRole('checkbox', { name: 'Select Prospection' })).not.toBeInTheDocument();
    expect(cards()[0].querySelector('p')).toHaveAttribute('title', team[0].description);
  });

  it('translates persona controls and tooltips while keeping selection and favorites interactive', () => {
    const team = personaTeam('creator');
    const labels = fr.PersonaLanding.agents;
    render(<NextIntlClientProvider locale="fr" messages={fr} onError={(error) => { throw error; }}>
      <AgentsShowcase team={team} persona="creator" locale="fr" />
    </NextIntlClientProvider>);
    for (const key of ['tabAgents', 'tabSkills', 'tabFleet', 'tabMetrics'] as const) {
      expect(screen.getByText(fr.emptyState.agent[key])).toBeInTheDocument();
    }
    expect(screen.queryByText('Metrics')).not.toBeInTheDocument();
    expect(screen.getAllByTitle(labels.published).length).toBeGreaterThan(0);
    expect(screen.getAllByTitle(fr.common.visibilityPrivate).length).toBeGreaterThan(0);
    expect(screen.getAllByTitle(labels.webhook).length).toBeGreaterThan(0);
    expect(screen.getAllByTitle(labels.scheduled).length).toBeGreaterThan(0);
    const name = team[3].name;
    const favorite = labels.star.replace('{name}', name);
    fireEvent.click(screen.getByRole('button', { name: favorite }));
    expect(cardNames().slice(0, 2)).toEqual([team[0].name, name]);
    expect(screen.getByRole('button', { name: labels.unstar.replace('{name}', name) })).toHaveAttribute('aria-pressed', 'true');
    const select = screen.getByRole('checkbox', { name: labels.select.replace('{name}', name) });
    fireEvent.click(select);
    expect(select).toBeChecked();
    expect(select.closest('div[role="button"]')).toHaveAttribute('aria-pressed', 'true');
  });

  it('gives the creator team writing, visual, video and publishing badges from existing presets', () => {
    const team = personaTeam('creator');
    render(<NextIntlClientProvider locale="fr" messages={fr} onError={(error) => { throw error; }}>
      <AgentsShowcase team={team} persona="creator" locale="fr" />
    </NextIntlClientProvider>);
    const tools = ['pen', 'palette', 'film', 'languages', 'pen', 'camera', 'book', 'mail', 'paintbrush', 'newspaper', 'calendar', 'megaphone'] as const;
    cards().forEach((card, index) => {
      const badge = within(card).getByLabelText(fr.avatarPicker.tools[tools[index]]);
      expect(badge.querySelector('svg')).not.toBeNull();
      expect(within(card).getByAltText(team[index].name)).toBeInTheDocument();
    });
    expect(screen.getByLabelText(fr.avatarPicker.tools.film).querySelector('svg')).toHaveClass('lucide-film');
    expect(screen.queryByLabelText(fr.avatarPicker.tools.headset)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(fr.avatarPicker.tools.chart)).not.toBeInTheDocument();
  });

  it('pairs each operations agent with the badge for its own job', () => {
    // The pairing is positional: `PERSONA_AVATARS[persona]` is read by index, so entry three
    // wears the third avatar of that row and a reorder on either side misaligns the whole
    // roster in silence. This row was written before the ops roster existed, so it had been
    // paired with nothing: a chart sat on Intake, money on Reports, a pen on Quality, and slot
    // five asked for a `table` tool the picker does not have, which drew no badge at all.
    const team = personaTeam('ops');
    render(<NextIntlClientProvider locale="fr" messages={fr} onError={(error) => { throw error; }}>
      <AgentsShowcase team={team} persona="ops" locale="fr" />
    </NextIntlClientProvider>);
    const tools = ['mail', 'git-branch', 'shopping-cart', 'truck', 'dollar', 'chart',
      'book', 'calendar', 'database', 'shield', 'zap', 'handshake'] as const;
    cards().forEach((card, index) => {
      expect(within(card).getByLabelText(fr.avatarPicker.tools[tools[index]])).toBeInTheDocument();
      expect(within(card).getByAltText(team[index].name)).toBeInTheDocument();
    });
  });

  it.each(PERSONA_KEYS)('fills the original twelve-card roster with distinct %s avatars and complete role labels', (persona) => {
    const team = personaTeam(persona);
    render(<NextIntlClientProvider locale="fr" messages={fr} onError={(error) => { throw error; }}>
      <AgentsShowcase team={team} persona={persona} locale="fr" />
    </NextIntlClientProvider>);
    expect(cardNames()).toEqual(team.map((member) => member.name));
    expect(cards()).toHaveLength(12);
    expect(new Set(cards().map((card) => card.querySelector('img')?.getAttribute('src'))).size).toBe(12);
    for (const [index, member] of team.entries()) {
      expect(cards()[index].querySelector('span.font-medium')).toHaveAttribute('title', member.name);
      expect(cards()[index].querySelector('p')).toHaveAttribute('title', member.description);
    }
    const initialFavorite = fr.PersonaLanding.agents.unstar.replace('{name}', team[0].name);
    expect(screen.getByRole('button', { name: initialFavorite })).toHaveAttribute('aria-pressed', 'true');
    // Every card wears its tool badge. A preset whose `tool=` names something the picker does
    // not have draws NO badge and says nothing about it, so the card is simply plainer than
    // its eleven neighbours. `ops` shipped with `tool=table` that way, unnoticed for as long
    // as that roster was never rendered.
    // `span[aria-label]` is the badge specifically: the card's other labelled elements are its
    // checkbox and its star button, so a bare `[aria-label]` would find one of those and pass
    // for a card that has no badge at all.
    expect(cards().filter((card) => card.querySelector('span[aria-label]'))).toHaveLength(12);
  });

  it('keeps the homepage card and crop dimensions for a full persona roster', () => {
    render(<AgentsShowcase />);
    const originalCard = cards()[0];
    const original = {
      card: originalCard.className,
      grid: originalCard.parentElement!.className,
      preview: originalCard.firstElementChild!.className,
      crop: document.querySelector('figure')!.parentElement!.parentElement!.className,
    };
    cleanup();
    render(<NextIntlClientProvider locale="fr" messages={fr}>
      <AgentsShowcase team={personaTeam('creator')} persona="creator" locale="fr" />
    </NextIntlClientProvider>);
    const personaCard = cards()[0];
    expect(personaCard.className).toBe(original.card);
    expect(personaCard.parentElement!.className).toBe(original.grid);
    expect(personaCard.firstElementChild!.className).toBe(original.preview);
    expect(document.querySelector('figure')!.parentElement!.parentElement!.className).toBe(original.crop);
  });

  it('renders the full demo team with the real card fields (name, description, model)', () => {
    render(<AgentsShowcase />);

    // A full roster, not a handful of cards: the section sells "your whole
    // agent team, on one screen".
    expect(cardNames()).toHaveLength(12);
    // The cards are titled by the job, not by a first name.
    expect(screen.getByText('Customer support')).toBeInTheDocument();
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

    // Same entries, same order, and NOTHING else, because the rail is built from
    // SIDEBAR_NAV_ITEMS rather than a copy of it: Agenda is in this list because
    // the product gained it, not because anyone remembered to add it. Asserting
    // the sequence rather than membership is what makes that true: the old
    // per-label loop passed on a rail that rendered twenty items backwards.
    // The rail is aria-hidden (decorative), so target the title tooltips.
    // Anchored on the rail's own first entry rather than on "the first
    // aria-hidden node in the document", which silently retargets the day
    // anything else aria-hidden renders above it.
    const rail = screen.getByTitle('Marketplace').parentElement!;
    const railLabels = Array.from(rail.querySelectorAll('[title]')).map((el) => el.getAttribute('title'));
    expect(railLabels).toEqual([
      'Marketplace', 'Board', 'Agenda', 'Agents', 'Applications',
      'Workflows', 'Interfaces', 'Tables', 'Files', 'Account',
    ]);
    expect(screen.getByTitle('Agents')).toHaveAttribute('data-active', 'true');
    expect(screen.getByTitle('Marketplace')).not.toHaveAttribute('data-active');
    expect(screen.getByTitle('Account')).toBeInTheDocument();
    // There is deliberately no search toolbar in the landing replica.
    expect(screen.queryByLabelText('Search agents')).not.toBeInTheDocument();
  });

  it('recolors the custom-color avatar through the production svg pipeline', async () => {
    render(<AgentsShowcase />);

    // The research avatar is 'preset:blue?c1=..&c2=..': its base preset svg is fetched (via
    // avatarColors' module-level svgTextCache, so the network call itself only
    // happens on the first render in the suite), the gradient stops are
    // swapped, and the img flips to the recolored data URI.
    await waitFor(() =>
      expect(screen.getByAltText('Research')).toHaveAttribute(
        'src',
        expect.stringContaining('data:image/svg+xml'),
      ),
    );
    // The swap actually happened: the encoded svg carries the custom stops.
    const src = screen.getByAltText('Research').getAttribute('src')!;
    expect(decodeURIComponent(src)).toContain('#0EA5E9');
  });

  it('starring an agent floats it above non-favorites (after the default favorite)', () => {
    render(<AgentsShowcase />);
    expect(cardNames()[0]).toBe('Customer support'); // default favorite

    fireEvent.click(screen.getByRole('button', { name: 'Star Social publishing' }));
    expect(cardNames().slice(0, 2)).toEqual(['Customer support', 'Social publishing']);

    // Unstar puts it back at the tail of the base order.
    fireEvent.click(screen.getByRole('button', { name: 'Unstar Social publishing' }));
    expect(cardNames()[1]).toBe('Lead qualification');
  });

  it('clicking cards toggles selection on and off, multiple cards at once', () => {
    render(<AgentsShowcase />);
    const leadCard = screen.getByText('Lead qualification').closest('div[aria-pressed]')! as HTMLElement;
    const socialCard = screen.getByText('Social publishing').closest('div[aria-pressed]')! as HTMLElement;

    fireEvent.click(leadCard);
    fireEvent.click(socialCard);
    expect(leadCard).toHaveAttribute('aria-pressed', 'true');
    expect(socialCard).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(leadCard);
    expect(leadCard).toHaveAttribute('aria-pressed', 'false');
    expect(socialCard).toHaveAttribute('aria-pressed', 'true');
  });

  it('the checkbox also toggles selection, exactly once (stopPropagation vs the card click)', () => {
    render(<AgentsShowcase />);
    const leadCard = screen.getByText('Lead qualification').closest('div[aria-pressed]')! as HTMLElement;
    const checkbox = screen.getByLabelText('Select Lead qualification');

    // If the checkbox click also bubbled into the card's toggle, the two
    // toggles would cancel out and selection would be a no-op.
    fireEvent.click(checkbox);
    expect(leadCard).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(checkbox);
    expect(leadCard).toHaveAttribute('aria-pressed', 'false');
  });

  it('cards toggle selection from the keyboard (Enter and Space)', () => {
    render(<AgentsShowcase />);
    const leadCard = screen.getByText('Lead qualification').closest('div[aria-pressed]')! as HTMLElement;

    fireEvent.keyDown(leadCard, { key: 'Enter' });
    expect(leadCard).toHaveAttribute('aria-pressed', 'true');

    fireEvent.keyDown(leadCard, { key: ' ' });
    expect(leadCard).toHaveAttribute('aria-pressed', 'false');

    fireEvent.keyDown(leadCard, { key: 'a' }); // unrelated keys are ignored
    expect(leadCard).toHaveAttribute('aria-pressed', 'false');
  });

  it('starring never selects (stopPropagation guards the card click)', () => {
    render(<AgentsShowcase />);

    fireEvent.click(screen.getByRole('button', { name: 'Star Lead qualification' }));
    const leadCard = screen.getByText('Lead qualification').closest('div[aria-pressed]')! as HTMLElement;
    expect(leadCard).toHaveAttribute('aria-pressed', 'false');
  });
});
