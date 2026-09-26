'use client';

import { SidebarProvider } from '@/contexts/SidebarContext';
import { UnifiedAppProvider } from '@/contexts/UnifiedAppContext';
import { NavigationGuardProvider } from '@/contexts/NavigationGuardContext';
import { StreamingProvider } from '@/contexts/StreamingContext';
import { WorkflowRunProvider } from '@/contexts/WorkflowRunContext';
import { SidePanelProvider } from '@/contexts/SidePanelContext';
import { SidePanelLayoutProvider } from '@/contexts/SidePanelLayoutContext';
import { WorkflowLayoutDirectionProvider } from '@/contexts/WorkflowLayoutDirectionContext';
import { InspectorDockProvider } from '@/contexts/InspectorDockContext';
import { InspectorOpenModeProvider } from '@/contexts/InspectorOpenModeContext';
import { AppShell } from './AppShell';
import InsufficientCreditsModal from '@/components/billing/InsufficientCreditsModal';
import InsufficientStorageModal from '@/components/billing/InsufficientStorageModal';
import AppPlanComparisonDialog from '@/components/pricing/AppPlanComparisonDialog';
import MissingApiKeyModal from '@/components/billing/MissingApiKeyModal';
import CeCloudCreditModal from '@/components/billing/CeCloudCreditModal';
import ModelNotManagedModal from '@/components/billing/ModelNotManagedModal';
import CloudLinkPlanRequiredModal from '@/components/billing/CloudLinkPlanRequiredModal';
import AgentErrorModal from '@/components/billing/AgentErrorModal';
import SuggestedAppsModal from '@/components/billing/SuggestedAppsModal';
import WelcomeGiftModal from '@/components/billing/WelcomeGiftModal';
import AccountRestoreModal from '@/components/auth/AccountRestoreModal';
import ChangelogModal from '@/components/changelog/ChangelogModal';
import AppViewTracker from '@/components/analytics/AppViewTracker';
import ProfileContextReporter from '@/components/lifecycle/ProfileContextReporter';
import IncidentStrip from '@/components/app/IncidentStrip';
import TwoFactorNudge from '@/components/app/TwoFactorNudge';
import { IS_CE } from '@/lib/edition';

/**
 * Layout for all /app routes
 *
 * Structure:
 *   AppSidebar | (AppHeader + main content) | SidePanel
 *
 * AppHeader and SidePanel live here so they persist across navigations
 * and are never duplicated. Each page just renders its content.
 *
 * SidePanel is lazy-rendered: nothing is mounted until a tab is opened.
 * Pages register tabs via useSidePanel().openTab() / addTab().
 */
export default function AppLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <SidebarProvider>
      <UnifiedAppProvider>
        <StreamingProvider>
          <WorkflowRunProvider>
              <SidePanelProvider>
                <SidePanelLayoutProvider>
                <WorkflowLayoutDirectionProvider>
                <InspectorDockProvider>
                <InspectorOpenModeProvider>
                <NavigationGuardProvider>
                  <div className="h-[100dvh] bg-theme-primary transition-colors duration-300 fixed inset-0 z-50">
                    {/* Sidebar + content + side panel, arranged per the dock-position
                        preference (right / bottom / bottom-full). */}
                    {/* CE (self-hosted) ships no product analytics/tracking. */}
                    {!IS_CE && <AppViewTracker />}
                    {/* Locale, time zone and first-touch acquisition for the cloud
                        lifecycle e-mails, once per session. Here, not in the root
                        providers, so share, embed and public pages never send it. */}
                    {!IS_CE && <ProfileContextReporter />}
                    <AppShell>{children}</AppShell>
                    {/* Ongoing-incident strip. Mounted here rather than inside
                        AppShell: AppShell renders two different arrangements and
                        moving a child between those branches remounts the subtree
                        (a running canvas, an SSE stream). Cloud-only, like the
                        rest of the status feature. */}
                    {!IS_CE && <IncidentStrip />}
                    {/* Team owner without two-factor: a one-time, dismissible suggestion.
                        Here for the same reason as the strip above. Cloud-only. */}
                    {!IS_CE && <TwoFactorNudge />}
                    {/* The two modals onboarding arms, in the order it arms
                        them: what the new account already has, then what it can
                        start from. The ORDER on screen is the hand-off's doing,
                        but the mount order below is not free: the gift is
                        mounted first, and a child's mount effects run before its
                        parent's, so the suggestions modal subscribes to the
                        release event AFTER the gift's own mount effect has run.
                        The gift therefore never settles inside that effect, only
                        on a later render pass. Moving either line, or making the
                        gift decide at mount, drops the release into a window
                        where nobody is listening. */}
                    <WelcomeGiftModal />
                    <SuggestedAppsModal />
                    <InsufficientCreditsModal />
                    <InsufficientStorageModal />
                    {/* The comparison's only in-app opener is the pricing page's
                        "Compare plans" button, which lives in this tree. It stays
                        mounted here rather than inside that page because the
                        dialog listens on a window event. */}
                    <AppPlanComparisonDialog />
                    <MissingApiKeyModal />
                    <CeCloudCreditModal />
                    <ModelNotManagedModal />
                    <CloudLinkPlanRequiredModal />
                    <AgentErrorModal />
                    <AccountRestoreModal />
                    {/* One entry, the newest, once per user. Both editions: the announcement is
                        about the build the user is actually running. */}
                    <ChangelogModal />
                  </div>
                </NavigationGuardProvider>
                </InspectorOpenModeProvider>
                </InspectorDockProvider>
                </WorkflowLayoutDirectionProvider>
                </SidePanelLayoutProvider>
              </SidePanelProvider>
          </WorkflowRunProvider>
        </StreamingProvider>
      </UnifiedAppProvider>
    </SidebarProvider>
  );
}
