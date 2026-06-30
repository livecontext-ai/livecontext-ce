/**
 * Tests for the Phase 2b virtual-folder helpers. These are the pure functions the
 * Files page relies on to tell a COMPUTED workflow folder (id null, navigates by
 * virtualId) apart from a real row (manual folder / file, navigates by id), and to
 * localise the grouping labels (1-based from the 0-based data).
 */
import { describe, it, expect } from 'vitest';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';
import {
  isVirtualEntry,
  folderNavKey,
  entryKey,
  virtualFolderLabel,
  folderLabel,
} from '../virtualFolders';

/** Minimal entry factory - only the fields the helpers read matter. */
function entry(overrides: Partial<StorageExplorerEntry>): StorageExplorerEntry {
  return {
    id: 'real-1',
    storageType: 'S3_FILE',
    sourceType: null,
    fileName: null,
    mimeType: null,
    sizeBytes: null,
    formattedSize: '0 B',
    createdAt: '2026-06-01T00:00:00Z',
    workflowId: null,
    workflowName: null,
    projectId: null,
    runId: null,
    stepKey: null,
    epoch: null,
    s3Key: null,
    contentType: null,
    isFolder: false,
    virtualId: null,
    virtualKind: null,
    spawn: null,
    itemIndex: null,
    ...overrides,
  } as unknown as StorageExplorerEntry;
}

// A simple stand-in for the next-intl `files` translator: substitutes {number}.
function t(key: string, vars?: Record<string, unknown>): string {
  if (vars && 'number' in vars) return `${key}:${vars.number}`;
  return key;
}

