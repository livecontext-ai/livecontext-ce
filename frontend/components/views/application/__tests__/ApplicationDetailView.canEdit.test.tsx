/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';

/**
 * Pins the editable-application gating: the acquirer of their OWN clone
 * (canEdit / isClonedAcquisition) gets the standard run/edit toggle on the
 * embedded workflow canvas (hideToggle=false), so they can edit in place and
 * persist via the normal save-on-run. The publisher-self-view fallback and the
 * anonymous preview (canEdit=false, the default) stay run-locked (hideToggle=true).
 *
 * Regression: dropping `canEdit` anywhere in the layout -> ApplicationDetailView ->
 * WorkflowRunCanvas chain silently flips an app to read-only or editable.
 */

const canvasProps = vi.hoisted(() => [] as Array<{ hideToggle?: boolean }>);
const addTabMock = vi.hoisted(() => vi.fn());
const updatePublicationMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api', () => ({
  orchestratorApi: { updatePublication: updatePublicationMock },
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (k: string) => k,
}));

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useWorkflowMode: () => ({ setRunId: vi.fn(), isPreviewOnly: false, setViewingEpoch: vi.fn() }),
}));

vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({
    addTab: addTabMock,
    setActiveTab: vi.fn(),
    open: vi.fn(),
    isOpen: true,
  }),
}));

// Render the canvas slot so the (mocked) WorkflowRunCanvas runs and records its props.
vi.mock('@/components/app/WorkflowPanelContent', () => ({
  WorkflowPanelContent: ({ workflowCanvasSlot }: { workflowCanvasSlot?: React.ReactNode }) => (
    <div data-testid="workflow-panel">{workflowCanvasSlot}</div>
  ),
  setPendingActivateTab: vi.fn(),
}));

vi.mock('@/components/workflow/WorkflowRunCanvas', () => ({
  WorkflowRunCanvas: (props: { hideToggle?: boolean }) => {
    canvasProps.push({ hideToggle: props.hideToggle });
    return <div data-testid="workflow-canvas" />;
  },
}));

vi.mock('@/components/chat/ApplicationCarousel', () => ({ ApplicationCarousel: () => null }));
vi.mock('@/components/marketplace/PublicationInfoPanel', () => ({ PublicationInfoPanel: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useInterfacePaginationStore: { getState: () => ({ setCarouselIndex: vi.fn() }) },
}));
vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({ normalizeLabel: (s: string) => s }));
vi.mock('../workflow/WorkflowLoadingState', () => ({ WorkflowLoadingState: () => null }));
vi.mock('../workflow/WorkflowUnauthorizedState', () => ({ WorkflowUnauthorizedState: () => null }));
vi.mock('../workflow/hooks', () => ({ useAutoCollapseSidebar: () => undefined }));

import { ApplicationDetailView } from '@/components/views/application/ApplicationDetailView';

/** Pull the hideToggle the captured side-panel tab content forwards to WorkflowRunCanvas. */
function renderAndReadHideToggle(canEdit: boolean | undefined): boolean | undefined {
  canvasProps.length = 0;
  addTabMock.mockClear();
  render(
    <ApplicationDetailView
      workflowId="wf-1"
      runId="run-1"
      title="My App"
      canEdit={canEdit}
    />
  );
  // The workflow canvas lives inside the side-panel tab content (registered via addTab),
  // not the main render tree. Render that captured content to run WorkflowRunCanvas.
  expect(addTabMock).toHaveBeenCalledTimes(1);
  const tabContent = addTabMock.mock.calls[0][0].content as React.ReactElement;
  render(tabContent);
  expect(canvasProps).toHaveLength(1);
  return canvasProps[0].hideToggle;
}

describe('ApplicationDetailView - editable gating (canEdit -> hideToggle)', () => {
  afterEach(() => {
    canvasProps.length = 0;
    addTabMock.mockReset();
    updatePublicationMock.mockReset();
    vi.restoreAllMocks();
    cleanup();
  });

  it('shows the run/edit toggle for the acquirer of their own clone (canEdit=true -> hideToggle=false)', () => {
    expect(renderAndReadHideToggle(true)).toBe(false);
  });

  it('keeps the canvas run-locked for non-owners (canEdit=false -> hideToggle=true)', () => {
    expect(renderAndReadHideToggle(false)).toBe(true);
  });

  it('defaults to read-only when canEdit is omitted (hideToggle=true)', () => {
    expect(renderAndReadHideToggle(undefined)).toBe(true);
  });

  it('does not render the Publish-update button (UI removed) even for the publication owner', () => {
    // The publish logic (handlePublishUpdate + updatePublication) is intentionally
    // kept in the component, but its button is no longer rendered, so there is no
    // UI affordance to trigger a publish-update for anyone - owner, acquirer, or
    // anonymous preview alike.
    const pub = { id: 'p1', title: 'X', visibility: 'PRIVATE', creditsPerUse: 0 } as never;
    const { queryByText } = render(
      <ApplicationDetailView workflowId="wf-1" runId="run-1" canEdit canPublish publication={pub} />
    );
    expect(queryByText('publishUpdate')).toBeNull();
    expect(updatePublicationMock).not.toHaveBeenCalled();
  });
});
