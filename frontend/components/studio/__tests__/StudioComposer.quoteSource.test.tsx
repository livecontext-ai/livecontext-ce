// @vitest-environment jsdom
/**
 * What the composer hands the price question.
 *
 * <p>The source is read by two things that want different parts of it: the SIZE of the call, which
 * decides how many units the published rate multiplies, and the CHOICES in it, which decide the
 * factor that multiplies the result. Both readings are silent when they are wrong - the reader sees
 * a confident number either way - so what reaches the source is worth pinning.
 *
 * <p>The files are the half that was missing. They live in their own state, so a source built from
 * the value fields alone quotes a call with no images and bills one with three. They are packed
 * through the submission's own shaping, so the quote counts exactly the files the call will send.
 *
 * <p>The values stay RAW, which is the other half of the same decision: passed through that same
 * shaping, a number that does not parse is dropped, and an absent parameter reads as "nothing
 * typed" rather than "typed, and invalid" - the estimate would then fall back to the model's
 * default size and quote a price for a call that cannot run.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => {
    const t = (key: string) => key;
    t.has = () => false;
    return t;
  },
}));
vi.mock('@/hooks/useGenerationOptions', () => ({ useGenerationOptions: () => ({}) }));
vi.mock('@/lib/generation/price', () => ({
  describeQuotedPrice: () => '12 credits',
  describePriceFactors: () => '',
  formatCredits: (value: number) => String(value),
}));
const uploadGeneric = vi.hoisted(() => vi.fn());
vi.mock('@/lib/api/orchestrator/file.service', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/orchestrator/file.service')>()),
  fileService: { uploadGeneric: (...a: unknown[]) => uploadGeneric(...a) },
}));
// The hook is stood in for so its SECOND argument - the source this suite is about - is reachable.
const quoteSource = vi.hoisted(() => ({ last: null as Record<string, unknown> | null }));
vi.mock('@/hooks/useGenerationQuote', () => ({
  useGenerationQuote: (_model: unknown, source: Record<string, unknown>) => {
    quoteSource.last = source;
    return { quote: undefined, quantity: null, settled: true, stale: false, multiplier: 1 };
  },
}));

import { StudioComposer } from '../StudioComposer';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

function model(overrides: Partial<GenerationModel> = {}): GenerationModel {
  return {
    model: 'seedance-2.0',
    kind: 'video',
    label: 'Seedance',
    provider: 'Seedance',
    iconSlug: null,
    apiToolId: 't-1',
    integrationName: 'seedance',
    accepts: ['prompt', 'duration_seconds'],
    required: [],
    limits: {},
    billedOn: 'duration_seconds',
    measuredUnit: 'second',
    defaultQuantity: '5',
    async: false,
    price: { unit: 'second', baseCredits: '0', unitCredits: '100' },
    ...overrides,
  } as GenerationModel;
}

function renderComposer(selected: GenerationModel) {
  return render(
    <StudioComposer
      models={[selected]}
      selectedModel={selected}
      onSelectModel={vi.fn()}
      onSubmit={vi.fn(async () => true)}
    />,
  );
}

/** Type into a parameter the way a reader does: open its control, then the field inside it. */
function typeParam(name: string, value: string) {
  fireEvent.click(screen.getByTitle(name));
  const input = screen.getByRole('spinbutton');
  fireEvent.change(input, { target: { value } });
}

beforeEach(() => {
  quoteSource.last = null;
  uploadGeneric.mockReset();
});
afterEach(cleanup);

describe('StudioComposer - the source the price is computed from', () => {
  it('carries the prompt, which a per-character model is measured by', () => {
    renderComposer(model());

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'a dolly shot' } });

    expect(quoteSource.last).toMatchObject({ prompt: 'a dolly shot' });
  });

  it('carries a typed size, as typed', () => {
    // Raw rather than converted: the estimate distinguishes "nothing typed" from "typed, and not a
    // number", and only the first one is allowed to fall back to the model's default size.
    renderComposer(model({ limits: { duration_seconds: { min: 1, max: 20 } } }));

    typeParam('duration_seconds', '10');

    expect(quoteSource.last).toMatchObject({ duration_seconds: '10' });
  });

  it('carries an emptied size as empty, so the estimate can fall back to the default', () => {
    // "Nothing typed" and "typed, and not a number" are different facts to the estimate, and only
    // the first one may fall back to the model's default size.
    renderComposer(model({ limits: { duration_seconds: { min: 1, max: 20 } } }));

    typeParam('duration_seconds', '10');
    // The control is already open; clicking its trigger again would close it.
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '' } });

    expect(quoteSource.last?.duration_seconds).toBe('');
  });

  it('carries the attached FILES, which live in a state of their own', async () => {
    // Without them a per-file surcharge is quoted as if nothing were attached, and charged on
    // everything that was.
    uploadGeneric.mockResolvedValue({
      id: 'f1', storageKey: 't/1/f1.png', fileName: 'f1.png', mimeType: 'image/png', size: 10,
    });
    const { container } = renderComposer(model({
      accepts: ['prompt', 'reference_image'],
      inputs: { reference_image: { role: 'reference', maxItems: 3 } },
    }));

    fireEvent.click(screen.getByTitle('composer.addFile'));
    fireEvent.click(screen.getAllByText('reference_image')[0]);
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, 'files', {
      value: [new File(['x'], 'a.png', { type: 'image/png' })], configurable: true,
    });
    fireEvent.change(input);

    await waitFor(() => expect(uploadGeneric).toHaveBeenCalled());
    await waitFor(() => expect(Array.isArray(quoteSource.last?.reference_image)).toBe(true));
    expect((quoteSource.last?.reference_image as unknown[]).length).toBe(1);
  });

  it('carries no file slot at all until one is attached', () => {
    // An empty slot is not a file, and a surcharge counted on an empty slot is a charge for
    // nothing.
    renderComposer(model({
      accepts: ['prompt', 'reference_image'],
      inputs: { reference_image: { role: 'reference', maxItems: 3 } },
    }));

    expect(quoteSource.last).not.toHaveProperty('reference_image');
  });
});
