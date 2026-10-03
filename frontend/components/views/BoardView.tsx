'use client';

import { useCallback, useEffect, useState } from 'react';
import { urlEnum, urlNullable, useUrlState } from '@/hooks/useUrlState';
import { useTranslations } from 'next-intl';
import { usePathname, useSearchParams } from 'next/navigation';
import { samePageUrl, showSamePageUrl } from '@/lib/navigation/showSamePageUrl';
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
 *
 * Each board keeps its own view (search, sort, filters, open task) in the address too. Those
 * parameters describe the board being left, so switching resource drops them.
 */
type BoardResource = 'task' | 'application' | 'workflow';

const STORAGE_KEY = 'lc.boardResource';
const RESOURCES = ['task', 'application', 'workflow'] as const;

/** Every parameter a board under the toggle writes: the task board's and the two kanbans'. */
const BOARD_VIEW_URL_KEYS = [
  'q', 'sort', 'agent', 'task', 'label', 'mine', 'blocked', 'modified', 'trigger', 'visibility',
];

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
  // The active resource. URL ?resource= wins (so the redirected old routes land on the right
  // tab); with no or an invalid param it is the last-used choice, else 'task'. A switch is a
  // step Back should undo, and it drops the parameters of the board being left.
  const [urlResource, setUrlResource] = useUrlState<BoardResource | null>('resource', null, {
    codec: urlNullable(urlEnum(RESOURCES)),
    history: 'push',
    clears: BOARD_VIEW_URL_KEYS,
  });

  // Read after mount rather than in the initial state: the server has no localStorage, and a
  // first render that differed from its markup would be a hydration mismatch.
  const [lastUsed, setLastUsed] = useState<BoardResource | null>(null);
  useEffect(() => {
    let stored: string | null = null;
    try { stored = localStorage.getItem(STORAGE_KEY); } catch { /* localStorage unavailable */ }
    setLastUsed(isResource(stored) ? stored : 'task');
  }, []);

  const resource: BoardResource = urlResource ?? lastUsed ?? 'task';

  // An address that does not name its board gets it written in, so the link says what it
  // shows: copied as it stands, a bare address would open whatever board its reader used last.
  // `replace`: it corrects the address the user arrived on, it is not a step to come Back to.
  const pathname = usePathname();
  const searchParams = useSearchParams();
  useEffect(() => {
    if (urlResource !== null || lastUsed === null || !pathname) return;
    const params = new URLSearchParams(searchParams.toString());
    params.set('resource', lastUsed);
    showSamePageUrl(`${pathname}?${params.toString()}`, samePageUrl(pathname, searchParams), 'replace');
  }, [urlResource, lastUsed, pathname, searchParams]);

  const handleSelect = useCallback((next: BoardResource) => {
    // The board already on screen: writing its name would only drop its own filters.
    if (next === resource) return;
    setLastUsed(next);
    try { localStorage.setItem(STORAGE_KEY, next); } catch { /* localStorage unavailable */ }
    setUrlResource(next);
  }, [resource, setUrlResource]);

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
