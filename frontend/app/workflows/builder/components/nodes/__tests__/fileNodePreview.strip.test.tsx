// @vitest-environment jsdom
/**
 * Regression (integration point): FileNodePreview must render the run-time
 * FileRef as the absolutely-positioned FileResultStrip, NEVER as the old
 * in-flow block (mt-3 + full-width image / chip) that grew the node after
 * auto-layout had already measured it and pushed it under its neighbor.
 * fileResultStrip.test.tsx pins the strip component itself; THIS suite pins
 * that FileNodePreview actually uses it (a revert to the in-flow markup
 * would keep the strip suite green and fail here).
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';

const FILE_REF = {
  _type: 'file',
  path: '1/wf/run/core:add_sound/out.mp4',
  name: 'add_sound_mux_audio_epoch_1_spawn_0.mp4',
  mimeType: 'video/mp4',
  size: 15328326,
  id: 'file-1',
};

let mockOutputObject: Record<string, unknown>;

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: true, workflowId: 'wf-1', runId: 'run-1', viewingEpoch: 1 }),
}));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [{ runStatus: 'completed', completedSteps: new Set(['a']), failedSteps: new Set(), skippedSteps: new Set() }],
}));
vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}));
vi.mock('../../../hooks/useRunOutputData', () => ({
  useRunOutputData: () => ({
    totalItems: 1,
    currentIndex: 0,
    currentItem: { id: 'item-1', metadata: null },
    goToIndex: vi.fn(),
    getObjectAtPath: () => Promise.resolve(mockOutputObject),
  }),
}));
vi.mock('@/hooks/useAuthedObjectUrl', () => ({
  useAuthedObjectUrl: () => ({ url: null, loading: false, error: false }),
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({ openTab: vi.fn() }),
}));
vi.mock('@/lib/sidePanel/openFilesPanel', () => ({
  openFilesPanel: vi.fn(),
}));
// Keep the real FileRef walker (isFileRef/findFileRefs/normalizeFileRef are
// the extraction logic under test); only neutralize the URL builder.
vi.mock('@/lib/api/orchestrator/file.service', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api/orchestrator/file.service')>();
  return { ...actual, fileRefToUrl: () => '' };
});

import { FileNodePreview } from '../FileNodePreview';

const preview = (status: string, setCurrentFile: (f: any) => void = vi.fn()) => (
  <FileNodePreview
    data={{ id: 'n1', label: 'Add Sound', kind: 'media', status } as any}
    setCurrentFile={setCurrentFile as any}
    selected={false}
    isStaticFileProducingNode={true}
  />
);

const renderPreview = () => render(preview('completed'));

beforeEach(() => {
  mockOutputObject = { _status: 'COMPLETED', file: FILE_REF };
});

describe('FileNodePreview strip integration', () => {
  it('renders the resolved FileRef as the absolute zero-footprint strip, not the old in-flow block', async () => {
    const c = renderPreview();
    await waitFor(() => expect(c.queryByText(FILE_REF.name)).not.toBeNull());

    const root = c.container.firstChild as HTMLElement;
    // The strip must sit OUTSIDE the node's content flow, below its border.
    expect(root.classList.contains('absolute')).toBe(true);
    expect(root.style.top).toBe('calc(100% + 8px)');
    // The old in-flow wrapper was `mt-3 w-full [&>img]:max-h-12` - its return
    // is exactly the regression this suite guards against.
    expect(root.classList.contains('mt-3')).toBe(false);
    expect(c.container.querySelector('img')).toBeNull();
    // Name stays one truncated line.
    expect(c.getByText(FILE_REF.name).classList.contains('truncate')).toBe(true);
  });

  it('renders nothing at all when the output carries no FileRef (media probe operation)', async () => {
    mockOutputObject = { _status: 'COMPLETED', duration_seconds: 20.4, has_audio: true };
    const c = renderPreview();
    // Give the lazy getObjectAtPath promise a tick to resolve.
    await waitFor(() => expect(c.container.firstChild).toBeNull());
  });

  it('publishes the resolved file to setCurrentFile, the flag FlowNode reads to swap its Files button for this strip', async () => {
    const setCurrentFile = vi.fn();
    render(preview('completed', setCurrentFile));
    await waitFor(() =>
      expect(setCurrentFile).toHaveBeenCalledWith(
        expect.objectContaining({ name: FILE_REF.name, path: FILE_REF.path, mimeType: FILE_REF.mimeType }),
      ),
    );
  });

  it('clears currentFile the moment the strip stops rendering (rerun: status leaves completed) - a stale flag hid the Files button with no strip to replace it', async () => {
    const setCurrentFile = vi.fn();
    const c = render(preview('completed', setCurrentFile));
    await waitFor(() => expect(setCurrentFile).toHaveBeenCalledWith(expect.objectContaining({ path: FILE_REF.path })));

    // Rerun: the node goes back to running. The already-resolved output stays in
    // component state, so pre-fix `normalized` was still truthy here and kept
    // publishing the file while the strip itself was gone.
    setCurrentFile.mockClear();
    c.rerender(preview('running', setCurrentFile));
    await waitFor(() => expect(c.container.firstChild).toBeNull());
    expect(setCurrentFile).toHaveBeenLastCalledWith(null);
  });
});
