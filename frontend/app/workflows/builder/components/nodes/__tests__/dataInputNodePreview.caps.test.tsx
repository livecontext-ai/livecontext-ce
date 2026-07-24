// @vitest-environment jsdom
/**
 * Regression: the data_input EDIT-TIME preview must stay bounded. An uncapped
 * item list (or a long pasted text with no clamp) grew the node into a poster
 * that overlapped its neighbors.
 *
 * v2 contract pinned here:
 * - every file item renders as the SHARED FileRefPill (same component as the
 *   run-time FileResultStrip): mime icon + truncated name, NO inline <img>;
 * - the in-flow list is capped at 3 items + a "+N more" line;
 * - text items keep the clamped (line-clamp-3) text preview;
 * - expanding a pill shows the shared FilePreviewCard ABSOLUTELY positioned
 *   below the node: the node itself must not grow (zero in-flow height).
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, fireEvent } from '@testing-library/react';

let mockObjectUrl: { url: string | null; loading: boolean; error: boolean };

vi.mock('@/hooks/useAuthedObjectUrl', () => ({
  useAuthedObjectUrl: () => mockObjectUrl,
}));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileRefToUrl: () => '/api/files/f-1',
  fileService: { formatFileSize: (bytes: number) => `${bytes} B` },
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) =>
    params ? `${key}(${Object.values(params).join(',')})` : key,
}));
const sidePanelStub = { openTab: vi.fn() };
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => sidePanelStub,
}));
const openFilesPanelMock = vi.fn();
vi.mock('@/lib/sidePanel/openFilesPanel', () => ({
  openFilesPanel: (...args: unknown[]) => openFilesPanelMock(...args),
}));

import { DataInputNodePreview } from '../DataInputNodePreview';

const fileItem = (n: number, mime = 'image/png') => ({
  id: `i${n}`,
  label: `File ${n}`,
  type: 'file' as const,
  file: { _type: 'file', path: `1/p${n}.png`, name: `p${n}.png`, mimeType: mime, size: 10 + n },
});

const renderPreview = (items: unknown[]) =>
  render(<DataInputNodePreview data={{ id: 'n1', label: 'Data', dataInputItems: items } as any} />);

beforeEach(() => {
  mockObjectUrl = { url: 'blob:main', loading: false, error: false };
  openFilesPanelMock.mockClear();
});

describe('DataInputNodePreview bounded item list', () => {
  it('renders each file item as the shared collapsed pill (icon + name), never an inline <img>', () => {
    const c = renderPreview([fileItem(1)]);
    expect(c.getAllByTestId('file-ref-pill')).toHaveLength(1);
    expect(c.container.querySelector('img')).toBeNull();
    expect(c.getByText('p1.png').classList.contains('truncate')).toBe(true);
    // Pill affordances: expand + open in side panel.
    expect(c.getByLabelText('expand')).not.toBeNull();
    expect(c.getByLabelText('openInPanel')).not.toBeNull();
  });

  it('caps the list at 3 pills and shows a "+N more" line for the rest', () => {
    const c = renderPreview([fileItem(1), fileItem(2), fileItem(3), fileItem(4), fileItem(5)]);
    expect(c.getAllByTestId('file-ref-pill')).toHaveLength(3);
    expect(c.getByText('more(2)')).not.toBeNull();
  });

  it('clamps a text item to 3 lines (current clamped text preview is kept)', () => {
    const c = renderPreview([
      { id: 't1', label: 'Notes', type: 'text', text: 'line\n'.repeat(50) },
    ]);
    const p = c.container.querySelector('p') as HTMLParagraphElement;
    expect(p).not.toBeNull();
    expect(p.classList.contains('line-clamp-3')).toBe(true);
  });

  it('renders nothing when there are no items', () => {
    const c = renderPreview([]);
    expect(c.container.firstChild).toBeNull();
  });
});

describe('DataInputNodePreview expanded preview (node-growth guard)', () => {
  it('expanding a pill mounts the shared preview card in an ABSOLUTE below-node wrapper (no in-flow growth)', () => {
    const c = renderPreview([fileItem(1)]);
    fireEvent.click(c.getByLabelText('expand'));

    const card = c.getByTestId('file-preview-card');
    // Climb to the positioning wrapper: it must be absolute, anchored below
    // the node border, so the open preview never adds height to the node.
    const wrapper = card.parentElement!.parentElement as HTMLElement;
    expect(wrapper.classList.contains('absolute')).toBe(true);
    // 12px (user-requested closeness). Deliberately NOT the run-time strip's 8px:
    // that strip is permanent and takes the bar's row (the bar steps down for it),
    // while this card is transient and simply paints over the bar until collapsed.
    expect(wrapper.style.top).toBe('calc(100% + 12px)');
    // The card is NOT inside the in-flow item list.
    const inFlowList = c.container.querySelector('.mt-3') as HTMLElement;
    expect(inFlowList.contains(card)).toBe(false);
  });

  it('expanding renders the real inline preview for the item (image -> <img>)', () => {
    const c = renderPreview([fileItem(1)]);
    fireEvent.click(c.getByLabelText('expand'));
    const img = c.container.querySelector('img') as HTMLImageElement;
    expect(img).not.toBeNull();
    expect(img.getAttribute('src')).toBe('blob:main');
  });

  it('collapsing via the card removes it again', () => {
    const c = renderPreview([fileItem(1)]);
    fireEvent.click(c.getByLabelText('expand'));
    fireEvent.click(c.getAllByLabelText('collapse')[1]); // [0] is the pill chevron, [1] the card header
    expect(c.queryByTestId('file-preview-card')).toBeNull();
  });

  it('the pill panel button opens the side-panel Files view focused on the file', () => {
    const c = renderPreview([fileItem(1)]);
    fireEvent.click(c.getAllByLabelText('openInPanel')[0]);
    expect(openFilesPanelMock).toHaveBeenCalledWith(sidePanelStub, expect.objectContaining({ name: 'p1.png' }));
  });
});
