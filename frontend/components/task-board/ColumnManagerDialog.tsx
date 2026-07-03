'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { useTranslations } from 'next-intl';
import { Columns3, Plus, Trash2, ChevronUp, ChevronDown, Eye, EyeOff, Check, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '@/components/ui/select';
import { taskService } from '@/lib/api/orchestrator/task.service';
import type { TaskStatusConfig, TaskStatusCategory } from '@/lib/api/orchestrator/task.types';

/** The seven canonical lifecycle categories a column can map to (F4). */
const CATEGORIES: TaskStatusCategory[] = [
  'pending', 'in_progress', 'in_review', 'done', 'failed', 'cancelled', 'deleted',
];

/**
 * Human board-layout editor (F4 / F3): create / rename / recolor / re-category /
 * WIP-cap / hide / reorder / delete the board's columns. Built from the shared
 * design-system primitives (Button, Input, Select) + theme palette, mirroring the
 * CreateTaskDialog chrome (portal + backdrop-blur). Wraps the taskService status
 * endpoints; calls {@link onChanged} after every mutation so the board reloads
 * its server-driven columns. Agents never see this surface.
 *
 * Owns its own status list (seeded from the {@code statuses} prop to avoid a
 * flash) and re-fetches on open: the GET seeds the seven built-in columns the
 * first time a board is touched, so the existing/default columns are always
 * listed here even when the board passed an empty set (e.g. its initial load
 * raced or failed). It also re-fetches after each mutation so the list reflects
 * server truth immediately, not only after the parent reload.
 */
export function ColumnManagerDialog({ statuses, onClose, onChanged }: {
  statuses: TaskStatusConfig[];
  onClose: () => void;
  onChanged: () => void;
}) {
  const t = useTranslations('taskBoard');
  const [mounted, setMounted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const [rows, setRows] = useState<TaskStatusConfig[]>(statuses);
  // New-column form.
  const [label, setLabel] = useState('');
  const [category, setCategory] = useState<TaskStatusCategory>('in_progress');
  const [color, setColor] = useState('#6366f1');
  const [wip, setWip] = useState('');

  useEffect(() => { setMounted(true); }, []);

  const reload = useCallback(() => {
    // Guarded so a partial taskService mock (unit tests) can't crash the dialog.
    const p = taskService.listStatuses?.();
    if (!p) return Promise.resolve();
    return p.then(s => setRows(Array.isArray(s) ? s : [])).catch(() => {});
  }, []);
  // Fetch on open: seeds the built-ins server-side and populates the list even if
  // the prop arrived empty.
  useEffect(() => { reload(); }, [reload]);

  const ordered = useMemo(() => [...rows].sort((a, b) => a.position - b.position), [rows]);

  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try { await fn(); await reload(); onChanged(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };

  const addColumn = () => {
    const l = label.trim();
    if (!l) return;
    const w = wip.trim() === '' ? null : Number(wip);
    if (w != null && (!Number.isFinite(w) || w < 0)) return;
    return run(async () => {
      await taskService.createStatus({ label: l, category, color, wipLimit: w });
      setLabel('');
      setWip('');
    });
  };

  const rename = (s: TaskStatusConfig, next: string) => {
    const l = next.trim();
    if (!l || l === s.label) return;
    return run(() => taskService.updateStatus(s.id, { label: l }));
  };

  const setWipLimit = (s: TaskStatusConfig, raw: string) => {
    const v = raw.trim();
    if (v === '') return run(() => taskService.updateStatus(s.id, { clearWipLimit: true }));
    const n = Number(v);
    if (!Number.isFinite(n) || n < 0) return;
    if (n === (s.wipLimit ?? -1)) return;
    return run(() => taskService.updateStatus(s.id, { wipLimit: n }));
  };

  const move = (idx: number, dir: -1 | 1) => {
    const next = [...ordered];
    const j = idx + dir;
    if (j < 0 || j >= next.length) return;
    [next[idx], next[j]] = [next[j], next[idx]];
    return run(() => taskService.reorderStatuses(next.map(x => x.id)));
  };

  const remove = (s: TaskStatusConfig) => run(async () => {
    await taskService.deleteStatus(s.id);
    setConfirmId(null);
  });

  const iconBtn = 'h-8 w-8 rounded-lg';

  const content = (
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/20 backdrop-blur-sm p-4"
      onClick={onClose}
    >
      <div
        data-testid="column-manager"
        className="relative max-w-2xl w-full bg-theme-primary rounded-3xl shadow-2xl border border-theme max-h-[90vh] flex flex-col overflow-hidden animate-in fade-in-0 zoom-in-95 duration-300"
        onClick={(e) => e.stopPropagation()}
      >
        <Button variant="ghost" size="icon" onClick={onClose} disabled={busy}
          className="absolute right-4 top-4 h-8 w-8 rounded-full z-10" aria-label={t('manageColumns.close')}>
          <X className="h-4 w-4" />
        </Button>
        {/* Header (mirrors CreateTaskDialog) */}
        <div className="text-center px-8 pt-8 pb-5">
          <div className="w-14 h-14 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-3">
            <Columns3 className="w-7 h-7 text-theme-primary" />
          </div>
          <h3 className="text-2xl font-semibold text-theme-primary">{t('manageColumns.title')}</h3>
        </div>

        {/* Add-column form */}
        <div className="px-8 pb-4">
          <div className="flex flex-wrap items-end gap-2 rounded-2xl border border-theme bg-theme-secondary/40 p-3">
            <div className="flex-1 min-w-[9rem]">
              <label className="block text-xs font-medium text-theme-secondary mb-1">{t('manageColumns.namePlaceholder')}</label>
              <Input
                data-testid="new-column-label"
                value={label}
                onChange={(e) => setLabel(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addColumn(); } }}
                placeholder={t('manageColumns.namePlaceholder')}
                className="h-9 w-full rounded-md"
              />
            </div>
            <div className="w-36">
              <label className="block text-xs font-medium text-theme-secondary mb-1">{t('manageColumns.category')}</label>
              <Select value={category} onValueChange={(v) => setCategory(v as TaskStatusCategory)}>
                <SelectTrigger className="min-h-0 h-9 w-full rounded-md" data-testid="new-column-category"><SelectValue /></SelectTrigger>
                <SelectContent>
                  {CATEGORIES.map(c => <SelectItem key={c} value={c}>{t(`manageColumns.cat.${c}`)}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <input
              data-testid="new-column-color"
              type="color"
              value={color}
              onChange={(e) => setColor(e.target.value)}
              aria-label="color"
              className="h-9 w-9 rounded-md border border-theme bg-transparent cursor-pointer p-0.5"
            />
            <div className="w-20">
              <label className="block text-xs font-medium text-theme-secondary mb-1">{t('manageColumns.wipPlaceholder')}</label>
              <Input
                data-testid="new-column-wip"
                type="number"
                min={0}
                value={wip}
                onChange={(e) => setWip(e.target.value)}
                placeholder="-"
                className="h-9 w-full px-2 text-center rounded-md"
              />
            </div>
            <Button size="sm" data-testid="add-column" disabled={busy || !label.trim()} onClick={addColumn} className="gap-1">
              <Plus className="h-4 w-4" /> {t('manageColumns.add')}
            </Button>
          </div>
        </div>

        {error && (
          <div className="mx-8 mb-3 text-sm text-red-500 bg-red-50 dark:bg-red-900/20 rounded-xl px-3 py-2">{error}</div>
        )}

        {/* Column list */}
        <div className="flex-1 overflow-y-auto px-8 pb-2 space-y-1.5">
          {ordered.map((s, idx) => (
            <div key={s.id} data-testid={`column-row-${s.key}`} className="flex items-center gap-2 rounded-xl border border-theme bg-theme-secondary/40 px-2 py-1.5">
              <div className="flex flex-col -my-1">
                <Button variant="ghost" size="icon" className="h-5 w-6 rounded-md" aria-label={t('manageColumns.moveUp')}
                  disabled={busy || idx === 0} onClick={() => move(idx, -1)}><ChevronUp className="h-3.5 w-3.5" /></Button>
                <Button variant="ghost" size="icon" className="h-5 w-6 rounded-md" aria-label={t('manageColumns.moveDown')}
                  disabled={busy || idx === ordered.length - 1} onClick={() => move(idx, 1)}><ChevronDown className="h-3.5 w-3.5" /></Button>
              </div>
              <span className="h-3 w-3 rounded-full flex-shrink-0 border border-theme"
                style={{ backgroundColor: s.color && /^#[0-9a-fA-F]{3,8}$/.test(s.color) ? s.color : 'var(--bg-tertiary)' }} />
              <Input
                data-testid={`column-label-${s.key}`}
                defaultValue={s.label}
                disabled={busy}
                onBlur={(e) => rename(s, e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }}
                className="h-8 flex-1 min-w-0 rounded-md"
              />
              {s.isSystem ? (
                <span className="w-28 flex-shrink-0 text-xs text-theme-muted truncate" title={t(`manageColumns.cat.${s.category}`)}>
                  {t(`manageColumns.cat.${s.category}`)}
                </span>
              ) : (
                <Select value={s.category} disabled={busy}
                  onValueChange={(v) => run(() => taskService.updateStatus(s.id, { category: v as TaskStatusCategory }))}>
                  <SelectTrigger className="min-h-0 h-8 w-28 flex-shrink-0 text-xs rounded-md" data-testid={`column-category-${s.key}`}><SelectValue /></SelectTrigger>
                  <SelectContent>{CATEGORIES.map(c => <SelectItem key={c} value={c}>{t(`manageColumns.cat.${c}`)}</SelectItem>)}</SelectContent>
                </Select>
              )}
              <div className="flex items-center gap-1 flex-shrink-0">
                <span className="text-xs uppercase tracking-wide text-theme-muted">{t('manageColumns.wipPlaceholder')}</span>
                <Input
                  data-testid={`column-wip-${s.key}`}
                  type="number"
                  min={0}
                  defaultValue={s.wipLimit ?? ''}
                  disabled={busy}
                  onBlur={(e) => setWipLimit(s, e.target.value)}
                  placeholder="-"
                  className="h-8 w-14 px-2 text-center rounded-md"
                />
              </div>
              <Button variant="ghost" size="icon" className={iconBtn} disabled={busy}
                aria-label={s.hidden ? t('manageColumns.show') : t('manageColumns.hide')}
                onClick={() => run(() => taskService.updateStatus(s.id, { hidden: !s.hidden }))}>
                {s.hidden ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </Button>
              {s.isSystem ? (
                <span className="w-16 text-right text-[10px] uppercase tracking-wide text-theme-muted">{t('manageColumns.builtin')}</span>
              ) : confirmId === s.id ? (
                <span className="flex items-center gap-0.5">
                  <Button variant="ghost" size="icon" className="h-8 w-8 rounded-lg text-red-500" data-testid={`column-delete-confirm-${s.key}`}
                    disabled={busy} aria-label={t('manageColumns.confirmYes')} onClick={() => remove(s)}><Check className="h-4 w-4" /></Button>
                  <Button variant="ghost" size="icon" className="h-8 w-8 rounded-lg" disabled={busy}
                    aria-label={t('manageColumns.confirmNo')} onClick={() => setConfirmId(null)}><X className="h-4 w-4" /></Button>
                </span>
              ) : (
                <Button variant="ghost" size="icon" className="h-8 w-8 rounded-lg hover:!text-red-500" data-testid={`column-delete-${s.key}`}
                  disabled={busy} aria-label={t('manageColumns.delete')} onClick={() => setConfirmId(s.id)}><Trash2 className="h-4 w-4" /></Button>
              )}
            </div>
          ))}
        </div>

        {confirmId && (
          <p className="px-8 pt-2 text-xs text-theme-secondary">{t('manageColumns.confirmDelete')}</p>
        )}

        <div className="pb-6" />
      </div>
    </div>
  );

  if (!mounted) return null;
  return createPortal(content, document.body);
}
