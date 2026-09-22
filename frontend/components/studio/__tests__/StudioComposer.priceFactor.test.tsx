// @vitest-environment jsdom
/**
 * The price beside the button, when the reader's own choices moved it.
 *
 * <p>The amount is the server's. What this suite is about is the BADGE that explains it, and the
 * one rule that makes it honest: it is drawn from the factor the server says it applied, never
 * from the one the surface computed. A server that did not apply it - an older self-hosted build,
 * a relay leg that dropped it - answers with a total at the published rate, and a badge from the
 * local calculation would then claim a surcharge the amount beside it does not contain. Two
 * numbers, one of them wrong, and the reader can only find out by spending.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => {
    // Renders the factor into the key, so an assertion can see WHICH number was used.
    const t = (key: string, values?: Record<string, unknown>) => (
      values && 'factor' in values ? `${key}:${values.factor}` : key
    );
    t.has = () => false;
    return t;
  },
}));
vi.mock('@/hooks/useGenerationOptions', () => ({ useGenerationOptions: () => ({}) }));
vi.mock('@/lib/generation/price', () => ({
  describeQuotedPrice: () => '12 credits',
  describePriceFactors: () => 'resolution x2',
  formatCredits: (value: number) => String(value),
}));
const quoteState = vi.hoisted(() => ({
  value: {
    quote: undefined as unknown,
    quantity: null as number | null,
    settled: true,
    stale: false,
    multiplier: 1,
  },
}));
vi.mock('@/hooks/useGenerationQuote', () => ({
  useGenerationQuote: () => quoteState.value,
}));

import { StudioComposer } from '../StudioComposer';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

/** A model that declares a priced resolution, so the local calculation has something to find. */
function modulatedModel(): GenerationModel {
  return {
    model: 'seedance-2.0',
    kind: 'video',
    label: 'Seedance',
    provider: 'Seedance',
    iconSlug: null,
    apiToolId: 't-1',
    integrationName: 'seedance',
    accepts: ['prompt', 'resolution'],
    required: [],
    limits: { resolution: { allowed: ['480p', '1080p'] } },
    billedOn: null,
    measuredUnit: null,
    defaultQuantity: null,
    async: false,
    price: {
      unit: 'call',
      baseCredits: '100',
      unitCredits: '0',
      modifiers: { resolution: { by_value: { '480p': 1, '1080p': 2 } } },
    },
  } as GenerationModel;
}

/** A quote answer, with or without the server having applied a factor. */
function quote(priceMultiplier?: string) {
  return {
    integrationName: 'seedance',
    available: true,
    hasPricing: true,
    platformCredentialId: 7,
    markupCredits: '200',
    ...(priceMultiplier ? { priceMultiplier } : {}),
  };
}

function renderComposer(props: Record<string, unknown> = {}) {
  const selected = modulatedModel();
  render(
    <StudioComposer
      models={[selected]}
      selectedModel={selected}
      onSelectModel={vi.fn()}
      onSubmit={vi.fn(async () => true)}
      {...props}
    />,
  );
}

/** The badge, by the key the stub translator renders for it. */
function badge() {
  return screen.queryByText(/^price\.multiplierBadge:/);
}

afterEach(() => {
  quoteState.value = { quote: undefined, quantity: null, settled: true, stale: false, multiplier: 1 };
  cleanup();
});

