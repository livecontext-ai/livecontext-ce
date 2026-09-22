// @vitest-environment jsdom
import { describe, it, expect, afterEach, beforeAll, vi } from 'vitest';
import React from 'react';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import FeatureLabel from '../FeatureLabel';

beforeAll(() => {
  // Radix positioning (@floating-ui) needs ResizeObserver, absent from jsdom.
  class ResizeObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  vi.stubGlobal('ResizeObserver', ResizeObserverStub);
});

afterEach(() => cleanup());

describe('FeatureLabel', () => {
  it('renders a plain feature with no info icon when there is no || tooltip', () => {
    render(<FeatureLabel feature="100 MB storage" />);
    expect(screen.getByText('100 MB storage')).toBeTruthy();
    // No tooltip trigger button when the feature carries no embedded tooltip.
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('renders the label plus an accessible info "i" trigger when a ||tooltip is present', () => {
    render(
      <FeatureLabel feature="1,000 credits per month||Free credits power your workflows." />
    );
    // The label is shown without the delimiter and without the tooltip text inline.
    expect(screen.getByText('1,000 credits per month')).toBeTruthy();
    expect(screen.queryByText(/Free credits power your workflows/)).toBeNull(); // closed by default
    // The info icon is an accessible button named after the feature label.
    expect(screen.getByRole('button', { name: '1,000 credits per month' })).toBeTruthy();
  });

  it('opens its tooltip ABOVE the plan-comparison dialog, not behind it', async () => {
    // The regression this pins: TooltipContent is portalled to document.body, so its
    // z-index competes with the whole page. At the old shared default of z-[9999] the
    // "i" inside the comparison table (dialog z-[100000]) opened its tooltip UNDERNEATH
    // the dialog - hovering it showed nothing at all, with no error anywhere. The
    // assertion is on the MERGED class list of the portalled element, because the fix
    // only holds if tailwind-merge resolves the z-* conflict the way we expect.
    render(<FeatureLabel feature="Nodes||Some nodes need a paid plan." />);

    fireEvent.focus(screen.getByRole('button', { name: 'Nodes' }));
    // Radix renders the content twice: the visible bubble and a visually-hidden
    // copy for screen readers. Only the visible one carries the z-index layer.
    const rendered = await screen.findAllByText('Some nodes need a paid plan.');
    const layer = rendered
      .map((node) => node.closest('[class*="z-["]'))
      .find((node): node is Element => node !== null);

    expect(layer, 'the tooltip should carry an explicit z-index layer').toBeTruthy();
    const classes = (layer as HTMLElement).className;
    expect(classes).not.toContain('z-[9999]');
    const z = Number(/z-\[(\d+)\]/.exec(classes)?.[1] ?? 0);
    // 100000 is the plan-comparison dialog; 100001 the ModelInfo (i) card.
    expect(z).toBeGreaterThan(100001);
  });

  it('splits on the first || only and keeps the label exact', () => {
    render(<FeatureLabel feature="Label||tip" />);
    expect(screen.getByText('Label')).toBeTruthy();
    expect(screen.queryByText('Label||tip')).toBeNull();
  });

  it('pins the "i" to the right of the row, so a wrapping label cannot pull it inward', () => {
    // The defect: the icon used to sit immediately after the last word, which
    // looks right-aligned only while every label is one line. As soon as one
    // wraps, its icon lands mid-row while its neighbours' sit at the edge.
    // The row is a full-width flex line and the icon is its LAST item, pushed
    // out by justify-between - the only arrangement jsdom can hold us to.
    const { container } = render(
      <FeatureLabel feature="A feature whose label is long enough to wrap onto a second line||why" />
    );

    const row = container.firstElementChild as HTMLElement;
    expect(row.className).toContain('flex');
    expect(row.className).toContain('justify-between');
    // Width to fill, or justify-between has nothing to push against.
    expect(row.className).toContain('flex-1');
    const icon = screen.getByRole('button', {
      name: 'A feature whose label is long enough to wrap onto a second line',
    });
    expect(row.lastElementChild).toBe(icon);
    // Top-aligned, so the icon stays on the label's FIRST line when it wraps.
    expect(row.className).toContain('items-start');
  });

  it('bolds a figure the tooltip marks, and shows no marker to the reader', () => {
    render(<FeatureLabel feature="5,000 credits per month||About **80 credits** for a plain question." />);

    fireEvent.focus(screen.getByRole('button', { name: '5,000 credits per month' }));
    const bolded = Array.from(document.body.querySelectorAll('strong'))
      .map((node) => node.textContent);
    expect(bolded).toContain('80 credits');
    expect(document.body.textContent).not.toContain('**');
  });
});
