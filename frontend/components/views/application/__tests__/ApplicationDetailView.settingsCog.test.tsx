/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

/**
 * The application settings cog, in the application controls toolbar.
 *
 * "Create an editable copy" used to sit INLINE in the Info panel's Info tab, above the
 * app description, which every visitor had to scroll past for a one-shot action most
 * never take. It now lives behind this cog (its behaviour is covered by
 * ApplicationSettingsMenu's own suite). What this suite pins is the GATING and the
 * placement: the cog is mounted only for a viewer who can actually make a copy, INSIDE
 * the controls toolbar the central button opens (it used to float alone in the
 * bottom-right corner), and never for a preview / a publisher / a non-application.
 */

const cogProps = vi.hoisted(() => [] as Array<{ publicationId?: string; remote?: boolean }>);
const isPreviewOnly = vi.hoisted(() => ({ value: false }));
const numericUserId = vi.hoisted(() => ({ value: 42 as number | null }));
// The interfaces are discovered by a canvas inside a SIDE-PANEL tab: captured here
// and mounted, so the configs arrive and the carousel (which carries the toolbar) mounts.
const panelContent = vi.hoisted(() => ({ node: null as unknown }));
// The interfaces the canvas reports; emptied to play an application with none.
const canvasConfigs = vi.hoisted(() => ({ value: [{ interfaceId: 'iface-1', actionMapping: {} }] as unknown[] }));

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
  WorkflowPanelContent: ({ workflowCanvasSlot }: any) => <>{workflowCanvasSlot}</>,
  setPendingActivateTab: vi.fn(),
}));
vi.mock('@/components/workflow/WorkflowRunCanvas', async () => {
  const ReactMod = await import('react');
  return {
    WorkflowRunCanvas: ({ onApplicationConfigsChange }: any) => {
      ReactMod.useEffect(() => {
        onApplicationConfigsChange?.(canvasConfigs.value);
      }, [onApplicationConfigsChange]);
      return null;
    },
  };
});
// Stand-in for the carousel and its controls toolbar: renders the settings control
// it is handed inside a toolbar element, as ApplicationTabContent does.
vi.mock('@/components/chat/ApplicationCarousel', () => ({
  ApplicationCarousel: (p: { settingsControl?: React.ReactNode }) => (
    <div data-testid="application-toolbar">{p.settingsControl}</div>
  ),
}));
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

// Capture what the cog is mounted with (its own behaviour is tested separately).
vi.mock('@/components/marketplace/ApplicationSettingsMenu', () => ({
  ApplicationSettingsMenu: (p: { publicationId: string; remote?: boolean }) => {
    cogProps.push({ publicationId: p.publicationId, remote: p.remote });
    return <div data-testid="application-settings-menu" />;
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

function renderView(props: Partial<React.ComponentProps<typeof ApplicationDetailView>> = {}) {
  cogProps.length = 0;
  panelContent.node = null;
  const result = render(
    // `isInstalledClone` is what "installed" MEANS to this component now: the page
    // is bound to the caller's own clone, which is what the copy endpoint resolves.
    <ApplicationDetailView workflowId="wf-1" runId="run-1" publication={pub()} isInstalledClone {...props} />,
  );
  if (panelContent.node) {
    act(() => { render(panelContent.node as React.ReactElement); });
  }
  return result;
}

beforeEach(() => {
  canvasConfigs.value = [{ interfaceId: 'iface-1', actionMapping: {} }];
  cogProps.length = 0;
  isPreviewOnly.value = false;
  numericUserId.value = 42;
});
afterEach(cleanup);

describe('ApplicationDetailView - the settings cog', () => {
  it('mounts the cog for someone who installed the application', () => {
    renderView();

    expect(screen.getByTestId('application-settings-menu')).toBeDefined();
    expect(cogProps.at(-1)).toEqual({ publicationId: 'p1', remote: false });
  });

  it('puts it INSIDE the controls toolbar, no longer floating in the bottom-right corner', () => {
    renderView();

    const cog = screen.getByTestId('application-settings-menu');
    expect(cog.parentElement?.getAttribute('data-testid')).toBe('application-toolbar');
    expect(document.querySelector('.bottom-4.right-4')).toBeNull();
  });

  it('keeps it reachable, bottom-centre, for an application with NO interface (no toolbar to carry it)', () => {
    // No interface means no carousel and so no controls toolbar. The copy is then
    // the one thing the user may still want, so the cog must not vanish with it.
    canvasConfigs.value = [];
    renderView();

    const cog = screen.getByTestId('application-settings-menu');
    expect(screen.queryByTestId('application-toolbar')).toBeNull();
    expect(cog.parentElement?.className).toContain('left-1/2');
    expect(cog.parentElement?.className).toContain('bottom-4');
  });

  it('forwards the cloud-linked CE flag so the copy goes through the remote endpoint', () => {
    renderView({ remote: true });

    expect(cogProps.at(-1)?.remote).toBe(true);
  });

  it('forwards a publication-stamped remote flag too (cloud by-id fallback)', () => {
    renderView({ publication: pub({ remote: true }) });

    expect(cogProps.at(-1)?.remote).toBe(true);
  });

  it('is withheld on an anonymous marketplace PREVIEW (no acquired clone, no auth state)', () => {
    renderView({ publicPreviewMode: true });

    expect(screen.queryByTestId('application-settings-menu')).toBeNull();
  });

  it('is withheld from a visitor who installed nothing, whose copy call would be refused', () => {
    // The page is then bound to the publisher's preview clone: an application by
    // display mode, but not one of theirs to copy.
    renderView({ isInstalledClone: false });

    expect(screen.queryByTestId('application-settings-menu')).toBeNull();
  });

  it('is withheld from the publisher of an app they have NOT installed - no clone to copy', () => {
    // Offering it would only ever produce "Application is not installed in this workspace".
    renderView({ publication: pub({ publisherId: '42' }), isInstalledClone: false });

    expect(screen.queryByTestId('application-settings-menu')).toBeNull();
  });

  it('OFFERS it to a publisher who installed their own app, whose copy would succeed', () => {
    // The endpoint resolves the caller's install and never asks who published it.
    // Denying this user was the mirror of offering it to a non-installer.
    renderView({ publication: pub({ publisherId: '42' }) });

    // The copy entry is the cog's only reason to mount, so its presence IS the
    // assertion that the copy is offered.
    expect(screen.getByTestId('application-settings-menu')).toBeDefined();
  });

  it('is withheld for a non-APPLICATION publication (a plain workflow is already editable)', () => {
    renderView({ publication: pub({ displayMode: 'WORKFLOW' }) });

    expect(screen.queryByTestId('application-settings-menu')).toBeNull();
  });

  it('is withheld when the page carries no publication at all', () => {
    renderView({ publication: undefined });

    expect(screen.queryByTestId('application-settings-menu')).toBeNull();
  });

  it('stays mounted in preview-ONLY mode of an acquired app (isPreviewOnly is a layout switch, not a gate)', () => {
    // isPreviewOnly only decides how the Info panel is framed; the viewer still owns
    // the clone, so the copy action must not disappear with it.
    isPreviewOnly.value = true;
    renderView();

    expect(screen.getByTestId('application-settings-menu')).toBeDefined();
  });
});
