// @vitest-environment jsdom
/**
 * FileResultStrip v2 contract.
 *
 * Zero-footprint regression (kept from v1): the run-time FileRef must NOT grow
 * the canvas node - the strip is absolutely positioned below the node border,
 * in the inter-node gap, in BOTH modes (collapsed pill and expanded card).
 *
 * v2 behaviors pinned here:
 * - collapsed = icon-only pill (mime icon + truncated name + human size +
 *   expand / open-in-side-panel buttons), NO thumbnail <img>;
 * - expanded = real inline preview per mime family (img / video / audio /
 *   iframe-pdf / no-preview hint for anything else);
 * - media bytes are fetched LAZILY: useAuthedObjectUrl never receives a URL
 *   while collapsed (the preview card is only mounted when expanded);
 * - the open-in-panel button reuses the bottom-bar Files opener (openFilesPanel);
 * - v3 geometry: the strip OWNS the hover NodeBottomBar's row (calc(100% + 8px))
 *   in BOTH states, hugging the node with no gap. The bar does not fight it for
 *   that band: the node drops its redundant Files button (useNodeContextualButtons)
 *   and FlowNode lowers any remaining bar buttons a row (extraTopOffset). z-20 in
 *   both states so the strip wins over the later z-10 bar row either way;
 * - while expanded the HOST ReactFlow node's z-index is bumped so the card
 *   paints above neighboring nodes, and restored on collapse.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, fireEvent } from '@testing-library/react';

let mockObjectUrl: { url: string | null; loading: boolean; error: boolean };
const useAuthedObjectUrlMock = vi.fn(
  (_src: string | null | undefined, _hint?: string | null) => mockObjectUrl,
);

vi.mock('@/hooks/useAuthedObjectUrl', () => ({
  useAuthedObjectUrl: (src: string | null, hint?: string | null) => useAuthedObjectUrlMock(src, hint),
}));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileRefToUrl: (f: { id?: string }) => (f.id ? `/api/files/${f.id}` : ''),
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

import { File, FileText, FileType2, Film, Image, Music } from 'lucide-react';
import { FileResultStrip, getFileKindIcon } from '../FileResultStrip';

const makeFile = (over: Partial<{ path: string; name: string; mimeType: string; size: number; id: string }> = {}) => ({
  path: '1/general/catalog-binary/sfx.mp3',
  name: 'elevenlabs-sound-generation_6758384d.mp3',
  mimeType: 'audio/mpeg',
  size: 321036,
  id: 'f-1',
  ...over,
});

const audioFile = makeFile();
const imageFile = makeFile({ path: '1/general/shot.png', name: 'shot.png', mimeType: 'image/png', size: 2048, id: 'f-2' });
const videoFile = makeFile({ path: '1/general/clip.mp4', name: 'clip.mp4', mimeType: 'video/mp4', id: 'f-3' });
const pdfFile = makeFile({ path: '1/general/doc.pdf', name: 'doc.pdf', mimeType: 'application/pdf', id: 'f-4' });
const otherFile = makeFile({ path: '1/general/data.bin', name: 'data.bin', mimeType: 'application/octet-stream', id: 'f-5' });

const rootOf = (c: ReturnType<typeof render>) => c.container.firstChild as HTMLElement;
const expandBtn = (c: ReturnType<typeof render>) => c.getByLabelText('expand');
const expand = (c: ReturnType<typeof render>) => fireEvent.click(expandBtn(c));

beforeEach(() => {
  mockObjectUrl = { url: 'blob:media', loading: false, error: false };
  useAuthedObjectUrlMock.mockClear();
  openFilesPanelMock.mockClear();
});

describe('FileResultStrip v2 - collapsed pill', () => {
  it('is absolutely positioned below the node border so it adds zero height to the node flow', () => {
    const c = render(<FileResultStrip file={audioFile} />);
    const root = rootOf(c);
    expect(root.classList.contains('absolute')).toBe(true);
    expect(root.style.top).toBe('calc(100% + 8px)');
    // The old in-flow preview used mt-3 inside the node body; the strip must
    // not participate in the node's content flow at all.
    expect(root.classList.contains('mt-3')).toBe(false);
  });

  it('renders an icon-only pill: mime icon + truncated name + size, NEVER a thumbnail <img>', () => {
    const c = render(<FileResultStrip file={imageFile} />);
    expect(c.container.querySelector('img')).toBeNull();
    expect(c.container.querySelector('svg')).not.toBeNull();
    expect(c.getByText(imageFile.name).classList.contains('truncate')).toBe(true);
    expect(c.getByText('2048 B')).not.toBeNull();
  });

  it('offers expand and open-in-side-panel buttons on the pill', () => {
    const c = render(<FileResultStrip file={audioFile} />);
    expect(c.getByLabelText('expand')).not.toBeNull();
    expect(c.getByLabelText('openInPanel')).not.toBeNull();
  });

  it('wrapper never captures pointer events but the pill re-enables them (nodrag/nopan for canvas clicks)', () => {
    const c = render(<FileResultStrip file={audioFile} />);
    const root = rootOf(c);
    expect(root.classList.contains('pointer-events-none')).toBe(true);
    const inner = root.firstChild as HTMLElement;
    expect(inner.classList.contains('pointer-events-auto')).toBe(true);
    const pill = c.getByTestId('file-ref-pill');
    expect(pill.classList.contains('nodrag')).toBe(true);
    expect(pill.classList.contains('nopan')).toBe(true);
  });

  it('does NOT fetch media bytes while collapsed (useAuthedObjectUrl never sees a URL)', () => {
    render(<FileResultStrip file={imageFile} />);
    const urlArgs = useAuthedObjectUrlMock.mock.calls.map((call: unknown[]) => call[0]);
    expect(urlArgs.every((u) => u === null || u === undefined)).toBe(true);
  });

  it('a11y: no interactive element nests inside another (name area is a real button, controls are siblings)', () => {
    const c = render(<FileResultStrip file={audioFile} />);
    const pill = c.getByTestId('file-ref-pill');
    // Container is a plain layout div, not a click target.
    expect(pill.getAttribute('role')).toBeNull();
    // The name area is a keyboard-toggleable <button> carrying aria-expanded.
    const nameBtn = c.getByText(audioFile.name).closest('button') as HTMLButtonElement;
    expect(nameBtn).not.toBeNull();
    expect(nameBtn.getAttribute('aria-expanded')).toBe('false');
    // No button (or role=button) is a descendant of another one.
    expect(c.container.querySelector('button button, button [role="button"], [role="button"] button')).toBeNull();
  });
});

describe('FileResultStrip v2 - expanded preview card', () => {
  it('clicking the pill name area (not just the chevron) expands to the preview card', () => {
    const c = render(<FileResultStrip file={imageFile} />);
    fireEvent.click(c.getByText(imageFile.name));
    expect(c.getByTestId('file-preview-card')).not.toBeNull();
  });

  it('fetches the blob only once expanded and renders an inline <img> for image/*', () => {
    const c = render(<FileResultStrip file={imageFile} />);
    expect(useAuthedObjectUrlMock.mock.calls.every((call: unknown[]) => call[0] == null)).toBe(true);

    expand(c);

    expect(useAuthedObjectUrlMock.mock.calls.some((call: unknown[]) => call[0] === '/api/files/f-2')).toBe(true);
    const img = c.container.querySelector('img') as HTMLImageElement;
    expect(img).not.toBeNull();
    expect(img.getAttribute('src')).toBe('blob:media');
    expect(img.classList.contains('max-h-60')).toBe(true);
    expect(img.classList.contains('object-contain')).toBe(true);
  });

  it('renders a <video controls playsInline> for video/*', () => {
    const c = render(<FileResultStrip file={videoFile} />);
    expand(c);
    const video = c.container.querySelector('video') as HTMLVideoElement;
    expect(video).not.toBeNull();
    expect(video.getAttribute('src')).toBe('blob:media');
    expect(video.hasAttribute('controls')).toBe(true);
    expect(video.hasAttribute('playsinline')).toBe(true);
    expect(video.getAttribute('preload')).toBe('metadata');
  });

  it('renders an <audio controls> for audio/*', () => {
    const c = render(<FileResultStrip file={audioFile} />);
    expand(c);
    const audio = c.container.querySelector('audio') as HTMLAudioElement;
    expect(audio).not.toBeNull();
    expect(audio.hasAttribute('controls')).toBe(true);
  });

  it('renders an <iframe> for application/pdf', () => {
    const c = render(<FileResultStrip file={pdfFile} />);
    expand(c);
    const iframe = c.container.querySelector('iframe') as HTMLIFrameElement;
    expect(iframe).not.toBeNull();
    expect(iframe.getAttribute('src')).toBe('blob:media');
  });

  it('falls back to the no-preview hint when the blob fetch errors (v1 "blob error fallback" regression, now on the card)', () => {
    mockObjectUrl = { url: null, loading: false, error: true };
    const c = render(<FileResultStrip file={imageFile} />);
    expand(c);
    expect(c.container.querySelector('img, video, audio, iframe')).toBeNull();
    expect(c.getByText('noPreview')).not.toBeNull();
    // Never the infinite loading skeleton.
    expect(c.container.querySelector('.animate-pulse')).toBeNull();
  });

  it('an id-less (legacy) media FileRef shows the no-preview hint, NOT an infinite loading skeleton', () => {
    // fileRefToUrl returns '' without an id -> no usable media source.
    const legacy = { ...imageFile, id: undefined };
    mockObjectUrl = { url: null, loading: false, error: false };
    const c = render(<FileResultStrip file={legacy} />);
    expand(c);
    expect(c.getByText('noPreview')).not.toBeNull();
    expect(c.container.querySelector('.animate-pulse')).toBeNull();
    expect(c.container.querySelector('img, video, audio, iframe')).toBeNull();
  });

  it('shows the loading skeleton while the blob is being fetched', () => {
    mockObjectUrl = { url: null, loading: true, error: false };
    const c = render(<FileResultStrip file={imageFile} />);
    expand(c);
    expect(c.container.querySelector('.animate-pulse')).not.toBeNull();
    expect(c.queryByText('noPreview')).toBeNull();
  });

  it('unknown types get the no-preview hint, no media element, and no blob fetch', () => {
    const c = render(<FileResultStrip file={otherFile} />);
    expand(c);
    expect(c.container.querySelector('img, video, audio, iframe')).toBeNull();
    expect(c.getByText('noPreview')).not.toBeNull();
    expect(useAuthedObjectUrlMock.mock.calls.every((call: unknown[]) => call[0] == null)).toBe(true);
  });

  it('the card header carries the name plus collapse and open-in-panel buttons; collapse returns to the pill', () => {
    const c = render(<FileResultStrip file={imageFile} />);
    expand(c);
    expect(c.getByText(imageFile.name)).not.toBeNull();
    expect(c.getByLabelText('openInPanel')).not.toBeNull();

    fireEvent.click(c.getByLabelText('collapse'));

    expect(c.queryByTestId('file-preview-card')).toBeNull();
    expect(c.getByTestId('file-ref-pill')).not.toBeNull();
  });

  it('the expanded card stays absolutely positioned below the node (zero in-flow growth) close to the node border', () => {
    const c = render(<FileResultStrip file={imageFile} />);
    expand(c);
    const root = rootOf(c);
    expect(root.classList.contains('absolute')).toBe(true);
    // Same row as the collapsed pill: expanding must not make the card jump.
    expect(root.style.top).toBe('calc(100% + 8px)');
  });

  it('the expanded card has the softer rounded-2xl corners (user-requested)', () => {
    const c = render(<FileResultStrip file={imageFile} />);
    expand(c);
    expect(c.container.querySelector('.rounded-2xl')).not.toBeNull();
  });
});

describe('FileResultStrip v2 - side panel opener', () => {
  it('the panel button invokes the SAME opener as the bottom-bar Files button, focused on THIS file', () => {
    const c = render(<FileResultStrip file={audioFile} />);
    fireEvent.click(c.getByLabelText('openInPanel'));
    expect(openFilesPanelMock).toHaveBeenCalledWith(sidePanelStub, {
      path: audioFile.path,
      id: audioFile.id,
      name: audioFile.name,
      mimeType: audioFile.mimeType,
      size: audioFile.size,
    });
  });

  it('the expanded card panel button opens the same target', () => {
    const c = render(<FileResultStrip file={imageFile} />);
    expand(c);
    fireEvent.click(c.getByLabelText('openInPanel'));
    expect(openFilesPanelMock).toHaveBeenCalledWith(sidePanelStub, expect.objectContaining({ id: imageFile.id }));
  });
});

describe('FileResultStrip v3 - the strip owns the bottom bar row', () => {
  it('sits at the bar row (calc(100% + 8px)) in BOTH states: the strip replaces the bar there, so it hugs the node with no gap and does not jump when expanded', () => {
    const c = render(<FileResultStrip file={audioFile} />);
    // Pre-fix the pill sat a row lower (+44px) to clear the bar, and the card
    // sat at +12px: a visible gap under the node, and a jump on expand.
    expect(rootOf(c).style.top).toBe('calc(100% + 8px)');

    expand(c);
    expect(rootOf(c).style.top).toBe('calc(100% + 8px)');
  });

  it('is z-20 in BOTH states so it paints OVER the later z-10 bar row (at equal z the bar took the click)', () => {
    const c = render(<FileResultStrip file={audioFile} />);
    expect(rootOf(c).classList.contains('z-20')).toBe(true);
    expect(rootOf(c).classList.contains('z-10')).toBe(false);

    expand(c);
    expect(rootOf(c).classList.contains('z-20')).toBe(true);
    expect(rootOf(c).classList.contains('z-10')).toBe(false);
  });
});

describe('FileResultStrip v2 - z-order while expanded', () => {
  it('bumps the host .react-flow__node z-index on expand and restores it on collapse', () => {
    const host = document.createElement('div');
    host.className = 'react-flow__node';
    host.style.zIndex = '3';
    document.body.appendChild(host);

    const c = render(<FileResultStrip file={imageFile} />, { container: host });
    expect(host.style.zIndex).toBe('3');

    expand(c);
    expect(host.style.zIndex).toBe('1200');

    fireEvent.click(c.getByLabelText('collapse'));
    expect(host.style.zIndex).toBe('3');

    document.body.removeChild(host);
  });
});

describe('getFileKindIcon', () => {
  it.each([
    ['image/png', Image],
    ['video/mp4', Film],
    ['audio/mpeg', Music],
    ['application/pdf', FileType2],
    ['text/plain', FileText],
    ['application/octet-stream', File],
  ] as const)('maps %s to the exact mime-family icon', (mime, expected) => {
    expect(getFileKindIcon(mime)).toBe(expected);
  });
});
