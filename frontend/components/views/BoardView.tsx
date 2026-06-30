'use client';

import { useCallback, useEffect, useState } from 'react';
import { useSearchParams, useRouter, usePathname } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { ClipboardList, AppWindow, Workflow as WorkflowIcon } from 'lucide-react';
import { AuthenticatedView } from './AuthenticatedView';
import { TaskBoardPage } from '@/components/task-board/TaskBoardPage';
import { WorkflowKanbanBoard } from '@/components/workflow-board/WorkflowKanbanBoard';

/**
 * BoardView - single aggregated board for the three resources, with a toggle to switch
 * between them (replaces the standalone Tasks / Applications / Workflows pages). Each
 * resource reuses its existing self-contained board:
 *   - task        → <TaskBoardPage />
 *   - application → <WorkflowKanbanBoard source="application" /> (APPLICATION-type workflows)
 *   - workflow    → <WorkflowKanbanBoard source="workflow" />
 *
 * Selection is driven by the URL (?resource=...) so the old list routes can deep-link the
 * right tab; it falls back to the last-used choice (localStorage), else 'task'.
 */
type BoardResource = 'task' | 'application' | 'workflow';

const STORAGE_KEY = 'lc.boardResource';

function isResource(v: string | null | undefined): v is BoardResource {
  return v === 'task' || v === 'application' || v === 'workflow';
}

const TABS: { key: BoardResource; icon: typeof ClipboardList }[] = [
  { key: 'task', icon: ClipboardList },
  { key: 'application', icon: AppWindow },
  { key: 'workflow', icon: WorkflowIcon },
];

export function BoardView() {
  const t = useTranslations('board');
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();

  const [resource, setResource] = useState<BoardResource>('task');

  // Resolve the active resource. URL ?resource= wins (so the redirected old routes land on the
  // right tab); otherwise fall back to the last-used choice, else 'task'. When no/invalid param
  // is present, normalize the URL once so refresh/share preserves the selection.
  useEffect(() => {
    const fromUrl = searchParams.get('resource');
    if (isResource(fromUrl)) {
      setResource(fromUrl);
      return;
    }
    let stored: string | null = null;
    try { stored = localStorage.getItem(STORAGE_KEY); } catch { /* localStorage unavailable */ }
    const initial: BoardResource = isResource(stored) ? stored : 'task';
    setResource(initial);
    router.replace(`${pathname}?resource=${initial}`);
    // Only react to URL changes; pathname/router are stable for this route.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);

  const handleSelect = useCallback((next: BoardResource) => {
    setResource(next);
    try { localStorage.setItem(STORAGE_KEY, next); } catch { /* localStorage unavailable */ }
    router.replace(`${pathname}?resource=${next}`);
  }, [router, pathname]);

  return (
    <AuthenticatedView overflow>
      {/* Resource toggle - switch between the three boards */}
      <div className="flex-shrink-0 flex items-center gap-1 border-b border-theme overflow-x-auto" style={{ scrollbarWidth: 'none' }}>
        {TABS.map(({ key, icon: Icon }) => (
          <button
            key={key}
            type="button"
            onClick={() => handleSelect(key)}
            className={`inline-flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium transition-all border-b-2 -mb-px whitespace-nowrap flex-shrink-0 ${
              resource === key
                ? 'border-[var(--accent-primary)] text-theme-primary'
                : 'border-transparent text-theme-muted hover:text-theme-primary'
            }`}
          >
            <Icon className="h-3.5 w-3.5" />
            {t(`toggle.${key}`)}
          </button>
        ))}
      </div>

      <div className="flex-1 min-h-0 flex flex-col">
        {resource === 'task' && <TaskBoardPage />}
        {resource === 'application' && <WorkflowKanbanBoard source="application" />}
        {resource === 'workflow' && <WorkflowKanbanBoard source="workflow" />}
      </div>
    </AuthenticatedView>
  );
}
