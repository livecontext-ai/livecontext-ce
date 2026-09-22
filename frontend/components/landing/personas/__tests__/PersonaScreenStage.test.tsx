// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import { WELL_KNOWN_INTEGRATIONS } from '@/lib/integrations/wellKnownIntegrations';

// The thumbnail mounts an iframe with its own document, which jsdom neither lays out nor
// paints. What this suite is about is the WIRING around it, so it is replaced by a marker
// that still carries the node id the wires attach to.
vi.mock('@/app/workflows/builder/components/interface/InterfaceThumbnail', () => ({
  InterfaceThumbnail: ({ viewport }: { viewport: { width: number; height: number } }) =>
    <div data-testid="thumbnail" data-viewport={`${viewport.width}x${viewport.height}`} />,
}));
vi.mock('@/components/integrations/BrandMark', () => ({ BrandMark: ({ iconSlug }: { iconSlug: string }) => <i data-testid="brand-mark" data-slug={iconSlug} /> }));
// jsdom reports every box as 0x0, so the measured SVG stays empty. The wires' CONTRACT is
// written to the DOM as `data-wires` for exactly this reason (see StudioWires).
// Parameterised, so the pre-scroll state (the one a crawler and every visitor sees first)
// is reachable too. Defaults to seen, which is what most of this suite is about.
const visibility = vi.hoisted(() => ({ seen: true }));
vi.mock('@/hooks/useOnVisibleOnce', () => ({ useOnVisibleOnce: () => [{ current: null }, visibility.seen] }));

import PersonaScreenStage from '../PersonaScreenStage';
import {
  BUSINESS_DESTINATIONS,
  CREATOR_DESTINATION_SLUGS,
  HERO_EXAMPLE_KEYS,
  PERSONA_KEYS,
  type BusinessPersona,
  type PersonaKey,
} from '../personas';

afterEach(() => { cleanup(); visibility.seen = true; });

const messages = { en, fr };

function renderStage(persona: PersonaKey, locale: 'en' | 'fr' = 'en') {
  return render(
    <NextIntlClientProvider locale={locale} messages={messages[locale]} timeZone="UTC">
      <PersonaScreenStage persona={persona} />
    </NextIntlClientProvider>,
  );
}

const wires = (): { from: string; to: string }[] =>
  JSON.parse(document.querySelector('[data-wires]')!.getAttribute('data-wires')!);

/**
 * The section's claim is that a workflow ties the whole card together, and the wires are what
 * makes that claim. A card whose screen renders but whose wires point at nothing looks
 * completely fine and says nothing, so the ends are what this suite checks.
 */
describe('the wired workflow on a role card', () => {
  it.each(PERSONA_KEYS)('wires the trigger into the screen and the screen out to every destination, for %s', (persona) => {
    renderStage(persona);
    const expected = persona === 'creator'
      ? CREATOR_DESTINATION_SLUGS.slice(0, 3)
      : BUSINESS_DESTINATIONS[HERO_EXAMPLE_KEYS[persona as BusinessPersona]].slice(0, 3);

    expect(wires()).toEqual([
      { from: 'trigger', to: 'screen' },
      ...expected.map((slug) => ({ from: 'screen', to: `dest-${slug}` })),
    ]);
  });

  it.each(PERSONA_KEYS)('gives every wire an element at BOTH ends, for %s', (persona) => {
    // A wire whose target is missing is skipped silently by StudioWires: the card then draws
    // fewer edges than it promises and nothing fails.
    renderStage(persona);
    for (const wire of wires()) {
      expect(document.querySelector(`[data-node="${wire.from}"]`), wire.from).not.toBeNull();
      expect(document.querySelector(`[data-node="${wire.to}"]`), wire.to).not.toBeNull();
    }
  });

  it('only ever names connectors the catalogue actually carries', () => {
    const known = new Set(WELL_KNOWN_INTEGRATIONS.map((integration) => integration.slug));
    for (const persona of PERSONA_KEYS) {
      cleanup();
      renderStage(persona);
      const destinations = wires().filter((wire) => wire.to.startsWith('dest-')).map((wire) => wire.to.slice(5));
      expect(destinations.length, persona).toBe(3);
      for (const slug of destinations) expect(known.has(slug), `${persona}: ${slug}`).toBe(true);
    }
  });

  it('labels the trigger in the page language', () => {
    renderStage('ops', 'fr');
    expect(screen.getByText(fr.PersonaLanding.personas.ops.workflowShowcase.examples.report.triggerLabel)).toBeInTheDocument();
    cleanup();
    renderStage('ops', 'en');
    expect(screen.getByText(en.PersonaLanding.personas.ops.workflowShowcase.examples.report.triggerLabel)).toBeInTheDocument();
  });

  it('starts the creator card from its content, which owns no trigger label', () => {
    // `examples.product` has no `triggerLabel`; reading one would render the raw key path.
    renderStage('creator', 'fr');
    expect(screen.getByText(fr.PersonaLanding.personas.creator.workflowShowcase.examples.product.label)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/triggerLabel/);
  });
});

describe('the screen each card shows', () => {
  it('renders the persona own authored format, not one shared shape', () => {
    // The five business personas are authored at the workspace size and Creator at story
    // size; handing the story the landscape viewport letterboxes a document that is not that
    // shape, which is what the aspect ratio here is guarding.
    renderStage('ops');
    expect(screen.getByTestId('thumbnail')).toHaveAttribute('data-viewport', '1020x1080');
    cleanup();
    renderStage('creator');
    expect(screen.getByTestId('thumbnail')).toHaveAttribute('data-viewport', '1080x1920');
  });

  it('holds the composition together before the screen has mounted', () => {
    // What a crawler and every pre-scroll visitor actually see. The placeholder has to carry
    // the SAME node id, or the wires find no target and are silently dropped, and the same
    // aspect ratio, or the card resizes under the visitor when the screen arrives.
    visibility.seen = false;
    renderStage('ops');
    const placeholder = document.querySelector('.role-stage-placeholder') as HTMLElement;
    expect(placeholder).not.toBeNull();
    expect(placeholder.getAttribute('data-node')).toBe('screen');
    expect(placeholder.getAttribute('aria-hidden')).toBe('true');
    expect(placeholder.style.aspectRatio).toBe('1020 / 1080');
    expect(document.querySelector('[data-testid="thumbnail"]')).toBeNull();
    // The wires still have both ends, so nothing is dropped while the screen loads.
    for (const wire of wires()) {
      expect(document.querySelector(`[data-node="${wire.from}"]`), wire.from).not.toBeNull();
      expect(document.querySelector(`[data-node="${wire.to}"]`), wire.to).not.toBeNull();
    }
  });

  it('classifies shape by RATIO, so a near-square workspace is not treated as a story', () => {
    // The bug this replaced: the workspace is 1020x1080, i.e. taller than it is wide by six
    // percent, so `height > width` called all six screens portrait and shrank five of them.
    const shapeOf = () => document.querySelector('.role-stage-box')!.getAttribute('data-shape');
    renderStage('ops');
    expect(shapeOf()).toBe('landscape');
    cleanup();
    renderStage('sales');
    expect(shapeOf()).toBe('landscape');
    cleanup();
    renderStage('creator');
    expect(shapeOf()).toBe('portrait');
  });
});
