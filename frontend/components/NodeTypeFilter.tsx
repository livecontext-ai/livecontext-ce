'use client';

import React, { useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Check, Layers, X } from 'lucide-react';

import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { SearchField } from '@/components/ui/search-field';
import { NodeIcon } from '@/app/workflows/builder/components/nodes/shared';
import type { NodeTypeFacet } from '@/lib/api/orchestrator/types';
import {
  groupNodeTypesByFamily,
  nodeTypeIconProps,
  nodeTypeLabel,
  nodeTypeMatchesSearch,
  parseNodeType,
} from '@/lib/workflows/nodeTypeTokens';
import { cn } from '@/lib/utils';
import { getClientLocale } from '@/lib/utils/locale';

export interface NodeTypeFilterProps {
  /** Every type present in the list being filtered, with its row count. */
  facets: NodeTypeFacet[];
  /** Currently selected tokens. */
  value: string[];
  onChange: (next: string[]) => void;
  className?: string;
}

/**
 * Multi-select filter over the node types a workflow or application contains.
 *
 * <p>Options come from the list itself (facets), never from the ~60 node types
 * the product supports: a workspace with three integrations gets three
 * integration options, each with the number of rows behind it, so no choice can
 * lead to an empty page. Above ten-odd options that list stops being scannable,
 * which is what the search field is for - it matches the label AND the raw
 * token, so "gmail", "mcp:" and a token pasted out of an agent's response all
 * find their option.
 *
 * <p>Selecting several types means ANY of them, matching how the server reads
 * the filter: ticking two integrations asks for the workflows touching either,
 * since asking for both at once is a question almost nothing answers.
 */
export function NodeTypeFilter({ facets, value, onChange, className }: NodeTypeFilterProps) {
  const t = useTranslations('nodeTypeFilter');
  // getClientLocale rather than useLocale: this component is mounted by two pages
  // and resolves the SAME app locale (URL prefix -> NEXT_LOCALE cookie -> en)
  // without requiring a next-intl provider around every caller.
  const locale = getClientLocale();
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');

  // Lowercased throughout: every producer of a token lowercases it, but a value
  // can also arrive from a URL or pasted from an agent's response. Comparing raw
  // would show "MCP:Gmail" as a second, unticked option beside the real one.
  const selected = useMemo(() => new Set(value.map((token) => token.toLowerCase())), [value]);

  // Selected options survive the search box and the facet list both: a type
  // stays visible while it is ticked, so the only way to lose a filter you
  // cannot see is to untick it yourself.
  const options = useMemo(() => {
    const known = new Set(facets.map((facet) => facet.value.toLowerCase()));
    const orphans = value
      .filter((token) => !known.has(token.toLowerCase()))
      .map((token) => ({ value: token.toLowerCase(), count: 0 }));
    return [...facets, ...orphans];
  }, [facets, value]);

  const groups = useMemo(() => {
    const visible = options.filter(
      (option) => selected.has(option.value) || nodeTypeMatchesSearch(parseNodeType(option.value), search)
    );
    return groupNodeTypesByFamily(visible);
  }, [options, search, selected]);

  const toggle = (token: string) => {
    onChange(
      selected.has(token)
        ? value.filter((v) => v.toLowerCase() !== token)
        : [...value, token]
    );
  };

  const count = value.length;

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={t('label')}
          className={cn(
            'flex h-9 items-center gap-2 rounded-xl border border-theme bg-[var(--bg-primary)] px-3 text-sm text-[var(--text-primary)] transition-colors duration-150 hover:bg-[var(--bg-secondary)] focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)]',
            count > 0 && 'border-[var(--accent-primary)]',
            className
          )}
        >
          <Layers className="h-3.5 w-3.5 text-[var(--text-secondary)]" />
          <span>{t('label')}</span>
          {count > 0 ? (
            <span className="rounded-md bg-[var(--accent-primary)] px-1.5 py-0.5 text-xs font-medium text-[var(--accent-foreground)]">
              {count.toLocaleString(locale)}
            </span>
          ) : null}
        </button>
      </PopoverTrigger>

      <PopoverContent align="start" className="w-72 p-0">
        <div className="border-b border-theme p-2">
          <SearchField
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            onClear={() => setSearch('')}
            clearLabel={t('clearSearch')}
            placeholder={t('searchPlaceholder')}
            aria-label={t('searchPlaceholder')}
          />
        </div>

        <div className="max-h-72 overflow-y-auto p-1">
          {groups.length === 0 ? (
            <p className="px-2 py-6 text-center text-sm text-[var(--text-secondary)]">
              {options.length === 0 ? t('empty') : t('noResults')}
            </p>
          ) : (
            groups.map((group) => (
              <div key={group.family} className="py-1">
                <p className="px-2 py-1 text-xs font-medium uppercase tracking-wide text-[var(--text-secondary)]">
                  {t(`families.${group.family}`)}
                </p>
                {group.items.map((option) => {
                  const isSelected = selected.has(option.value);
                  return (
                    <button
                      key={option.value}
                      type="button"
                      role="checkbox"
                      aria-checked={isSelected}
                      onClick={() => toggle(option.value)}
                      className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm text-[var(--text-primary)] transition-colors duration-150 hover:bg-[var(--bg-secondary)]"
                    >
                      {/* Purely visual. The row itself is the checkbox (role +
                          aria-checked above); a real <Checkbox> here would nest
                          a button inside a button - invalid HTML, and a second
                          thing for a screen reader to announce and focus. */}
                      <span
                        aria-hidden="true"
                        className={cn(
                          'flex h-4 w-4 shrink-0 items-center justify-center rounded-md border',
                          isSelected
                            ? 'border-[var(--accent-primary)] bg-[var(--accent-primary)] text-[var(--accent-foreground)]'
                            : 'border-theme'
                        )}
                      >
                        {isSelected ? <Check className="h-3 w-3" /> : null}
                      </span>
                      <NodeIcon size="xs" {...nodeTypeIconProps(option.parsed)} />
                      <span className="flex-1 truncate">{nodeTypeLabel(option.parsed)}</span>
                      <span className="text-xs text-[var(--text-secondary)]">
                        {option.count.toLocaleString(locale)}
                      </span>
                    </button>
                  );
                })}
              </div>
            ))
          )}
        </div>

        {count > 0 ? (
          <div className="border-t border-theme p-2">
            <button
              type="button"
              onClick={() => onChange([])}
              className="flex w-full items-center justify-center gap-1.5 rounded-lg px-2 py-1.5 text-sm text-[var(--text-secondary)] transition-colors duration-150 hover:bg-[var(--bg-secondary)] hover:text-[var(--text-primary)]"
            >
              <X className="h-3.5 w-3.5" />
              {t('clearAll')}
            </button>
          </div>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}

export default NodeTypeFilter;
