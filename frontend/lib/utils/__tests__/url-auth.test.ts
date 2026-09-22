// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { isInternalUrl, fetchAuthedBlobUrl, openAuthedFileInNewTab, downloadAuthedFile } from '../url-auth';

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { getAuthToken: vi.fn() },
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: vi.fn(() => ({ 'X-Active-Organization-ID': 'org-7' })),
}));

import { apiClient } from '@/lib/api/api-client';
const mockGetAuthToken = vi.mocked(apiClient.getAuthToken);

beforeEach(() => {
  vi.resetAllMocks();
  mockGetAuthToken.mockResolvedValue('jwt-abc');
  // jsdom doesn't implement object URLs - stub them.
  URL.createObjectURL = vi.fn(() => 'blob:mock-object-url');
  URL.revokeObjectURL = vi.fn();
});

afterEach(() => {
  vi.restoreAllMocks();
});

function mockFetchOk() {
  const blob = new Blob(['bytes'], { type: 'image/png' });
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, blob: () => Promise.resolve(blob) });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

/** Capture the blob the object URL is minted from, which is the only place the type is visible. */
function captureBlobOfType(type: string): () => Blob | null {
  const blob = new Blob(['<script>parent.postMessage(document.cookie)</script>'], { type });
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, blob: () => Promise.resolve(blob) }));
  let captured: Blob | null = null;
  URL.createObjectURL = vi.fn((b: Blob) => { captured = b; return 'blob:mock-object-url'; });
  return () => captured;
}

/**
 * A blob URL inherits this app's origin, so a file the browser EXECUTES becomes same-origin code
 * the moment it is opened in a tab - a stronger position than any preview iframe, and reachable
 * from a single click on a row. The bytes are not the problem, the type is: re-stamped as plain
 * text, the tab shows the page's source instead of running it, which is what a forge does with a
 * raw file. Downloading is untouched, and so is every type that merely renders.
 */
describe('openAuthedFileInNewTab - what a tab is allowed to execute', () => {
  const EXECUTABLE = ['text/html', 'application/xhtml+xml', 'image/svg+xml', 'application/xml', 'text/xml'];

  it.each(EXECUTABLE)('serves %s as its own source instead of running it', async (type) => {
    const captured = captureBlobOfType(type);
    vi.stubGlobal('open', vi.fn());

    await openAuthedFileInNewTab('/api/proxy/files/by-id/abc/raw');

    expect(captured()!.type).toBe('text/plain');
    expect(captured()!.size).toBeGreaterThan(0); // same bytes, only the type changed
  });

  it('neutralizes a type that arrives with its parameters, which is how a server sends it', async () => {
    // The spelling a real server uses. It executes exactly like a bare `text/html`, so a guard
    // that compares the raw string answers "not executable" for the commonest form of the thing
    // it exists to stop. Latent today only because this browser strips the parameter off
    // Blob.type before the guard sees it, which is not a guarantee.
    const captured = captureBlobOfType('text/html;charset=utf-8');
    vi.stubGlobal('open', vi.fn());

    await openAuthedFileInNewTab('/api/proxy/files/by-id/abc/raw');

    expect(captured()!.type).toBe('text/plain');
  });

  it('leaves a type that merely renders alone, so a PDF still opens as a PDF', async () => {
    const captured = captureBlobOfType('application/pdf');
    vi.stubGlobal('open', vi.fn());

    await openAuthedFileInNewTab('/api/proxy/files/by-id/abc/raw');

    expect(captured()!.type).toBe('application/pdf');
  });

  it('leaves a file with no served type alone, rather than guessing at it', async () => {
    // The generic type our raw serve falls back to. It executes nothing on its own, and
    // re-stamping it would break the browser's own handling of a file we know nothing about.
    const captured = captureBlobOfType('application/octet-stream');
    vi.stubGlobal('open', vi.fn());

    await openAuthedFileInNewTab('/api/proxy/files/by-id/abc/raw');

    expect(captured()!.type).toBe('application/octet-stream');
  });

  it('does not touch a download: the saved file keeps the type it was stored with', async () => {
    // Nothing executes on the way to disk, and re-typing here would hand the user a file whose
    // type argues with its own name.
    const captured = captureBlobOfType('text/html');

    await downloadAuthedFile('/api/proxy/files/by-id/abc/raw', 'page.html');

    expect(captured()!.type).toBe('text/html');
  });
});

