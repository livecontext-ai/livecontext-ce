'use client';

import { Blocks, Camera, Globe, CheckCircle2, CircleSlash } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useFeatureCapabilities } from '@/hooks/useFeatureCapabilities';
import { RENDERER_ENABLE_COMMAND, BROWSER_AGENT_ENABLE_COMMAND } from '@/lib/optionalComponentCommands';

interface ComponentRowProps {
  icon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
  name: string;
  description: string;
  enabled: boolean;
  enabledLabel: string;
  disabledLabel: string;
  command: string;
}

function ComponentRow({ icon: Icon, name, description, enabled, enabledLabel, disabledLabel, command }: ComponentRowProps) {
  return (
    <div className="py-3 first:pt-0 last:pb-0">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2 min-w-0">
          <Icon className="h-3.5 w-3.5 shrink-0 text-theme-secondary" />
          <span className="text-sm font-medium text-theme-primary truncate">{name}</span>
        </div>
        {enabled ? (
          <span className="inline-flex items-center gap-1 text-xs text-green-600 dark:text-green-400">
            <CheckCircle2 className="h-3 w-3" />
            {enabledLabel}
          </span>
        ) : (
          <span className="inline-flex items-center gap-1 text-xs text-amber-600 dark:text-amber-400">
            <CircleSlash className="h-3 w-3" />
            {disabledLabel}
          </span>
        )}
      </div>
      <p className="mt-1 text-sm text-theme-secondary">{description}</p>
      {!enabled && (
        <code className="mt-1.5 block break-all rounded bg-theme-secondary px-2 py-1 text-xs text-theme-primary">
          {command}
        </code>
      )}
    </div>
  );
}

/**
 * Settings > Information "Optional components" card (CE self-hosted only - the
 * page wrapper gates it on IS_CE). Shows whether the two opt-in heavy components
 * are running and, when not, the exact docker command to enable each. The status
 * comes from the same capabilities endpoint the workflow builder uses for its
 * warnings, so the two surfaces can never disagree.
 */
export default function OptionalComponentsCard() {
  const t = useTranslations('optionalComponents');
  const { capabilities, isLoading } = useFeatureCapabilities();

  return (
    <div className="rounded-xl border border-theme p-6">
      <div className="flex items-center gap-3 mb-5">
        <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center shrink-0">
          <Blocks className="w-5 h-5 text-theme-primary" />
        </div>
        <div>
          <h2 className="text-lg font-semibold text-theme-primary">{t('title')}</h2>
          <p className="text-sm text-theme-secondary">{t('subtitle')}</p>
        </div>
      </div>

      {isLoading ? (
        <div className="space-y-3" aria-hidden="true">
          <div className="h-4 w-2/3 rounded bg-theme-tertiary animate-pulse" />
          <div className="h-4 w-1/2 rounded bg-theme-tertiary animate-pulse" />
        </div>
      ) : !capabilities ? (
        <p className="text-sm text-theme-secondary">{t('loadError')}</p>
      ) : (
        <div className="divide-y divide-theme">
          <ComponentRow
            icon={Camera}
            name={t('renderer')}
            description={t('rendererDescription')}
            enabled={capabilities.screenshotRenderer}
            enabledLabel={t('enabled')}
            disabledLabel={t('notEnabled')}
            command={RENDERER_ENABLE_COMMAND}
          />
          <ComponentRow
            icon={Globe}
            name={t('browserAgent')}
            description={t('browserAgentDescription')}
            enabled={capabilities.browserAgent}
            enabledLabel={t('enabled')}
            disabledLabel={t('notEnabled')}
            command={BROWSER_AGENT_ENABLE_COMMAND}
          />
        </div>
      )}
    </div>
  );
}
