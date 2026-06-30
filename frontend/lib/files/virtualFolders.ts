/**
 * Pure helpers for the Phase 2b VIRTUAL workflow folder tree on the Files page.
 *
 * A virtual folder is a COMPUTED grouping of a workflow's files (workflow → epoch
 * → spawn → iteration). In the explorer JSON it has {@code id: null},
 * {@code isFolder: true} and a {@code virtualId} navigation key ({@code "wf:<id>"},
 * {@code ".../e<n>"}, {@code ".../s<n>"}, {@code ".../i<n>"}). Real rows (files +
 * V313 manual folders) keep {@code virtualId} null/absent and navigate by their
 * real {@code id}. These helpers centralise that "virtual vs real" distinction so
 * the navigation key, the React key, and the localised label never drift.
 */
import type { StorageExplorerEntry } from '@/lib/api/storage-api';

/** A next-intl translator (the {@code files} namespace) with optional ICU values. */
type Translator = (key: string, values?: Record<string, unknown>) => string;

/** True when this entry is a computed virtual folder (has a {@code virtualId}). */
export function isVirtualEntry(e: StorageExplorerEntry): boolean {
  return !!e.virtualId;
}

/**
 * Navigation key: virtual folders navigate by {@code virtualId}, real folders by
 * {@code id}. Null when neither is present (a malformed row - caller no-ops).
 */
export function folderNavKey(e: StorageExplorerEntry): string | null {
  return e.virtualId ?? e.id ?? null;
}

/**
 * Stable React key - never null and never duplicated. Virtual folders use their
 * {@code virtualId} (real {@code id} is null), real rows use {@code id}, and a row
 * with neither falls back to a name+timestamp composite.
 */
export function entryKey(e: StorageExplorerEntry): string {
  return e.virtualId ?? e.id ?? `${e.fileName}-${e.createdAt}`;
}

/**
 * Localised display label for a VIRTUAL folder. RUN carries a 1-based POSITIONAL
 * number in {@code epoch} (a run_id is opaque, so "Run 1/2/…" is the only sensible
 * label). EPOCH carries the REAL stored epoch in {@code epoch}, shown as-is so the
 * folder matches the same epoch number the run/interface EpochSlider displays.
 * {@code spawn}/{@code itemIndex} are 0-based in the data and shown 1-based.
 * {@code t} is the next-intl {@code files} translator.
 */
export function virtualFolderLabel(e: StorageExplorerEntry, t: Translator): string {
  switch (e.virtualKind) {
    case 'WORKFLOW':
      return e.workflowName || t('workflowFallback');
    case 'RUN':
      // The backend carries the 1-based run number ("Run 1", "Run 2", oldest-first) in `epoch`
      // so the label is position-independent (works in the grid AND the breadcrumb).
      return t('runLabel', { number: e.epoch ?? 1 });
    case 'EPOCH':
      // The backend carries the REAL stored epoch in `epoch` (NOT a positional index), so the
      // label is shown as-is. This matches the run-inspector / application EpochSlider, which
      // reads the same raw workflow_epochs.epoch: a file produced at real epoch 4 reads "Epoch 4"
      // here too. A one-shot run's first epoch is 0-based and reads "Epoch 0" (the slider shows
      // that same value). Never add +1 - that would re-diverge from the slider.
      return t('epochLabel', { number: e.epoch ?? 1 });
    case 'SPAWN':
      return t('spawnLabel', { number: (e.spawn ?? 0) + 1 });
    case 'ITERATION':
      return t('iterationLabel', { number: (e.itemIndex ?? 0) + 1 });
    default:
      return e.fileName || t('workflowFallback');
  }
}

/**
 * The label to show for ANY folder card: a manual folder shows its stored name,
 * a virtual folder shows the localised grouping label.
 */
export function folderLabel(e: StorageExplorerEntry, t: Translator): string {
  return isVirtualEntry(e) ? virtualFolderLabel(e, t) : (e.fileName || t('workflowFallback'));
}
