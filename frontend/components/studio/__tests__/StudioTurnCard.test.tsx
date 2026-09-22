// @vitest-environment jsdom
/**
 * The turn card, which is where the studio tells a reader whether their money moved.
 *
 * <p>The card renders THREE mutually exclusive money states and, before this suite, none of them
 * had a test:
 *
 * <ul>
 *   <li><b>answered</b> - the asset is on screen, the charge happened and produced something;
 *   <li><b>refused</b> - stated as refused, and a refusal on this path costs nothing;
 *   <li><b>unanswered</b> - the request was written down, the answer never came, so the generation
 *       may have run and been CHARGED. This is the expensive one: it must not be worded as a
 *       failure, because "failed" invites a resend and a resend is a second purchase.
 * </ul>
 *
 * <p>They are asserted as mutually exclusive rather than one at a time. The bug this guards is not
 * "the wrong text appears", it is "the right text appears NEXT TO the wrong one" - a refused turn
 * that also carries the may-have-been-charged warning tells the reader two contradictory things
 * about their money, and reading only for the presence of the correct sentence cannot see it.
 *
 * <p>Rendered against the REAL dictionary. The distinction between these three states lives
 * entirely in their wording, so a stub translator returning key paths would let every assertion
 * below pass on a card that says nothing at all.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

// The app's file viewer: real here would pull the by-id fetch, the media element and the object-URL
// hook into a test about wording. Its PRESENCE is what this suite checks, plus the id it is given.
vi.mock('@/components/app/FileDetailView', () => ({
  FileDetailView: ({ entryId }: { entryId: string }) => (
    <div data-testid="file-detail" data-entry-id={entryId} />
  ),
}));
vi.mock('@/lib/generation/formats', () => ({
  FormatGlyph: () => <span data-testid="format-glyph" />,
  ProviderIcon: ({ slug }: { slug?: string | null }) =>
    slug ? <span data-testid="provider-icon" data-slug={slug} /> : null,
}));

import { StudioTurnCard } from '../StudioTurnCard';
import type { StudioRequestEnvelope, StudioResultEnvelope } from '@/lib/generation/studioMessage';

const REQUEST: StudioRequestEnvelope = {
  type: '__GENERATION__',
  role: 'request',
  prompt: 'a lighthouse in fog',
  model: 'seedance-2',
  kind: 'video',
  provider: 'seedance',
};

function result(over: Partial<StudioResultEnvelope> = {}): StudioResultEnvelope {
  return {
    type: '__GENERATION__',
    role: 'result',
    success: true,
    model: 'seedance-2',
    kind: 'video',
    ...over,
  } as StudioResultEnvelope;
}

function renderCard(props: Partial<React.ComponentProps<typeof StudioTurnCard>> = {}) {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <StudioTurnCard request={REQUEST} {...props} />
    </NextIntlClientProvider>,
  );
}

/** The exact sentence that says "this may have cost you something". */
const MAY_HAVE_BEEN_CHARGED = /may have|charged/i;

afterEach(cleanup);

describe('StudioTurnCard - the three money states', () => {
  it('warns that a turn with no answer may still have been charged', () => {
    // No result and nothing in flight. The request is already in the thread, so the server may have
    // finished it: the card must not call this a failure.
    renderCard();

    expect(screen.getByText(MAY_HAVE_BEEN_CHARGED)).toBeInTheDocument();
    expect(screen.queryByTestId('file-detail')).toBeNull();
  });

  it('shows a refusal as a refusal, and NOT as a possible charge', () => {
    // The inversion this exists for. A refusal is answered and free; leaving the unanswered warning
    // beside it tells the reader their money may have moved when it certainly did not.
    renderCard({ result: result({ success: false, error: 'Model is not available in your region' }) });

    expect(screen.getByText(/not available in your region/i)).toBeInTheDocument();
    expect(screen.queryByText(MAY_HAVE_BEEN_CHARGED)).toBeNull();
  });

  it('shows the asset when the turn succeeded, and neither warning', () => {
    renderCard({
      result: result({ file: { id: 'file-77', name: 'clip.mp4', mimeType: 'video/mp4' } as never }),
    });

    expect(screen.getByTestId('file-detail')).toHaveAttribute('data-entry-id', 'file-77');
    expect(screen.queryByText(MAY_HAVE_BEEN_CHARGED)).toBeNull();
  });

  it('says nothing about a charge while the turn is still running', () => {
    // Same shape as "unanswered" - no result - and the ONLY thing separating them is isRunning.
    // Getting this wrong paints the may-have-been-charged warning over every healthy generation.
    renderCard({ isRunning: true });

    expect(screen.queryByText(MAY_HAVE_BEEN_CHARGED)).toBeNull();
  });

  it('states that a successful turn produced nothing this card can show, without warning of a charge', () => {
    // Success with no file id: certain that it finished, certain that nothing is previewable here.
    renderCard({ result: result({ success: true }) });

    expect(screen.queryByTestId('file-detail')).toBeNull();
    expect(screen.queryByText(MAY_HAVE_BEEN_CHARGED)).toBeNull();
  });
});

