'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { usePathname } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { Bot, MessageSquare, Search, Workflow as WorkflowIcon, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { SearchField } from '@/components/ui/search-field';
import { conversationApi } from '@/lib/api/conversationApi';
import type { Conversation } from '@/lib/api/conversationApi';
import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import { settingsNavItems } from '@/components/settings/settingsNavItems';
import { stripLocale } from '@/contexts/SidePanelContext';
import { useSafeNavigate } from '@/contexts/NavigationGuardContext';
import { useAuth } from '@/lib/providers/smart-providers';
import { IS_CE } from '@/lib/edition';
import { cn } from '@/lib/utils';
import { conversationDisplayTitle } from '@/lib/utils/conversationTitle';

type ResultGroup = 'conversations' | 'workflows' | 'agents' | 'settings';

interface SearchResultItem {
  key: string;
  group: ResultGroup;
  label: string;
  icon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
  href: string;
}

const MAX_PER_GROUP = 5;

/**
 * Shared search state: debounced fan-out to the conversations / workflows /
 * agents server-side searches plus a client-side match over the settings
 * sections, and navigation to the picked result.
 */
function useGlobalSearch() {
  const pathname = usePathname();
  const isSettings = stripLocale(pathname).startsWith('/app/settings');
  const t = useTranslations('globalSearch');
  const safeNavigate = useSafeNavigate();
  const { isAuthenticated, hasRole } = useAuth();
  const isAdmin = hasRole('ADMIN');

  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [remoteResults, setRemoteResults] = useState<SearchResultItem[]>([]);
  const [activeIndex, setActiveIndex] = useState(0);
  const requestIdRef = useRef(0);

  const trimmed = query.trim();

  // Settings sections are matched client-side against the same visibility rules
  // as SettingsNav (platform-admin + CE gating). Labels come from settingsNavItems,
  // which is English-only today (pre-existing, SettingsNav displays them raw); the
  // match therefore only hits the English label until that nav is internationalized.
  const settingsResults = useMemo<SearchResultItem[]>(() => {
    if (!trimmed) return [];
    const q = trimmed.toLowerCase();
    return settingsNavItems
      .filter(item =>
        !item.hidden && (!item.adminOnly || isAdmin) && (!item.hiddenInCE || !IS_CE) && (!item.ceOnly || IS_CE)
      )
      .filter(item => item.label.toLowerCase().includes(q))
      .slice(0, MAX_PER_GROUP)
      .map(item => ({
        key: `settings:${item.href}`,
        group: 'settings' as const,
        label: item.label,
        icon: item.icon,
        href: item.href,
      }));
  }, [trimmed, isAdmin]);

  useEffect(() => {
    if (!trimmed || !isAuthenticated) {
      // Functional bail-out: an unconditional `setRemoteResults([])` would put a
      // NEW array in state on every effect run and re-render forever whenever a
      // dependency (e.g. `t`) has an unstable identity.
      setRemoteResults(prev => (prev.length ? [] : prev));
      setLoading(false);
      return;
    }
    const requestId = ++requestIdRef.current;
    setLoading(true);
    const timeout = setTimeout(async () => {
      const [conversations, workflows, agents] = await Promise.allSettled([
        conversationApi.searchConversations(trimmed, 'title'),
        workflowService.getWorkflowsPage({ q: trimmed, size: MAX_PER_GROUP }),
        agentService.getAgentsPage({ q: trimmed, size: MAX_PER_GROUP }),
      ]);
      if (requestId !== requestIdRef.current) return; // stale response

      const items: SearchResultItem[] = [];
      if (conversations.status === 'fulfilled') {
        const content = ((conversations.value as { content?: Conversation[] })?.content ?? []).slice(0, MAX_PER_GROUP);
        for (const conv of content) {
          items.push({
            key: `conversation:${conv.id}`,
            group: 'conversations',
            label: conversationDisplayTitle(conv, t('untitled')),
            icon: MessageSquare,
            href: `/app/c/${conv.id}`,
          });
        }
      }
      if (workflows.status === 'fulfilled') {
        for (const wf of workflows.value.workflows ?? []) {
          items.push({
            key: `workflow:${wf.id}`,
            group: 'workflows',
            label: wf.name || t('untitled'),
            icon: WorkflowIcon,
            href: `/app/workflow/${wf.id}`,
          });
        }
      }
      if (agents.status === 'fulfilled') {
        for (const agent of agents.value.items ?? []) {
          items.push({
            key: `agent:${agent.id}`,
            group: 'agents',
            label: agent.name || t('untitled'),
            icon: Bot,
            href: `/app/agent?openAgent=${agent.id}`,
          });
        }
      }
      setRemoteResults(items);
      setLoading(false);
    }, 250);
    return () => clearTimeout(timeout);
  }, [trimmed, isAuthenticated, t]);

  // Flat, group-ordered result list. In settings the settings sections come
  // first; elsewhere the order favors content (conversations first).
  const results = useMemo<SearchResultItem[]>(() => {
    const all = [...settingsResults, ...remoteResults];
    const order: ResultGroup[] = isSettings
      ? ['settings', 'conversations', 'workflows', 'agents']
      : ['conversations', 'workflows', 'agents', 'settings'];
    return order.flatMap(group => all.filter(item => item.group === group));
  }, [settingsResults, remoteResults, isSettings]);

  useEffect(() => {
    setActiveIndex(0);
  }, [results.length, trimmed]);

  const navigate = useCallback((item: SearchResultItem) => {
    setQuery('');
    safeNavigate(item.href);
  }, [safeNavigate]);

  return { t, query, setQuery, trimmed, loading, results, activeIndex, setActiveIndex, navigate };
}

