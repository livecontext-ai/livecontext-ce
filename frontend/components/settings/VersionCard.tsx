'use client';

import { useState } from 'react';
import {
  Package,
  GitCommitHorizontal,
  Calendar,
  Server,
  Cloud,
  ArrowUpCircle,
  ShieldAlert,
  CheckCircle2,
  ExternalLink,
} from 'lucide-react';
import { useTranslations, useLocale } from 'next-intl';
import { useAppVersion } from '@/hooks/useAppVersion';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';
import { cn } from '@/lib/utils';
import HowToUpdateDialog from './HowToUpdateDialog';

/** Maps a backend edition token to its translation key. */
function editionLabelKey(edition: string): string {
  switch (edition) {
    case 'ce':
      return 'editionCe';
    case 'self-hosted-enterprise':
      return 'editionSelfHostedEnterprise';
    case 'cloud':
      return 'editionCloud';
    case 'dedicated-cloud':
      return 'editionDedicatedCloud';
    default:
      return '';
  }
}

function Row({ icon: Icon, label, children }: {
  icon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex items-center justify-between gap-3 py-1.5">
      <div className="flex items-center gap-2 text-theme-secondary">
        <Icon className="h-3.5 w-3.5 shrink-0" />
        <span className="text-sm">{label}</span>
      </div>
      <div className="min-w-0 text-right">{children}</div>
    </div>
  );
}

/**
 * Settings > Information "Version" card: shows the running build (version, commit,
 * build date) and whether a newer release is available (with a "How to update"
 * dialog). Shown in the CE (self-hosted) edition ONLY - the page wrapper gates it on
 * IS_CE, and the useAppVersion hook does not fetch in cloud. Backend is the single
 * source of truth via GET /api/version.
 */
export default function VersionCard() {
  const t = useTranslations('version');
  const locale = useLocale();
  const { version, isLoading, isError } = useAppVersion();
  const [showUpdate, setShowUpdate] = useState(false);

  const editionKey = version ? editionLabelKey(version.edition) : '';
  const editionLabel = editionKey ? t(editionKey) : (version?.edition ?? '');
  const EditionIcon = version?.managedCloud ? Cloud : Server;

  return (
    <div className="rounded-xl border border-theme p-6">
      <div className="flex items-center gap-3 mb-5">
        <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center shrink-0">
          <Package className="w-5 h-5 text-theme-primary" />
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
          <div className="h-4 w-1/3 rounded bg-theme-tertiary animate-pulse" />
        </div>
      ) : !version || isError ? (
        <p className="text-sm text-theme-secondary">{t('loadError')}</p>
      ) : (
        <>
          <div className="space-y-0.5">
            <Row icon={EditionIcon} label={t('edition')}>
              <span className="text-sm font-medium text-theme-primary">{editionLabel}</span>
            </Row>

            <Row icon={Package} label={t('version')}>
              <span className="text-sm font-medium text-theme-primary tabular-nums">{version.version}</span>
            </Row>

            {version.gitSha && (
              <Row icon={GitCommitHorizontal} label={t('commit')}>
                <span className="text-xs font-mono text-theme-secondary">{version.gitSha}</span>
              </Row>
            )}

            {version.buildTime && (
              <Row icon={Calendar} label={t('builtOn')}>
                <span className="text-sm text-theme-secondary">
                  {formatUtcDateTime(version.buildTime, { locale })}
                </span>
              </Row>
            )}
          </div>

          {/* Update status: self-hosted editions only (managed cloud auto-updates). */}
          {version.selfHosted && (version.updateAvailable || version.latestVersion) && (
            <div className="mt-5">
              {version.updateAvailable ? (
                <div
                  className={cn(
                    'rounded-lg p-3',
                    version.securityFix ? 'bg-red-500/10' : 'bg-amber-500/10',
                  )}
                >
                  <div className="flex items-start gap-2">
                    {version.securityFix ? (
                      <ShieldAlert className="h-4 w-4 shrink-0 mt-0.5 text-red-500" />
                    ) : (
                      <ArrowUpCircle className="h-4 w-4 shrink-0 mt-0.5 text-amber-500" />
                    )}
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium text-theme-primary">
                        {version.securityFix ? t('securityFix') : t('updateAvailable')}
                      </p>
                      <p className="text-sm text-theme-secondary">
                        {t('newVersion', { version: version.latestVersion ?? '' })}
                      </p>
                      {version.securityFix && (
                        <p className="mt-1 text-xs text-red-600 dark:text-red-400">
                          {t('securityFixHint')}
                        </p>
                      )}
                      <div className="mt-2 flex flex-wrap items-center gap-3">
                        <button
                          type="button"
                          onClick={() => setShowUpdate(true)}
                          className="rounded-full bg-black px-3 py-1 text-xs font-medium text-white transition-colors hover:bg-black/90 dark:bg-white dark:text-black dark:hover:bg-white/90 cursor-pointer"
                        >
                          {t('howToUpdate')}
                        </button>
                        {version.releaseUrl && (
                          <a
                            href={version.releaseUrl}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="inline-flex items-center gap-1 text-xs text-theme-secondary hover:text-theme-primary"
                          >
                            <ExternalLink className="h-3 w-3" />
                            {t('releaseNotes')}
                          </a>
                        )}
                      </div>
                    </div>
                  </div>
                </div>
              ) : (
                <div className="flex items-center gap-2 text-sm text-theme-secondary">
                  <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-green-500" />
                  <span>{t('upToDate')}</span>
                </div>
              )}

              {version.checkedAt && (
                <p className="mt-2 text-xs text-theme-muted">
                  {t('lastChecked', { date: formatUtcDateTime(version.checkedAt, { locale }) })}
                </p>
              )}
            </div>
          )}

          <HowToUpdateDialog
            open={showUpdate}
            onOpenChange={setShowUpdate}
            releaseUrl={version.releaseUrl}
          />
        </>
      )}
    </div>
  );
}
