'use client';

import React, { useCallback, useState } from 'react';
import { Loader2, Settings, Workflow } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Link } from '@/i18n/navigation';
import { publicationService } from '@/lib/api/orchestrator/publication.service';

interface ApplicationSettingsMenuProps {
  publicationId: string;
  /**
   * Cloud-linked CE: the publication lives on the cloud, so the copy goes through
   * the remote route (the local publication row does not exist).
   */
  remote?: boolean;
  /**
   * Offer the "Create an editable copy" entry. When false the menu has no entry at
   * all, which is a dead affordance, so no cog is rendered either.
   */
  canCreateEditableCopy?: boolean;
  className?: string;
}

/**
 * Settings cog for an installed application: a small popover holding the actions that
 * are not part of reading the app's Info panel. Mounted as the last control of the
 * application toolbar, the one the central button at the bottom of the app opens.
 *
 * <p>Today it holds a single entry, "Create an editable copy", which used to sit inline
 * in the Info tab. It is a rarely-used, one-shot action, so it reads better behind a cog
 * than as a permanent block above the app description. The same copy can also be
 * requested at install time from the acquire modal.
 */
export function ApplicationSettingsMenu({
  publicationId,
  remote = false,
  canCreateEditableCopy = false,
  className,
}: ApplicationSettingsMenuProps) {
  const t = useTranslations('marketplace');
  const [open, setOpen] = useState(false);

  const [editableCopy, setEditableCopy] = useState<{
    status: 'idle' | 'creating' | 'done' | 'error';
    workflowId?: string;
    created?: boolean;
  }>({ status: 'idle' });

  const handleCreateEditableCopy = useCallback(async () => {
    setEditableCopy({ status: 'creating' });
    try {
      const result = await publicationService.createEditableWorkflowCopy(publicationId, remote);
      setEditableCopy({ status: 'done', workflowId: result.workflowId, created: result.created });
    } catch (err: any) {
      // A workflow-quota refusal (409 PLAN_RESOURCE_LIMIT_EXCEEDED) already raises the
      // GLOBAL plan-limit toast with its upgrade call to action, so this component stays
      // quiet and just resets - saying it twice, once vaguely, is the house anti-pattern.
      const limitReached = err?.status === 409 && err?.code === 'PLAN_RESOURCE_LIMIT_EXCEEDED';
      setEditableCopy({ status: limitReached ? 'idle' : 'error' });
    }
  }, [publicationId, remote]);

  // Nothing to put in the menu - render no cog rather than an empty popover.
  if (!canCreateEditableCopy) return null;

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          data-testid="application-settings-trigger"
          aria-label={t('settingsMenu.label')}
          title={t('settingsMenu.label')}
          // Same shape as the other icon controls of the application toolbar it sits in.
          className={cn(
            'w-7 h-7 p-0 rounded-xl transition-colors inline-flex items-center justify-center',
            'text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]',
            className
          )}
        >
          <Settings className="h-3.5 w-3.5" />
        </button>
      </PopoverTrigger>
      <PopoverContent
        // The toolbar sits at the bottom of the application, so the menu opens UPWARD.
        side="top"
        align="end"
        sideOffset={6}
        className="w-[264px] p-1.5 bg-theme-primary rounded-xl border border-gray-300/70 dark:border-gray-600/70 z-[100000]"
      >
        {editableCopy.status === 'done' && editableCopy.workflowId ? (
          <div className="px-2.5 py-2 space-y-1">
            <p className="text-sm text-theme-primary leading-snug">
              {editableCopy.created ? t('editableCopy.created') : t('editableCopy.existing')}
            </p>
            {/* Locale-aware Link (house style): a bare href drops the /{locale}
                prefix and forces a full reload of the app shell. */}
            <Link
              href={`/app/workflow/${editableCopy.workflowId}`}
              className="inline-flex items-center gap-1.5 text-sm font-medium text-[var(--accent-primary)] hover:underline"
              onClick={() => setOpen(false)}
            >
              <Workflow className="h-3.5 w-3.5" aria-hidden="true" />
              {t('editableCopy.open')}
            </Link>
          </div>
        ) : (
          <div className="space-y-0.5">
            <button
              type="button"
              data-testid="application-settings-editable-copy"
              disabled={editableCopy.status === 'creating'}
              onClick={handleCreateEditableCopy}
              className="w-full flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-sm text-theme-primary transition-colors hover:bg-gray-100 dark:hover:bg-gray-700 disabled:opacity-60"
            >
              {editableCopy.status === 'creating' ? (
                <>
                  <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin" />
                  <span className="text-left">{t('editableCopy.creating')}</span>
                </>
              ) : (
                <>
                  <Workflow className="h-3.5 w-3.5 shrink-0" />
                  <span className="text-left">{t('editableCopy.button')}</span>
                </>
              )}
            </button>
            <p className="px-2.5 pb-1 text-xs text-theme-secondary leading-snug">
              {editableCopy.status === 'error'
                ? t('editableCopy.error')
                : t('editableCopy.description')}
            </p>
          </div>
        )}
      </PopoverContent>
    </Popover>
  );
}

export default ApplicationSettingsMenu;
