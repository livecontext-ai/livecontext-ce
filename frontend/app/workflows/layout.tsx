import React from 'react';
import { NextIntlClientProvider } from 'next-intl';
import { getMessages, setRequestLocale } from 'next-intl/server';
import { resolveRequestLocale } from '@/i18n/resolveRequestLocale';
import CeCloudCreditModal from '@/components/billing/CeCloudCreditModal';
import ModelNotManagedModal from '@/components/billing/ModelNotManagedModal';
import CloudLinkPlanRequiredModal from '@/components/billing/CloudLinkPlanRequiredModal';
import AgentErrorModal from '@/components/billing/AgentErrorModal';
import InsufficientCreditsModal from '@/components/billing/InsufficientCreditsModal';
import AccountRestoreModal from '@/components/auth/AccountRestoreModal';
import { WorkflowLayoutDirectionProvider } from '@/contexts/WorkflowLayoutDirectionContext';
import { InspectorOpenModeProvider } from '@/contexts/InspectorOpenModeContext';

export default async function WorkflowsLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const locale = await resolveRequestLocale();
  setRequestLocale(locale);
  const messages = await getMessages();

  return (
    <NextIntlClientProvider messages={messages} locale={locale}>
      {/* The standalone builder lives outside the /app layout, so it needs its own
          layout-direction provider - otherwise the canvas here always falls back to the
          safe-hook default and the reading-direction preference silently does nothing. */}
      <WorkflowLayoutDirectionProvider>
        {/* The same reason as the direction provider above: without it this route falls
            back to the safe-hook default, so the canvas settings panel here would read
            "simple" whatever the user chose in Settings, and its select would write to
            nothing.
            Only the OPEN MODE. The inspector-dock preference is deliberately absent: this
            route mounts no side panel, so the canvas cannot honour a docked inspector and
            the settings panel hides that control here - a provider for it would be a
            preference nothing on this route can read or show. */}
        <InspectorOpenModeProvider>
        {children}
        {/* The standalone builder lives outside the /app layout, so the CE cloud-relay modals
            (no-op in Cloud, self-gated to CE) are mounted here too - otherwise a relay error
            during a builder test-run would dispatch its event with no listener. */}
        <CeCloudCreditModal />
        <ModelNotManagedModal />
        <CloudLinkPlanRequiredModal />
        <AgentErrorModal />
        {/* Same reason again, and this one was already firing into nothing:
            `useWorkflowExecution` calls showInsufficientCreditsModal() when a
            test-run is refused for want of credits, and the builder route never
            mounted a listener, so the run simply stopped with no explanation. */}
        <InsufficientCreditsModal />
        {/* Same reason: a deactivated person who opens a bookmarked builder URL gets every call
            refused here too, and without this listener the restore interstitial never appears,
            leaving them in an app where nothing loads and no path leads anywhere. */}
        <AccountRestoreModal />
        </InspectorOpenModeProvider>
      </WorkflowLayoutDirectionProvider>
    </NextIntlClientProvider>
  );
}
