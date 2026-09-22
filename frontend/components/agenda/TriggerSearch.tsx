'use client';

import { useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import { ExternalLink, Pause, Play, Search, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { NodeIcon } from '@/app/workflows/builder/components/nodes/shared';
import { KIND_TO_NODE_ICON_KEY } from '@/lib/api/orchestrator/dashboard.service';
import type { AgendaMarker, AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';
import { agendaTriggerKey, occurrenceUsesTrigger } from './agendaTriggerSelection';
import { resourceIcon } from './agendaVisuals';
import {
  canControlProductionResource,
  productionResourceKind,
} from '@/lib/api/orchestrator/resource-control';

interface TriggerSearchProps {
  triggers: AgendaMarker[];
  /**
   * EVERY marker in the window, unfiltered.
   *
   * Distinct from `triggers`, which is what this picker LISTS and is already narrowed by
   * the resource-kind chips, the launch-kind filter and "show paused". The agent branch
   * of `occurrenceUsesTrigger` decides by counting an agent's same-kind markers, so
   * counting against the narrowed list answers a different question from the one the
   * calendar answers when the row is clicked: an agent with two schedules, one paused
   * and hidden, would read "1 use" here and then filter to nothing.
   */
  catalogue?: AgendaMarker[];
  occurrences: AgendaOccurrence[];
  selectedKey: string | null;
  query: string;
  busy: boolean;
  canMutate: boolean;
  onQueryChange: (query: string) => void;
  onSelect: (key: string | null) => void;
  onTogglePause: (trigger: AgendaMarker) => void;
  onOpen: (trigger: AgendaMarker) => void;
}

/** Searchable production-trigger picker, including triggers that have no calendar date. */
export function TriggerSearch({
  triggers,
  catalogue,
  occurrences,
  selectedKey,
  query,
  busy,
  canMutate,
  onQueryChange,
  onSelect,
  onTogglePause,
  onOpen,
}: TriggerSearchProps) {
  const t = useTranslations('agenda');
  const [focused, setFocused] = useState(false);
  const selected = triggers.find((trigger) => agendaTriggerKey(trigger) === selectedKey) ?? null;
  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    const unique = new Map<string, AgendaMarker>();
    for (const trigger of triggers) unique.set(agendaTriggerKey(trigger), trigger);
    return [...unique.values()]
      .filter((trigger) => !needle
        || trigger.name.toLowerCase().includes(needle)
        || trigger.triggerLabel?.toLowerCase().includes(needle)
        || trigger.triggerId?.toLowerCase().includes(needle)
        || t(`kind.${trigger.triggerType.toLowerCase()}`).toLowerCase().includes(needle))
      .slice(0, 10);
  }, [query, t, triggers]);

  // The catalogue is passed, because an AGENT run is attributed by launch kind and the
  // helper needs to know whether that kind is unambiguous on the agent. Without it this
  // count is 0 for every agent trigger while SELECTING the same trigger fills the
  // calendar with its runs: the page contradicting itself one click apart.
  const usageCount = (trigger: AgendaMarker) => occurrences
    .filter((occurrence) => occurrenceUsesTrigger(occurrence, trigger, catalogue ?? triggers))
    .length;

  if (selected) {
    const resourceKind = productionResourceKind(selected.resourceType);
    const resourceAction = selected.resourcePaused
      ? t('menu.resumeResource', { type: resourceKind })
      : t('menu.pauseResource', { type: resourceKind });
    return (
      <div className="flex h-8 min-w-[14rem] flex-1 items-center gap-2 rounded-lg border border-theme bg-theme-primary px-2 sm:max-w-sm">
        <NodeIcon nodeId={KIND_TO_NODE_ICON_KEY[selected.triggerType]} size="xs" />
        <span className="min-w-0 flex-1 truncate text-sm text-theme-primary">
          {selected.triggerLabel || selected.name}
          <span className="ml-1 text-xs text-theme-muted">
            {t('triggerSearch.uses', { n: usageCount(selected) })}
          </span>
        </span>
        {canMutate && canControlProductionResource(selected) && (
          <Button
            type="button"
            variant="ghostGray"
            size="sm"
            className="h-7 w-7 p-0"
            disabled={busy}
            title={resourceAction}
            aria-label={resourceAction}
            onClick={() => onTogglePause(selected)}
          >
            {selected.resourcePaused ? <Play className="h-3.5 w-3.5" /> : <Pause className="h-3.5 w-3.5" />}
          </Button>
        )}
        <Button
          type="button"
          variant="ghostGray"
          size="sm"
          className="h-7 w-7 p-0"
          title={t('menu.open')}
          aria-label={t('menu.open')}
          onClick={() => onOpen(selected)}
        >
          <ExternalLink className="h-3.5 w-3.5" />
        </Button>
        <Button
          type="button"
          variant="ghostGray"
          size="sm"
          className="h-7 w-7 p-0"
          title={t('triggerSearch.clear')}
          aria-label={t('triggerSearch.clear')}
          onClick={() => { onQueryChange(''); onSelect(null); }}
        >
          <X className="h-3.5 w-3.5" />
        </Button>
      </div>
    );
  }

  return (
    <div className="relative min-w-[14rem] flex-1 sm:max-w-sm">
      <Search className="pointer-events-none absolute left-2 top-4 h-3.5 w-3.5 -translate-y-1/2 text-theme-muted" />
      <Input
        value={query}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        onChange={(event) => onQueryChange(event.target.value)}
        placeholder={t('triggerSearch.placeholder')}
        aria-label={t('triggerSearch.label')}
        className="h-8 w-full pl-7 pr-8 text-sm"
      />
      {query && (
        <button
          type="button"
          className="absolute right-2 top-4 -translate-y-1/2 text-theme-muted hover:text-theme-primary"
          aria-label={t('triggerSearch.clear')}
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => { onQueryChange(''); onSelect(null); }}
        >
          <X className="h-3.5 w-3.5" />
        </button>
      )}

      {focused && (
        <div className="absolute left-0 top-9 z-40 max-h-80 w-full overflow-y-auto rounded-xl border border-theme bg-theme-primary p-1 shadow-xl">
          {matches.length === 0 ? (
            <p className="px-3 py-4 text-center text-sm text-theme-muted">{t('triggerSearch.empty')}</p>
          ) : matches.map((trigger) => {
            const key = agendaTriggerKey(trigger);
            const ResourceIcon = resourceIcon(trigger.resourceType);
            return (
              <button
                key={key}
                type="button"
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => { onSelect(key); onQueryChange(''); setFocused(false); }}
                className="flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left hover:bg-surface-hover"
              >
                <NodeIcon nodeId={KIND_TO_NODE_ICON_KEY[trigger.triggerType]} size="xs" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm text-theme-primary">
                    {trigger.triggerLabel || trigger.name}
                  </span>
                  <span className="flex items-center gap-1 text-xs text-theme-muted">
                    <ResourceIcon className="h-3 w-3" />
                    {trigger.name} · {t(`kind.${trigger.triggerType.toLowerCase()}`)}
                  </span>
                </span>
                <span className="text-xs tabular-nums text-theme-muted">
                  {t('triggerSearch.uses', { n: usageCount(trigger) })}
                </span>
              </button>
            );
          })}
        </div>
      )}

    </div>
  );
}
