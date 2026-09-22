// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';

import LogoAnimate from '../LogoAnimate';

/**
 * The mark is either a named image or invisible to assistive tech, never both.
 *
 * <p>`decorative` exists because the landing chrome puts the logo immediately beside the word
 * "LiveContext", so announcing the mark as well made the brand link read "Logo LiveContext",
 * with the first half untranslatable and stuck in English on all six locales.
 *
 * <p>Asserted by RENDERING rather than by scanning the source, which is what the chrome's own
 * guard does. A source scan pins the call sites; it cannot see the component quietly emitting
 * `aria-hidden` AND `aria-label` together, which announces the word again while looking
 * correct everywhere the prop is passed.
 */
afterEach(cleanup);

const svg = (container: HTMLElement) => container.querySelector('svg')!;

describe('the brand mark and assistive technology', () => {
  it('is announced as an image when it stands on its own', () => {
    // Its other callers use it without a brand word beside it, so the default has to keep a
    // name: an unnamed graphic in a link leaves the link unnamed.
    const { container } = render(<LogoAnimate />);
    expect(svg(container)).toHaveAttribute('role', 'img');
    expect(svg(container)).toHaveAttribute('aria-label', 'Logo');
    expect(svg(container)).not.toHaveAttribute('aria-hidden');
  });

  it('disappears from the accessibility tree when it is decorative', () => {
    const { container } = render(<LogoAnimate decorative />);
    expect(svg(container)).toHaveAttribute('aria-hidden', 'true');
    // Both of these, because `aria-hidden` next to a name is not a hidden element: the name
    // is what a screen reader reads, and leaving it there would restore the exact bug.
    expect(svg(container)).not.toHaveAttribute('aria-label');
    expect(svg(container)).not.toHaveAttribute('role');
  });
});
