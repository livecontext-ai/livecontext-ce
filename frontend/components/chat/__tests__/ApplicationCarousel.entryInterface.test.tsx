/**
 * @vitest-environment jsdom
 *
 * Entry-interface opening behavior of {@link ApplicationCarousel}.
 *
 * An application declares ONE entry page (isEntryInterface). Page ORDER stays
 * canvas x-order (multi-page apps read left to right), but the carousel must
 * OPEN on the entry page - the card thumbnails already preview it
 * (showcaseInterfaceId = entry), so opening on the leftmost page instead
 * showed users a different page than the card promised.
 *
 * The initial jump is deliberately conservative: it fires once per mount, and
 * only while nothing else has navigated (persisted index still the default 0,
 * no run-activity auto-nav).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import * as React from 'react';

const storeState = vi.hoisted(() => ({
  carouselIndex: 0,
  setCarouselIndex: vi.fn(),
}));

vi.mock('../ApplicationTabContent', () => ({
  ApplicationTabContent: () => null,
}));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [null],
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: false }),
}));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useInterfacePaginationStore: (selector: (s: typeof storeState) => unknown) => selector(storeState),
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { ApplicationCarousel } from '../ApplicationCarousel';
import type { ApplicationConfig } from '../ApplicationTabContent';

const config = (interfaceId: string, isEntryInterface?: boolean): ApplicationConfig => ({
  interfaceId,
  label: interfaceId,
  actionMapping: {},
  nodeId: `interface:${interfaceId}`,
  isEntryInterface,
});

function renderCarousel(configs: ApplicationConfig[]) {
  return render(
    <ApplicationCarousel
      configs={configs}
      runId={null}
      workflowId="wf-1"
      onAction={() => undefined}
    />,
  );
}

describe('ApplicationCarousel - opens on the entry interface', () => {
  beforeEach(() => {
    storeState.carouselIndex = 0;
    storeState.setCarouselIndex.mockClear();
  });

  it('jumps to the flagged entry page when it is not the leftmost', () => {
    renderCarousel([config('a'), config('b', true), config('c')]);
    expect(storeState.setCarouselIndex).toHaveBeenCalledWith(1);
  });

  it('stays on index 0 when the entry IS the leftmost page (no redundant store write)', () => {
    renderCarousel([config('a', true), config('b')]);
    expect(storeState.setCarouselIndex).not.toHaveBeenCalled();
  });

  it('stays on index 0 when no entry is flagged (legacy plans: first page wins)', () => {
    renderCarousel([config('a'), config('b')]);
    expect(storeState.setCarouselIndex).not.toHaveBeenCalled();
  });

  it('never overrides a remembered position (persisted index != default)', () => {
    storeState.carouselIndex = 2;
    renderCarousel([config('a'), config('b', true), config('c')]);
    expect(storeState.setCarouselIndex).not.toHaveBeenCalled();
  });
});