describe('isInternalUrl', () => {
  it('returns true for /api/ prefixed URLs', () => {
    expect(isInternalUrl('/api/proxy/files/abc')).toBe(true);
    expect(isInternalUrl('/api/files/proxy?key=abc')).toBe(true);
  });

  it('returns false for external / data / non-/api relative URLs', () => {
    expect(isInternalUrl('https://picsum.photos/200/200')).toBe(false);
    expect(isInternalUrl('data:image/png;base64,AAAA')).toBe(false);
    expect(isInternalUrl('/images/logo.png')).toBe(false);
  });
});

describe('fetchAuthedBlobUrl', () => {
  it('returns external URLs unchanged WITHOUT fetching (no token, no header)', async () => {
    const fetchMock = mockFetchOk();
    const url = 'https://picsum.photos/200/200';
    expect(await fetchAuthedBlobUrl(url)).toBe(url);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('fetches an internal URL with the Bearer + active-org header and returns a blob: URL', async () => {
    const fetchMock = mockFetchOk();
    const result = await fetchAuthedBlobUrl('/api/proxy/files/by-id/abc/raw?disposition=inline');

    expect(result).toBe('blob:mock-object-url');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [calledUrl, init] = fetchMock.mock.calls[0];
    // SECURITY: the token must NEVER be in the URL - only in the Authorization header.
    expect(calledUrl).toBe('/api/proxy/files/by-id/abc/raw?disposition=inline');
    expect(calledUrl).not.toMatch(/token=/);
    expect(init.headers.Authorization).toBe('Bearer jwt-abc');
    // Cross-org: the active-org header travels so a non-default-workspace file resolves.
    expect(init.headers['X-Active-Organization-ID']).toBe('org-7');
  });

  it('normalizes a legacy /api/files/ URL to the /api/proxy/files/ path', async () => {
    const fetchMock = mockFetchOk();
    await fetchAuthedBlobUrl('/api/files/proxy-signed?key=x');
    expect(fetchMock.mock.calls[0][0]).toBe('/api/proxy/files/proxy-signed?key=x');
  });

  it('still fetches (header-only) when no token is available - no token ever appears in the URL', async () => {
    mockGetAuthToken.mockResolvedValue(null);
    const fetchMock = mockFetchOk();
    await fetchAuthedBlobUrl('/api/proxy/files/by-id/abc/raw');
    const [calledUrl, init] = fetchMock.mock.calls[0];
    expect(calledUrl).toBe('/api/proxy/files/by-id/abc/raw');
    expect(init.headers.Authorization).toBeUndefined();
  });

  it('throws on a non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 403 }));
    await expect(fetchAuthedBlobUrl('/api/proxy/files/by-id/abc/raw')).rejects.toThrow('HTTP 403');
  });
});

describe('openAuthedFileInNewTab', () => {
  it('opens the blob: URL (never a token URL) in a new tab', async () => {
    mockFetchOk();
    const openSpy = vi.fn();
    vi.stubGlobal('open', openSpy);
    await openAuthedFileInNewTab('/api/proxy/files/by-id/abc/raw');
    expect(openSpy).toHaveBeenCalledWith('blob:mock-object-url', '_blank', 'noopener,noreferrer');
  });
});

describe('downloadAuthedFile', () => {
  it('downloads via an anchor whose href is the blob: URL with the given filename', async () => {
    mockFetchOk();
    const clicked: { href?: string; download?: string } = {};
    const anchor = {
      set href(v: string) { clicked.href = v; },
      set download(v: string) { clicked.download = v; },
      click: vi.fn(),
    } as unknown as HTMLAnchorElement;
    vi.spyOn(document, 'createElement').mockReturnValue(anchor);
    vi.spyOn(document.body, 'appendChild').mockImplementation((n) => n);
    vi.spyOn(document.body, 'removeChild').mockImplementation((n) => n);

    await downloadAuthedFile('/api/proxy/files/by-id/abc/raw', 'photo.png');

    expect(clicked.href).toBe('blob:mock-object-url');
    expect(clicked.download).toBe('photo.png');
    expect((anchor.click as ReturnType<typeof vi.fn>)).toHaveBeenCalled();
  });
});
