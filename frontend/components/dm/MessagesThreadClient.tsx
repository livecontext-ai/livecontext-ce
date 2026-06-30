'use client';

import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { dmApi } from '@/lib/api/dm-api';
import DmThreadView from './DmThreadView';

/** Resolves a thread by id (to get the other participant) then renders the live view. */
export function MessagesThreadClient({ threadId }: { threadId: string }) {
  const t = useTranslations('dm');
  const { data: thread, isLoading, isError } = useQuery({
    queryKey: ['dm', 'thread', threadId],
    queryFn: () => dmApi.getThread(threadId),
    retry: false,
  });

  if (isLoading) {
    return <div className="flex h-full items-center justify-center text-sm text-theme-muted">…</div>;
  }
  if (isError || !thread) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-theme-muted">
        {t('threadNotFound')}
      </div>
    );
  }

  return (
    <div className="h-full">
      <DmThreadView threadId={threadId} otherUserId={thread.otherUserId} />
    </div>
  );
}

export default MessagesThreadClient;
