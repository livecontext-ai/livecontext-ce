// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// The picker pulls the whole storage explorer (and its data hook) in. These tests are about the
// cell's own behaviour, so stand it in with a button that reports one pick.
vi.mock('@/app/workflows/builder/components/inspector/StorageExplorerTab', () => ({
  StorageExplorerTab: ({ onSelect }: { onSelect?: (entry: unknown) => void }) => (
    <>
      <button
        type="button"
        onClick={() =>
          onSelect?.({
            id: '99999999-9999-9999-9999-999999999999',
            fileName: 'picked.pdf',
            mimeType: 'application/pdf',
            sizeBytes: 10,
            s3Key: 't/general/x_picked.pdf',
          })
        }
      >
        pick-one
      </button>
      {/* The picker lists folders too, and a folder row can reach onSelect. */}
      <button
        type="button"
        onClick={() =>
          onSelect?.({ id: 'folder-1', fileName: 'Reports', isFolder: true, mimeType: null })
        }
      >
        pick-folder
      </button>
    </>
  ),
}));


// Authenticated previews fetch bytes; hand back a stable object URL instead.
vi.mock('@/hooks/useAuthedObjectUrl', () => ({
  // A distinct blob per source, as the real hook produces - so a test cannot pass by accident
  // just because two different files happened to share one preview URL. An external source is
  // returned verbatim because that is what the real hook does with one (it fetches only
  // same-origin `/api/` URLs); the stand-in has to match, or the external-image test below would
  // assert a `blob:` prefix this app never produces. What the real hook sends, and to whom, is
  // covered where it can actually be observed: hooks/__tests__/useAuthedObjectUrl.
  useAuthedObjectUrl: (url: string | null) => ({
    url: url ? (url.startsWith('/api/') ? `blob:${url}` : url) : null,
    error: false,
  }),
}));

/**
 * jsdom ships no IntersectionObserver, and the visibility latch then fails OPEN - so a test that
 * installs nothing renders the lazy kinds with the gate switched off, which is not the browser.
 * Install one and drive it, so a clip has to travel the real path: observed, scrolled to, loaded.
 */
let observerCallbacks: ((entries: { isIntersecting: boolean }[]) => void)[] = [];
function withObserver() {
  class FakeObserver {
    constructor(cb: (entries: { isIntersecting: boolean }[]) => void) { observerCallbacks.push(cb); }
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  vi.stubGlobal('IntersectionObserver', FakeObserver);
}
const scrollIntoView = () =>
  act(() => observerCallbacks.forEach((cb) => cb([{ isIntersecting: true }])));

const uploadGeneric = vi.fn();
vi.mock('@/lib/api/orchestrator/file.service', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  fileService: { uploadGeneric: (...args: unknown[]) => uploadGeneric(...args) },
}));

import { AssetCell } from '../cells/AssetCell';

const UUID = '44444444-4444-4444-4444-444444444444';

function renderCell(props: Partial<React.ComponentProps<typeof AssetCell>> = {}) {
  const onSaveAndExit = vi.fn();
  render(
    <AssetCell
      value={null}
      rowKey="r1"
      field="photo"
      isEditing={false}
      onSaveAndExit={onSaveAndExit}
      onStartEditing={() => {}}
      onExitEditing={() => {}}
      {...props}
    />,
  );
  return { onSaveAndExit };
}


const OTHER_UUID = '55555555-5555-5555-5555-555555555555';

/** Render a thumbnail cell and expose a rerender that swaps only the value. */
function renderCellFor(value: unknown) {
  const props = {
    rowKey: 'r1', field: 'photo', isEditing: false,
    onSaveAndExit: vi.fn(), onStartEditing: () => {}, onExitEditing: () => {},
    displayConfig: { render: 'thumbnail' as const },
  };
  const view = render(<AssetCell value={value} {...props} />);
  return {
    rerender: (next: unknown) => view.rerender(<AssetCell value={next} {...props} />),
  };
}

afterEach(() => {
  cleanup();
  uploadGeneric.mockReset();
  observerCallbacks = [];
  vi.unstubAllGlobals();
});

