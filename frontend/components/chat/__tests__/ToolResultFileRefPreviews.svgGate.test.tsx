// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/lib/api/orchestrator/file.service', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  fileRefToUrl: (f: { id?: string }) => (f.id ? `/api/proxy/files/by-id/${f.id}/raw` : ''),
}));
// The bytes have their own suite; hand back a stable app-origin blob URL, which is what makes
// this gate matter: the anchor below would open a document on THIS origin.
vi.mock('@/hooks/useAuthedObjectUrl', () => ({
  useAuthedObjectUrl: (src: string | null) => ({ url: src ? `blob:${src}` : null, loading: false, error: false }),
}));

import { ToolResultFileRefPreviews } from '../ToolResultFileRefPreviews';

afterEach(() => cleanup());

const ID = '11111111-1111-1111-1111-111111111111';

function renderRef(mimeType: string, name = 'drawing.svg') {
  return render(
    <ToolResultFileRefPreviews
      rawResult={{ _type: 'file', id: ID, path: `1/general/${name}`, name, mimeType, size: 12 }}
    />,
  );
}

/**
 * An SVG can carry script, and the URL this card renders is a blob this app minted, so opening
 * that document lands on OUR origin with the session in reach. The gate routes an SVG to a
 * download chip instead of the open-in-a-tab image anchor. What it compares decides whether it
 * exists at all: a served type carries parameters and is not guaranteed lowercase.
 */
describe('ToolResultFileRefPreviews - the SVG gate', () => {
  it('never gives an SVG an anchor that opens it, whatever spelling the type arrives in', () => {
    // `IMAGE/SVG` is the spelling only THIS gate can catch: the legacy `image/svg` is not in
    // the executable list, so if this comparison stops normalising, an uppercase legacy SVG
    // walks past both halves and gets an anchor that opens it.
    for (const mimeType of ['image/svg+xml', 'image/svg+xml;charset=utf-8', 'IMAGE/SVG+XML', 'image/svg', 'IMAGE/SVG']) {
      const { container } = renderRef(mimeType);

      // The image card's anchor is the one that opens a tab; the download chip carries
      // `download`, which saves instead. An SVG must only ever get the second.
      const opening = Array.from(container.querySelectorAll('a'))
        .filter((a) => !a.hasAttribute('download'));
      expect(opening, mimeType).toHaveLength(0);
      expect(container.querySelector('a[download]'), mimeType).toBeInTheDocument();
      expect(container.querySelector('img'), mimeType).toBeNull();
      cleanup();
    }
  });

  it('never gives an opening anchor to anything else that EXECUTES when opened', () => {
    // The chip is a plain anchor on the same app-origin blob, and an anchor re-types nothing, so
    // the neutralisation that protects the View action elsewhere cannot reach it. A tool result
    // carrying a scraped or agent-written HTML file is the ordinary case, not a corner.
    for (const mimeType of ['text/html', 'application/xhtml+xml', 'text/xml', 'application/xml']) {
      const { container } = renderRef(mimeType, 'page.html');
      const anchor = container.querySelector('a')!;

      expect(anchor, mimeType).toHaveAttribute('download');
      expect(anchor.getAttribute('aria-label'), mimeType).toMatch(/^Download /);
      cleanup();
    }
  });

  it('still lets a file that only DISPLAYS be opened, so the chip is not a blanket refusal', () => {
    // A PDF and a ZIP keep the friendlier open-in-a-tab behaviour: nothing executes, and the
    // browser's own viewer is what the user wants. This is the control that stops the guard from
    // being widened into "never open anything".
    for (const mimeType of ['application/pdf', 'application/zip']) {
      const { container } = renderRef(mimeType, 'doc.pdf');
      const anchor = container.querySelector('a')!;

      expect(anchor, mimeType).not.toHaveAttribute('download');
      expect(anchor.getAttribute('aria-label'), mimeType).toMatch(/^Open /);
      cleanup();
    }
  });

  it('still shows an ordinary image inline, so the gate is not just "no previews"', () => {
    const { container } = renderRef('image/png', 'shot.png');

    expect(container.querySelector('img')).toBeInTheDocument();
    expect(screen.getByAltText('shot.png')).toBeInTheDocument();
  });
});
