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
import { render, screen, cleanup } from '@testing-library/react';
import * as React from 'react';

const edition = vi.hoisted(() => ({ IS_CE: false }));
vi.mock('@/lib/edition', () => edition);

vi.mock('@/components/lifecycle/ProfileContextReporter', () => ({
  default: () => <div data-testid="profile-context-reporter" />,
}));

// Stubbed for the same reason: the real one reads auth and react-query and speaks through
// next-intl, none of which this pass-through render provides. This pins where it is mounted.
vi.mock('@/components/reward/PendingRewardCodeRedeemer', () => ({
  default: () => <div data-testid="pending-reward-code-redeemer" />,
}));

// Stubbed like its neighbour: the real one reads the profile through react-query, and this test
// renders the layout with its providers passed through, so there is no QueryClient here. It
// renders its CHILDREN, because the real one wraps the shell rather than sitting beside it - a
// stub that dropped them would hide the page and pass only by accident.
vi.mock('@/components/lifecycle/DisplayPreferencesGate', () => ({
  default: ({ children }: { children?: React.ReactNode }) => (
    <div data-testid="display-preferences-gate">{children}</div>
  ),
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

  it('mounts the partner-code redeemer in the cloud edition only (codes live on the cloud account)', async () => {
    await renderLayout();
    expect(screen.getByTestId('pending-reward-code-redeemer')).toBeTruthy();

    cleanup();
    edition.IS_CE = true;

    await renderLayout();
    expect(screen.queryByTestId('pending-reward-code-redeemer')).toBeNull();
  });

  it('mounts the display-preferences gate in BOTH editions, unlike the reporter above', async () => {
    // The two look alike and are gated differently on purpose. The lifecycle report feeds the
    // cloud marketing sequences, so CE sends none. The display preference decides how every date
    // in the product reads AND what language auth-service writes a notification e-mail in, which
    // a self-hosted install does too - gating it would leave CE reading UTC and writing English.
    await renderLayout();
    expect(screen.getByTestId('display-preferences-gate')).toBeTruthy();

    cleanup();
    edition.IS_CE = true;

    await renderLayout();
    expect(screen.getByTestId('display-preferences-gate')).toBeTruthy();
  });

  it('WRAPS the shell, so applying a zone can re-render what shows dates', async () => {
    // Nothing that formats a date subscribes to the zone, so a preference that arrives after the
    // first paint has to re-render the tree. Beside the shell it could not; around it, it can.
    await renderLayout();

    const gate = screen.getByTestId('display-preferences-gate');
    expect(gate.textContent).toContain('app page');
  });
});
