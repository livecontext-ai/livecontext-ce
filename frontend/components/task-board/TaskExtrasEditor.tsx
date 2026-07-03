'use client';

import { useState, useEffect, useCallback, useMemo } from 'react';
import { useTranslations } from 'next-intl';
import { Plus, X, Lock, Timer, ListChecks, Tag } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '@/components/ui/select';
import { taskService } from '@/lib/api/orchestrator/task.service';
import type { Task, TaskLabel } from '@/lib/api/orchestrator/task.types';

/** Deterministic palette for auto-created labels (no Math.random, so it is stable per index). */
const LABEL_PALETTE = ['#6366f1', '#10b981', '#f59e0b', '#ef4444', '#06b6d4', '#8b5cf6', '#ec4899', '#84cc16'];

/**
 * Write-side editors for the card fields (F2 labels, F12 estimate/time, F9
 * blockers, F10 checklist). Self-contained: fetches the label catalog + the task
 * list for the pickers, and saves each change immediately via the dedicated
 * service endpoints, then calls {@link onSaved} to refresh the panel + board.
 *
 * Rendered inside the detail panel's right settings rail, so every control uses
 * the same compact side style as the Status / Priority selects (h-7, text-xs,
 * rounded-md, full width). Adding a label creates-or-attaches by name, so the
 * first label on a fresh board can be made here. Estimate/time auto-save on blur
 * (no explicit Save button).
 *
 * The `only` prop lets the rail split this across its core / advanced sections:
 * `'labels'` renders just the Labels block (and fetches only the label catalog),
 * `'rest'` renders estimate/blockers/checklist (and fetches only the task list).
 * Omitting it renders everything (used standalone / in unit tests).
 */
