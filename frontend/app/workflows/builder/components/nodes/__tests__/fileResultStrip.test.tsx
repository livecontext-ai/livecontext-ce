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
import { render, fireEvent, act } from '@testing-library/react';

let mockObjectUrl: { url: string | null; loading: boolean; error: boolean };
// Three parameters, matching the hook: the third is the type FORCED on the blob, and a double
// shaped for the old two-argument contract would swallow it without a word.
const useAuthedObjectUrlMock = vi.fn(
  (_src: string | null | undefined, _hint?: string | null, _forced?: string | null) => mockObjectUrl,
);

vi.mock('@/hooks/useAuthedObjectUrl', () => ({
  useAuthedObjectUrl: (src: string | null, hint?: string | null, forced?: string | null) =>
    useAuthedObjectUrlMock(src, hint, forced),
}));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileRefToUrl: (f: { id?: string }) => (f.id ? `/api/files/${f.id}` : ''),
  fileService: { formatFileSize: (bytes: number) => `${bytes} B` },
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) =>
    params ? `${key}(${Object.values(params).join(',')})` : key,
}));
// The strip asks ReactFlow for its host node id. Null here (no ReactFlow around
// a standalone render), which exercises the useId fallback that keeps each strip
// instance distinct - exactly the path the canvas-coordination suite below needs.
vi.mock('reactflow', () => ({ useNodeId: () => null }));
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
import {
  FileStripExpansionProvider,
  useFileStripExpansionSafe,
  type FileStripExpansionContextValue,
} from '@/contexts/FileStripExpansionContext';

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
  // expandAll now records a standing preference, so a case that calls it leaves
  // that choice behind for the next one exactly as it would for the next visit.
  // Without this, a later case opens on a canvas that is already expanded.
  window.localStorage.clear();
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

  it('forces the blob type of the page it FRAMES, whatever the ref claims', () => {
    // A blob URL inherits this app's origin, and a page is typed by NAME as well as by mime, so
    // a file stored as text/html under a `.pdf` name would run its own script here - on the
    // builder canvas, and on a published showcase canvas. Forced, it renders as a broken page.
    const hostile = makeFile({ path: '1/general/x.pdf', name: 'invoice.pdf', mimeType: 'text/html', id: 'f-9' });
    const c = render(<FileResultStrip file={hostile} />);
    expand(c);

    expect(useAuthedObjectUrlMock).toHaveBeenLastCalledWith(
      expect.any(String), expect.anything(), 'application/pdf',
    );
  });

  it('only hints the type of what it plays, since media executes nothing', () => {
    // Forcing here would be wrong, not merely useless: a clip stored as video/webm under an
    // `.mp4` name would stop decoding.
    const clip = makeFile({ path: '1/general/c.mp4', name: 'clip.mp4', mimeType: 'video/webm', id: 'f-10' });
    const c = render(<FileResultStrip file={clip} />);
    expand(c);

    expect(useAuthedObjectUrlMock).toHaveBeenLastCalledWith(
      expect.any(String), 'video/webm', undefined,
    );
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

describe('FileResultStrip v4 - canvas-wide expand/collapse (toolbar toggle-all)', () => {
  /** Captures the live registry so a test can fire the toolbar's bulk actions. */
  function Probe({ sink }: { sink: { current: FileStripExpansionContextValue | null } }) {
    sink.current = useFileStripExpansionSafe();
    return null;
  }

  const renderCanvas = (files: ReturnType<typeof makeFile>[]) => {
    const sink: { current: FileStripExpansionContextValue | null } = { current: null };
    const view = render(
      <FileStripExpansionProvider>
        <Probe sink={sink} />
        {files.map((f) => <FileResultStrip key={f.id} file={f} />)}
      </FileStripExpansionProvider>,
    );
    return { ...view, sink };
  };

  it('registers with the canvas so the toolbar knows a file preview is on screen', () => {
    const { sink } = renderCanvas([imageFile, videoFile]);
    expect(sink.current?.stripCount).toBe(2);
    expect(sink.current?.expandedCount).toBe(0);
  });

  it('survives ReactFlow virtualization: a strip scrolled off-canvas and back is still expanded', () => {
    const sink: { current: FileStripExpansionContextValue | null } = { current: null };
    const tree = (files: ReturnType<typeof makeFile>[]) => (
      <FileStripExpansionProvider>
        <Probe sink={sink} />
        {files.map((f) => <FileResultStrip key={f.id} file={f} />)}
      </FileStripExpansionProvider>
    );
    const { rerender, container } = render(tree([imageFile, videoFile]));
    act(() => { sink.current?.expandAll(); });
    expect(container.querySelectorAll('[data-testid="file-preview-card"]').length).toBe(2);

    // The canvas mounts nodes only while they are in the viewport, so panning
    // unmounts them. Pre-fix this pruned their expanded flag and the previews
    // came back folded.
    rerender(tree([videoFile]));
    rerender(tree([imageFile, videoFile]));

    expect(container.querySelectorAll('[data-testid="file-preview-card"]').length).toBe(2);
  });

  it('expandAll unfolds EVERY strip into a real preview card in one go', () => {
    const { sink, container, getAllByTestId } = renderCanvas([imageFile, videoFile]);
    expect(container.querySelectorAll('[data-testid="file-preview-card"]').length).toBe(0);

    act(() => { sink.current?.expandAll(); });

    expect(getAllByTestId('file-preview-card').length).toBe(2);
    expect(container.querySelector('img')).not.toBeNull();
    expect(container.querySelector('video')).not.toBeNull();
  });

  it('collapseAll folds every strip back to its pill', () => {
    const { sink, container, getAllByTestId } = renderCanvas([imageFile, videoFile]);
    act(() => { sink.current?.expandAll(); });

    act(() => { sink.current?.collapseAll(); });

    expect(container.querySelectorAll('[data-testid="file-preview-card"]').length).toBe(0);
    expect(getAllByTestId('file-ref-pill').length).toBe(2);
  });

  it('a strip expanded by hand STAYS expanded - the shared registry must not fold it back on the next render', () => {
    const c = renderCanvas([imageFile, videoFile]);
    // Expanding one strip gives the context value a new identity, which
    // re-renders every strip; a registration effect keyed on that value would
    // unregister this strip and collapse it on the spot.
    fireEvent.click(c.getAllByLabelText('expand')[0]);

    expect(c.getAllByTestId('file-preview-card').length).toBe(1);
    expect(c.sink.current?.stripCount).toBe(2);
    expect(c.sink.current?.expandedCount).toBe(1);
  });

  it('expanding one strip leaves its neighbours collapsed', () => {
    const c = renderCanvas([imageFile, videoFile]);
    fireEvent.click(c.getAllByLabelText('expand')[0]);
    expect(c.getAllByTestId('file-ref-pill').length).toBe(1);
  });

  it('without a provider the pill still toggles on its own local state (marketplace preview, isolated render)', () => {
    const c = render(<FileResultStrip file={imageFile} />);
    expand(c);
    expect(c.getByTestId('file-preview-card')).not.toBeNull();
    fireEvent.click(c.getByLabelText('collapse'));
    expect(c.queryByTestId('file-preview-card')).toBeNull();
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

describe('FileResultStrip - showcase file (pre-signed URL, no storage handle)', () => {
  // On a marketplace canvas the file lives in the publication's storage namespace and the
  // visitor's only channel to it is an HMAC-signed URL minted server-side. There is no storage
  // path, no storage id, and no session token.
  const showcaseFile = {
    path: '',
    name: 'clip.mp4',
    mimeType: 'video/mp4',
    size: 2048,
    previewUrl: '/api/files/proxy-signed?key=x&exp=1&sig=y',
  };

  it('hands the signed URL straight to the media element instead of the authenticated blob fetch', () => {
    // Routing it through useAuthedObjectUrl would rewrite the path to /api/proxy/files/... and
    // attach a bearer the visitor does not have: the preview would 401 for every visitor.
    const c = render(<FileResultStrip file={showcaseFile} />);
    expand(c);
    const video = c.container.querySelector('video') as HTMLVideoElement;
    expect(video.getAttribute('src')).toBe(showcaseFile.previewUrl);
    const urlArgs = useAuthedObjectUrlMock.mock.calls.map((call: unknown[]) => call[0]);
    expect(urlArgs.every((u) => u === null || u === undefined)).toBe(true);
  });

  it('renders the preview even while the authenticated hook reports loading, which it never leaves', () => {
    // The hook is called with a null src, so it stays {url:null, loading:false} forever. Reading
    // its loading flag would pin the card on its skeleton.
    mockObjectUrl = { url: null, loading: true, error: true };
    const c = render(<FileResultStrip file={showcaseFile} />);
    expand(c);
    expect(c.container.querySelector('video')).not.toBeNull();
    expect(c.queryByText('noPreview')).toBeNull();
  });

  it('drops the open-in-Files control: there is no storage row for the panel to open', () => {
    const c = render(<FileResultStrip file={showcaseFile} />);
    expect(c.queryByLabelText('openInPanel')).toBeNull();
    expand(c);
    expect(c.queryByLabelText('openInPanel')).toBeNull();
  });

  it('still shows the file name and human size - the pill is labelled, not a bare thumbnail', () => {
    const c = render(<FileResultStrip file={showcaseFile} />);
    expect(c.getByText('clip.mp4')).not.toBeNull();
    expect(c.getByText('2048 B')).not.toBeNull();
  });

  it('a file that DOES have a storage handle keeps its open-in-Files control', () => {
    // Guard against the showcase change quietly removing the control on the owner's canvas.
    const c = render(<FileResultStrip file={videoFile} />);
    expect(c.getByLabelText('openInPanel')).not.toBeNull();
  });

  it('an unpreviewable kind still shows the hint rather than a broken element', () => {
    const c = render(<FileResultStrip file={{ ...showcaseFile, name: 'data.bin', mimeType: 'application/octet-stream' }} />);
    expand(c);
    expect(c.getByText('noPreview')).not.toBeNull();
  });
});
