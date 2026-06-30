/**
 * @vitest-environment jsdom
 *
 * Plumbing regression test - pins the {@code previewMode} prop forwarding
 * from {@link ApplicationCarousel} to its inner {@link ApplicationTabContent}.
 *
 * Why: the marketplace-preview UX fix gates three authed-API affordances
 * (Launch, Continue, Maximize buttons) + the toolbar portal on this single
 * boolean. The actual gating logic lives in {@code ApplicationTabContent},
 * but the carousel is the only path that connects the page-level
 * {@code publicPreviewMode} (set by the preview page) to the gate-evaluating
 * component. A refactor that silently drops {@code previewMode} from the
 * carousel's prop spread would re-introduce the prod bug class where an
 * anonymous visitor could fire the publisher's workflow.
 *
 * The test stubs ApplicationTabContent to a prop-capture mock so we don't
 * need the 15-hook harness the real component requires.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import * as React from 'react';

const capturedProps = vi.hoisted(() => ({
  /** Mutable holder so each test sees the most recent render's props. */
  current: null as Record<string, unknown> | null,
}));

// Mock the inner component to a prop-capture stub. The real
// ApplicationTabContent mounts a forest of context hooks (auth, workflow
// mode, run, epoch, interface render, pagination store, …) - none of which
// matter for this plumbing assertion. Capturing the props as they cross the
// carousel boundary is the contract we're testing.
vi.mock('../ApplicationTabContent', () => ({
  ApplicationTabContent: (props: Record<string, unknown>) => {
    capturedProps.current = props;
    return null;
  },
}));

// Lightweight mocks for the other carousel deps that we don't want to
// exercise in this test (workflow-run context, mode context, pagination
// store). Each returns the minimum surface the carousel reads.
vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [null],
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: false }),
}));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useInterfacePaginationStore: (selector: (s: { carouselIndex: number; setCarouselIndex: (i: number) => void }) => unknown) =>
    selector({ carouselIndex: 0, setCarouselIndex: () => undefined }),
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { ApplicationCarousel } from '../ApplicationCarousel';
import type { ApplicationConfig } from '../ApplicationTabContent';

const baseConfig: ApplicationConfig = {
  interfaceId: 'iface-1',
  // Minimum shape - ApplicationTabContent is mocked so the rest is irrelevant.
  // Casts keep the test focused on prop plumbing, not interface contract.
} as unknown as ApplicationConfig;

describe('ApplicationCarousel - previewMode prop plumbing', () => {
  beforeEach(() => {
    capturedProps.current = null;
  });

  it('forwards previewMode=true to ApplicationTabContent - pins the marketplace-preview UX gate contract', () => {
    render(
      <ApplicationCarousel
        configs={[baseConfig]}
        runId={null}
        workflowId="wf-1"
        onAction={() => undefined}
        previewMode
      />,
    );
    expect(capturedProps.current).not.toBeNull();
    expect(capturedProps.current?.previewMode).toBe(true);
  });

  it('forwards previewMode=false when explicitly passed', () => {
    render(
      <ApplicationCarousel
        configs={[baseConfig]}
        runId={null}
        workflowId="wf-1"
        onAction={() => undefined}
        previewMode={false}
      />,
    );
    expect(capturedProps.current?.previewMode).toBe(false);
  });

  it('defaults previewMode to false when omitted (workflow side-panel callers must keep all controls)', () => {
    // The non-preview caller is WorkflowPanelContent. If a future refactor
    // makes previewMode default to anything other than false, the side-panel
    // would silently lose Launch / Continue / Maximize - a regression worth
    // pinning even though the current default is correct.
    render(
      <ApplicationCarousel
        configs={[baseConfig]}
        runId={null}
        workflowId="wf-1"
        onAction={() => undefined}
      />,
    );
    expect(capturedProps.current?.previewMode).toBe(false);
  });
});
