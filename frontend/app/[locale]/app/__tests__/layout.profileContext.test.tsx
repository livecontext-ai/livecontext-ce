/**
 * @vitest-environment jsdom
 *
 * The /app layout mounts the lifecycle profile-context reporter (locale, time zone, first-touch
 * acquisition) in the cloud edition only, and here rather than in the root providers so share,
 * embed and public pages never send it. The reporter's own guards (signed in, auth ready, once per
 * session) are covered by useProfileContextReport's test. Every other child of the layout is
 * stubbed: this pins the mount decision, not the shell.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import * as React from 'react';

const edition = vi.hoisted(() => ({ IS_CE: false }));
vi.mock('@/lib/edition', () => edition);

vi.mock('@/components/lifecycle/ProfileContextReporter', () => ({
  default: () => <div data-testid="profile-context-reporter" />,
}));

const passThrough = ({ children }: { children?: React.ReactNode }) => <>{children}</>;
vi.mock('@/contexts/SidebarContext', () => ({ SidebarProvider: passThrough }));
vi.mock('@/contexts/UnifiedAppContext', () => ({ UnifiedAppProvider: passThrough }));
vi.mock('@/contexts/NavigationGuardContext', () => ({ NavigationGuardProvider: passThrough }));
vi.mock('@/contexts/StreamingContext', () => ({ StreamingProvider: passThrough }));
vi.mock('@/contexts/WorkflowRunContext', () => ({ WorkflowRunProvider: passThrough }));
vi.mock('@/contexts/SidePanelContext', () => ({ SidePanelProvider: passThrough }));
vi.mock('@/contexts/SidePanelLayoutContext', () => ({ SidePanelLayoutProvider: passThrough }));
vi.mock('@/contexts/WorkflowLayoutDirectionContext', () => ({ WorkflowLayoutDirectionProvider: passThrough }));
vi.mock('@/contexts/InspectorDockContext', () => ({ InspectorDockProvider: passThrough }));
vi.mock('@/contexts/InspectorOpenModeContext', () => ({ InspectorOpenModeProvider: passThrough }));
vi.mock('../AppShell', () => ({ AppShell: passThrough }));

const nothing = () => null;
vi.mock('@/components/billing/InsufficientCreditsModal', () => ({ default: nothing }));
vi.mock('@/components/billing/InsufficientStorageModal', () => ({ default: nothing }));
vi.mock('@/components/pricing/AppPlanComparisonDialog', () => ({ default: nothing }));
vi.mock('@/components/billing/MissingApiKeyModal', () => ({ default: nothing }));
vi.mock('@/components/billing/CeCloudCreditModal', () => ({ default: nothing }));
vi.mock('@/components/billing/ModelNotManagedModal', () => ({ default: nothing }));
vi.mock('@/components/billing/CloudLinkPlanRequiredModal', () => ({ default: nothing }));
vi.mock('@/components/billing/AgentErrorModal', () => ({ default: nothing }));
vi.mock('@/components/billing/SuggestedAppsModal', () => ({ default: nothing }));
vi.mock('@/components/billing/WelcomeGiftModal', () => ({ default: nothing }));
vi.mock('@/components/auth/AccountRestoreModal', () => ({ default: nothing }));
vi.mock('@/components/changelog/ChangelogModal', () => ({ default: nothing }));
vi.mock('@/components/analytics/AppViewTracker', () => ({ default: nothing }));
vi.mock('@/components/app/IncidentStrip', () => ({ default: nothing }));
vi.mock('@/components/app/TwoFactorNudge', () => ({ default: nothing }));

async function renderLayout() {
  vi.resetModules();
  const { default: AppLayout } = await import('../layout');
  render(
    <AppLayout>
      <p>app page</p>
    </AppLayout>,
  );
}

describe('app layout', () => {
  beforeEach(() => {
    edition.IS_CE = false;
  });

  it('mounts the profile-context reporter in the cloud edition, next to the page', async () => {
    await renderLayout();

    expect(screen.getByTestId('profile-context-reporter')).toBeTruthy();
    expect(screen.getByText('app page')).toBeTruthy();
  });

  it('never mounts it in CE (self-hosted sends no lifecycle context)', async () => {
    edition.IS_CE = true;

    await renderLayout();

    expect(screen.queryByTestId('profile-context-reporter')).toBeNull();
    expect(screen.getByText('app page')).toBeTruthy();
  });
});