describe('StudioTurnCard - what the card attributes and offers', () => {
  it('names the model that RAN the turn, not the one selected now', () => {
    // The reader changes model mid-thread; a card labelled with the current selection would
    // misattribute every earlier asset in the conversation.
    renderCard({ result: result({ model: 'flux-1' }) });

    expect(screen.getByText('flux-1')).toBeInTheDocument();
    expect(screen.queryByText('seedance-2')).toBeNull();
  });

  it('falls back to the requested model when the answer never named one', () => {
    renderCard();
    expect(screen.getByText('seedance-2')).toBeInTheDocument();
  });

  it('says a promptless turn ran on its files, rather than rendering an empty line', () => {
    render(
      <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
        <StudioTurnCard request={{ ...REQUEST, prompt: '' }} />
      </NextIntlClientProvider>,
    );

    expect(screen.getByText(/files and settings/i)).toBeInTheDocument();
  });

  it('states what the turn cost, in the same words as the history below it', () => {
    // A turn that names the size it was billed on and not the amount sends the reader to look the
    // price up elsewhere for the generation they just ran.
    render(
      <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
        <StudioTurnCard request={REQUEST} result={{ ...result(), billedCredits: 78 }} />
      </NextIntlClientProvider>,
    );

    expect(screen.getByText('78 credits')).toBeInTheDocument();
  });

  it('states NO cost for a turn the platform did not charge for, rather than a zero', () => {
    // The reader's own provider key paid. "0 credits" under it would be a claim about money.
    render(
      <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
        <StudioTurnCard request={REQUEST} result={{ ...result(), billedCredits: 0 }} />
      </NextIntlClientProvider>,
    );

    expect(screen.queryByText(/credits?$/)).not.toBeInTheDocument();
  });

  it('hands the WHOLE request back to be modified, so a replay is the same turn', () => {
    // The control loads a recipe back into the composer. Passing anything less than the original
    // envelope - dropping the params, say - silently replays a DIFFERENT and cheaper-looking
    // generation.
    const onReuse = vi.fn();
    const withParams: StudioRequestEnvelope = { ...REQUEST, params: { seed: 7 } };
    render(
      <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
        <StudioTurnCard request={withParams} result={result()} onReuse={onReuse} />
      </NextIntlClientProvider>,
    );

    // Named as the reader sees it, in the real dictionary: one verb for this action on every
    // surface that offers it, so a rename that split them again fails here.
    fireEvent.click(screen.getByRole('button', { name: /^Modify$/i }));

    expect(onReuse).toHaveBeenCalledWith(withParams);
  });

  it('renders no provider mark at all for a retired model, rather than a placeholder', () => {
    renderCard({ iconSlug: null, result: result() });
    expect(screen.queryByTestId('provider-icon')).toBeNull();
  });
});

describe('StudioTurnCard - a failure that was charged anyway', () => {
  /**
   * The most expensive state on this card, and the one with no other witness.
   *
   * <p>Billing commits before the asset is fetched and stored, so a generation can run, be paid
   * for, and leave no file. What the reader must not conclude is "it failed, send it again": that
   * is a second purchase. And because nothing was stored, the provider's own short-lived link is
   * the ONLY route back to what they bought, so it has to be on screen while it is still alive.
   */
  const CHARGED = { success: false, error: 'The generation ran but no asset could be retrieved.',
    chargedAnyway: true, assetUrl: 'https://provider.example/clip.mp4?exp=1' };

  it('says the generation was charged for, rather than only that it failed', () => {
    renderCard({ result: result(CHARGED as never) });

    expect(screen.getByText(/charged/i)).toBeInTheDocument();
  });

  it('offers the provider link, which is the only copy of what was paid for', () => {
    renderCard({ result: result(CHARGED as never) });

    expect(screen.getByRole('link'))
      .toHaveAttribute('href', 'https://provider.example/clip.mp4?exp=1');
  });

  it('opens that link in a new tab, so leaving the studio does not lose the thread', () => {
    // The link expires in minutes and the page it sits on is the only record of it. Navigating
    // away in place would drop the turn card and, with it, any second chance to save the asset.
    renderCard({ result: result(CHARGED as never) });

    expect(screen.getByRole('link')).toHaveAttribute('target', '_blank');
  });

  it('shows NEITHER the charge notice nor a link for an ordinary refusal', () => {
    // The other direction. A refusal never reached the provider and cost nothing; telling a reader
    // they may have paid when they did not is its own defect, and a warning that cries wolf is one
    // readers learn to skip when it is real.
    renderCard({ result: result({ success: false, error: 'No published price' }) });

    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.queryByText(/charged/i)).toBeNull();
  });

  it('states the charge even when the provider handed back no link at all', () => {
    // The endpoint reports this case explicitly: the asset URL was not where the descriptor said.
    // There is nothing to recover, and saying so is still what stops the reader paying twice.
    renderCard({ result: result({ success: false, error: 'no asset URL was found',
      chargedAnyway: true } as never) });

    expect(screen.getByText(/charged/i)).toBeInTheDocument();
    expect(screen.queryByRole('link')).toBeNull();
  });
});
