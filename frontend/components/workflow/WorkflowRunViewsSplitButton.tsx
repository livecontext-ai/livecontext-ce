'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { BarChart3, Check, ChevronDown, FileText } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { openRunPanel } from '@/components/workflow/run-panel/runPanelBus';

/** What the header can show about the bound run, in menu order. */
export type HeaderRunView = 'analysis' | 'logs';

/**
 * Logs and Analysis only: the run itself (epochs and steps) is one click away already, on the
 * canvas pill, so a third "Run" entry here only duplicated it. A pick stored as "run" by an
 * earlier version is not a view any more and falls back to Logs.
 */
const VIEWS: readonly HeaderRunView[] = ['analysis', 'logs'];

const ICON: Record<HeaderRunView, React.ComponentType<{ className?: string }>> = {
  analysis: BarChart3,
  logs: FileText,
};

/** Where the pick is remembered across reloads: a per-browser convenience, never shared state. */
export const HEADER_RUN_VIEW_STORAGE_KEY = 'lc.workflow.headerRunView';

function isHeaderRunView(value: unknown): value is HeaderRunView {
  return typeof value === 'string' && (VIEWS as readonly string[]).includes(value);
}

/** The stored pick, or Logs. Storage can be missing or refuse access (private mode, blocked site data). */
function readStoredView(): HeaderRunView {
  try {
    const stored = typeof window !== 'undefined' ? window.localStorage.getItem(HEADER_RUN_VIEW_STORAGE_KEY) : null;
    return isHeaderRunView(stored) ? stored : 'logs';
  } catch {
    return 'logs';
  }
}

function storeView(view: HeaderRunView): void {
  try {
    window.localStorage.setItem(HEADER_RUN_VIEW_STORAGE_KEY, view);
  } catch {
    // Not remembered across reloads; the session still keeps it below.
  }
}

/**
 * The view the primary part opens: the last one picked (remembered in this browser across
 * reloads), else Logs, which is what this button always opened (the header's run-mode button was
 * "Logs"). Module-scoped so it survives the header remounting on navigation; read from storage
 * lazily, on the first client render, never on the server.
 */
let lastPickedView: HeaderRunView | null = null;

function currentView(): HeaderRunView {
  if (lastPickedView === null) lastPickedView = readStoredView();
  return lastPickedView;
}
/**
 * Every mounted instance follows the pick: the header mounts a desktop and a mobile copy side by
 * side (one hidden per breakpoint), and a pick in one must not leave the other on the old view.
 */
const pickListeners = new Set<() => void>();

function subscribePick(listener: () => void): () => void {
  pickListeners.add(listener);
  return () => pickListeners.delete(listener);
}

function setLastPickedView(view: HeaderRunView): void {
  if (view === currentView()) return;
  lastPickedView = view;
  storeView(view);
  pickListeners.forEach(listener => listener());
}

/** Test-only: forget the last pick (the stored one is re-read on the next render). */
export function resetHeaderRunViewForTests(): void {
  lastPickedView = null;
  pickListeners.forEach(listener => listener());
}

/**
 * Run-mode counterpart of {@link WorkflowRunSplitButton}, at the same place in the page header:
 * a split button whose primary part opens a view of the bound run in the side panel, and whose
 * chevron lists the three views (Run: epochs and steps, Analysis: trends and comparison, Logs:
 * inputs and outputs). Run and Analysis go through `openRunPanel` (the workflow and application
 * pages bring the panel forward on it), Logs through the caller's existing logs opener.
 */