type GlobalSearch = ReturnType<typeof useGlobalSearch>;

/** Stable DOM id for an option, used for aria-activedescendant. */
function optionId(listId: string, index: number) {
  return `${listId}-option-${index}`;
}

/** Grouped dropdown list shared by the desktop and mobile variants. */
function SearchResultsList({
  search,
  onPick,
  listId,
  className,
}: {
  search: GlobalSearch;
  onPick: (item: SearchResultItem) => void;
  listId: string;
  className?: string;
}) {
  const { t, results, activeIndex, setActiveIndex, loading, trimmed } = search;
  const groupLabel: Record<ResultGroup, string> = {
    conversations: t('conversations'),
    workflows: t('workflows'),
    agents: t('agents'),
    settings: t('settings'),
  };
  let lastGroup: ResultGroup | null = null;

  return (
    <div
      data-testid="global-search-results"
      className={cn(
        'overflow-hidden rounded-xl border border-theme bg-theme-primary shadow-[0_16px_48px_rgba(0,0,0,0.16)]',
        className
      )}
    >
      <div id={listId} role="listbox" className="max-h-80 overflow-y-auto p-1.5">
        {results.length === 0 ? (
          <div className="px-3 py-6 text-center text-sm text-theme-secondary">
            {loading ? t('searching') : t('noResults', { query: trimmed })}
          </div>
        ) : (
          results.map((item, index) => {
            const showHeader = item.group !== lastGroup;
            lastGroup = item.group;
            const Icon = item.icon;
            return (
              <React.Fragment key={item.key}>
                {showHeader && (
                  <div role="presentation" className="px-3 pb-1 pt-2 text-xs font-medium text-theme-secondary">
                    {groupLabel[item.group]}
                  </div>
                )}
                <button
                  type="button"
                  id={optionId(listId, index)}
                  role="option"
                  aria-selected={index === activeIndex}
                  onClick={() => onPick(item)}
                  onMouseEnter={() => setActiveIndex(index)}
                  className={cn(
                    'flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-left text-sm text-theme-primary transition-colors duration-150',
                    index === activeIndex ? 'bg-[var(--bg-secondary)]' : 'hover:bg-[var(--bg-secondary)]'
                  )}
                >
                  <Icon className="h-4 w-4 flex-shrink-0 text-theme-secondary" />
                  <span className="truncate">{item.label}</span>
                </button>
              </React.Fragment>
            );
          })
        )}
      </div>
    </div>
  );
}

function useResultsKeyDown(search: GlobalSearch, onPick: (item: SearchResultItem) => void, onEscape: () => void) {
  const { results, activeIndex, setActiveIndex } = search;
  return useCallback((e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Escape') {
      onEscape();
      return;
    }
    if (!results.length) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActiveIndex((prev: number) => (prev + 1) % results.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActiveIndex((prev: number) => (prev - 1 + results.length) % results.length);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const item = results[activeIndex] ?? results[0];
      if (item) onPick(item);
    }
  }, [results, activeIndex, setActiveIndex, onPick, onEscape]);
}

