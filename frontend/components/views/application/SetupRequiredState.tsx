'use client';

/**
 * Pre-flight gate shown by {@code ApplicationLayout} when an acquired
 * application needs credentials the user has not yet connected. The user
 * must finish the wizard (or skip) before {@code executeWorkflow} is fired.
 *
 * <p>Why a full-page state instead of a passive banner: when the user opens
 * an acquired app for the first time, the layout auto-creates a workflow run.
 * If the run starts without creds, the user sees a generic failure page
 * before any prompt. Blocking run creation upfront converts that into a
 * deterministic "Connect 3 services to start" experience.
 */
import * as React from 'react';
import { useTranslations } from 'next-intl';
import { AlertCircle, ChevronRight, ExternalLink } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ServiceIcon } from '@/components/ui/service-icon';
import { CredentialWizard } from '@/components/credentials/CredentialWizard';
import type {
  ManualMissingCredential,
  WizardableMissingCredential,
} from '@/lib/credentials/missingCredentials';

interface SetupRequiredStateProps {
  appTitle: string;
  wizardable: WizardableMissingCredential[];
  manual: ManualMissingCredential[];
  /** Called after the wizard finishes (with the icon slugs the user actually connected). */
  onConnectionsUpdated: () => Promise<void> | void;
  /**
   * Allow the user to skip the gate and continue to the run anyway. Useful
   * when the user knows some nodes are optional. The run will likely fail
   * at the missing-cred step, but the failure path remains available.
   */
  onSkip: () => void;
}

export function SetupRequiredState({
  appTitle,
  wizardable,
  manual,
  onConnectionsUpdated,
  onSkip,
}: SetupRequiredStateProps) {
  const t = useTranslations('applications.setup');
  const [wizardOpen, setWizardOpen] = React.useState(false);

  const totalCount = wizardable.length + manual.length;

  const handleWizardComplete = React.useCallback(async () => {
    setWizardOpen(false);
    await onConnectionsUpdated();
  }, [onConnectionsUpdated]);

  return (
    <div className="absolute inset-0 flex items-center justify-center p-6 overflow-auto">
      <div className="w-full max-w-lg bg-white dark:bg-gray-800 rounded-2xl shadow-lg border border-amber-200 dark:border-amber-700/50 p-6 space-y-5">
        <div className="flex items-start gap-3">
          <div className="w-10 h-10 rounded-full bg-amber-100 dark:bg-amber-900/30 flex items-center justify-center shrink-0">
            <AlertCircle className="w-5 h-5 text-amber-600 dark:text-amber-400" />
          </div>
          <div className="flex-1 min-w-0">
            <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100">
              {t('title', { count: totalCount })}
            </h2>
            <p className="text-sm text-gray-600 dark:text-gray-400 mt-1">
              {t('subtitle', { app: appTitle })}
            </p>
          </div>
        </div>

        {wizardable.length > 0 && (
          <div className="space-y-2">
            <p className="text-xs font-medium text-gray-500 dark:text-gray-400 uppercase tracking-wide">
              {t('connectableHeader', { count: wizardable.length })}
            </p>
            <ul className="space-y-1.5">
              {wizardable.map((w) => (
                <li
                  key={w.iconSlug}
                  className="flex items-center gap-2.5 px-3 py-2 rounded-lg bg-gray-50 dark:bg-gray-900/40 border border-gray-100 dark:border-gray-700/50"
                >
                  <ServiceIcon iconSlug={w.iconSlug} size="md" />
                  <span className="text-sm text-gray-800 dark:text-gray-200 flex-1 truncate">
                    {w.serviceName}
                  </span>
                  {w.sourceNodeIds.length > 1 && (
                    <span className="text-xs text-gray-400 dark:text-gray-500">
                      {t('usedInNSteps', { count: w.sourceNodeIds.length })}
                    </span>
                  )}
                </li>
              ))}
            </ul>
            {/* Hint when an app uses multiple accounts of the same service */}
            <p className="text-xs text-gray-500 dark:text-gray-400">
              {t('multiAccountHint')}
            </p>
          </div>
        )}

        {manual.length > 0 && (
          <div className="space-y-2">
            <p className="text-xs font-medium text-gray-500 dark:text-gray-400 uppercase tracking-wide">
              {t('manualHeader', { count: manual.length })}
            </p>
            <ul className="space-y-1.5">
              {manual.map((m) => (
                <li
                  key={m.nodeId}
                  className="flex items-start gap-2.5 px-3 py-2 rounded-lg bg-gray-50 dark:bg-gray-900/40 border border-gray-100 dark:border-gray-700/50"
                >
                  <ExternalLink className="w-4 h-4 text-gray-400 mt-0.5 shrink-0" />
                  <div className="flex-1 min-w-0 text-sm">
                    <div className="text-gray-800 dark:text-gray-200 truncate">
                      {m.label}
                    </div>
                    <div className="text-xs text-gray-500 dark:text-gray-400 mt-0.5">
                      {m.reason}
                    </div>
                  </div>
                </li>
              ))}
            </ul>
            <p className="text-xs text-gray-500 dark:text-gray-400">
              {t('manualHint')}
            </p>
          </div>
        )}

        <div className="flex items-center justify-end gap-2 pt-2 border-t border-gray-100 dark:border-gray-700/50">
          <Button variant="ghost" size="sm" onClick={onSkip}>
            {t('skip')}
          </Button>
          {wizardable.length > 0 && (
            <Button
              variant="default"
              size="sm"
              onClick={() => setWizardOpen(true)}
              className="gap-1"
            >
              {t('connectButton', { count: wizardable.length })}
              <ChevronRight className="w-4 h-4" />
            </Button>
          )}
        </div>
      </div>

      {wizardable.length > 0 && (
        <CredentialWizard
          open={wizardOpen}
          onOpenChange={setWizardOpen}
          requirements={wizardable.map((w) => ({
            iconSlug: w.iconSlug,
            serviceName: w.serviceName,
            toolId: w.toolId,
          }))}
          onComplete={handleWizardComplete}
          // Refresh each time a single cred lands so the gate decrements
          // progressively even if the user closes the wizard mid-flow.
          onCredentialAdded={() => {
            void onConnectionsUpdated();
          }}
        />
      )}
    </div>
  );
}

