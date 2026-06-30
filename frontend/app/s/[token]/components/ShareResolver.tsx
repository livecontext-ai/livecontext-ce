'use client';

import React, { useState, useEffect, useMemo } from 'react';
import PublicChat from './PublicChat';
import PublicForm from '../../../f/[token]/components/PublicForm';
import SharedConversation from './SharedConversation';
import SharedApplication from './SharedApplication';
import ShareError from './ShareError';
import ShareLoading from './ShareLoading';
import { useTranslations } from 'next-intl';

interface SharedLinkResolution {
  token: string;
  resourceType: string;
  resourceToken: string;
  title: string | null;
  description: string | null;
  isActive: boolean;
  hasPassword: boolean;
  metadata: Record<string, unknown> | null;
}

interface ShareResolverProps {
  token: string;
}

/**
 * Resolves a share token and renders the appropriate component.
 *
 * Token prefix fallback (no registry needed):
 * - ch_xxx → PublicChat directly
 * - fm_xxx → PublicForm directly
 *
 * Registry resolution (sl_ tokens):
 * - GET /share/{token} → SharedLinkResolution → dispatch by type
 */
export default function ShareResolver({ token }: ShareResolverProps) {
  const t = useTranslations('publicShare');
  const [resolution, setResolution] = useState<SharedLinkResolution | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  // Determine prefix-based fallback before effects
  const prefixFallback = useMemo(() => {
    if (token.startsWith('ch_')) return 'CHAT';
    if (token.startsWith('fm_')) return 'FORM';
    if (token.startsWith('cs_')) return 'CONVERSATION';
    return null;
  }, [token]);

  useEffect(() => {
    // Skip registry resolution for prefix-based tokens
    if (prefixFallback) {
      setLoading(false);
      return;
    }

    async function resolve() {
      try {
        const res = await fetch(`/share/${token}`);

        if (res.status === 404) {
          setError(t('share.invalidOrExpired'));
          return;
        }

        if (!res.ok) {
          setError(t('share.failedToLoad'));
          return;
        }

        const data: SharedLinkResolution = await res.json();

        if (!data.isActive) {
          setError(t('share.deactivated'));
          return;
        }

        setResolution(data);
      } catch {
        setError(t('share.failedToConnect'));
      } finally {
        setLoading(false);
      }
    }

    resolve();
  }, [token, prefixFallback, t]);

  // Prefix-based fallback renders (after all hooks)
  if (prefixFallback === 'CHAT') {
    return <PublicChat resourceToken={token} />;
  }
  if (prefixFallback === 'FORM') {
    return <PublicForm token={token} />;
  }
  if (prefixFallback === 'CONVERSATION') {
    return <SharedConversation token={token} />;
  }

  if (loading) return <ShareLoading />;
  if (error) return <ShareError message={error} />;
  if (!resolution) return <ShareError message={t('share.unknownError')} />;

  switch (resolution.resourceType) {
    case 'CHAT':
      return <PublicChat resourceToken={resolution.resourceToken} title={resolution.title ?? undefined} />;
    case 'FORM':
      return <PublicForm token={resolution.resourceToken} />;
    case 'CONVERSATION':
      return <SharedConversation token={resolution.resourceToken} title={resolution.title ?? undefined} />;
    case 'APPLICATION':
      return <SharedApplication publicationId={resolution.resourceToken} token={token} title={resolution.title ?? undefined} description={resolution.description ?? undefined} />;
    default:
      return <ShareError message={t('share.unknownResourceType', { type: resolution.resourceType })} />;
  }
}
