'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { AlertCircle } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { ShareProviders } from '@/components/share/ShareProviders';
import { WorkflowModeProvider } from '@/contexts/WorkflowModeContext';
import { WorkflowRunProvider } from '@/contexts/WorkflowRunContext';
import { ApplicationDetailView } from '@/components/views/application/ApplicationDetailView';
import { WorkflowLoadingState } from '@/components/views/workflow/WorkflowLoadingState';
import { SidePanelProvider } from '@/contexts/SidePanelContext';
import { SidePanel } from '@/components/app/SidePanel';
import { StreamingProvider } from '@/contexts/StreamingContext';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

interface SharedApplicationProps {
  publicationId: string;
  token: string;
  title?: string;
  description?: string;
}

/**
 * Shared application viewer - uses the SAME components as /app/applications.
 *
 * Flow:
 * 1. ShareProviders wraps everything → initShareApiClient(token) → apiClient uses "ShareToken sl_xxx"
 * 2. Gateway resolves share token → injects X-User-ID (owner) → same as authenticated requests
 * 3. Same init logic as /app/applications/[publicationId]/layout.tsx (find or create application run)
 * 4. Same ApplicationDetailView renders the full application (carousel, toolbar, epochs, etc.)
 */
export default function SharedApplication({ publicationId, token, title }: SharedApplicationProps) {
  return (
    <ShareProviders token={token}>
      {/* key={publicationId} forces a clean remount when the share token swaps
          to a different publication: without it, `data` state and the inner
          WorkflowModeProvider keep the FIRST loaded publication's runId. */}
      <SharedApplicationInner key={publicationId} publicationId={publicationId} title={title} />
    </ShareProviders>
  );
}

function SharedApplicationInner({ publicationId, title }: { publicationId: string; title?: string }) {
  const t = useTranslations('applications');

  const [data, setData] = useState<{
    workflowId: string;
    runId: string;
    title: string;
    publisherName?: string;
    publisherId?: string;
    publication: WorkflowPublication;
    plan: unknown;
  } | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Same init logic as /app/applications/[publicationId]/layout.tsx
  const initialize = useCallback(async () => {
    try {
      const pub = await publicationService.getPublicationById(publicationId);

      // Determine workflowId (same logic as authenticated app layout)
      let targetWorkflowId: string | null = null;
      try {
        const acquiredRes = await publicationService.getAcquiredApplications();
        const acquiredApp = acquiredRes.applications?.find(
          (app) => app.sourcePublicationId === publicationId
        );
        if (acquiredApp?.workflowId) {
          targetWorkflowId = acquiredApp.workflowId;
        }
      } catch { /* not acquired, use publisher's workflow */ }

      if (!targetWorkflowId) {
        try {
          const appWorkflow = await publicationService.getApplicationWorkflow(publicationId);
          targetWorkflowId = appWorkflow?.workflowId || pub.workflowId;
        } catch {
          targetWorkflowId = pub.workflowId;
        }
      }

      if (!targetWorkflowId) {
        setError(t('noWorkflow'));
        return;
      }

      let workflowPlan = pub.planSnapshot;

      // Find or create application run (same as authenticated app layout)
      let runId: string | null = null;
      const existingRun = await workflowService.getApplicationRun(targetWorkflowId, publicationId);
      if (existingRun?.runId) {
        runId = existingRun.runId;
      }

      if (!runId) {
        if (!workflowPlan) {
          const workflow = await workflowService.getWorkflow(targetWorkflowId);
          workflowPlan = workflow?.plan;
        }
        if (!workflowPlan) {
          setError(t('noSnapshot'));
          return;
        }
        const result = await workflowService.executeWorkflow({
          workflowId: targetWorkflowId,
          planJson: JSON.stringify(workflowPlan),
          dataInputs: {},
          executionMode: 'automatic',
          source: 'application',
          publicationId,
        });
        if (result?.runId) {
          runId = result.runId;
        }
      }

      if (!runId) {
        setError(t('noRun'));
        return;
      }
      if (!workflowPlan) {
        setError(t('noSnapshot'));
        return;
      }

      setData({
        workflowId: targetWorkflowId,
        runId,
        title: pub.title,
        publisherName: pub.publisherName,
        publisherId: pub.publisherId,
        publication: pub,
        plan: workflowPlan,
      });
    } catch (err) {
      console.error('[SharedApplication] Failed to initialize:', err);
      setError(t('loadFailed'));
    }
  }, [publicationId, t]);

  useEffect(() => {
    initialize();
  }, [initialize]);

  if (error) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-slate-50 dark:bg-slate-950">
        <div className="max-w-md w-full mx-4 text-center space-y-4 p-8 bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-800">
          <AlertCircle className="w-8 h-8 text-slate-400 mx-auto" />
          <p className="text-sm text-slate-500 dark:text-slate-400">{error}</p>
        </div>
      </div>
    );
  }

  if (!data) {
    return <WorkflowLoadingState />;
  }

  // Same component tree as /app/applications/[publicationId]/layout.tsx
  return (
    <StreamingProvider>
    <WorkflowModeProvider workflowId={data.workflowId} initialRunId={data.runId}>
      <WorkflowRunProvider>
        <SidePanelProvider>
          <ApplicationDetailView
            workflowId={data.workflowId}
            runId={data.runId}
            title={data.title}
            publisherName={data.publisherName}
            publisherId={data.publisherId}
            publication={data.publication}
            planOverride={data.plan}
          />
          {/* SidePanel must be mounted (WorkflowRunCanvas lives inside it as a keepMounted tab)
              but stays hidden in shared context - only visible in /app/applications */}
          <div className="hidden"><SidePanel /></div>
        </SidePanelProvider>
      </WorkflowRunProvider>
    </WorkflowModeProvider>
    </StreamingProvider>
  );
}
