/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

/**
 * Sound on the application page.
 *
 * The application is live HTML in a sandboxed, cross-origin frame, so this page
 * can neither silence it nor see whether it has anything to play. It starts the
 * application MUTED (an app that talks the moment its page opens is startling,
 * and on a shared link the visitor had not decided to be there yet) and hands the
 * switch to the application controls, where the speaker sits next to pagination
 * and fullscreen.
 *
 * The consequence this suite exists for: the sound no longer depends on the
 * settings cog. The cog is back to a single reason to exist (the editable copy),
 * and an app whose visitor gets no cog at all still has its sound within reach.
 */

const carouselProps = vi.hoisted(() => ({
  mediaMuted: undefined as boolean | undefined,
  toggleSound: null as null | (() => void),
}));
const cogProps = vi.hoisted(() => [] as Array<{
  canCreateEditableCopy?: boolean;
  soundMuted?: boolean;
  onToggleSound?: () => void;
}>);
const isPreviewOnly = vi.hoisted(() => ({ value: false }));
// The application's interfaces are discovered by a canvas that lives inside a
// SIDE-PANEL tab, not in the page tree. Swallowing addTab (as the sibling suites
// do) means the configs never arrive and the page renders an empty div where the
// carousel would be - so this suite captures the tab content and mounts it.
const panelContent = vi.hoisted(() => ({ node: null as unknown }));
const numericUserId = vi.hoisted(() => ({ value: 42 as number | null }));

vi.mock('@/lib/api', () => ({ orchestratorApi: { updatePublication: vi.fn() } }));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({
    isAuthenticated: true,
    isAuthChecking: false,
    get numericUserId() { return numericUserId.value; },
  }),
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useWorkflowMode: () => ({
    setRunId: vi.fn(),
    get isPreviewOnly() { return isPreviewOnly.value; },
    setViewingEpoch: vi.fn(),
  }),
}));
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({
    addTab: (tab: { content?: unknown }) => { panelContent.node = tab.content ?? null; },
    setActiveTab: vi.fn(),
    open: vi.fn(),
    isOpen: true,
  }),
}));
vi.mock('@/components/app/WorkflowPanelContent', () => ({
  // Renders its canvas slot, which is where the interface configs come from.
  WorkflowPanelContent: ({ workflowCanvasSlot }: any) => <>{workflowCanvasSlot}</>,
  setPendingActivateTab: vi.fn(),
}));
// The canvas is what discovers the application's interfaces. Without at least
// one config the page renders an empty div instead of the carousel, so there
// would be no frame to mute and nothing to test.
vi.mock('@/components/workflow/WorkflowRunCanvas', async () => {
  const ReactMod = await import('react');
  return {
    WorkflowRunCanvas: ({ onApplicationConfigsChange }: any) => {
      ReactMod.useEffect(() => {
        onApplicationConfigsChange?.([{ interfaceId: 'iface-1', actionMapping: {} }]);
      }, [onApplicationConfigsChange]);
      return null;
    },
  };
});
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/marketplace/PublicationInfoPanel', () => ({ PublicationInfoPanel: () => null }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useInterfacePaginationStore: { getState: () => ({ setCarouselIndex: vi.fn() }) },
  carouselKeyFor: (workflowId?: string | null, runId?: string | null) => `${workflowId ?? ''}:${runId ?? ''}`,
}));
vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({ normalizeLabel: (s: string) => s }));
vi.mock('../workflow/WorkflowLoadingState', () => ({ WorkflowLoadingState: () => null }));
vi.mock('../workflow/WorkflowUnauthorizedState', () => ({ WorkflowUnauthorizedState: () => null }));
vi.mock('../workflow/hooks', () => ({ useAutoCollapseSidebar: () => undefined }));

// Stand-in for the interface frames and the controls toolbar they carry: records
// the mute state it is handed and exposes the toggle, which is what the speaker
// in the toolbar calls. Whether the speaker is shown at all (only for an app the
// frame reports has media) is the toolbar's own contract, covered in
// ApplicationTabContent.sound.test.tsx.
vi.mock('@/components/chat/ApplicationCarousel', () => ({
  ApplicationCarousel: (p: {
    mediaMuted?: boolean;
    onToggleMediaMuted?: () => void;
    settingsControl?: React.ReactNode;
  }) => {
    carouselProps.mediaMuted = p.mediaMuted;
    carouselProps.toggleSound = p.onToggleMediaMuted ?? null;
    return (
      <>
        <button
          type="button"
          data-testid="toolbar-sound"
          onClick={p.onToggleMediaMuted}
        >
          carousel
        </button>
        {p.settingsControl}
      </>
    );
  },
}));

