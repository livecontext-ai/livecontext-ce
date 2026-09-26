// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import * as React from 'react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StudioBackdrop } from '../StudioBackdrop';

describe('StudioBackdrop - the fixed ambient ground behind the studio', () => {
  it('draws the ambient scene behind its content, hidden from assistive technology', () => {
    render(<StudioBackdrop><p>studio content</p></StudioBackdrop>);

    const ambient = screen.getByTestId('studio-ambient');
    // Decoration only: a screen reader must not walk five empty spans before the composer.
    expect(ambient).toHaveAttribute('aria-hidden', 'true');
    expect(ambient.querySelectorAll('.studio-ambient-orb')).toHaveLength(3);
    // The content still renders, beside the layer rather than inside it.
    expect(screen.getByText('studio content')).toBeInTheDocument();
    expect(ambient).not.toContainElement(screen.getByText('studio content'));
  });

  it('gives the layer a stacking context of its own, so -z-10 stays behind the studio only', () => {
    const { container } = render(<StudioBackdrop><p>x</p></StudioBackdrop>);
    // Without `isolate` the negative z-index escapes to the nearest ancestor context and the scene
    // is painted UNDER the page background, i.e. not at all.
    expect(container.firstElementChild).toHaveClass('relative', 'isolate');
  });

  it('keeps the layer out of layout and out of the way of every click', () => {
    // The two properties that, lost, turn decoration into a bug: a layer in the flow pushes the
    // composer down, and a layer that takes pointer events swallows every click on the studio.
    const css = readFileSync(join(process.cwd(), 'app', 'globals.css'), 'utf8');
    const rule = /\.studio-ambient \{([^}]*)\}/.exec(css)?.[1] ?? '';
    expect(rule).toMatch(/position:\s*absolute;/);
    expect(rule).toMatch(/inset:\s*0;/);
    expect(rule).toMatch(/z-index:\s*-10;/);
    expect(rule).toMatch(/pointer-events:\s*none;/);
    // The colour is masked out of the centre column, where the text sits.
    // Pinned to the geometry the contrast was measured with: a narrower clear centre lets the
    // colour back under the text.
    expect(rule).toContain('mask-image: radial-gradient(ellipse 62% 60% at 50% 45%, transparent 68%, #000 100%)');
  });

  it('pins the field opacities the contrast was measured at', () => {
    // Muted text over the centre was measured in a real browser at every keyframe with these
    // values. Raising either one invalidates that measurement, so it has to be a deliberate edit.
    const css = readFileSync(join(process.cwd(), 'app', 'globals.css'), 'utf8');
    const light = /\.studio-ambient \{[^}]*--studio-orb-opacity:\s*([\d.]+);/.exec(css)?.[1];
    const dark = /\.dark \.studio-ambient \{[^}]*--studio-orb-opacity:\s*([\d.]+);/.exec(css)?.[1];
    expect(Number(light)).toBeLessThanOrEqual(0.34);
    expect(Number(dark)).toBeLessThanOrEqual(0.24);
  });

  it('stops every animated layer under prefers-reduced-motion', () => {
    const css = readFileSync(join(process.cwd(), 'app', 'globals.css'), 'utf8');
    // Every selector list inside a reduced-motion block whose declaration is `animation: none`.
    // Each chunk is cut at the block's closing brace, so a rule OUTSIDE the media query never counts.
    const blocks = css.split('@media (prefers-reduced-motion: reduce)').slice(1)
      .map((chunk) => chunk.slice(0, chunk.search(/\}\s*\}/) + 1));
    const stopped = blocks
      .flatMap((b) => [...b.matchAll(/([^{}]+)\{\s*animation:\s*none;/g)].map((m) => m[1]))
      .join(' ');
    expect(stopped).toContain('.studio-ambient-orb');
    expect(stopped).toContain('.studio-ambient-grid');
  });
});