describe('AssetCell', () => {
  it('shows the empty placeholder that matches the column display', () => {
    renderCell({ value: null, displayConfig: { render: 'thumbnail' } });
    expect(screen.getByText('noImage')).toBeInTheDocument();

    cleanup();

    renderCell({ value: null, displayConfig: { render: 'card' } });
    expect(screen.getByText('noFile')).toBeInTheDocument();
  });

  it('renders a stored asset by its file name', () => {
    renderCell({ value: { _type: 'file', id: UUID, name: 'invoice.pdf', mimeType: 'application/pdf', size: 2048 } });

    expect(screen.getByText('invoice.pdf')).toBeInTheDocument();
  });

  it('reads the JSON-string encoding the CRUD write path persists', () => {
    renderCell({ value: JSON.stringify({ _type: 'file', id: UUID, name: 'from-json.pdf' }) });

    expect(screen.getByText('from-json.pdf')).toBeInTheDocument();
  });

  it('says so when the reference cannot be resolved, instead of rendering an empty cell', () => {
    // A file deleted from Files, or a cell written before the file-URL cutover, used to look
    // exactly like a cell nobody had filled in. That silence is the bug being fixed.
    renderCell({ value: { _type: 'file', path: '1/general/general/ab_gone.txt', name: 'gone.txt' } });

    expect(screen.getByText('gone.txt')).toBeInTheDocument();
    expect(screen.queryByText('noFile')).not.toBeInTheDocument();
  });

  it('marks an external link as such rather than showing a fake size', () => {
    renderCell({ value: 'https://cdn.example.com/photo.png' });

    expect(screen.getByText('assetExternal')).toBeInTheDocument();
  });

  it('offers the three sources when editing', () => {
    renderCell({ isEditing: true });

    expect(screen.getByTitle('upload')).toBeInTheDocument();
    expect(screen.getByTitle('pickFromFiles')).toBeInTheDocument();
    expect(screen.getByTitle('assetUrl')).toBeInTheDocument();
  });

  it('stores the canonical asset when a file is picked from Files', async () => {
    const { onSaveAndExit } = renderCell({ isEditing: true });

    fireEvent.click(screen.getByTitle('pickFromFiles'));
    // The picker is loaded lazily, so it only exists after the dialog opens.
    fireEvent.click(await screen.findByText('pick-one'));

    expect(onSaveAndExit).toHaveBeenCalledWith(
      expect.objectContaining({
        _type: 'file',
        id: '99999999-9999-9999-9999-999999999999',
        name: 'picked.pdf',
        path: 't/general/x_picked.pdf',
      }),
    );
  });

  it('stores an external URL the user pastes', () => {
    const { onSaveAndExit } = renderCell({ isEditing: true });

    fireEvent.click(screen.getByTitle('assetUrl'));
    fireEvent.change(screen.getByPlaceholderText('assetUrlPlaceholder'), {
      target: { value: 'https://example.com/a.png' },
    });
    fireEvent.click(screen.getByText('assetUrlConfirm'));

    expect(onSaveAndExit).toHaveBeenCalledWith(
      expect.objectContaining({ _type: 'file', url: 'https://example.com/a.png' }),
    );
  });

  it('leaves the link row by the back button, so picking "use a link" is not a one-way door', () => {
    // The complaint this fixes: the three sources are replaced by the URL row, and the only way
    // back used to be an Escape key nothing on screen mentioned.
    renderCell({ isEditing: true });

    fireEvent.click(screen.getByTitle('assetUrl'));
    expect(screen.queryByTitle('upload')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTitle('assetUrlBack'));

    expect(screen.getByTitle('upload')).toBeInTheDocument();
    expect(screen.getByTitle('pickFromFiles')).toBeInTheDocument();
    expect(screen.getByTitle('assetUrl')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('assetUrlPlaceholder')).not.toBeInTheDocument();
  });

  it('drops the rejected-URL message on the way back, so it cannot blame the upload button', () => {
    const { onSaveAndExit } = renderCell({ isEditing: true });

    fireEvent.click(screen.getByTitle('assetUrl'));
    fireEvent.change(screen.getByPlaceholderText('assetUrlPlaceholder'), { target: { value: 'not a url' } });
    fireEvent.click(screen.getByText('assetUrlConfirm'));
    expect(screen.getByText('assetUrlInvalid')).toBeInTheDocument();

    fireEvent.click(screen.getByTitle('assetUrlBack'));

    expect(screen.queryByText('assetUrlInvalid')).not.toBeInTheDocument();
    // Going back is not a write: nothing was chosen, so nothing is stored.
    expect(onSaveAndExit).not.toHaveBeenCalled();
  });

  it('still accepts Escape as the keyboard route back', () => {
    renderCell({ isEditing: true });

    fireEvent.click(screen.getByTitle('assetUrl'));
    fireEvent.keyDown(screen.getByPlaceholderText('assetUrlPlaceholder'), { key: 'Escape' });

    expect(screen.getByTitle('upload')).toBeInTheDocument();
  });

  it('re-opens the link row on an empty draft after a round trip, never on the last typed URL', () => {
    // Back has to CLEAR the draft, not hide it: coming back to a URL the cell already refused
    // (or to one the user abandoned) reads as if it had been stored.
    renderCell({ isEditing: true });

    fireEvent.click(screen.getByTitle('assetUrl'));
    fireEvent.change(screen.getByPlaceholderText('assetUrlPlaceholder'), {
      target: { value: 'https://example.com/abandoned.png' },
    });
    fireEvent.click(screen.getByTitle('assetUrlBack'));
    fireEvent.click(screen.getByTitle('assetUrl'));

    expect(screen.getByPlaceholderText('assetUrlPlaceholder')).toHaveValue('');
  });

  it('refuses a value that is not a usable URL, and stores nothing', () => {
    const { onSaveAndExit } = renderCell({ isEditing: true });

    fireEvent.click(screen.getByTitle('assetUrl'));
    fireEvent.change(screen.getByPlaceholderText('assetUrlPlaceholder'), { target: { value: 'not a url' } });
    fireEvent.click(screen.getByText('assetUrlConfirm'));

    expect(screen.getByText('assetUrlInvalid')).toBeInTheDocument();
    expect(onSaveAndExit).not.toHaveBeenCalled();
  });

  it('hides the destructive actions in read-only mode', () => {
    renderCell({ value: { _type: 'file', id: UUID, name: 'a.pdf' }, readOnly: true });

    expect(screen.queryByTitle('removeFile')).not.toBeInTheDocument();
    expect(screen.queryByTitle('download')).not.toBeInTheDocument();
  });

  it('stores the uploaded file, keeping the storage key the publication copier needs', async () => {
    uploadGeneric.mockResolvedValue({
      id: UUID, storageKey: 't/general/datatable/ab_shot.png',
      fileName: 'shot.png', mimeType: 'image/png', size: 77,
    });
    const { onSaveAndExit } = renderCell({ isEditing: true });

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(['x'], 'shot.png', { type: 'image/png' })] } });

    await waitFor(() => expect(onSaveAndExit).toHaveBeenCalled());
    expect(onSaveAndExit).toHaveBeenCalledWith(
      expect.objectContaining({
        _type: 'file', id: UUID, name: 'shot.png',
        path: 't/general/datatable/ab_shot.png', size: 77,
      }),
    );
  });

  it('reports a failed upload and stores nothing', async () => {
    uploadGeneric.mockRejectedValue(new Error('quota exceeded'));
    const { onSaveAndExit } = renderCell({ isEditing: true });

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(['x'], 'shot.png', { type: 'image/png' })] } });

    expect(await screen.findByText('quota exceeded')).toBeInTheDocument();
    expect(onSaveAndExit).not.toHaveBeenCalled();
  });

  it('constrains the file input to images only on a thumbnail column', () => {
    renderCell({ isEditing: true, displayConfig: { render: 'thumbnail' } });

    expect(document.querySelector('input[type="file"]')).toHaveAttribute('accept', 'image/*');
  });

  it('accepts any file on a card column', () => {
    renderCell({ isEditing: true, displayConfig: { render: 'card' } });

    expect(document.querySelector('input[type="file"]')).not.toHaveAttribute('accept');
  });

  it('clears the cell when the file is removed', () => {
    const { onSaveAndExit } = renderCell({ value: { _type: 'file', id: UUID, name: 'a.pdf' } });

    fireEvent.click(screen.getByTitle('removeFile'));

    expect(onSaveAndExit).toHaveBeenCalledWith('');
  });

  it('renders the image itself on a thumbnail column', () => {
    renderCell({
      value: { _type: 'file', id: UUID, name: 'a.png', mimeType: 'image/png' },
      displayConfig: { render: 'thumbnail' },
    });

    expect(screen.getByAltText('a.png')).toBeInTheDocument();
  });

  it('shows the replacement image after a broken one, instead of latching on the icon', () => {
    // The fallback is keyed by the URL that failed, not a boolean: the grid re-renders this
    // component instance rather than remounting it, so a latch would keep hiding every later
    // image in that cell until the page was reloaded.
    const { rerender } = renderCellFor({ _type: 'file', id: UUID, name: 'broken.png', mimeType: 'image/png' });
    fireEvent.error(screen.getByAltText('broken.png'));
    expect(screen.queryByAltText('broken.png')).not.toBeInTheDocument();

    rerender({ _type: 'file', id: OTHER_UUID, name: 'fixed.png', mimeType: 'image/png' });

    expect(screen.getByAltText('fixed.png')).toBeInTheDocument();
  });

  it('renders an extension-less external image on a thumbnail column', () => {
    // Found live: a picsum/unsplash/signed-CDN link has neither a mime type nor an extension, so
    // the mime heuristic said "not an image" and the cell showed a generic file icon. A thumbnail
    // column IS an image column; attempting the image is safe because onError falls back.
    renderCell({ value: 'https://picsum.photos/seed/e2e/80/80', displayConfig: { render: 'thumbnail' } });

    expect(screen.getByRole('img')).toHaveAttribute('src', 'https://picsum.photos/seed/e2e/80/80');
  });

  it('does not guess that a pdf is an image on a card column', () => {
    renderCell({
      value: { _type: 'file', id: UUID, name: 'a.pdf', mimeType: 'application/pdf' },
      displayConfig: { render: 'card' },
    });

    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('falls back to the type icon when the image cannot be decoded', () => {
    // A URL can resolve and still fail to render. Showing a broken-image glyph in a grid row is
    // worse than showing what kind of file it is.
    renderCell({
      value: { _type: 'file', id: UUID, name: 'a.png', mimeType: 'image/png' },
      displayConfig: { render: 'thumbnail' },
    });

    fireEvent.error(screen.getByAltText('a.png'));

    expect(screen.queryByAltText('a.png')).not.toBeInTheDocument();
  });

  it('draws the thumbnail as a rounded square, never a circle', () => {
    // A circle crops the sides off anything that is not a portrait, which is most of what a
    // table holds. Pinned in both places (see the modal preview test) because the change states
    // the two shapes must not drift.
    const { container } = render(
      <AssetCell
        value={{ _type: 'file', id: UUID, name: 'a.png', mimeType: 'image/png' }}
        rowKey="r1"
        field="photo"
        isEditing={false}
        onSaveAndExit={vi.fn()}
        onStartEditing={() => {}}
        onExitEditing={() => {}}
        displayConfig={{ render: 'thumbnail' }}
      />,
    );

    expect(container.querySelector('.rounded-xl')).not.toBeNull();
    expect(container.querySelector('.rounded-full')).toBeNull();
  });

  it('plays a clip, a sound and a page in the row once it is scrolled to, not just a picture', () => {
    // The complaint this answers: an attachment column showed a name and a grey icon, which is
    // the one thing the row already told you. Every kind Files previews, the cell previews.
    //
    // Driven through a real observer on purpose. These three kinds are lazy, and a browser only
    // reports a target that HAS a box: a version of this cell that hid the observed element
    // while it was empty rendered nothing here, at any scroll position, and a test that relied
    // on jsdom's missing observer called it green.
    const cases = [
      { name: 'clip.mp4', mimeType: 'video/mp4', tag: 'video' },
      { name: 'voice.mp3', mimeType: 'audio/mpeg', tag: 'audio' },
      { name: 'invoice.pdf', mimeType: 'application/pdf', tag: 'iframe' },
    ];
    for (const media of cases) {
      withObserver();
      const { container } = render(
        <AssetCell
          value={{ _type: 'file', id: UUID, name: media.name, mimeType: media.mimeType }}
          rowKey="r1" field="attachment" isEditing={false}
          onSaveAndExit={vi.fn()} onStartEditing={() => {}} onExitEditing={() => {}}
          displayConfig={{ render: 'card' }}
        />,
      );

      expect(container.querySelector(media.tag), media.name + ' before scroll').toBeNull();
      scrollIntoView();

      expect(container.querySelector(media.tag), media.name).toBeInTheDocument();
      cleanup();
      observerCallbacks = [];
    }
  });

  it('reads the kind off the file name when the mime type is the generic one', () => {
    // What our own raw serve answers for a row with no stored mime, which is most of what a
    // workflow writes into a table.
    withObserver();
    const { container } = render(
      <AssetCell
        value={{ _type: 'file', id: UUID, name: 'render.mp4', mimeType: 'application/octet-stream' }}
        rowKey="r1" field="attachment" isEditing={false}
        onSaveAndExit={vi.fn()} onStartEditing={() => {}} onExitEditing={() => {}}
        displayConfig={{ render: 'card' }}
      />,
    );
    scrollIntoView();

    expect(container.querySelector('video')).toBeInTheDocument();
    // The badge beside it has to agree, shape AND colour: it used to read the mime type alone,
    // so a clip stored as application/octet-stream played as a video under a generic grey file
    // icon. Both halves are asserted because they are two separate functions.
    expect(container.querySelector('.bg-red-500 svg')).toHaveClass('lucide-video');
  });

  it('shows a clip as a still frame on a thumbnail column, and a sound as its icon', () => {
    // A 56px box has no room for a control bar, and a sound has no frame to put in it.
    const { container: withVideo } = render(
      <AssetCell
        value={{ _type: 'file', id: UUID, name: 'clip.mp4', mimeType: 'video/mp4' }}
        rowKey="r1" field="photo" isEditing={false}
        onSaveAndExit={vi.fn()} onStartEditing={() => {}} onExitEditing={() => {}}
        displayConfig={{ render: 'thumbnail' }}
      />,
    );
    expect(withVideo.querySelector('video')).toBeInTheDocument();
    expect(withVideo.querySelector('video')).not.toHaveAttribute('controls');
    cleanup();

    const { container: withAudio } = render(
      <AssetCell
        value={{ _type: 'file', id: UUID, name: 'voice.mp3', mimeType: 'audio/mpeg' }}
        rowKey="r1" field="photo" isEditing={false}
        onSaveAndExit={vi.fn()} onStartEditing={() => {}} onExitEditing={() => {}}
        displayConfig={{ render: 'thumbnail' }}
      />,
    );
    expect(withAudio.querySelector('audio')).toBeNull();
    // Scoped to the 56px box: the row also carries the Eye/Download/Delete icons, so a bare
    // "there is an svg somewhere" would pass with no type icon at all.
    expect(withAudio.querySelector('.rounded-xl svg')).toBeInTheDocument();
  });

  it('leaves a file it cannot show as a name and its icon, with no media in the row', () => {
    const { container } = render(
      <AssetCell
        value={{ _type: 'file', id: UUID, name: 'archive.zip', mimeType: 'application/zip', size: 12 }}
        rowKey="r1" field="attachment" isEditing={false}
        onSaveAndExit={vi.fn()} onStartEditing={() => {}} onExitEditing={() => {}}
        displayConfig={{ render: 'card' }}
      />,
    );

    expect(screen.getByText('archive.zip')).toBeInTheDocument();
    expect(container.querySelector('video')).toBeNull();
    expect(container.querySelector('audio')).toBeNull();
    expect(container.querySelector('iframe')).toBeNull();
    expect(container.querySelector('img')).toBeNull();
    // A kind with no preview keeps the plain slate badge, and no preview wrapper is rendered
    // at all - not even an empty one waiting for bytes that are never coming.
    expect(container.querySelector('.bg-slate-500')).toBeInTheDocument();
    expect(container.querySelector('.bg-slate-100')).toBeNull();
  });

  it('offers the View action for a clip whose stored type says nothing, and never for an archive', () => {
    // The case that was missing before: the gate read the mime type alone, so a clip our own raw
    // serve types as a binary stream had no View button, for the same reason it had no preview.
    // Reading the name is what fixes both.
    renderCell({
      value: { _type: 'file', id: UUID, name: 'render.mp4', mimeType: 'application/octet-stream' },
    });
    expect(screen.getByTitle('view')).toBeInTheDocument();
    cleanup();

    // Plain text keeps the arm it always had: not a kind a row previews, but it opens fine.
    renderCell({ value: { _type: 'file', id: UUID, name: 'notes.txt', mimeType: 'text/plain' } });
    expect(screen.getByTitle('view')).toBeInTheDocument();
    cleanup();

    // An archive opens as nothing at all, so the button stays off.
    renderCell({ value: { _type: 'file', id: UUID, name: 'archive.zip', mimeType: 'application/zip' } });
    expect(screen.queryByTitle('view')).not.toBeInTheDocument();
  });

  it('leaves what is SAFE to open to the layer that holds the bytes', () => {
    // Deliberately no deny-list here. What executes is the type the SERVER sends, and a cell can
    // hold a file with no stored type at all - most of what a workflow writes - so a guard on the
    // cell's copy would pass exactly the case that matters and read as if it had closed it. The
    // View button says "worth opening"; openAuthedFileInNewTab says "safe to open", on the served
    // type (see lib/utils/__tests__/url-auth.test.ts).
    renderCell({ value: { _type: 'file', id: UUID, name: 'page.html', mimeType: 'text/html' } });

    expect(screen.getByTitle('view')).toBeInTheDocument();
  });

  it('never stores a folder as if it were a file', () => {
    // The picker lists folders now, so a folder row can reach the select handler. Storing one
    // would put a reference with no bytes behind it into the cell.
    const { onSaveAndExit } = renderCell({ isEditing: true });

    fireEvent.click(screen.getByTitle('pickFromFiles'));
    return screen.findByText('pick-folder').then((btn) => {
      fireEvent.click(btn);
      expect(onSaveAndExit).not.toHaveBeenCalled();
    });
  });
});
