// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest';
import {
  emitFilesDetailState,
  onFilesDetailState,
  emitFilesDetailCommand,
  onFilesDetailCommand,
  emitFilesFolderNavigate,
  onFilesFolderNavigate,
  FILES_DETAIL_BACK,
  FILES_DETAIL_PREV,
  type FilesDetailState,
} from '../filesHeaderBus';

describe('filesHeaderBus', () => {
  it('round-trips the focused-viewer state from emit to subscriber', () => {
    const seen: FilesDetailState[] = [];
    const off = onFilesDetailState((s) => seen.push(s));

    emitFilesDetailState({ open: true, fileName: 'a.png', canPrev: false, canNext: true });

    expect(seen).toHaveLength(1);
    expect(seen[0]).toMatchObject({ open: true, fileName: 'a.png', canPrev: false, canNext: true });

    // After unsubscribe the listener stops receiving - no leak across navigations.
    off();
    emitFilesDetailState({ open: false });
    expect(seen).toHaveLength(1);
  });

  it('delivers a command only to its matching listener, and stops after unsubscribe', () => {
    const back = vi.fn();
    const prev = vi.fn();
    const offBack = onFilesDetailCommand(FILES_DETAIL_BACK, back);
    const offPrev = onFilesDetailCommand(FILES_DETAIL_PREV, prev);

    emitFilesDetailCommand(FILES_DETAIL_BACK);
    expect(back).toHaveBeenCalledTimes(1);
    expect(prev).not.toHaveBeenCalled();

    offBack();
    offPrev();
    emitFilesDetailCommand(FILES_DETAIL_BACK);
    expect(back).toHaveBeenCalledTimes(1);
  });

  it('round-trips a folder-navigate target (V313), including null for the root', () => {
    const seen: Array<string | null> = [];
    const off = onFilesFolderNavigate((id) => seen.push(id));

    emitFilesFolderNavigate('folder-7');
    emitFilesFolderNavigate(null);
    expect(seen).toEqual(['folder-7', null]);

    off();
    emitFilesFolderNavigate('again');
    expect(seen).toEqual(['folder-7', null]);
  });

  it('carries the folderTrail on the focused-viewer state', () => {
    const seen: FilesDetailState[] = [];
    const off = onFilesDetailState((s) => seen.push(s));
    emitFilesDetailState({ open: false, folderTrail: [{ id: 'a', name: 'A' }, { id: 'b', name: 'B' }] });
    expect(seen[0].folderTrail).toEqual([{ id: 'a', name: 'A' }, { id: 'b', name: 'B' }]);
    off();
  });
});