export function TaskExtrasEditor({ task, onSaved, only }: { task: Task; onSaved: () => void; only?: 'labels' | 'rest' }) {
  const t = useTranslations('taskBoard');
  const showLabels = only !== 'rest';
  const showRest = only !== 'labels';
  const [labels, setLabels] = useState<TaskLabel[]>([]);
  const [allTasks, setAllTasks] = useState<{ id: string; title: string }[]>([]);
  const [busy, setBusy] = useState(false);
  const [newItem, setNewItem] = useState('');
  const [newLabel, setNewLabel] = useState('');
  const [est, setEst] = useState('');
  const [spent, setSpent] = useState('');

  const reloadLabels = useCallback(() => {
    // Guarded so a partial taskService mock (unit tests) can't crash the panel.
    const lp = taskService.listLabels?.();
    if (lp) return lp.then(setLabels).catch(() => {});
    return undefined;
  }, []);

  useEffect(() => {
    if (showLabels) reloadLabels();
    if (showRest) {
      const tp = taskService.listTasks?.({ size: 200 });
      if (tp) tp.then(r => setAllTasks((r.tasks || []).map(x => ({ id: x.id, title: x.title })))).catch(() => {});
    }
  }, [reloadLabels, showLabels, showRest]);

  // Keep the estimate inputs in sync with the loaded task.
  useEffect(() => {
    setEst(task.estimateMinutes != null ? String(task.estimateMinutes) : '');
    setSpent(task.timeSpentMinutes != null ? String(task.timeSpentMinutes) : '');
  }, [task.estimateMinutes, task.timeSpentMinutes]);

  const run = useCallback(async (fn: () => Promise<unknown>) => {
    setBusy(true);
    try { await fn(); onSaved(); }
    catch { /* refresh reflects the truth */ }
    finally { setBusy(false); }
  }, [onSaved]);

  // ── Labels (F2) ──
  const labelsById = useMemo(() => new Map(labels.map(l => [l.id, l])), [labels]);
  const currentLabels = task.labelIds.map(id => labelsById.get(id)).filter((l): l is TaskLabel => !!l);
  const availableLabels = labels.filter(l => !task.labelIds.includes(l.id));

  // Attach an existing label by name, or create it first (so the very first label
  // on a board can be made right here - the catalog no longer has to exist).
  const addLabelByName = () => {
    const name = newLabel.trim();
    if (!name || busy) return;
    setNewLabel('');
    const existing = labels.find(l => l.name.toLowerCase() === name.toLowerCase());
    return run(async () => {
      let id = existing?.id;
      if (!id) {
        const created = await taskService.createLabel({ name, color: LABEL_PALETTE[labels.length % LABEL_PALETTE.length] });
        id = created.id;
      }
      if (id && !task.labelIds.includes(id)) {
        await taskService.setTaskLabels(task.id, [...task.labelIds, id]);
      }
      await reloadLabels();
    });
  };

  // ── Blockers (F9) ──
  const tasksById = useMemo(() => new Map(allTasks.map(x => [x.id, x])), [allTasks]);
  const currentBlockers = task.blockedByIds.map(id => tasksById.get(id)).filter((x): x is { id: string; title: string } => !!x);
  const availableTasks = allTasks.filter(x => x.id !== task.id && !task.blockedByIds.includes(x.id));

  // Auto-save estimate / time on blur - skips the call when nothing changed so a
  // plain focus-out does not trigger a redundant reload.
  const saveEstimate = () => {
    const e = est.trim();
    const s = spent.trim();
    const eNum = e === '' ? null : Number(e);
    const sNum = s === '' ? null : Number(s);
    if ((eNum != null && (!Number.isFinite(eNum) || eNum < 0)) || (sNum != null && (!Number.isFinite(sNum) || sNum < 0))) return;
    if (eNum === (task.estimateMinutes ?? null) && sNum === (task.timeSpentMinutes ?? null)) return;
    return run(() => taskService.setEstimate(task.id, {
      estimateMinutes: eNum, clearEstimate: e === '',
      timeSpentMinutes: sNum, clearTimeSpent: s === '',
    }));
  };

  const addItem = () => {
    const text = newItem.trim();
    if (!text) return;
    setNewItem('');
    return run(() => taskService.setChecklist(task.id, [...task.checklist, { id: '', text, done: false }]));
  };

  const section = 'space-y-1.5';
  const header = 'flex items-center gap-1.5 text-xs font-medium text-theme-muted uppercase tracking-wide';
  const chip = 'inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-xs bg-theme-tertiary text-theme-secondary';

  return (
    <div data-testid={only === 'labels' ? 'task-labels-editor' : 'task-extras-editor'} className="space-y-4">
      {/* Labels (F2) */}
      {showLabels && (
      <div className={section}>
        <div className={header}><Tag className="h-3.5 w-3.5" /> {t('detail.labels')}</div>
        {currentLabels.length > 0 && (
          <div className="flex flex-wrap items-center gap-1.5">
            {currentLabels.map(l => (
              <span key={l.id} className={chip}>
                {l.color && /^#[0-9a-fA-F]{3,8}$/.test(l.color) && (
                  <span className="h-1.5 w-1.5 rounded-full" style={{ backgroundColor: l.color }} />
                )}
                {l.name}
                <button type="button" disabled={busy} aria-label={`remove ${l.name}`}
                  onClick={() => run(() => taskService.setTaskLabels(task.id, task.labelIds.filter(x => x !== l.id)))}
                  className="text-theme-muted hover:text-red-500"><X className="h-3 w-3" /></button>
              </span>
            ))}
          </div>
        )}
        {/* Add or create a label by name - works even when the catalog is empty. */}
        <div className="flex items-center gap-1.5">
          <Input
            data-testid="task-add-label-input"
            value={newLabel}
            disabled={busy}
            onChange={(e) => setNewLabel(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addLabelByName(); } }}
            placeholder={t('detail.addOrCreateLabel')}
            className="h-7 text-xs flex-1 px-2 rounded-md"
          />
          <Button type="button" variant="outline" size="icon" className="h-7 w-7 flex-shrink-0"
            data-testid="task-add-label-create" aria-label={t('detail.createLabel')}
            disabled={busy || !newLabel.trim()} onClick={addLabelByName}>
            <Plus className="h-3.5 w-3.5" />
          </Button>
        </div>
        {/* Quick-pick existing labels (one tap to attach). */}
        {availableLabels.length > 0 && (
          <div className="flex flex-wrap gap-1">
            {availableLabels.slice(0, 12).map(l => (
              <button key={l.id} type="button" disabled={busy}
                data-testid={`task-pick-label-${l.id}`}
                onClick={() => run(() => taskService.setTaskLabels(task.id, [...task.labelIds, l.id]))}
                className="inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-xs border border-theme text-theme-secondary hover:border-[var(--accent-primary)] hover:text-theme-primary transition-colors">
                {l.color && /^#[0-9a-fA-F]{3,8}$/.test(l.color) && (
                  <span className="h-1.5 w-1.5 rounded-full" style={{ backgroundColor: l.color }} />
                )}
                {l.name}
              </button>
            ))}
          </div>
        )}
      </div>
      )}

      {showRest && (<>
      {/* Estimate / time (F12) - auto-saved on blur */}
      <div className={section}>
        <div className={header}><Timer className="h-3.5 w-3.5" /> {t('detail.estimate')}</div>
        <Input type="number" min={0} inputMode="numeric" data-testid="task-estimate-input"
          value={est} onChange={(e) => setEst(e.target.value)} onBlur={saveEstimate} placeholder={t('detail.estimate')}
          className="h-7 text-xs w-full px-2 rounded-md" />
        <Input type="number" min={0} inputMode="numeric" data-testid="task-timespent-input"
          value={spent} onChange={(e) => setSpent(e.target.value)} onBlur={saveEstimate} placeholder={t('detail.timeSpent')}
          className="h-7 text-xs w-full px-2 rounded-md" />
      </div>

      {/* Blocked by (F9) */}
      <div className={section}>
        <div className={header}><Lock className="h-3.5 w-3.5" /> {t('detail.blockedBy')}</div>
        {currentBlockers.length > 0 && (
          <div className="flex flex-wrap items-center gap-1.5">
            {currentBlockers.map(b => (
              <span key={b.id} className={chip} title={b.title}>
                <span className="max-w-[140px] truncate">{b.title}</span>
                <button type="button" disabled={busy} aria-label={`unblock ${b.title}`}
                  onClick={() => run(() => taskService.setBlockers(task.id, task.blockedByIds.filter(x => x !== b.id)))}
                  className="text-theme-muted hover:text-red-500"><X className="h-3 w-3" /></button>
              </span>
            ))}
          </div>
        )}
        <Select value="" disabled={busy || availableTasks.length === 0}
          onValueChange={(v) => { if (v) run(() => taskService.setBlockers(task.id, [...task.blockedByIds, v])); }}>
          <SelectTrigger className="min-h-0 h-7 text-xs w-full rounded-md" data-testid="task-add-blocker">
            <SelectValue placeholder={availableTasks.length === 0 ? t('detail.noBlockerOptions') : t('detail.addBlocker')} />
          </SelectTrigger>
          <SelectContent>{availableTasks.slice(0, 100).map(x => <SelectItem key={x.id} value={x.id}>{x.title}</SelectItem>)}</SelectContent>
        </Select>
      </div>

      {/* Checklist (F10) */}
      <div className={section}>
        <div className={header}>
          <ListChecks className="h-3.5 w-3.5" /> {t('detail.checklist')}
          {task.checklist.length > 0 && (
            <span className="text-theme-muted tabular-nums normal-case">
              {task.checklist.filter(i => i.done).length}/{task.checklist.length}
            </span>
          )}
        </div>
        <div className="space-y-1">
          {task.checklist.map(item => (
            <div key={item.id} data-testid={`task-checklist-item-${item.id}`} className="flex items-center gap-2 group/cl">
              <button type="button" disabled={busy} aria-pressed={item.done}
                onClick={() => run(() => taskService.setChecklist(task.id, task.checklist.map(i => i.id === item.id ? { ...i, done: !i.done } : i)))}
                className={`flex items-center justify-center w-4 h-4 rounded border flex-shrink-0 ${
                  item.done ? 'bg-[var(--accent-primary)] border-[var(--accent-primary)] text-[var(--accent-foreground)]' : 'border-theme'
                }`}>
                {item.done && <span className="text-xs leading-none">✓</span>}
              </button>
              <span className={`flex-1 text-xs ${item.done ? 'line-through text-theme-muted' : 'text-theme-primary'}`}>{item.text}</span>
              <button type="button" disabled={busy} aria-label="remove item"
                onClick={() => run(() => taskService.setChecklist(task.id, task.checklist.filter(i => i.id !== item.id)))}
                className="text-theme-muted hover:text-red-500 opacity-0 group-hover/cl:opacity-100"><X className="h-3 w-3" /></button>
            </div>
          ))}
          <div className="flex items-center gap-2">
            <Input type="text" data-testid="task-checklist-add" value={newItem}
              onChange={(e) => setNewItem(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addItem(); } }}
              placeholder={t('detail.addChecklistItem')} className="h-7 flex-1 text-xs px-2 rounded-md" />
            <Button type="button" variant="outline" size="icon" className="h-7 w-7 flex-shrink-0" disabled={busy || !newItem.trim()} onClick={addItem}>
              <Plus className="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>
      </div>
      </>)}
    </div>
  );
}