export function WorkflowRunViewsSplitButton({
  workflowId,
  desktop,
  disabled,
  onOpenLogs,
}: {
  workflowId: string;
  desktop: boolean;
  /** No bound run: nothing to show. */
  disabled?: boolean;
  onOpenLogs: () => void;
}) {
  const t = useTranslations();
  const [isMenuOpen, setIsMenuOpen] = React.useState(false);
  const primary = React.useSyncExternalStore(subscribePick, currentView, () => 'logs' as HeaderRunView);
  const menuRef = React.useRef<HTMLDivElement | null>(null);

  const label = (view: HeaderRunView) =>
    view === 'analysis' ? t('sidePanel.analysisTab') : t('actions.logs');

  const open = (view: HeaderRunView) => {
    setIsMenuOpen(false);
    setLastPickedView(view);
    if (view === 'logs') onOpenLogs();
    else openRunPanel({ workflowId, view: 'run', tab: 'analysis' });
  };

  // Arrow keys move between the items (the popover already moves focus into its content).
  const onMenuKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const items = [...(menuRef.current?.querySelectorAll<HTMLElement>('[role="menuitemradio"]') ?? [])];
    const at = items.indexOf(document.activeElement as HTMLElement);
    const focusAt = (index: number) => { event.preventDefault(); items[(index + items.length) % items.length]?.focus(); };
    if (event.key === 'ArrowDown') focusAt(at + 1);
    else if (event.key === 'ArrowUp') focusAt(at - 1);
    else if (event.key === 'Home') focusAt(0);
    else if (event.key === 'End') focusAt(items.length - 1);
  };

  const PrimaryIcon = ICON[primary];
  return (
    <div className="flex items-center" data-run-views-split>
      <Button
        variant="default"
        size="sm"
        disabled={disabled}
        onClick={(e) => {
          e.stopPropagation();
          open(primary);
        }}
        title={label(primary)}
        data-run-views-primary={primary}
        className={desktop ? 'h-8 px-2 lg:px-3 rounded-r-none' : 'h-8 px-2 rounded-r-none'}
      >
        <PrimaryIcon className={desktop ? 'w-4 h-4 lg:mr-1' : 'w-4 h-4'} />
        {desktop && <span className="hidden lg:inline">{label(primary)}</span>}
      </Button>
      <Popover open={isMenuOpen} onOpenChange={setIsMenuOpen}>
        <PopoverTrigger asChild>
          <Button
            variant="default"
            size="sm"
            disabled={disabled}
            onClick={(e) => e.stopPropagation()}
            aria-label={t('workflow.runViews.choose')}
            title={t('workflow.runViews.choose')}
            aria-haspopup="menu"
            aria-expanded={isMenuOpen}
            data-run-views-menu-button
            className="h-8 px-1 rounded-l-none border-l border-white/20 dark:border-black/20"
          >
            <ChevronDown className={`w-3.5 h-3.5 transition-transform duration-200 ${isMenuOpen ? 'rotate-180' : ''}`} />
          </Button>
        </PopoverTrigger>
        <PopoverContent
          align="end"
          className="w-72 p-2 rounded-2xl bg-theme-primary border border-theme shadow-lg"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="px-3 py-2">
            <div className="text-sm font-medium text-theme-primary">{t('workflow.runViews.title')}</div>
          </div>
          <div role="menu" ref={menuRef} onKeyDown={onMenuKeyDown} className="space-y-1">
            {VIEWS.map(view => {
              const Icon = ICON[view];
              return (
                <button
                  key={view}
                  type="button"
                  role="menuitemradio"
                  aria-checked={view === primary}
                  data-run-view-option={view}
                  className="w-full flex items-start gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800"
                  onClick={() => open(view)}
                >
                  <Icon className="mt-0.5 h-4 w-4 flex-shrink-0" />
                  <span className="flex min-w-0 flex-1 flex-col text-left">
                    <span className="text-sm">{label(view)}</span>
                    <span className="text-sm text-theme-muted">{t(`workflow.runViews.${view}Hint`)}</span>
                  </span>
                  {view === primary && <Check className="mt-0.5 h-4 w-4 flex-shrink-0" />}
                </button>
              );
            })}
          </div>
        </PopoverContent>
      </Popover>
    </div>
  );
}
