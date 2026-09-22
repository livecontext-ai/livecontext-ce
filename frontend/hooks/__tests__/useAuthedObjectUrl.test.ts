// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useAuthedObjectUrl } from '../useAuthedObjectUrl';

vi.mock('@/lib/api/api-client', () => ({
  // getTokenProvider is mocked as "not installed yet" on purpose: that is the real state during
  // the async auth bootstrap, and it is what the pre-fix code read. Any call site that reaches
  // for it instead of getAuthToken therefore reproduces the prod 401 in these tests.
  apiClient: { getAuthToken: vi.fn(), getTokenProvider: vi.fn(() => undefined) },
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: vi.fn(() => ({ 'X-Active-Organization-ID': 'org-7' })),
}));

import { apiClient } from '@/lib/api/api-client';
const mockGetAuthToken = vi.mocked(apiClient.getAuthToken);

let revoked: string[] = [];

beforeEach(() => {
  vi.resetAllMocks();
  revoked = [];
  mockGetAuthToken.mockResolvedValue('jwt-abc');
  URL.createObjectURL = vi.fn(() => 'blob:obj-1');
  URL.revokeObjectURL = vi.fn((u: string) => { revoked.push(u); });
});

afterEach(() => {
  vi.restoreAllMocks();
});

function mockFetchOk() {
  const blob = new Blob(['x'], { type: 'image/png' });
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, blob: () => Promise.resolve(blob) });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('useAuthedObjectUrl', () => {
  it('fetches an internal URL with the Bearer + active-org header (NO token in the URL) and returns a blob: URL', async () => {
    const fetchMock = mockFetchOk();
    const { result } = renderHook(() =>
      useAuthedObjectUrl('/api/proxy/files/by-id/abc/raw?disposition=inline'),
    );

    await waitFor(() => expect(result.current.url).toBe('blob:obj-1'));
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBe(false);

    const [calledUrl, init] = fetchMock.mock.calls[0];
    // The hallmark of the fix: the credential is in the header, never the URL.
    expect(calledUrl).toBe('/api/proxy/files/by-id/abc/raw?disposition=inline');
    expect(String(calledUrl)).not.toMatch(/token=/);
    expect(init.headers.Authorization).toBe('Bearer jwt-abc');
    expect(init.headers['X-Active-Organization-ID']).toBe('org-7'); // cross-org resolution
  });

  it('passes external URLs straight through without fetching', async () => {
    const fetchMock = mockFetchOk();
    const { result } = renderHook(() => useAuthedObjectUrl('https://cdn.example.com/x.png'));
    await waitFor(() => expect(result.current.url).toBe('https://cdn.example.com/x.png'));
    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.current.loading).toBe(false);
  });

  it('returns {url:null, loading:false} for a falsy source and does not fetch', async () => {
    const fetchMock = mockFetchOk();
    const { result } = renderHook(() => useAuthedObjectUrl(null));
    expect(result.current.url).toBeNull();
    expect(result.current.loading).toBe(false);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('normalizes a legacy /api/files/ URL to the /api/proxy/files/ path before fetching', async () => {
    const fetchMock = mockFetchOk();
    const { result } = renderHook(() => useAuthedObjectUrl('/api/files/by-id/abc/raw'));
    await waitFor(() => expect(result.current.url).toBe('blob:obj-1'));
    expect(fetchMock.mock.calls[0][0]).toBe('/api/proxy/files/by-id/abc/raw');
  });

  it('surfaces an error (no crash, url stays null) on a non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 403 }));
    const { result } = renderHook(() => useAuthedObjectUrl('/api/proxy/files/by-id/abc/raw'));
    await waitFor(() => expect(result.current.error).toBe(true));
    expect(result.current.url).toBeNull();
  });

  it('revokes the blob: object URL on unmount (no memory leak)', async () => {
    mockFetchOk();
    const { result, unmount } = renderHook(() => useAuthedObjectUrl('/api/proxy/files/by-id/abc/raw'));
    await waitFor(() => expect(result.current.url).toBe('blob:obj-1'));
    unmount();
    expect(revoked).toContain('blob:obj-1');
  });

  it('revokes the previous blob and refetches when the src changes', async () => {
    let n = 0;
    URL.createObjectURL = vi.fn(() => `blob:obj-${++n}`);
    const fetchMock = mockFetchOk();
    const { result, rerender } = renderHook(({ src }) => useAuthedObjectUrl(src), {
      initialProps: { src: '/api/proxy/files/by-id/a/raw' },
    });
    await waitFor(() => expect(result.current.url).toBe('blob:obj-1'));

    rerender({ src: '/api/proxy/files/by-id/b/raw' });
    await waitFor(() => expect(result.current.url).toBe('blob:obj-2'));

    expect(revoked).toContain('blob:obj-1'); // the stale blob is released
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  function mockFetchWithType(type: string) {
    const blob = new Blob(['x'], { type });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, blob: () => Promise.resolve(blob) }));
  }

  it('re-types a generic (octet-stream) blob from the mimeTypeHint so a PDF/video can render', async () => {
    // The by-id raw serve returns octet-stream for a row with no stored mime_type - that type
    // can't drive a <video>/<iframe>(pdf). The hint must re-stamp the blob so it renders.
    let captured: Blob | null = null;
    URL.createObjectURL = vi.fn((b: Blob) => { captured = b; return 'blob:obj-1'; });
    mockFetchWithType('application/octet-stream');

    const { result } = renderHook(() =>
      useAuthedObjectUrl('/api/proxy/files/by-id/abc/raw?disposition=inline', 'application/pdf'),
    );
    await waitFor(() => expect(result.current.url).toBe('blob:obj-1'));
    expect(captured).toBeTruthy();
    expect(captured!.type).toBe('application/pdf');
  });

  // Regression - prod 2026-08-25: 98 of the gateway's 401s in 7 days were
  // GET /api/files/by-id/<id>/raw arriving with NO Authorization header at all
  // ("Authentication required", rejected in 0 ms). The hook read
  // apiClient.getTokenProvider() directly, which is undefined until the async auth
  // bootstrap in smart-providers.tsx installs it, so a component mounting inside that
  // window fetched anonymously - and the effect, keyed only on [src, mimeTypeHint],
  // never retried once auth arrived. Going through getAuthToken makes the fetch WAIT.
  it('waits for the token instead of firing an anonymous request while auth is still booting', async () => {
    let releaseToken: (t: string) => void = () => {};
    mockGetAuthToken.mockReturnValue(new Promise<string>((resolve) => { releaseToken = resolve; }));
    const fetchMock = mockFetchOk();

    const { result } = renderHook(() => useAuthedObjectUrl('/api/proxy/files/by-id/abc/raw'));

    // The window where the old code fired an anonymous request the gateway answered 401.
    await Promise.resolve();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.current.loading).toBe(true);

    releaseToken('jwt-late');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer jwt-late');
    await waitFor(() => expect(result.current.url).toBe('blob:obj-1'));
  });

  it('still serves a signed-out caller anonymously when no token exists at all', async () => {
    // getAuthToken resolves null once its wait is exhausted; the hook must answer rather than
    // hang, so a public/share-token context keeps working.
    mockGetAuthToken.mockResolvedValue(null);
    const fetchMock = mockFetchOk();

    const { result } = renderHook(() => useAuthedObjectUrl('/api/proxy/files/by-id/abc/raw'));

    await waitFor(() => expect(result.current.url).toBe('blob:obj-1'));
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined();
  });

  it('treats a generic type as generic even when it arrives with parameters', async () => {
    // `application/octet-stream;charset=binary` is just as unable to drive a <video>, and an
    // exact-match check would call it specific and leave the clip undecodable.
    let captured: Blob | null = null;
    URL.createObjectURL = vi.fn((b: Blob) => { captured = b; return 'blob:obj-1'; });
    mockFetchWithType('application/octet-stream;charset=binary');

    const { result } = renderHook(() =>
      useAuthedObjectUrl('/api/proxy/files/by-id/abc/raw', 'video/mp4'),
    );
    await waitFor(() => expect(result.current.url).toBe('blob:obj-1'));
    expect(captured!.type).toBe('video/mp4');
  });

  it('keeps a specific server Content-Type even when a hint is provided (no re-type)', async () => {
    let captured: Blob | null = null;
    URL.createObjectURL = vi.fn((b: Blob) => { captured = b; return 'blob:obj-1'; });
    mockFetchWithType('video/mp4');

    const { result } = renderHook(() =>
      useAuthedObjectUrl('/api/proxy/files/by-id/abc/raw', 'application/pdf'),
    );
    await waitFor(() => expect(result.current.url).toBe('blob:obj-1'));
    expect(captured!.type).toBe('video/mp4'); // a specific server type wins; the hint is ignored
  });

  it('overrides even a specific server type when the caller forces it, so a framed document is one', async () => {
    // The served type comes from the storage row, and a blob URL inherits the app's origin: a
    // row stored as text/html under a `.pdf` name would become a same-origin document running
    // its own script inside the <iframe> that previews it. Callers that FRAME bytes force the
    // type; forced, that payload renders as a broken PDF instead of executing.
    let captured: Blob | null = null;
    URL.createObjectURL = vi.fn((b: Blob) => { captured = b; return 'blob:obj-1'; });
    mockFetchWithType('text/html');

    const { result } = renderHook(() =>
      useAuthedObjectUrl('/api/proxy/files/by-id/abc/raw', undefined, 'application/pdf'),
    );
    await waitFor(() => expect(result.current.url).toBe('blob:obj-1'));
    expect(captured!.type).toBe('application/pdf');
  });

  it('refetches when the caller starts forcing, so the blob cannot keep a stale type', async () => {
    // The forced type decides what the BLOB is, not just what is rendered, so it has to be part
    // of what the effect keys on. Left out, a component that starts hinting and then forces
    // would keep serving the first, unforced blob.
    const types = ['text/html', 'text/html'];
    let call = 0;
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve({
      ok: true, blob: () => Promise.resolve(new Blob(['x'], { type: types[call++] })),
    })));
    const seen: Blob[] = [];
    URL.createObjectURL = vi.fn((b: Blob) => { seen.push(b); return `blob:obj-${seen.length}`; });

    const { result, rerender } = renderHook(({ force }: { force?: string }) =>
      useAuthedObjectUrl('/api/proxy/files/by-id/abc/raw', undefined, force), {
      initialProps: {} as { force?: string },
    });
    await waitFor(() => expect(result.current.url).toBe('blob:obj-1'));
    expect(seen[0].type).toBe('text/html');

    rerender({ force: 'application/pdf' });

    await waitFor(() => expect(result.current.url).toBe('blob:obj-2'));
    expect(seen[1].type).toBe('application/pdf');
  });
});
