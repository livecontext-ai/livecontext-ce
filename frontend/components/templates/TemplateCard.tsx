'use client';

import { Loader2 } from 'lucide-react';
import { useTranslations } from 'next-intl';

import { AvatarDisplay } from '@/components/agents';
import { DataSourceColumnIcons, normalizeColumnType } from '@/components/DataSourceColumnIcons';
import { WorkflowNodeIcons } from '@/components/WorkflowNodeIcons';
import { templateCopy } from '@/lib/templates/hydrate';
import type { TemplateMeta } from '@/lib/templates/types';

interface TemplateCardProps {
  meta: TemplateMeta;
  /** True while THIS card is being instantiated. Blocks the double click. */
  busy: boolean;
  /** True while another card is busy, so this one greys out but does not spin. */
  disabled: boolean;
  onSelect: (meta: TemplateMeta) => void;
}

const DIFFICULTY_CLASS: Record<TemplateMeta['difficulty'], string> = {
  beginner: 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-400',
  intermediate: 'bg-blue-500/10 text-blue-700 dark:text-blue-400',
  advanced: 'bg-amber-500/10 text-amber-700 dark:text-amber-400',
};

/**
 * Preview that mirrors what the resource looks like once created: the same
 * dotted canvas and node-icon row a real workflow card shows, or the agent's
 * avatar. Reuses `WorkflowNodeIcons` / `AvatarDisplay` rather than inventing a
 * second visual language for the same objects.
 *
 * Driven by the static meta (`nodeKinds` / `avatarUrl`), so a card renders
 * without loading the plan.
 */
function TemplatePreview({ meta, name }: { meta: TemplateMeta; name: string }) {
  if (meta.kind === 'agent') {
    return (
      <div className="relative flex h-[104px] items-center justify-center overflow-hidden rounded-t-[17px] bg-theme-secondary">
        <AvatarDisplay avatarUrl={meta.avatarUrl} name={name} size="lg" />
      </div>
    );
  }

  if (meta.kind === 'table') {
    return (
      <div className="relative flex h-[104px] items-center justify-center overflow-hidden rounded-t-[17px] bg-theme-secondary">
        {/* normalizeColumnType is the shared coercion, so an unknown preset
            degrades to a text chip instead of breaking the row. */}
        <DataSourceColumnIcons types={(meta.columnTypes ?? []).map(normalizeColumnType)} size="sm" />
      </div>
    );
  }

  const nodeIcons = (meta.nodeKinds ?? []).map((kind, i) => ({
    nodeId: `${kind}-${i}`,
    nodeKind: kind,
  }));

  return (
    <div className="relative flex h-[104px] items-center justify-center overflow-hidden rounded-t-[17px] bg-slate-50 dark:bg-slate-900">
      {/* Same ReactFlow-style dot grid as a real workflow card. */}
      <div
        className="absolute inset-0 dark:hidden"
        style={{
          backgroundImage: 'radial-gradient(circle, #cbd5e1 1px, transparent 1px)',
          backgroundSize: '16px 16px',
        }}
      />
      <div
        className="absolute inset-0 hidden dark:block"
        style={{
          backgroundImage: 'radial-gradient(circle, #475569 1px, transparent 1px)',
          backgroundSize: '16px 16px',
        }}
      />
      <div className="relative z-10">
        <WorkflowNodeIcons nodeIcons={nodeIcons} totalNodeCount={nodeIcons.length} size="compact" />
      </div>
    </div>
  );
}

export function TemplateCard({ meta, busy, disabled, onSelect }: TemplateCardProps) {
  const t = useTranslations();
  const copy = templateCopy(meta, t);
  const inert = busy || disabled;

  /**
   * An <article> with a nested action button, not one big <button>: a button
   * may only contain phrasing content, so wrapping the heading, paragraph and
   * bullet list would be invalid HTML and would collapse the whole card into a
   * single unreadable accessible name.
   */
  return (
    <article
      className={`flex h-full flex-col overflow-hidden rounded-[18px] border border-theme bg-[var(--bg-primary)] transition-shadow hover:shadow-md ${
        inert ? 'opacity-60' : ''
      }`}
    >
      <TemplatePreview meta={meta} name={copy.title} />

      <div className="flex flex-1 flex-col gap-3 p-4">
        <div className="min-w-0">
          <h3 className="truncate text-sm font-semibold text-theme-primary">{copy.title}</h3>
          <div className="mt-1 flex flex-wrap items-center gap-1.5">
            <span
              className={`rounded-md px-1.5 py-0.5 text-xs font-medium ${DIFFICULTY_CLASS[meta.difficulty]}`}
            >
              {t(`templates.gallery.${meta.difficulty}`)}
            </span>
            <span className="rounded-md bg-theme-secondary px-1.5 py-0.5 text-xs font-medium text-theme-secondary">
              {meta.runnable ? t('templates.gallery.readyToRun') : t('templates.gallery.needsSetup')}
            </span>
          </div>
        </div>

        <p className="text-sm text-theme-secondary">{copy.description}</p>

        {copy.teaches.length > 0 && (
          <div>
            <p className="text-sm font-medium text-theme-muted">{t('templates.gallery.youWillLearn')}</p>
            <ul className="mt-1 space-y-1">
              {copy.teaches.map((line, i) => (
                <li key={i} className="flex gap-2 text-sm text-theme-secondary">
                  <span aria-hidden="true" className="text-theme-muted">
                    &bull;
                  </span>
                  <span>{line}</span>
                </li>
              ))}
            </ul>
          </div>
        )}

        {/* Solid and theme-inverting: black on light, white on dark. */}
        <button
          type="button"
          disabled={inert}
          aria-busy={busy}
          onClick={() => onSelect(meta)}
          className="mt-auto inline-flex w-full items-center justify-center gap-2 rounded-xl bg-slate-900 px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-slate-800 disabled:cursor-not-allowed dark:bg-white dark:text-slate-900 dark:hover:bg-slate-100"
        >
          {busy && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
          {busy ? t('templates.gallery.creating') : t('templates.gallery.use', { name: copy.title })}
        </button>
      </div>
    </article>
  );
}