describe('virtualFolders helpers', () => {
  describe('isVirtualEntry', () => {
    it('is true for a row carrying a virtualId', () => {
      expect(isVirtualEntry(entry({ id: null as unknown as string, virtualId: 'wf:123' }))).toBe(true);
    });
    it('is false for a real row (no virtualId)', () => {
      expect(isVirtualEntry(entry({ id: 'abc', virtualId: null }))).toBe(false);
      expect(isVirtualEntry(entry({ id: 'abc' }))).toBe(false); // absent
    });
  });

  describe('folderNavKey', () => {
    it('returns the virtualId for a virtual folder (navigates by key, not id)', () => {
      expect(folderNavKey(entry({ id: null as unknown as string, virtualId: 'wf:123/e0' }))).toBe('wf:123/e0');
    });
    it('returns the id for a real folder', () => {
      expect(folderNavKey(entry({ id: 'folder-9', virtualId: null }))).toBe('folder-9');
    });
    it('returns null when neither id nor virtualId is present', () => {
      expect(folderNavKey(entry({ id: null as unknown as string, virtualId: null }))).toBeNull();
    });
  });

  describe('entryKey', () => {
    it('uses the virtualId for a virtual row whose id is null (never null, never collides)', () => {
      const k = entryKey(entry({ id: null as unknown as string, virtualId: 'wf:5/e1/s0' }));
      expect(k).toBe('wf:5/e1/s0');
    });
    it('two virtual rows with null ids get DISTINCT keys (no React key collision)', () => {
      const a = entryKey(entry({ id: null as unknown as string, virtualId: 'wf:5/e0' }));
      const b = entryKey(entry({ id: null as unknown as string, virtualId: 'wf:5/e1' }));
      expect(a).not.toBe(b);
    });
    it('uses the id for a real row', () => {
      expect(entryKey(entry({ id: 'real-42', virtualId: null }))).toBe('real-42');
    });
    it('falls back to name+timestamp when both id and virtualId are absent', () => {
      const k = entryKey(entry({ id: null as unknown as string, virtualId: null, fileName: 'x.png', createdAt: '2026-06-02T00:00:00Z' }));
      expect(k).toBe('x.png-2026-06-02T00:00:00Z');
    });
  });

  describe('virtualFolderLabel (RUN = positional 1-based; EPOCH = real stored epoch as-is; SPAWN/ITERATION = 0-based +1)', () => {
    it('WORKFLOW → resolved workflow name', () => {
      expect(virtualFolderLabel(entry({ virtualKind: 'WORKFLOW', workflowName: 'Daily Report' }), t)).toBe('Daily Report');
    });
    it('WORKFLOW → localised fallback when workflowName is null', () => {
      expect(virtualFolderLabel(entry({ virtualKind: 'WORKFLOW', workflowName: null }), t)).toBe('workflowFallback');
    });
    it('RUN → runLabel with the run number carried in epoch (already 1-based, oldest-first)', () => {
      expect(virtualFolderLabel(entry({ virtualKind: 'RUN', epoch: 1 }), t)).toBe('runLabel:1');
      expect(virtualFolderLabel(entry({ virtualKind: 'RUN', epoch: 3 }), t)).toBe('runLabel:3');
      // defensive: a RUN folder with no number falls back to Run 1.
      expect(virtualFolderLabel(entry({ virtualKind: 'RUN', epoch: null }), t)).toBe('runLabel:1');
    });
    it('EPOCH → epochLabel with the REAL stored epoch carried in `epoch`, shown as-is (no +1)', () => {
      // The backend carries the REAL epoch in `epoch` (matches the run/interface EpochSlider): a file
      // produced at real epoch 4 reads "Epoch 4" here, NOT a positional "Epoch 1". A one-shot's 0-based
      // first epoch reads "Epoch 0" (the slider shows that same value). Adding +1 would re-diverge.
      expect(virtualFolderLabel(entry({ virtualKind: 'EPOCH', epoch: 0 }), t)).toBe('epochLabel:0');
      expect(virtualFolderLabel(entry({ virtualKind: 'EPOCH', epoch: 1 }), t)).toBe('epochLabel:1');
      expect(virtualFolderLabel(entry({ virtualKind: 'EPOCH', epoch: 4 }), t)).toBe('epochLabel:4');
      // defensive: a missing number falls back to Epoch 1.
      expect(virtualFolderLabel(entry({ virtualKind: 'EPOCH', epoch: null }), t)).toBe('epochLabel:1');
    });
    it('SPAWN → spawnLabel with the 1-based number (spawn 0 → 1)', () => {
      expect(virtualFolderLabel(entry({ virtualKind: 'SPAWN', spawn: 0 }), t)).toBe('spawnLabel:1');
      expect(virtualFolderLabel(entry({ virtualKind: 'SPAWN', spawn: 2 }), t)).toBe('spawnLabel:3');
    });
    it('ITERATION → iterationLabel with the 1-based number (itemIndex 0 → 1)', () => {
      expect(virtualFolderLabel(entry({ virtualKind: 'ITERATION', itemIndex: 0 }), t)).toBe('iterationLabel:1');
      expect(virtualFolderLabel(entry({ virtualKind: 'ITERATION', itemIndex: 9 }), t)).toBe('iterationLabel:10');
    });
    it('treats a missing index as 1 (RUN/EPOCH) or 0 → 1 (SPAWN/ITERATION 0-based)', () => {
      expect(virtualFolderLabel(entry({ virtualKind: 'EPOCH', epoch: null }), t)).toBe('epochLabel:1');
      expect(virtualFolderLabel(entry({ virtualKind: 'SPAWN', spawn: null }), t)).toBe('spawnLabel:1');
      expect(virtualFolderLabel(entry({ virtualKind: 'ITERATION', itemIndex: null }), t)).toBe('iterationLabel:1');
    });
    it('unknown kind → fileName, else localised fallback', () => {
      expect(virtualFolderLabel(entry({ virtualKind: null, fileName: 'Loose' }), t)).toBe('Loose');
      expect(virtualFolderLabel(entry({ virtualKind: null, fileName: null }), t)).toBe('workflowFallback');
    });
  });

  describe('folderLabel', () => {
    it('a MANUAL folder shows its stored name', () => {
      expect(folderLabel(entry({ id: 'm1', virtualId: null, isFolder: true, fileName: 'Invoices' }), t)).toBe('Invoices');
    });
    it('a manual folder with no name falls back to the localised label', () => {
      expect(folderLabel(entry({ id: 'm1', virtualId: null, isFolder: true, fileName: null }), t)).toBe('workflowFallback');
    });
    it('a VIRTUAL folder shows the localised grouping label', () => {
      // virtualId AND the label coordinate both carry the RAW epoch (e0 → "Epoch 0").
      expect(folderLabel(entry({ id: null as unknown as string, virtualId: 'wf:1/e0', virtualKind: 'EPOCH', epoch: 0 }), t)).toBe('epochLabel:0');
    });
  });
});
