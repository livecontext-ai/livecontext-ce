// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import LandingThemeProvider from '@/components/landing/LandingThemeProvider';
import HeroFlowShowcase from '../HeroFlowShowcase';

// Regression: "gros blanc" under the hero on mobile.
//
// hero-flow.html shipped without a <!DOCTYPE>, so its document rendered in quirks
// mode where `body.scrollHeight` is at least the iframe's own viewport height.
// Feeding that back into the iframe height ratchets it: once a wide viewport set
// 720px+, a narrow one (real content ~300px) kept the tall iframe and painted a
// ~400px blank band under the card. The measurement now reads the content
// wrapper's own box, which shrinks with the width.

const TITLE = 'Watch an automation build itself and run';

/** Install a fake same-origin iframe document: a wrapper of `wrapperHeight`, with
 *  `body.scrollHeight` stuck at the quirks-mode floor (the iframe's own height). */
function stubIframeDocument(iframe: HTMLIFrameElement, wrapperHeight: number, bodyScrollHeight: number) {
  const wrapper = {
    getBoundingClientRect: () => ({ height: wrapperHeight }) as DOMRect,
  } as unknown as Element;
  Object.defineProperty(iframe, 'contentDocument', {
    value: { body: { firstElementChild: wrapper, scrollHeight: bodyScrollHeight } },
    configurable: true,
  });
}

function renderShowcase() {
  render(
    <LandingThemeProvider>
      <HeroFlowShowcase />
    </LandingThemeProvider>,
  );
  return screen.getByTitle(TITLE) as HTMLIFrameElement;
}

describe('HeroFlowShowcase height measurement', () => {
  afterEach(cleanup);

  it('sizes the iframe to the content wrapper, ignoring an inflated body.scrollHeight', async () => {
    const iframe = renderShowcase();
    stubIframeDocument(iframe, 306, 720);

    fireEvent.load(iframe);

    await waitFor(() => expect(iframe.style.height).toBe('306px'));
  });

  it('shrinks below a previously measured tall height when the content gets shorter', async () => {
    const iframe = renderShowcase();

    // Desktop-width measure first: tall content.
    stubIframeDocument(iframe, 814, 814);
    fireEvent.load(iframe);
    await waitFor(() => expect(iframe.style.height).toBe('814px'));

    // Then a phone-width resize: the content collapses, but quirks-mode
    // body.scrollHeight still reports the stale 814px floor.
    stubIframeDocument(iframe, 306, 814);
    fireEvent(window, new Event('resize'));

    await waitFor(() => expect(iframe.style.height).toBe('306px'));
  });

  it('rounds a fractional wrapper height up so the card is never cropped', async () => {
    const iframe = renderShowcase();
    stubIframeDocument(iframe, 305.66, 720);

    fireEvent.load(iframe);

    await waitFor(() => expect(iframe.style.height).toBe('306px'));
  });

  it('falls back to body.scrollHeight when the document has no wrapper element', async () => {
    const iframe = renderShowcase();
    Object.defineProperty(iframe, 'contentDocument', {
      value: { body: { firstElementChild: null, scrollHeight: 512 } },
      configurable: true,
    });

    fireEvent.load(iframe);

    await waitFor(() => expect(iframe.style.height).toBe('512px'));
  });

  it('keeps the current height when the document is unreachable (cross-origin guard)', async () => {
    const iframe = renderShowcase();
    Object.defineProperty(iframe, 'contentDocument', {
      get() {
        throw new Error('cross-origin');
      },
      configurable: true,
    });

    fireEvent.load(iframe);

    // The initial fallback height survives instead of collapsing to 0.
    await waitFor(() => expect(iframe.style.height).toBe('720px'));
  });
});

describe('hero-flow.html document mode', () => {
  it('declares a doctype so the embedded document is not in quirks mode', () => {
    const html = readFileSync(join(process.cwd(), 'public', 'hero-flow.html'), 'utf8');

    // Without it the document renders in quirks mode, where body height is
    // stretched to the viewport and the measured height can never shrink.
    expect(html.trimStart().slice(0, 15).toLowerCase()).toBe('<!doctype html>');
  });
});
