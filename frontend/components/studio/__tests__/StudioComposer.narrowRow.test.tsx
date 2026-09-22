// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  // `t.has` as well as `t`: the label helpers ask whether a key exists before falling back to the
  // raw parameter name, and a bare function throws on the first pill rendered.
  useTranslations: () => {
    const t = (key: string) => key;
    t.has = () => false;
    return t;
  },
  useLocale: () => 'en',
}));
vi.mock('@/hooks/useGenerationOptions', () => ({ useGenerationOptions: () => ({}) }));
vi.mock('@/lib/generation/price', () => ({
  describeQuotedPrice: () => 'about 60 credits',
  // The composer also states WHY a price is not the published rate, and formats the factor.
  describePriceFactors: () => '',
  formatCredits: (value: number) => String(value),
}));
vi.mock('@/hooks/useGenerationQuote', () => ({
  useGenerationQuote: () => ({
    quote: { integrationName: 'seedance', available: true, hasPricing: true, platformCredentialId: 7 },
    quantity: null,
    settled: true,
    stale: false,
  }),
}));

import { StudioComposer } from '../StudioComposer';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

/**
 * The composer's button row on a phone.
 *
 * <p><b>The bug.</b> The row's right-hand group (price, model picker, send) was `flex-shrink-0`
 * while the left one was `flex-1 min-w-0 overflow-hidden`. On a phone the price alone caps at
 * 144px and the picker at 190px, which already exceeds the row - so the entire deficit landed on
 * the left group, which collapsed toward zero and then CLIPPED its contents. The mode switch went
 * with them: not shrunk, gone. Reported as "seedance hides the toggle".
 *
 * <p>The chat composer never had this, because it does not squeeze: below the same width it merges
 * its actions into one menu and every surviving control keeps its size. This suite pins that the
 * studio now makes the same trade, and in the same order - the price truncates, the picker gives up
 * its name, the parameters move into a menu, and the send button is never touched.
 *
 * <p>jsdom lays nothing out, so both the observer and the measurement have to be supplied; without
 * them the component keeps its wide row, which is the documented no-ResizeObserver fallback and
 * would make every assertion below pass for the wrong reason.
 */
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

/** Widths every element reports, so the composer's own box drives the decision. */
function withComposerWidth(px: number) {
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(
    () => ({ width: px, height: 120, top: 0, left: 0, right: px, bottom: 120, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect,
  );
}

function model(overrides: Partial<GenerationModel> = {}): GenerationModel {
  return {
    model: 'seedance-2.5',
    kind: 'video',
    label: 'Seedance 2.5',
    provider: 'Seedance',
    iconSlug: null,
    apiToolId: 't1',
    integrationName: 'seedance',
    accepts: ['prompt', 'duration', 'resolution', 'aspect_ratio'],
    required: [],
    limits: {
      duration: { type: 'number', min: 1, max: 12 },
      resolution: { type: 'enum', values: ['720p', '1080p'] },
      aspect_ratio: { type: 'enum', values: ['16:9', '9:16'] },
    } as unknown as GenerationModel['limits'],
    billedOn: 'duration',
    measuredUnit: 'second',
    defaultQuantity: '5',
    price: { unit: 'second', baseCredits: '0', unitCredits: '60' },
    async: false,
    ...overrides,
  };
}

function renderComposer(width: number) {
  withComposerWidth(width);
  const selected = model();
  return render(
    <StudioComposer
      models={[selected]}
      selectedModel={selected}
      onSelectModel={vi.fn()}
      onSubmit={vi.fn(async () => true)}
      modeSwitch={<button type="button">mode-switch</button>}
      // Rendered by every studio layout and never folded, so it is part of the row's budget at
      // every width this suite measures. Left out, these tests would keep passing while the
      // control they were written to protect was pushed off the end of a phone.
      lookSwitch={<button type="button">look-switch</button>}
    />,
  );
}

/** The parameter pills, by the label the stubbed translator produces for their trigger. */
function inlineParamPills() {
  return screen.queryAllByRole('button').filter((b) => /resolution|aspect_ratio|duration/i.test(b.textContent ?? ''));
}

beforeEach(() => {
  (globalThis as unknown as { ResizeObserver: typeof ResizeObserverStub }).ResizeObserver = ResizeObserverStub;
});

afterEach(() => {
  vi.restoreAllMocks();
  cleanup();
});

describe('StudioComposer - the button row on a narrow composer', () => {
  it('keeps the mode switch and the send button on a phone-width row', () => {
    // The reported symptom, stated as the invariant it broke: these two must survive any width.
    // A clipped control is not a small control - it cannot be reached at all, and nothing says so.
    renderComposer(360);

    expect(screen.getByText('mode-switch')).toBeInTheDocument();
    expect(screen.getByTitle('composer.send')).toBeInTheDocument();
    // The look switch joined the row later and never folds either, so it is held to the same
    // promise: a control added to a row already over budget is how the next one gets clipped.
    expect(screen.getByText('look-switch')).toBeInTheDocument();
  });

  it('moves the parameters behind ONE trigger instead of squeezing them', () => {
    // Squeezing is what produced "the config buttons are all folded": every pill lost its words
    // and the ones that did not fit were cut off. In the menu they get their words back.
    renderComposer(360);

    expect(screen.getByTitle('composer.parameters')).toBeInTheDocument();
    expect(inlineParamPills()).toHaveLength(0);
  });

  it('drops the model NAME from the picker, keeping it reachable by icon', () => {
    // The widest thing in the row gives up its words first. The name is one tap from being read
    // again; a control pushed out of the row is not.
    renderComposer(360);

    expect(screen.queryByText('Seedance 2.5')).not.toBeInTheDocument();
    expect(screen.getByTitle('Seedance 2.5')).toBeInTheDocument();
  });

  it('keeps the pills inline and the model named on a wide row', () => {
    // The desktop row is unchanged - this is a narrow-width trade, not a redesign.
    renderComposer(900);

    expect(screen.queryByTitle('composer.parameters')).not.toBeInTheDocument();
    expect(screen.getByText('Seedance 2.5')).toBeInTheDocument();
    expect(inlineParamPills().length).toBeGreaterThan(0);
  });

  it('draws the full row when the browser has no ResizeObserver', () => {
    // Server render and jsdom both land here. Falling back to the NARROW row would hide controls
    // on a desktop that never asked for it, so the fallback is deliberately the wide one.
    delete (globalThis as unknown as { ResizeObserver?: unknown }).ResizeObserver;
    renderComposer(360);

    expect(screen.getByText('Seedance 2.5')).toBeInTheDocument();
    expect(screen.queryByTitle('composer.parameters')).not.toBeInTheDocument();
  });
});
