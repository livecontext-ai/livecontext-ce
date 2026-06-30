// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect, vi } from 'vitest';

/**
 * Regression test for bug 2 (fix/visualize-marker-in-codeblocks): the carousel
 * must thread each item's `runId` down to VisualizeBlock. Without it, grouped
 * application cards dropped back to their frozen publish-time showcase instead
 * of the agent's live execution run (the "both cards pinned to the same epoch /
 * live result never shown" bug). VisualizeBlock branches on `if (!runId)` →
 * showcase, so a dropped runId is exactly the regression.
 *
 * These assertions FAIL on the pre-fix carousel, which neither declared nor
 * forwarded `runId` (VisualizeBlock always received undefined → showcase).
 */
const visualizeSpy = vi.fn();
vi.mock('../VisualizeBlock', () => ({
  VisualizeBlock: (props: { type: string; id: string; runId?: string }) => {
    visualizeSpy(props);
    return <div data-testid="viz" data-id={props.id} data-runid={props.runId ?? ''} />;
  },
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { ResourceCarousel, type CarouselItem } from '../ResourceCarousel';

afterEach(() => {
  cleanup();
  visualizeSpy.mockClear();
});

describe('ResourceCarousel - runId threading (bug 2)', () => {
  it('single item: forwards runId to VisualizeBlock', () => {
    const items: CarouselItem[] = [{ vizType: 'application', id: 'app-1', runId: 'run-1' }];
    render(<ResourceCarousel items={items} />);
    expect(screen.getByTestId('viz')).toHaveAttribute('data-runid', 'run-1');
  });

  it('3-field marker (no runId) forwards undefined, not a stale value', () => {
    render(<ResourceCarousel items={[{ vizType: 'application', id: 'app-1' }]} />);
    expect(screen.getByTestId('viz')).toHaveAttribute('data-runid', '');
  });

  it('multi-item: the current slide carries its own run, and navigating shows the next run', () => {
    const items: CarouselItem[] = [
      { vizType: 'application', id: 'app-1', runId: 'run-1' },
      { vizType: 'application', id: 'app-1', runId: 'run-2' },
    ];
    render(<ResourceCarousel items={items} />);
    // Two distinct runs of the SAME app must be two distinct slides - exactly the
    // case that previously collapsed onto one frozen showcase.
    expect(screen.getByTestId('viz')).toHaveAttribute('data-runid', 'run-1');
    fireEvent.click(screen.getByTitle('next'));
    expect(screen.getByTestId('viz')).toHaveAttribute('data-runid', 'run-2');
  });
});
