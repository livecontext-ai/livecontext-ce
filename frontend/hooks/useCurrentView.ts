'use client';

import { usePathname, useParams } from 'next/navigation';
import { useMemo } from 'react';
// Shared helper (covers ALL routing locales - the old local copy hardcoded
// en|fr|es and broke view detection for de/pt/zh users).
import { removeLocalePrefix } from '@/lib/utils/locale';

// ============================================
// Types
// ============================================

export type AppView = 'chat' | 'settings' | 'workflow' | 'data' | 'agent' | 'interface' | 'marketplace' | 'applications' | 'project' | 'tasks' | 'files' | 'board';

export interface CurrentViewInfo {
  view: AppView;
  conversationId: string | null;
  workflowId: string | null;
  dataSourceId: string | null;
  runId: string | null;
  interfaceId: string | null;
  publicationId: string | null;
  isDetailPage: boolean;
}

// ============================================
// Hook
// ============================================

/**
 * Hook to get current view information from the URL
 * Replaces AppViewContext for view detection
 * Uses Next.js native routing (usePathname, useParams)
 */
export function useCurrentView(): CurrentViewInfo {
  const pathname = usePathname();
  const normalizedPathname = removeLocalePrefix(pathname);
  const params = useParams();

  return useMemo(() => {
    const defaultInfo: CurrentViewInfo = {
      view: 'chat',
      conversationId: null,
      workflowId: null,
      dataSourceId: null,
      runId: null,
      interfaceId: null,
      publicationId: null,
      isDetailPage: false,
    };

    if (!normalizedPathname) return defaultInfo;

    // Extract IDs from params (Next.js handles this natively)
    const conversationId = params?.conversationId as string | undefined || null;
    const workflowId = params?.workflowId as string | undefined || null;
    const dataSourceId = params?.dataSourceId as string | undefined || params?.tableId as string | undefined || null;
    const runId = params?.runId as string | undefined || null;
    const interfaceId = params?.id as string | undefined || null;
    const publicationId = params?.publicationId as string | undefined || null;

    // Determine view from normalized pathname (without locale prefix)
    let view: AppView = 'chat';
    let isDetailPage = false;

    if (normalizedPathname.startsWith('/app/c/') || normalizedPathname === '/app' || normalizedPathname === '/app/chat' || normalizedPathname.startsWith('/app/chat')) {
      view = 'chat';
      isDetailPage = !!conversationId;
    } else if (normalizedPathname.startsWith('/app/board')) {
      view = 'board';
    } else if (normalizedPathname.startsWith('/app/workflow')) {
      view = 'workflow';
      isDetailPage = !!workflowId && workflowId !== 'new';
    } else if (normalizedPathname.startsWith('/app/tables') || normalizedPathname.startsWith('/app/data')) {
      view = 'data';
      isDetailPage = !!dataSourceId;
    } else if (normalizedPathname.startsWith('/app/files')) {
      view = 'files';
    } else if (normalizedPathname.startsWith('/app/settings')) {
      view = 'settings';
    } else if (normalizedPathname.startsWith('/app/tasks')) {
      view = 'tasks';
    } else if (normalizedPathname.startsWith('/app/agent')) {
      view = 'agent';
    } else if (normalizedPathname.startsWith('/app/interface')) {
      view = 'interface';
      isDetailPage = !!interfaceId;
    } else if (normalizedPathname.startsWith('/app/marketplace')) {
      view = 'marketplace';
    } else if (normalizedPathname.startsWith('/app/project/')) {
      view = 'project';
      isDetailPage = true;
    } else if (normalizedPathname.startsWith('/app/project')) {
      view = 'project';
    } else if (normalizedPathname.startsWith('/app/applications/')) {
      view = 'applications';
      isDetailPage = true;
    } else if (normalizedPathname.startsWith('/app/applications')) {
      view = 'applications';
    }

    return {
      view,
      conversationId,
      workflowId,
      dataSourceId,
      runId,
      interfaceId,
      publicationId,
      isDetailPage,
    };
  }, [normalizedPathname, params]);
}

/**
 * Helper to check if current view matches
 */
export function useIsView(targetView: AppView): boolean {
  const { view } = useCurrentView();
  return view === targetView;
}
