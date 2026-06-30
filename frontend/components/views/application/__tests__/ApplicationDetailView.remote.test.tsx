/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';

/**
 * Pins the cloud-profile threading fix: ApplicationDetailView must hand the
 * Info-panel publisher menu a CORRECT `remote` flag so a CLOUD publisher (whose id
 * is absent from this CE install's auth DB) resolves through the cloud proxy and
 * opens the CLOUD profile - never the local /app/u/{handle} route that 404s and
 * bounces to the login page.
 *
 * Bug: the view passed `remote={publication.remote}`, which is usually undefined
 * (only resolveApplicationPublication's cloud by-id fallback stamps it) -> false ->
 * local route for a cloud publisher. Fix: a caller-computed `remote` prop OR'd with
 * publication.remote, so a truthy value from EITHER source wins and never downgrades.
 */

const infoPanelProps = vi.hoisted(() => [] as Array<{ remote?: boolean }>);

vi.mock('@/lib/api', () => ({ orchestratorApi: { updatePublication: vi.fn() } }));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  // isPreviewOnly false -> the non-preview Info-panel branch renders.
  useWorkflowMode: () => ({ setRunId: vi.fn(), isPreviewOnly: false, setViewingEpoch: vi.fn() }),
}));
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({ addTab: vi.fn(), setActiveTab: vi.fn(), open: vi.fn(), isOpen: true }),
}));
vi.mock('@/components/app/WorkflowPanelContent', () => ({
  WorkflowPanelContent: () => null,
  setPendingActivateTab: vi.fn(),
}));
vi.mock('@/components/workflow/WorkflowRunCanvas', () => ({ WorkflowRunCanvas: () => null }));
vi.mock('@/components/chat/ApplicationCarousel', () => ({ ApplicationCarousel: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useInterfacePaginationStore: { getState: () => ({ setCarouselIndex: vi.fn() }) },
}));
vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({ normalizeLabel: (s: string) => s }));
vi.mock('../workflow/WorkflowLoadingState', () => ({ WorkflowLoadingState: () => null }));
vi.mock('../workflow/WorkflowUnauthorizedState', () => ({ WorkflowUnauthorizedState: () => null }));
vi.mock('../workflow/hooks', () => ({ useAutoCollapseSidebar: () => undefined }));

// Capture the `remote` prop the Info panel receives.
vi.mock('@/components/marketplace/PublicationInfoPanel', () => ({
  PublicationInfoPanel: (p: { remote?: boolean }) => {
    infoPanelProps.push({ remote: p.remote });
    return null;
  },
}));

import { ApplicationDetailView } from '@/components/views/application/ApplicationDetailView';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

function pub(over: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return { id: 'p1', title: 'X', visibility: 'PRIVATE', creditsPerUse: 0, displayMode: 'APPLICATION', ...over } as WorkflowPublication;
}

/** Render the view and return the `remote` the Info panel was last handed. */
function renderAndReadRemote(props: { remote?: boolean; publication: WorkflowPublication }): boolean | undefined {
  infoPanelProps.length = 0;
  render(<ApplicationDetailView workflowId="wf-1" runId="run-1" {...props} />);
  expect(infoPanelProps.length).toBeGreaterThan(0);
  return infoPanelProps[infoPanelProps.length - 1].remote;
}

describe('ApplicationDetailView - cloud-profile remote threading', () => {
  beforeEach(() => { infoPanelProps.length = 0; });
  afterEach(() => { cleanup(); });

  it('threads an explicit remote=true down to the Info panel (cloud publisher -> cloud profile)', () => {
    expect(renderAndReadRemote({ remote: true, publication: pub() })).toBe(true);
  });

  it('falls back to publication.remote when the explicit prop is absent', () => {
    expect(renderAndReadRemote({ publication: pub({ remote: true }) })).toBe(true);
  });

  it('is false for a genuinely-local publisher (no explicit prop, no publication.remote)', () => {
    expect(renderAndReadRemote({ publication: pub() })).toBe(false);
  });

  it('never downgrades a remote publication: explicit remote=false but publication.remote=true -> true (regression)', () => {
    // OR (not ??) semantics: a remote publisher must never be routed to the broken
    // local /app/u route, even if a caller passed a stale/false flag.
    expect(renderAndReadRemote({ remote: false, publication: pub({ remote: true }) })).toBe(true);
  });

  it('explicit remote=false with a local publication stays local (false)', () => {
    expect(renderAndReadRemote({ remote: false, publication: pub() })).toBe(false);
  });
});