describe('StudioComposer - the price factor badge', () => {
  it('states the factor the SERVER applied', () => {
    quoteState.value = { ...quoteState.value, quote: quote('2'), multiplier: 2 };
    renderComposer();

    expect(badge()).toHaveTextContent('price.multiplierBadge:2');
  });

  it('says nothing when the server applied no factor, whatever the surface computed', () => {
    // The regression this file exists for: the surface believes the call is a 1080p render and the
    // server answered a total at the published rate. The amount is still right; it carries no
    // surcharge, and claiming one would make the two disagree on screen.
    quoteState.value = { ...quoteState.value, quote: quote(), multiplier: 2 };
    renderComposer();

    expect(badge()).toBeNull();
  });

  it('says nothing for a call at the published rate', () => {
    quoteState.value = { ...quoteState.value, quote: quote('1'), multiplier: 1 };
    renderComposer();

    expect(badge()).toBeNull();
  });

  it('says nothing while the reader is paying with their own key', () => {
    // Nothing is charged in platform credits then, so a surcharge on a platform rate is not a fact
    // about this call at all.
    //
    // Two gates produce this and the test can only see the outcome: the price pill itself is
    // hidden on `credentialSource === 'user'`, and `priceLabel &&` in the badge's own condition
    // therefore already suppresses it before its `credentialSource !== 'user'` clause is reached.
    // So this pins the BEHAVIOUR, and the clause it looks like it is testing is belt to that
    // braces. Said out loud because a reader deleting that clause would find this still green.
    quoteState.value = { ...quoteState.value, quote: quote('2'), multiplier: 2 };
    renderComposer({ credentialSource: 'user' });

    expect(badge()).toBeNull();
    // The mechanism, asserted so the reason for the absence is on record: no price is stated at
    // all on a reader's own key, so there is no amount for a factor to explain.
    expect(screen.queryByText(/12 credits/)).toBeNull();
  });

  it('says nothing before the price has been answered', () => {
    quoteState.value = { ...quoteState.value, quote: quote('2'), multiplier: 2, settled: false };
    renderComposer();

    expect(badge()).toBeNull();
  });

  it('ignores a factor that is not a usable number', () => {
    quoteState.value = { ...quoteState.value, quote: quote('not-a-number'), multiplier: 2 };
    renderComposer();

    expect(badge()).toBeNull();
  });

  it('does not explain a factor the answer was not computed for', () => {
    // The badge is the SERVER's answer for the debounced form; the reason is read from the live
    // one. Between a change and the next answer those are two different forms, and the surface
    // said so out loud: "x2" beside "resolution x2" computed from a form now asking for something
    // else. On a phone that sentence is the badge's ONLY explanation, so it is the half a reader
    // actually receives.
    //
    // `stale` already means exactly this - the hook compares the live factor against the one the
    // answer carries - so the explanation waits for the answer it belongs to.
    quoteState.value = { ...quoteState.value, quote: quote('2'), multiplier: 2, stale: true };
    renderComposer();

    // The amount the server did answer is still shown; only the explanation of a DIFFERENT form
    // is withheld.
    expect(badge()).toHaveTextContent('price.multiplierBadge:2');
    expect(badge()).not.toHaveTextContent('resolution x2');
  });

  it('reads the reason OUT, because a tooltip does not exist on a phone', () => {
    quoteState.value = { ...quoteState.value, quote: quote('2'), multiplier: 2 };
    renderComposer();

    // Text content, not `aria-label`. The badge is a bare `span`, and an `aria-label` on an element
    // with no role is dropped by browsers and screen readers: it would look like an accessible name
    // in the source and name nothing in the ear. `getByLabelText` does NOT reproduce that rule - it
    // reads the attribute off any element - so the previous spelling of this test passed against a
    // badge that announced only "x2". The assertion has to be on what is actually announced.
    expect(badge()).toHaveTextContent('resolution x2');
    expect(badge()).not.toHaveAttribute('aria-label');
  });
});

/**
 * The same sentence, in the parameters menu, under the same rule.
 *
 * <p>This is the half that was left behind: the badge was gated on the server's answer and the
 * menu note was not, so an older self-hosted build that did not apply the factor showed no badge
 * and still explained a surcharge, beside an amount that contains none. One rule for both, or the
 * two halves of the explanation contradict each other.
 */
describe('StudioComposer - the price factor note in the parameters menu', () => {
  class ResizeObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
  }

  beforeEach(() => {
    // jsdom lays nothing out, so the menu only exists if both the observer and the measurement are
    // supplied; without them the composer keeps its wide row and the note is never rendered at all,
    // which would make the "absent" assertion below pass for the wrong reason.
    vi.stubGlobal('ResizeObserver', ResizeObserverStub);
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(
      () => ({ width: 320, height: 120, top: 0, left: 0, right: 320, bottom: 120, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect,
    );
  });

  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

  function openParamsMenu() {
    fireEvent.click(screen.getByTitle('composer.parameters'));
  }

  it('explains the factor the server applied', () => {
    quoteState.value = { ...quoteState.value, quote: quote('2'), multiplier: 2 };
    renderComposer();

    openParamsMenu();

    // Scoped to the menu: the badge carries the same sentence, visually hidden, beside the price.
    expect(within(screen.getByRole('dialog')).getByText('resolution x2')).toBeInTheDocument();
  });

  it('says nothing when the server applied no factor, whatever the surface computed', () => {
    quoteState.value = { ...quoteState.value, quote: quote(), multiplier: 2 };
    renderComposer();

    openParamsMenu();

    // The menu is open, and only the explanation is gone.
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(within(screen.getByRole('dialog')).queryByText('resolution x2')).toBeNull();
  });
});
