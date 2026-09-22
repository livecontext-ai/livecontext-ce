// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

/**
 * What this workspace has generated.
 *
 * <p>The list has one job the file browser cannot do: say what an asset WAS MADE FROM. A generated
 * file is called something like {@code 20260824_elevenlabs-text-to-speech.mp3} and looks exactly
 * like an upload, so the things pinned here are the ones that make the list worth having - the
 * prompt is the title, the model is named, and the way back into the form is on every entry that
 * can still be run.
 */

const mocks = vi.hoisted(() => ({
  useGenerationHistory: vi.fn(),
  useGenerationModels: vi.fn(),
}));

vi.mock('@/hooks/useGenerationHistory', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/useGenerationHistory')>();
  return { ...actual, useGenerationHistory: mocks.useGenerationHistory };
});

vi.mock('@/hooks/useGenerationModels', () => ({
  useGenerationModels: mocks.useGenerationModels,
}));

/**
 * The thumbnail. Reduced to a marker: it fetches bytes with a Bearer header and renders them from
 * a blob URL, neither of which jsdom serves, and it has its own tests. What matters here is that
 * the entry reaches it, addressed by its opaque id.
 */
vi.mock('@/components/files/FileCard', () => ({
  FileThumb: ({ entry }: any) => <span data-testid="thumb" data-id={entry.id} />,
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${Object.values(values).join(',')}` : key,
}));

import { GenerationHistoryList } from '../GenerationHistoryList';

function entry(over: Record<string, unknown> = {}) {
  return {
    id: 'a1',
    fileName: '20260824_flux.png',
    mimeType: 'image/png',
    sizeBytes: 2048,
    formattedSize: '2.0 KB',
    createdAt: '2026-08-24T10:00:00Z',
    s3Key: 'tenant/general/20260824_flux.png',
    provenance: {
      model: 'flux-1.1-pro',
      kind: 'image',
      prompt: 'a lighthouse at dusk',
      params: { aspect_ratio: '16:9' },
    },
    ...over,
  };
}

function history(over: Record<string, unknown> = {}) {
  return {
    entries: [entry()],
    hasMore: false,
    isLoading: false,
    isError: false,
    ...over,
  };
}

beforeEach(() => {
  mocks.useGenerationHistory.mockReturnValue(history());
  mocks.useGenerationModels.mockReturnValue({
    models: [
      { model: 'flux-1.1-pro', kind: 'image', label: 'FLUX 1.1 Pro', iconSlug: 'flux' },
      { model: 'seedance-2.0', kind: 'video', label: 'Seedance 2.0', iconSlug: null },
    ],
    isLoading: false,
    availability: 'ready',
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('GenerationHistoryList', () => {
  it('titles an entry with the PROMPT, which is how a person recognises it', () => {
    // The file name is a timestamp and a slug: it says nothing about what the reader was making.
    render(<GenerationHistoryList />);

    expect(screen.getByText('a lighthouse at dusk')).toBeInTheDocument();
  });

  it('falls back to the file name when the model takes no prompt', () => {
    mocks.useGenerationHistory.mockReturnValue(history({
      entries: [entry({ provenance: { model: 'flux-1.1-pro', kind: 'image' } })],
    }));

    render(<GenerationHistoryList />);

    expect(screen.getByText('20260824_flux.png')).toBeInTheDocument();
  });

  it('names the model in the catalogue words, not by its wire id', () => {
    render(<GenerationHistoryList />);

    expect(screen.getByText('FLUX 1.1 Pro')).toBeInTheDocument();
  });

  it('still names a model that has left the catalogue, by its id', () => {
    // Losing the row would leave the entry saying nothing at all about what made it.
    mocks.useGenerationHistory.mockReturnValue(history({
      entries: [entry({ provenance: { model: 'retired-model', kind: 'image', prompt: 'a boat' } })],
    }));

    render(<GenerationHistoryList />);

    expect(screen.getByText('retired-model')).toBeInTheDocument();
  });

  it('hands the whole entry back when one is reused', () => {
    const onReuse = vi.fn();
    render(<GenerationHistoryList onReuse={onReuse} />);

    fireEvent.click(screen.getByText('modify'));

    expect(onReuse).toHaveBeenCalledWith(expect.objectContaining({ id: 'a1' }));
  });

  it('refuses to reuse a model that is no longer available', () => {
    // The form has no row to select and the id would be refused after everything was filled in
    // again, so the refusal belongs on the button rather than at the end of the flow.
    mocks.useGenerationHistory.mockReturnValue(history({
      entries: [entry({ provenance: { model: 'retired-model', kind: 'image', prompt: 'a boat' } })],
    }));
    const onReuse = vi.fn();

    render(<GenerationHistoryList onReuse={onReuse} />);

    expect(screen.getByText('modify').closest('button')).toBeDisabled();
  });

  it('offers no reuse control at all where there is nowhere for it to lead', () => {
    render(<GenerationHistoryList />);

    expect(screen.queryByText('modify')).not.toBeInTheDocument();
  });

  it('states what each generation cost when the platform charged for it', () => {
    // The one number a reader cannot get anywhere else: the usage page knows the total, and the
    // model listing knows today's rate, but only the asset knows what THIS one was charged.
    mocks.useGenerationHistory.mockReturnValue(history({
      entries: [entry({ provenance: {
        model: 'flux-1.1-pro', kind: 'image', prompt: 'a boat', billedCredits: 78,
      } })],
    }));

    render(<GenerationHistoryList />);

    // The VALUE, anchored: this suite renders through a stub translator that emits the key and its
    // arguments (`cost:78,78`), so the amount is observable even though the wording is not. Only
    // presence was checked here at first, and doubling the number at the call site left every test
    // in the file green - the binding from the stored recipe to the card was covered by nothing.
    // What a reader actually SEES is pinned exactly, per edition and per locale, in
    // GenerationCard.price.realIntl.test.tsx.
    expect(screen.getByTitle('costTitle')).toHaveTextContent(/^cost:78,/);
  });

  it('says nothing about price for a generation the platform did not charge for', () => {
    // Run on the reader's own provider key: they paid that provider. Drawing "0 credits" would
    // report a price that was never charged, on the surface where prices are compared.
    render(<GenerationHistoryList />);

    expect(screen.queryByTitle('costTitle')).not.toBeInTheDocument();
  });

  it('says nothing about price for an amount of zero, or one that is not a number', () => {
    // Zero is reachable (an endpoint published at nothing) and NaN is reachable (a value that
    // crossed JSON badly). Both would draw a price that was never charged: "0 credits" reads as
    // free, and a broken number reads as a bug in the ledger.
    mocks.useGenerationHistory.mockReturnValue(history({
      entries: [
        entry({ id: 'z', provenance: { model: 'flux-1.1-pro', kind: 'image', prompt: 'zero', billedCredits: 0 } }),
        entry({ id: 'n', provenance: { model: 'flux-1.1-pro', kind: 'image', prompt: 'nan', billedCredits: Number.NaN } }),
      ],
    }));

    render(<GenerationHistoryList />);

    expect(screen.queryByTitle('costTitle')).not.toBeInTheDocument();
  });

  it('keeps its heading and itself out of a surface where nothing has been generated', () => {
    // For a page where the list is an addition rather than the point: a heading over an empty
    // state is the empty shelf, and the studio has no reason to show one on a fresh install.
    mocks.useGenerationHistory.mockReturnValue(history({ entries: [] }));

    const { container } = render(<GenerationHistoryList heading="Your generations" hideWhenEmpty />);

    expect(container).toBeEmptyDOMElement();
  });

  it('keeps that surface intact while the answer is still coming, and when it fails', () => {
    // Hiding during the load would make the section appear late and jump the page; hiding on a
    // failed request would state that nothing has been generated, which a failure does not say.
    mocks.useGenerationHistory.mockReturnValue(history({ entries: [], isLoading: true }));
    const { container, rerender } = render(
      <GenerationHistoryList heading="Your generations" hideWhenEmpty />,
    );
    expect(container).not.toBeEmptyDOMElement();

    mocks.useGenerationHistory.mockReturnValue(history({ entries: [], isError: true }));
    rerender(<GenerationHistoryList heading="Your generations" hideWhenEmpty />);

    expect(screen.getByText('error')).toBeInTheDocument();
  });

  it('stays on screen when a FORMAT filter is what emptied it, so the chips remain', () => {
    // Not "nothing has been generated": the reader narrowed to a format that happens to have none.
    // Hiding takes the chips away with the grid, and there is then no way back to the other formats.
    mocks.useGenerationHistory.mockReturnValue(history({ hasMore: true }));
    const { rerender } = render(<GenerationHistoryList heading="Your generations" hideWhenEmpty />);

    fireEvent.click(screen.getByText('formats.video'));
    mocks.useGenerationHistory.mockReturnValue(history({ entries: [] }));
    rerender(<GenerationHistoryList heading="Your generations" hideWhenEmpty />);

    expect(screen.getByText('emptyForFormat:formats.video')).toBeInTheDocument();
  });

  it('stays on screen when a PAGE past the first is what emptied it, so Previous remains', () => {
    // `page` state survives the hide, so a section that disappears here stays gone until the
    // component remounts - with the reader stranded on a page they cannot leave.
    mocks.useGenerationHistory.mockReturnValue(history({ hasMore: true }));
    const { rerender } = render(<GenerationHistoryList heading="Your generations" hideWhenEmpty />);

    fireEvent.click(screen.getByText('next'));
    mocks.useGenerationHistory.mockReturnValue(history({ entries: [], hasMore: false }));
    rerender(<GenerationHistoryList heading="Your generations" hideWhenEmpty />);

    expect(screen.getByText('previous')).toBeInTheDocument();
  });

  it('opens the asset itself when asked to', () => {
    const onOpen = vi.fn();
    render(<GenerationHistoryList onOpen={onOpen} />);

    fireEvent.click(screen.getByTestId('thumb').closest('button')!);

    expect(onOpen).toHaveBeenCalledWith(expect.objectContaining({ id: 'a1' }));
  });

  it('says a request FAILED rather than claiming nothing has been generated', () => {
    // The two look identical on screen if both render the empty state, and one of them tells a
    // reader whose assets exist to start over.
    mocks.useGenerationHistory.mockReturnValue(history({ entries: [], isError: true }));

    render(<GenerationHistoryList />);

    expect(screen.getByText('error')).toBeInTheDocument();
    expect(screen.queryByText('empty')).not.toBeInTheDocument();
  });

  it('says the history is empty when it really is', () => {
    mocks.useGenerationHistory.mockReturnValue(history({ entries: [] }));

    render(<GenerationHistoryList />);

    expect(screen.getByText('empty')).toBeInTheDocument();
  });

  it('asks the server for the chosen format, and starts that list at its first page', () => {
    // Page 3 of "everything" is not page 3 of "images": kept, the offset lands past the end of the
    // filtered list and shows an empty grid under a non-zero total.
    mocks.useGenerationHistory.mockReturnValue(history({ hasMore: true }));
    render(<GenerationHistoryList />);

    fireEvent.click(screen.getByText('next'));
    expect(mocks.useGenerationHistory).toHaveBeenLastCalledWith(1, undefined, true);

    fireEvent.click(screen.getByText('formats.video'));

    expect(mocks.useGenerationHistory).toHaveBeenLastCalledWith(0, 'video', true);
  });

  it('draws the format filters as the shared button, not as pills', () => {
    // The design system is flat with a soft radius; its own docblock says it replaced the older
    // pill shape. A hand-rolled rounded-full chip is that older look surviving in one corner, and
    // it stops following the system the next time the radius or the accent moves. Pinned on the
    // shape the shared component gives, so a rewrite back to a bespoke chip fails here.
    render(<GenerationHistoryList />);

    const filter = screen.getByText('formats.image').closest('button')!;
    expect(filter.className).toContain('rounded-xl');
    expect(filter.className).not.toContain('rounded-full');
  });

  it('marks the chosen format with the same solid/outline pairing the Files toggle uses', () => {
    // Two controls doing the same kind of job must not read as two different ideas of "selected".
    render(<GenerationHistoryList />);

    expect(screen.getByText('allFormats').closest('button')).toHaveAttribute('data-variant', 'default');
    expect(screen.getByText('formats.image').closest('button')).toHaveAttribute('data-variant', 'outline');

    fireEvent.click(screen.getByText('formats.image'));

    expect(screen.getByText('formats.image').closest('button')).toHaveAttribute('data-variant', 'default');
    expect(screen.getByText('allFormats').closest('button')).toHaveAttribute('data-variant', 'outline');
  });

  it('costs nothing while the panel it lives in is closed', () => {
    render(<GenerationHistoryList enabled={false} />);

    expect(mocks.useGenerationHistory).toHaveBeenLastCalledWith(0, undefined, false);
  });

  it('offers no pager when everything fits on one page', () => {
    render(<GenerationHistoryList />);

    expect(screen.queryByText('next')).not.toBeInTheDocument();
  });

  it('stops offering a next page once the server says this one is the last', () => {
    // Without this the pager would walk the reader onto an empty screen, which is exactly what a
    // total-less slice has to prevent.
    mocks.useGenerationHistory.mockReturnValue(history({ hasMore: false }));

    render(<GenerationHistoryList />);

    expect(screen.queryByText('next')).not.toBeInTheDocument();
  });
});