/**
 * Header search. `inline` renders the centered pill with an anchored results
 * dropdown (desktop / tablet); `compact` renders an icon button that opens a
 * full-width bar under the header (mobile).
 */
export function GlobalSearchBar({ variant = 'inline' }: { variant?: 'inline' | 'compact' }) {
  const search = useGlobalSearch();
  const [open, setOpen] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  const { t, query, setQuery, trimmed, navigate } = search;

  const close = useCallback(() => {
    setOpen(false);
    inputRef.current?.blur();
  }, []);

  const pick = useCallback((item: SearchResultItem) => {
    close();
    navigate(item);
  }, [close, navigate]);

  const handleKeyDown = useResultsKeyDown(search, pick, close);

  // Close on outside click.
  useEffect(() => {
    if (!open) return;
    const onMouseDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onMouseDown);
    return () => document.removeEventListener('mousedown', onMouseDown);
  }, [open]);

  // Ctrl/Cmd+K focuses the inline bar (physical-keyboard shortcut; the compact
  // variant is touch-first so it does not register a second listener).
  useEffect(() => {
    if (variant !== 'inline') return;
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        inputRef.current?.focus();
        setOpen(true);
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [variant]);

  // Autofocus the field when the mobile bar opens.
  useEffect(() => {
    if (variant === 'compact' && open) {
      inputRef.current?.focus();
    }
  }, [variant, open]);

  const shortcutHint = useMemo(() => {
    if (typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)) return '⌘K';
    return 'Ctrl K';
  }, []);

  if (variant === 'compact') {
    const listOpen = open && trimmed.length > 0;
    return (
      <div ref={rootRef}>
        <Button
          variant="ghost"
          size="icon"
          data-testid="global-search-trigger"
          onClick={() => setOpen(o => !o)}
          title={t('placeholder')}
          aria-label={t('placeholder')}
          aria-expanded={open}
          className="h-8 w-8"
        >
          {open ? <X className="h-4 w-4" /> : <Search className="h-4 w-4" />}
        </Button>
        {open && (
          <div className="fixed inset-x-0 top-14 z-[60] border-b border-theme bg-theme-secondary p-2 shadow-[0_8px_24px_rgba(0,0,0,0.12)]">
            <SearchField
              ref={inputRef}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={handleKeyDown}
              onClear={() => {
                setQuery('');
                inputRef.current?.focus();
              }}
              clearLabel={t('clear')}
              placeholder={t('placeholder')}
              data-testid="global-search-input-mobile"
              role="combobox"
              aria-autocomplete="list"
              aria-expanded={listOpen}
              aria-controls={listOpen ? 'global-search-list-mobile' : undefined}
              aria-activedescendant={listOpen && search.results[search.activeIndex] ? optionId('global-search-list-mobile', search.activeIndex) : undefined}
            />
            {listOpen && (
              <SearchResultsList search={search} onPick={pick} listId="global-search-list-mobile" className="mt-2" />
            )}
          </div>
        )}
      </div>
    );
  }

  const showDropdown = open && trimmed.length > 0;

  return (
    <div ref={rootRef} className="relative w-full">
      <SearchField
        ref={inputRef}
        value={query}
        onChange={(e) => {
          setQuery(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={handleKeyDown}
        onClear={() => {
          setQuery('');
          inputRef.current?.focus();
        }}
        clearLabel={t('clear')}
        placeholder={t('placeholder')}
        shortcutHint={shortcutHint}
        data-testid="global-search-input"
        role="combobox"
        aria-autocomplete="list"
        aria-expanded={showDropdown}
        aria-controls={showDropdown ? 'global-search-list' : undefined}
        aria-activedescendant={showDropdown && search.results[search.activeIndex] ? optionId('global-search-list', search.activeIndex) : undefined}
      />
      {showDropdown && (
        <div className="absolute left-0 right-0 top-full z-50 mt-2">
          <SearchResultsList search={search} onPick={pick} listId="global-search-list" />
        </div>
      )}
    </div>
  );
}
