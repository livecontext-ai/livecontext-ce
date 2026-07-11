'use client';

import { SidebarProvider } from '@/contexts/SidebarContext';
import { UnifiedAppProvider } from '@/contexts/UnifiedAppContext';
import { NavigationGuardProvider } from '@/contexts/NavigationGuardContext';
import { StreamingProvider } from '@/contexts/StreamingContext';
import { WorkflowRunProvider } from '@/contexts/WorkflowRunContext';
import { SidePanelProvider } from '@/contexts/SidePanelContext';
import { SidePanelLayoutProvider } from '@/contexts/SidePanelLayoutContext';
import { AppShell } from './AppShell';
import InsufficientCreditsModal from '@/components/billing/InsufficientCreditsModal';
import InsufficientStorageModal from '@/components/billing/InsufficientStorageModal';
import MissingApiKeyModal from '@/components/billing/MissingApiKeyModal';
import CeCloudCreditModal from '@/components/billing/CeCloudCreditModal';
import ModelNotManagedModal from '@/components/billing/ModelNotManagedModal';
import AgentErrorModal from '@/components/billing/AgentErrorModal';
import WelcomeGiftModal from '@/components/billing/WelcomeGiftModal';
import SuggestedAppsModal from '@/components/billing/SuggestedAppsModal';

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
                <NavigationGuardProvider>
                  <div className="h-[100dvh] bg-theme-primary transition-colors duration-300 fixed inset-0 z-50">
                    {/* Sidebar + content + side panel, arranged per the dock-position
                        preference (right / bottom / bottom-full). */}
                    <AppShell>{children}</AppShell>
                    <WelcomeGiftModal />
                    <SuggestedAppsModal />
                    <InsufficientCreditsModal />
                    <InsufficientStorageModal />
                    <MissingApiKeyModal />
                    <CeCloudCreditModal />
                    <ModelNotManagedModal />
                    <AgentErrorModal />
                  </div>
                </NavigationGuardProvider>
                </SidePanelLayoutProvider>
              </SidePanelProvider>
          </WorkflowRunProvider>
        </StreamingProvider>
      </UnifiedAppProvider>
    </SidebarProvider>
  );
}
