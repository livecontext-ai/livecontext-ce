'use client';

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Share2, Trash2 } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { sharingService, shareLinkUrl, type SharedLink, type SharedLinkConfig } from '@/lib/api/sharing.service';
import { conversationApi } from '@/lib/api/conversationApi';
import { TriggerCard, type TriggerAction } from './TriggerCard';
import { TriggerEmptyState } from './TriggerEmptyState';
import { TriggerUsageGauge } from './TriggerUsageGauge';
import { DeleteSharedLinkDialog } from './DeleteSharedLinkDialog';

interface SharedLinksTabContentProps {
  isAuthenticated: boolean;
  resourceType: string;
  emptyIcon: LucideIcon;
  emptyTitle: string;
  emptyDescription: string;
  addToast: (toast: { type: 'success' | 'error'; title: string; message: string }) => void;
}

function getShareUrl(link: SharedLink): string {
  // Every shared link (FORM included) resolves through the unified /s/ route - see shareLinkUrl.
  return shareLinkUrl(link.token);
}

export function SharedLinksTabContent({
  isAuthenticated,
  resourceType,
  emptyIcon,
  emptyTitle,
  emptyDescription,
  addToast,
}: SharedLinksTabContentProps) {
  const t = useTranslations('triggerSettings');

  const [links, setLinks] = useState<SharedLink[]>([]);
  const [resolvedTitles, setResolvedTitles] = useState<Record<string, string>>({});
  // Tracks link IDs we've already attempted to resolve (success OR failure OR no-title),
  // so the resolver effect never refetches the same conversation.
  const attemptedIdsRef = useRef<Set<string>>(new Set());
  const [config, setConfig] = useState<SharedLinkConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleting, setDeleting] = useState<SharedLink | null>(null);

  const fetchData = useCallback(async () => {
    try {
      const [data, cfg] = await Promise.all([
        sharingService.getAll(resourceType),
        sharingService.getConfig(resourceType),
      ]);
      setLinks(data);
      setConfig(cfg);
    } catch {
      // Silently fail
    } finally {
      setLoading(false);
    }
  }, [resourceType]);

  useEffect(() => {
    if (isAuthenticated) fetchData();
    else setLoading(false);
  }, [isAuthenticated, fetchData]);

  // For CONVERSATION links, fetch the live conversation title once per link
  // (the stored title may be stale or empty if the conversation was untitled at share time).
  useEffect(() => {
    if (resourceType !== 'CONVERSATION' || links.length === 0) return;
    const toFetch = links.filter(l => l.resourceId && !attemptedIdsRef.current.has(l.id));
    if (toFetch.length === 0) return;
    // Mark as attempted up-front to prevent any chance of re-entry / refetch loops.
    toFetch.forEach(l => attemptedIdsRef.current.add(l.id));

    let cancelled = false;
    (async () => {
      const updates: Record<string, string> = {};
      await Promise.all(toFetch.map(async (link) => {
        try {
          const conv = await conversationApi.getConversation(link.resourceId!) as { title?: string };
          if (conv?.title) updates[link.id] = conv.title;
        } catch {
          // Conversation may have been deleted; fall back to the stored title.
        }
      }));
      if (!cancelled && Object.keys(updates).length > 0) {
        setResolvedTitles(prev => ({ ...prev, ...updates }));
      }
    })();
    return () => { cancelled = true; };
  }, [links, resourceType]);

  const handleDeleteClick = (link: SharedLink) => {
    setDeleting(link);
    setDeleteOpen(true);
  };

  const handleDeleteConfirm = async () => {
    if (!deleting) return;
    setActionLoading(true);
    try {
      await sharingService.delete(deleting.id);
      addToast({ type: 'success', title: t('sharedLinkDeleted'), message: '' });
      setDeleteOpen(false);
      setDeleting(null);
      fetchData();
    } catch {
      addToast({ type: 'error', title: t('sharedLinkDeleteError'), message: '' });
    } finally {
      setActionLoading(false);
    }
  };

  const buildActions = (link: SharedLink): TriggerAction[] => [
    { label: t('delete'), icon: Trash2, onClick: () => handleDeleteClick(link), variant: 'destructive' },
  ];

  const getDisplayName = (link: SharedLink): string => {
    return resolvedTitles[link.id] || link.title || `${link.resourceType} link`;
  };

  if (loading) {
    return (
      <div className="space-y-3">
        {[1, 2].map((i) => (
          <div key={i} className="h-24 bg-slate-100 dark:bg-slate-800 rounded-lg animate-pulse" />
        ))}
      </div>
    );
  }

  return (
    <>
      {config && (
        <div className="mb-4">
          <TriggerUsageGauge currentCount={config.currentCount} maxPerUser={config.maxPerUser} />
        </div>
      )}
      {links.length === 0 ? (
        <TriggerEmptyState icon={emptyIcon} title={emptyTitle} description={emptyDescription} />
      ) : (
        <div className="space-y-3">
          {links.map((link) => (
            <TriggerCard
              key={link.id}
              name={getDisplayName(link)}
              isActive={link.isActive}
              showWorkflowLink={false}
              createdAt={link.createdAt}
              detailLine={getShareUrl(link)}
              extraInfo={
                link.accessCount > 0 ? (
                  <span className="text-xs text-theme-secondary">
                    {t('sharedLinkViews', { count: link.accessCount })}
                  </span>
                ) : undefined
              }
              actions={buildActions(link)}
            />
          ))}
        </div>
      )}

      <DeleteSharedLinkDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        linkTitle={deleting ? getDisplayName(deleting) : ''}
        resourceType={deleting?.resourceType || ''}
        onConfirm={handleDeleteConfirm}
        isLoading={actionLoading}
      />
    </>
  );
}