// Real cog behaviour is covered by ApplicationSettingsMenu's own suite; here we
// only need what this page hands it - including what it must NOT hand it any more.
vi.mock('@/components/marketplace/ApplicationSettingsMenu', () => ({
  ApplicationSettingsMenu: (p: {
    canCreateEditableCopy?: boolean;
    soundMuted?: boolean;
    onToggleSound?: () => void;
  }) => {
    cogProps.push({
      canCreateEditableCopy: p.canCreateEditableCopy,
      soundMuted: p.soundMuted,
      onToggleSound: p.onToggleSound,
    });
    return <button type="button" data-testid="cog">cog</button>;
  },
}));

import { ApplicationDetailView } from '@/components/views/application/ApplicationDetailView';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

function pub(over: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'p1',
    title: 'X',
    visibility: 'PRIVATE',
    creditsPerUse: 0,
    displayMode: 'APPLICATION',
    publisherId: '999',
    ...over,
  } as WorkflowPublication;
}

/**
 * Render the page AND the side-panel tab it registers, so the canvas inside that
 * tab reports the application's interfaces back and the carousel actually mounts.
 * The tab content is captured during the page's effect, hence the second render.
 */
function renderView(props: Partial<React.ComponentProps<typeof ApplicationDetailView>> = {}) {
  cogProps.length = 0;
  panelContent.node = null;
  const result = render(
    <ApplicationDetailView workflowId="wf-1" runId="run-1" publication={pub()} isInstalledClone {...props} />,
  );
  if (panelContent.node) {
    act(() => { render(panelContent.node as React.ReactElement); });
  }
  return result;
}

const cog = () => screen.queryByTestId('cog');
const toolbarSound = () => screen.getByTestId('toolbar-sound');

beforeEach(() => {
  cogProps.length = 0;
  carouselProps.mediaMuted = undefined;
  carouselProps.toggleSound = null;
  isPreviewOnly.value = false;
  numericUserId.value = 42;
});
afterEach(cleanup);

describe('ApplicationDetailView - the application starts silent', () => {
  it('mutes the application on FIRST render, before anything is known about it', () => {
    // Waiting for the frame to report back would mean the sound had already been
    // playing while it did.
    renderView();

    expect(carouselProps.mediaMuted).toBe(true);
  });

  it('unmutes the application when the controls speaker is used, and mutes it again', () => {
    renderView();

    fireEvent.click(toolbarSound());
    expect(carouselProps.mediaMuted).toBe(false);

    fireEvent.click(toolbarSound());
    expect(carouselProps.mediaMuted).toBe(true);
  });
});

describe('ApplicationDetailView - the sound no longer hangs off the settings cog', () => {
  it('hands the cog no sound state and no sound toggle', () => {
    // Both live in the controls toolbar now. Leaving them here too would offer
    // the same switch twice, in two places that can disagree.
    renderView();

    expect(cogProps.at(-1)?.soundMuted).toBeUndefined();
    expect(cogProps.at(-1)?.onToggleSound).toBeUndefined();
  });

  it('keeps the sound reachable for a visitor who gets no cog at all', () => {
    // Nothing installed, so no copy to offer and no cog. Pre-fix that was exactly
    // the view whose sound could never be turned on.
    renderView({ isInstalledClone: false });

    expect(cog()).toBeNull();
    expect(carouselProps.toggleSound).not.toBeNull();

    fireEvent.click(toolbarSound());
    expect(carouselProps.mediaMuted).toBe(false);
  });

  it('still mounts the cog for the copy entry, which is now its only reason to exist', () => {
    renderView();

    expect(cog()).not.toBeNull();
    expect(cogProps.at(-1)?.canCreateEditableCopy).toBe(true);
  });

  it('offers no copy to a visitor who installed nothing, whose copy call would be refused', () => {
    // The page is bound to the publisher's preview clone here. Offering the copy
    // on "this publication is an application and I am not its publisher" produced
    // a button whose endpoint answers "Application is not installed in this
    // workspace".
    renderView({ isInstalledClone: false });

    // The cog is not merely told "no copy", it is not mounted at all - which is
    // what an assertion on the prop alone would also accept.
    expect(cog()).toBeNull();
  });
});
