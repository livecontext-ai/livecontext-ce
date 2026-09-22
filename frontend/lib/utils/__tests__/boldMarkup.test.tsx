// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it } from 'vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';

import { renderBoldMarkup } from '../boldMarkup';

/**
 * The marker exists so a translated string can carry emphasis through a pipeline
 * that only moves strings (see the module's own note). Everything below is a
 * property that would otherwise fail silently, in one locale at a time: a figure
 * that is not bold, a sentence that lost its surrounding words, or a stray `**`
 * printed at a customer.
 */

afterEach(() => cleanup());

function renderMarkup(text: string) {
  render(<p data-testid="out">{renderBoldMarkup(text)}</p>);
  return screen.getByTestId('out');
}

describe('renderBoldMarkup', () => {
  it('bolds the marked span and keeps every word around it', () => {
    const out = renderMarkup('About **214 credits** for a short exchange. Not a cap.');

    expect(out).toHaveTextContent('About 214 credits for a short exchange. Not a cap.');
    const strong = out.querySelector('strong');
    expect(strong).not.toBeNull();
    expect(strong).toHaveTextContent('214 credits');
  });

  it('bolds each of several figures separately rather than swallowing the text between them', () => {
    // The regression this pins: a greedy pattern would bold from the first `**`
    // to the last, turning the whole sentence into one emphasised block.
    const out = renderMarkup('About **80 credits** for one and **300** for the other.');

    const bolded = Array.from(out.querySelectorAll('strong')).map((node) => node.textContent);
    expect(bolded).toEqual(['80 credits', '300']);
    expect(out).toHaveTextContent('About 80 credits for one and 300 for the other.');
  });

  it('leaves a string with no marker exactly as it was', () => {
    const out = renderMarkup('A separate monthly allowance for chat and agent turns.');

    expect(out.querySelector('strong')).toBeNull();
    expect(out).toHaveTextContent('A separate monthly allowance for chat and agent turns.');
  });

  it('keeps an unpaired marker visible instead of hiding a broken translation', () => {
    // Deliberate: an odd marker is an authoring mistake in ONE locale, and a
    // stray `**` on screen is how it gets found. Swallowing it would leave that
    // locale silently un-bolded while every other one was fine.
    const out = renderMarkup('About **214 credits for a short exchange.');

    expect(out.querySelector('strong')).toBeNull();
    expect(out).toHaveTextContent('About **214 credits for a short exchange.');
  });

  it('handles a marker at the very start and at the very end of the string', () => {
    // Both edges skip one of the two slice-pushes, and a locale is free to open
    // or close its sentence on the figure: zh already opens several of them on a
    // clause the latin locales end with.
    const opening = renderMarkup('**214 credits** for a short exchange.');
    expect(opening.querySelector('strong')).toHaveTextContent('214 credits');
    expect(opening).toHaveTextContent('214 credits for a short exchange.');

    cleanup();
    const closing = renderMarkup('A short exchange costs **214 credits**');
    expect(closing.querySelector('strong')).toHaveTextContent('214 credits');
    expect(closing).toHaveTextContent('A short exchange costs 214 credits');
  });

  it('marks a figure written in a non-latin script, where the unit follows the number', () => {
    // zh writes the unit after the figure and inside the marker; nothing about
    // the split may assume a latin word boundary.
    const out = renderMarkup('约需 **214 积分**。');

    expect(out.querySelector('strong')).toHaveTextContent('214 积分');
  });
});
