'use client';

import React, { useState, type ReactNode } from 'react';
import { Copy, Check, MoreHorizontal, Workflow, AppWindow, type LucideIcon } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';
import Link from 'next/link';
import { formatUtcDate } from '@/lib/utils/dateFormatters';

export interface TriggerAction {
  label: string;
  icon: LucideIcon;
  onClick: () => void;
  variant?: 'default' | 'destructive';
}

interface TriggerCardProps {
  name: string;
  isActive: boolean;
  workflowId?: string | null;
  workflowName?: string | null;
  /** When false, the workflow link / "Not linked" badge is hidden entirely.
   * Use for resources that don't have a workflow concept (e.g. shared conversation links). */
  showWorkflowLink?: boolean;
  /**
   * When true, renders a small "App" badge alongside the workflow link to
   * mark this trigger as belonging to an application (workflowType=APPLICATION).
   * Computed by the parent via {@code useAcquiredAppWorkflowIds()} -
   * callers pass {@code appWorkflowIds.has(workflowId)}.
   */
  isApplication?: boolean;
  createdAt?: string;
  detailLine?: string;
  detailCopyable?: boolean;
  badges?: ReactNode;
  extraInfo?: ReactNode;
  actions: TriggerAction[];
}

export function TriggerCard({
  name,
  isActive,
  workflowId,
  workflowName,
  showWorkflowLink = true,
  isApplication = false,
  createdAt,
  detailLine,
  detailCopyable = true,
  badges,
  extraInfo,
  actions,
}: TriggerCardProps) {
  const t = useTranslations('triggerSettings');
  const tCommon = useTranslations('common');
  const [showActions, setShowActions] = useState(false);
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    if (!detailLine) return;
    navigator.clipboard.writeText(detailLine);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="border border-slate-200 dark:border-slate-700 rounded-lg p-4 hover:border-slate-300 dark:hover:border-slate-600 transition-colors">
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          {/* Name + Status */}
          <div className="flex items-center gap-2 mb-1">
            <h3 className="text-sm font-medium text-theme-primary truncate">{name}</h3>
            <span className={`inline-flex items-center px-1.5 py-0.5 text-xs font-medium rounded ${
              isActive
                ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                : 'bg-theme-tertiary text-theme-secondary'
            }`}>
              {isActive ? t('active') : t('inactive')}
            </span>
          </div>

          {/* Detail line (URL or cron) */}
          {detailLine && (
            <div className="flex items-center gap-1.5 mb-2">
              <code className="text-xs font-mono text-theme-secondary bg-theme-tertiary px-2 py-0.5 rounded-lg truncate max-w-[400px]">
                {detailLine}
              </code>
              {detailCopyable && (
                <button
                  onClick={handleCopy}
                  className="p-1 rounded hover:bg-theme-tertiary flex-shrink-0"
                >
                  {copied ? (
                    <Check className="h-3 w-3 text-green-500" />
                  ) : (
                    <Copy className="h-3 w-3 text-theme-secondary" />
                  )}
                </button>
              )}
            </div>
          )}

          {/* Extra info (execution count, next run, etc.) */}
          {extraInfo && (
            <div className="mb-2">{extraInfo}</div>
          )}

          {/* Badges + Workflow link */}
          <div className="flex items-center gap-2 flex-wrap">
            {badges}
            {isApplication && (
              <span
                title={t('appBadgeTooltip')}
                className="inline-flex items-center gap-1 px-1.5 py-0.5 text-xs rounded bg-emerald-50 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400"
              >
                <AppWindow className="h-3 w-3" />
                {t('appBadge')}
              </span>
            )}
            {showWorkflowLink && (workflowId ? (
              <Link
                href={`/app/workflow/${workflowId}`}
                onClick={(e) => e.stopPropagation()}
                className="inline-flex items-center gap-1 px-1.5 py-0.5 text-xs rounded bg-purple-50 text-purple-600 dark:bg-purple-900/30 dark:text-purple-400 hover:bg-purple-100 dark:hover:bg-purple-900/50 transition-colors"
              >
                <Workflow className="h-3 w-3" />
                {workflowName || 'Workflow'}
              </Link>
            ) : (
              <span className="inline-flex items-center px-1.5 py-0.5 text-xs rounded bg-theme-tertiary text-theme-secondary">
                {t('notLinked')}
              </span>
            ))}
            {createdAt && (
              <span className="text-xs text-theme-secondary">
                {formatUtcDate(createdAt)}
              </span>
            )}
          </div>
        </div>

        {/* Actions dropdown */}
        <div className="relative flex-shrink-0">
          <Button
            variant="ghost"
            size="sm"
            className="h-8 w-8 p-0"
            aria-label={`${tCommon('actions')}: ${name}`}
            onClick={(e) => {
              e.stopPropagation();
              setShowActions(!showActions);
            }}
          >
            <MoreHorizontal className="h-4 w-4" />
          </Button>
          {showActions && (
            <>
              <div className="fixed inset-0 z-40" onClick={() => setShowActions(false)} />
              <div className="absolute right-0 top-8 z-50 w-44 bg-theme-primary border border-theme rounded-xl shadow-lg py-1">
                {actions.map((action) => {
                  const ActionIcon = action.icon;
                  return (
                    <button
                      key={action.label}
                      className={`w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-theme-secondary ${
                        action.variant === 'destructive'
                          ? 'text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20'
                          : 'text-theme-primary'
                      }`}
                      onClick={(e) => {
                        e.stopPropagation();
                        setShowActions(false);
                        action.onClick();
                      }}
                    >
                      <ActionIcon className="h-3.5 w-3.5" />
                      {action.label}
                    </button>
                  );
                })}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
