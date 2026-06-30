'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { Copy, Check, RefreshCw, ExternalLink, Globe, AlertCircle } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { sharingService, type SharedLink } from '@/lib/api/sharing.service';
import { conversationSharingService } from '@/lib/api/conversation-sharing.service';
import { Link } from '@/i18n/navigation';
import { formatUtcDate } from '@/lib/utils/dateFormatters';

type Phase = 'loading' | 'confirm' | 'shared' | 'limit';

interface ShareLinkDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  resourceType: string;
  resourceToken: string;
  resourceId?: string;
  resourceName: string;
}

export function ShareLinkDialog({
  open,
  onOpenChange,
  resourceType,
  resourceToken,
  resourceId,
  resourceName,
}: ShareLinkDialogProps) {
  const t = useTranslations('sharing');

  const [link, setLink] = useState<SharedLink | null>(null);
  const [loading, setLoading] = useState(false);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [mounted, setMounted] = useState(false);
  const [phase, setPhase] = useState<Phase>('loading');
  const [quotaCount, setQuotaCount] = useState(0);
  const [quotaMax, setQuotaMax] = useState(0);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  const checkExistingLink = useCallback(async () => {
    setLoading(true);
    setError(null);
    setPhase('loading');
    try {
      // Single optimized call: checks for existing link by resourceToken + returns quota
      // For conversations, also pass resourceToken as resourceId for fallback lookup
      const lookupResourceId = resourceId || (resourceType === 'CONVERSATION' ? resourceToken : undefined);
      const { link: existing, config } = await sharingService.check(resourceToken, lookupResourceId);

      setQuotaCount(config.currentCount);
      setQuotaMax(config.maxPerUser);

      if (existing) {
        setLink(existing);
        setPhase('shared');
        return;
      }

      // No existing link - check quota
      if (config.currentCount >= config.maxPerUser) {
        setPhase('limit');
      } else {
        setPhase('confirm');
      }
    } catch {
      setError(t('errorLoadingLink'));
      setPhase('confirm');
    } finally {
      setLoading(false);
    }
  }, [resourceId, resourceType, resourceToken, t]);

  const handleConfirmShare = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      let actualResourceToken = resourceToken;

      // For conversations, enable sharing in conversation-service first to get cs_ token
      if (resourceType === 'CONVERSATION') {
        const conv = await conversationSharingService.enableSharing(resourceToken, {
          shareMode: 'read',
          memoryEnabled: true,
        });
        if (conv.shareToken) {
          actualResourceToken = conv.shareToken;
        }
      }

      const created = await sharingService.create({
        resourceType,
        resourceToken: actualResourceToken,
        title: resourceName,
        // For conversations, store original ID so check() can find the link later
        ...((resourceId || resourceType === 'CONVERSATION')
          ? { resourceId: resourceId || resourceToken }
          : {}),
      });
      setLink(created);
      setPhase('shared');
    } catch {
      setError(t('errorLoadingLink'));
    } finally {
      setLoading(false);
    }
  }, [resourceId, resourceType, resourceToken, resourceName, t]);

  useEffect(() => {
    if (open) {
      checkExistingLink();
      setCopied(false);
    }
  }, [open, checkExistingLink]);

  const shareUrl = link
    ? `${window.location.origin}/s/${link.token}`
    : null;

  const handleCopy = async () => {
    if (!shareUrl) return;
    try {
      await navigator.clipboard.writeText(shareUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      const textarea = document.createElement('textarea');
      textarea.value = shareUrl;
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  const handleToggleActive = async () => {
    if (!link) return;
    try {
      const updated = await sharingService.update(link.id, { isActive: !link.isActive });
      setLink(updated);

      // For conversations, also toggle sharing in conversation-service
      if (resourceType === 'CONVERSATION') {
        if (updated.isActive) {
          await conversationSharingService.enableSharing(resourceToken, {
            shareMode: 'read',
            memoryEnabled: true,
          });
        } else {
          await conversationSharingService.disableSharing(resourceToken).catch(() => {});
        }
      }
    } catch {
      setError(t('errorUpdatingLink'));
    }
  };

  const handleRegenerate = async () => {
    if (!link) return;
    try {
      const updated = await sharingService.regenerateToken(link.id);
      setLink(updated);
      setCopied(false);
    } catch {
      setError(t('errorRegeneratingLink'));
    }
  };

  const handleClose = () => onOpenChange(false);

  if (!mounted || !open) return null;
  const titleId = 'share-link-dialog-title';

  const renderConfirmPhase = () => (
    <>
      <div className="text-center mb-6">
        <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
          <Globe className="w-8 h-8 text-theme-primary" />
        </div>
        <h3 id={titleId} className="text-2xl font-semibold text-theme-primary">{t('confirmTitle')}</h3>
        <p className="text-sm text-theme-secondary mt-1">{resourceName}</p>
      </div>

      <div className="space-y-5">
        <div className="rounded-xl border border-amber-200 dark:border-amber-800 bg-amber-50 dark:bg-amber-900/20 p-4">
          <p className="text-sm text-amber-800 dark:text-amber-200">
            {t('confirmDescription')}
          </p>
        </div>

        {error && (
          <p className="text-sm text-red-500">{error}</p>
        )}
      </div>

      <div className="flex gap-3 mt-8">
        <Button
          variant="outline"
          onClick={handleClose}
          className="flex-1"
        >
          {t('cancel')}
        </Button>
        <Button
          onClick={handleConfirmShare}
          disabled={loading}
          className="flex-1"
        >
          {loading ? (
            <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
          ) : (
            t('confirmButton')
          )}
        </Button>
      </div>
    </>
  );

  const renderLimitPhase = () => (
    <>
      <div className="text-center mb-6">
        <div className="w-16 h-16 bg-amber-50 dark:bg-amber-900/20 rounded-full flex items-center justify-center mx-auto mb-4">
          <AlertCircle className="w-8 h-8 text-amber-600 dark:text-amber-400" />
        </div>
        <h3 id={titleId} className="text-2xl font-semibold text-theme-primary">{t('limitReachedTitle')}</h3>
      </div>

      <div className="space-y-5">
        <p className="text-sm text-theme-secondary text-center">
          {t('limitReachedDescription', { count: quotaCount, max: quotaMax })}
        </p>

        <div className="text-center">
          <Link
            href="/app/settings/public-access"
            onClick={handleClose}
            className="text-sm text-[var(--accent-primary)] hover:underline"
          >
            {t('manageSharedLinks')}
          </Link>
        </div>
      </div>

      <div className="flex gap-3 mt-8">
        <Button
          variant="outline"
          onClick={handleClose}
          className="flex-1"
        >
          {t('close')}
        </Button>
      </div>
    </>
  );

  const renderSharedPhase = () => (
    <>
      <div className="text-center mb-6">
        <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
          <Globe className="w-8 h-8 text-theme-primary" />
        </div>
        <h3 id={titleId} className="text-2xl font-semibold text-theme-primary">{t('shareLink')}</h3>
        <p className="text-sm text-theme-secondary mt-1">{resourceName}</p>
      </div>

      <div className="space-y-5">
        {error && (
          <p className="text-sm text-red-500">{error}</p>
        )}

        {link && (
          <>
            {/* URL copy area */}
            <div className="flex items-center gap-2 min-w-0">
              <div className="flex-1 min-w-0 flex items-center rounded-xl border border-theme bg-theme-secondary px-3 py-2.5">
                <code className="text-xs text-theme-primary truncate block">
                  {shareUrl}
                </code>
              </div>
              <button
                onClick={handleCopy}
                className="flex-shrink-0 h-10 w-10 rounded-xl border border-theme bg-theme-secondary flex items-center justify-center hover:bg-theme-tertiary transition-colors"
                title={t('copyLink')}
              >
                {copied ? (
                  <Check className="h-3.5 w-3.5 text-green-500" />
                ) : (
                  <Copy className="h-3.5 w-3.5 text-theme-muted" />
                )}
              </button>
              <a
                href={shareUrl || '#'}
                target="_blank"
                rel="noopener noreferrer"
                className="flex-shrink-0 h-10 w-10 rounded-xl border border-theme bg-theme-secondary flex items-center justify-center hover:bg-theme-tertiary transition-colors"
              >
                <ExternalLink className="h-3.5 w-3.5 text-theme-muted" />
              </a>
            </div>

            {copied && (
              <p className="text-xs text-green-600 dark:text-green-400">{t('linkCopied')}</p>
            )}

            {/* Info */}
            <div className="space-y-3 text-sm">
              <div className="flex items-center justify-between">
                <span className="text-theme-secondary">{t('accessCount')}</span>
                <span className="text-theme-primary font-medium">{link.accessCount}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-theme-secondary">{t('createdAt')}</span>
                <span className="text-theme-primary font-medium">
                  {formatUtcDate(link.createdAt)}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-theme-secondary">
                  {link.isActive ? t('shareActive') : t('shareInactive')}
                </span>
                <button
                  onClick={handleToggleActive}
                  className={`text-xs px-2.5 py-1 rounded-lg transition-colors ${
                    link.isActive
                      ? 'text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20'
                      : 'text-green-600 hover:bg-green-50 dark:hover:bg-green-900/20'
                  }`}
                >
                  {link.isActive ? t('disableSharing') : t('enableSharing')}
                </button>
              </div>
            </div>

            {/* Regenerate */}
            <div className="pt-4 border-t border-theme">
              <button
                onClick={handleRegenerate}
                className="flex items-center gap-1.5 text-xs text-theme-secondary hover:text-theme-primary transition-colors"
              >
                <RefreshCw className="h-3 w-3" />
                {t('regenerateLink')}
              </button>
              <p className="text-xs text-theme-muted mt-1">
                {t('shareLinkDescription')}
              </p>
            </div>
          </>
        )}
      </div>

      <div className="flex gap-3 mt-8">
        <Button
          variant="outline"
          onClick={handleClose}
          className="flex-1"
        >
          {t('close')}
        </Button>
        {link && (
          <Button
            onClick={handleCopy}
            disabled={!shareUrl}
            className="flex-1"
          >
            {copied ? t('linkCopied') : t('copyLink')}
          </Button>
        )}
      </div>
    </>
  );

  const renderLoading = () => (
    <>
      <div className="text-center mb-6">
        <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
          <Globe className="w-8 h-8 text-theme-primary" />
        </div>
        <h3 id={titleId} className="text-2xl font-semibold text-theme-primary">{t('shareLink')}</h3>
        <p className="text-sm text-theme-secondary mt-1">{resourceName}</p>
      </div>
      <div className="flex items-center justify-center py-6">
        <div className="w-6 h-6 border-2 border-theme border-t-[var(--accent-primary)] rounded-full animate-spin" />
      </div>
    </>
  );

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={handleClose}
    >
      <div
        className="max-w-lg w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto overflow-x-hidden"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
      >
        {phase === 'loading' && renderLoading()}
        {phase === 'confirm' && renderConfirmPhase()}
        {phase === 'limit' && renderLimitPhase()}
        {phase === 'shared' && renderSharedPhase()}
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
}
