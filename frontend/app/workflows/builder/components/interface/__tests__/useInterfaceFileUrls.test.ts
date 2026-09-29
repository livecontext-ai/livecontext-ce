// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useInterfaceFileUrls, INLINE_BUDGET_BYTES, resetSigningAvailabilityForTests } from '../useInterfaceFileUrls';

vi.mock('@/lib/api/api-client', () => ({
  // getTokenProvider is mocked as "not installed yet" on purpose: that is the real state during
  // the async auth bootstrap, and it is what the pre-fix code read. Any call site that reaches
  // for it instead of getAuthToken therefore reproduces the prod 401 in these tests.
  apiClient: { getAuthToken: vi.fn(), getTokenProvider: vi.fn(() => undefined), get: vi.fn() },
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: vi.fn(() => ({ 'X-Active-Organization-ID': 'org-7' })),
}));

import { apiClient } from '@/lib/api/api-client';
const mockGetAuthToken = vi.mocked(apiClient.getAuthToken);
const mockApiGet = vi.mocked(apiClient.get);

const ID = '9a443915-a594-48a1-9760-e7a1b4b2eaf7';
const RAW = `/api/proxy/files/by-id/${ID}/raw?disposition=inline`;

function fileRef() {
  return { _type: 'file' as const, path: 'tenant1/run/abc.png', name: 'abc.png', mimeType: 'image/png', size: 3, id: ID };
}

beforeEach(() => {
  vi.resetAllMocks();
  resetSigningAvailabilityForTests();
  mockGetAuthToken.mockResolvedValue('jwt-abc');
});
afterEach(() => vi.restoreAllMocks());

function mockFetchOk() {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    blob: () => Promise.resolve(new Blob(['png'], { type: 'image/png' })),
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('useInterfaceFileUrls', () => {
  it('resolves each FileRef to a base64 data: URI fetched with the Bearer + active-org header (no token in the URL)', async () => {
    const fetchMock = mockFetchOk();
    const { result } = renderHook(() => useInterfaceFileUrls({ photo: fileRef() }, true));

    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toMatch(/^data:/));
    // The resolved value is a self-contained data: URI - renders in a sandboxed (no same-origin) iframe.
    expect(result.current.resolveFileUrl(RAW).startsWith('data:image/png;base64,')).toBe(true);

    const [calledUrl, init] = fetchMock.mock.calls[0];
    // SECURITY: the by-id URL is fetched with the header - never with a ?token=.
    expect(calledUrl).toBe(RAW);
    expect(String(calledUrl)).not.toMatch(/token=/);
    expect(init.headers.Authorization).toBe('Bearer jwt-abc');
    expect(init.headers['X-Active-Organization-ID']).toBe('org-7'); // cross-org resolution
  });

  it('returns the raw URL unchanged for an unknown/unresolved key (never injects a token)', async () => {
    mockFetchOk();
    const { result } = renderHook(() => useInterfaceFileUrls({ photo: fileRef() }, true));
    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toMatch(/^data:/));
    const other = '/api/proxy/files/by-id/other/raw?disposition=inline';
    expect(result.current.resolveFileUrl(other)).toBe(other);
    expect(result.current.resolveFileUrl(other)).not.toMatch(/token=/);
  });

  // Regression - prod 2026-08-25. This hook produced the gateway's most frequent error: 98 x 401
  // on GET /api/files/by-id/<id>/raw in 7 days, arriving in PAIRS 0.0s apart. It read
  // apiClient.getTokenProvider() directly, which is undefined until the async auth bootstrap in
  // smart-providers.tsx installs it. Inside that window the fetch went out anonymous (401 #1),
  // res.ok was false so the entry stayed unresolved, and resolveFileUrl then handed the interface
  // the RAW by-id URL - which the sandboxed iframe (allow-scripts only, so no header and no
  // same-origin) could only load anonymously too (401 #2), leaving the image permanently broken.
  it('waits for the token instead of resolving every file anonymously while auth is still booting', async () => {
    let releaseToken: (t: string) => void = () => {};
    mockGetAuthToken.mockReturnValue(new Promise<string>((resolve) => { releaseToken = resolve; }));
    const fetchMock = mockFetchOk();

    const { result } = renderHook(() => useInterfaceFileUrls({ photo: fileRef() }, true));

    await Promise.resolve();
    expect(fetchMock).not.toHaveBeenCalled();

    releaseToken('jwt-late');
    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toMatch(/^data:/));
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer jwt-late');
  });

  it('falls back to the raw URL, not a crash, when there is genuinely no token', async () => {
    // The signed-out counterpart: getAuthToken answers null once its wait is exhausted, and this
    // hook must still resolve to SOMETHING the iframe can render rather than throwing inside the
    // effect. The raw URL is the documented fallback; what the fix removes is reaching it while a
    // token was merely still on its way.
    mockGetAuthToken.mockResolvedValue(null);
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 401 });
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useInterfaceFileUrls({ photo: fileRef() }, true));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined();
    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toBe(RAW));
  });

  it('does nothing when disabled (edit mode) - no fetch', () => {
    const fetchMock = mockFetchOk();
    renderHook(() => useInterfaceFileUrls({ photo: fileRef() }, false));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('does nothing when there are no FileRefs in the data', () => {
    const fetchMock = mockFetchOk();
    renderHook(() => useInterfaceFileUrls({ title: 'hello', count: 3 }, true));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  // -- Where the session token may go -------------------------------------
  //
  // A file reference's `url` is DATA: a table row can be written by an agent, a workflow, or an
  // acquired marketplace application, so it can name any origin. This fetch carries a long-lived,
  // full-scope bearer, so it must never leave our own origin.

  it('never sends the session token to a foreign origin named by a file reference', async () => {
    const fetchMock = mockFetchOk();
    const hostile = {
      _type: 'file' as const,
      path: 'a/b.png',
      name: 'b.png',
      mimeType: 'image/png',
      size: 1,
      url: 'https://attacker.example/x.png',
    };

    renderHook(() => useInterfaceFileUrls({ photo: hostile }, true));

    await waitFor(() => expect(mockGetAuthToken).toHaveBeenCalledTimes(0), { timeout: 50 })
      .catch(() => { /* the effect may still have run; the assertion below is what matters */ });
    const fetchedUrls = fetchMock.mock.calls.map(([url]) => String(url));
    expect(fetchedUrls).not.toContain('https://attacker.example/x.png');
    expect(fetchedUrls.every((url) => url.startsWith('/api/'))).toBe(true);
  });

  it('leaves an external image to the iframe, unresolved and untouched', async () => {
    mockFetchOk();
    const external = { _type: 'file' as const, url: 'https://cdn.example.com/photo.png', name: 'photo.png' };

    const { result } = renderHook(() => useInterfaceFileUrls({ photo: external }, true));

    // Unresolved means the resolver hands back the raw URL, which the sandboxed iframe loads
    // anonymously - correct for a public URL, and it carries no credential of ours.
    expect(result.current.resolveFileUrl('https://cdn.example.com/photo.png'))
      .toBe('https://cdn.example.com/photo.png');
  });

  it('pre-fetches a table asset that carries no path, mimeType or size', async () => {
    // findFileRefs keys on the stricter FileRef shape, so a media cell picked from Files or
    // repaired from a bare URL was never pre-fetched: the sandboxed iframe then received the
    // authenticated URL and loaded it anonymously, which is the 401 pair this hook exists to stop.
    const fetchMock = mockFetchOk();
    const asset = { _type: 'file' as const, id: ID, url: RAW, name: 'photo.png' };

    const { result } = renderHook(() => useInterfaceFileUrls({ photo: asset }, true));

    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toMatch(/^data:/));
    expect(fetchMock.mock.calls.map(([url]) => String(url))).toContain(RAW);
  });

  // -- Media and large files stream from a signed link ---------------------
  //
  // Regression - run page of a Caption Factory application, 2026-09-28. The interface received six
  // 18-20 MB videos; every one was downloaded and inlined as a base64 data: URI, then copied into
  // the iframe HTML and into __RESOLVED_DATA__. Several hundred MB of string: the whole page died
  // on `RangeError: Invalid string length`. The decision now comes from the file reference itself
  // (the Next proxy strips Content-Length, so these mocks deliberately send no headers at all).

  const SIGNED = '/api/files/proxy-signed?key=1%2Fgeneral%2Fep01.mp4&exp=9999999999&disposition=inline&sig=abc';
  const MB = 1024 * 1024;

  function idOf(n: number) {
    return `9a443915-a594-48a1-9760-e7a1b4b2ea${String(n).padStart(2, '0')}`;
  }
  function rawOf(id: string) {
    return `/api/proxy/files/by-id/${id}/raw?disposition=inline`;
  }
  function ref(id: string, mimeType: string, size: number) {
    return { _type: 'file' as const, path: `tenant1/general/${id}.bin`, name: `${id}.bin`, mimeType, size, id };
  }
  /** A fetch that answers every by-id URL with `bytes` of body and NO headers, as behind the proxy. */
  function mockBodies(bytes: number, type = 'application/octet-stream') {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve({
      ok: true,
      blob: () => Promise.resolve(new Blob([new Uint8Array(bytes)], { type })),
    }));
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
  }

  it('streams a large video from a signed link without downloading it here', async () => {
    const fetchMock = mockBodies(10);
    mockApiGet.mockResolvedValue({ url: SIGNED, expires_at: 9999999999 });

    const { result } = renderHook(() => useInterfaceFileUrls({ clip: ref(ID, 'video/mp4', 18_370_402) }, true));

    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toBe(SIGNED));
    expect(fetchMock).not.toHaveBeenCalled();
    expect(mockApiGet).toHaveBeenCalledWith(`/files/by-id/${ID}/signed-url`, { params: { disposition: 'inline' }, retries: 0 });
    // The signed link carries no session token - that property is what data: URIs were protecting.
    expect(result.current.resolveFileUrl(RAW)).not.toMatch(/token=/);
  });

  it('keeps a 5 MB document inline as before, so interface JS can still fetch() it (no CORS on the signed proxy)', async () => {
    mockBodies(5 * MB, 'application/pdf');
    mockApiGet.mockResolvedValue({ url: SIGNED });

    const { result } = renderHook(() => useInterfaceFileUrls({ doc: ref(ID, 'application/pdf', 5 * MB) }, true));

    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toMatch(/^data:application\/pdf;base64,/));
    expect(mockApiGet).not.toHaveBeenCalled();
  });

  it('gives a link to a document the budget cannot hold, without downloading it', async () => {
    const fetchMock = mockBodies(10);
    mockApiGet.mockResolvedValue({ url: SIGNED });

    const { result } = renderHook(() => useInterfaceFileUrls({ doc: ref(ID, 'application/pdf', INLINE_BUDGET_BYTES + 1) }, true));

    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toBe(SIGNED));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('trusts the bytes over a wrong declared size: a "20 KB" file that is 60 MB is never inlined', async () => {
    mockBodies(60 * MB, 'application/octet-stream');
    mockApiGet.mockResolvedValue({ url: SIGNED });

    const { result } = renderHook(() => useInterfaceFileUrls({ doc: ref(ID, 'application/octet-stream', 20_000) }, true));

    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toBe(SIGNED), { timeout: 5000 });
  });

  it('asks an install that cannot sign only once, then inlines media as before', async () => {
    mockBodies(120_000, 'audio/mpeg');
    mockApiGet.mockRejectedValue(Object.assign(new Error('Service Unavailable'), { status: 503 }));

    const first = renderHook(() => useInterfaceFileUrls({ a: ref(idOf(1), 'audio/mpeg', 120_000) }, true));
    await waitFor(() => expect(first.result.current.resolveFileUrl(rawOf(idOf(1)))).toMatch(/^data:audio/));
    const second = renderHook(() => useInterfaceFileUrls({ b: ref(idOf(2), 'audio/mpeg', 120_000) }, true));
    await waitFor(() => expect(second.result.current.resolveFileUrl(rawOf(idOf(2)))).toMatch(/^data:audio/));

    expect(mockApiGet).toHaveBeenCalledTimes(1);
  });

  it('streams a small audio clip when a link is available', async () => {
    const fetchMock = mockBodies(10);
    mockApiGet.mockResolvedValue({ url: SIGNED });

    const { result } = renderHook(() => useInterfaceFileUrls({ sound: ref(ID, 'audio/mpeg', 120_000) }, true));

    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toBe(SIGNED));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('inlines a small audio clip exactly as before when the install cannot mint links (no signing secret)', async () => {
    mockBodies(120_000, 'audio/mpeg');
    mockApiGet.mockRejectedValue(new Error('503'));

    const { result } = renderHook(() => useInterfaceFileUrls({ sound: ref(ID, 'audio/mpeg', 120_000) }, true));

    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toMatch(/^data:audio\/mpeg;base64,/));
  });

  it('without links, still inlines one large video as before (within the budget)', async () => {
    mockBodies(18 * MB, 'video/mp4');
    mockApiGet.mockRejectedValue(new Error('503'));

    const { result } = renderHook(() => useInterfaceFileUrls({ clip: ref(ID, 'video/mp4', 18 * MB) }, true));

    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toMatch(/^data:video\/mp4;base64,/), { timeout: 5000 });
  });

  it('without links, never inlines past the budget: six 18 MB videos cannot kill the page', async () => {
    const fetchMock = mockBodies(18 * MB, 'video/mp4');
    mockApiGet.mockRejectedValue(new Error('503'));
    const ids = [0, 1, 2, 3, 4, 5].map(idOf);
    const data = { clips: ids.map((id) => ref(id, 'video/mp4', 18 * MB)) };

    const { result } = renderHook(() => useInterfaceFileUrls(data, true));

    await waitFor(() => expect(mockApiGet).toHaveBeenCalledTimes(6));
    await waitFor(() => expect(ids.some((id) => result.current.resolveFileUrl(rawOf(id)).startsWith('data:'))).toBe(true), { timeout: 5000 });
    const inlined = ids.filter((id) => result.current.resolveFileUrl(rawOf(id)).startsWith('data:'));
    expect(inlined.length * 18 * MB).toBeLessThanOrEqual(INLINE_BUDGET_BYTES);
    // A size the reference already says overflows the budget is not even downloaded.
    expect(fetchMock.mock.calls.length).toBeLessThanOrEqual(Math.floor(INLINE_BUDGET_BYTES / (18 * MB)));
  });

  it('keeps inlining a small image as a data: URI and never asks for a link', async () => {
    mockBodies(40_000, 'image/png');

    const { result } = renderHook(() => useInterfaceFileUrls({ photo: ref(ID, 'image/png', 40_000) }, true));

    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toMatch(/^data:image\/png;base64,/));
    expect(mockApiGet).not.toHaveBeenCalled();
  });

  it('streams a table media cell from its own mimeType, without downloading it first', async () => {
    const fetchMock = mockBodies(10);
    mockApiGet.mockResolvedValue({ url: SIGNED });
    const asset = { _type: 'file' as const, id: ID, url: RAW, name: 'clip.mp4', mimeType: 'video/mp4' };

    const { result } = renderHook(() => useInterfaceFileUrls({ clip: asset }, true));

    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toBe(SIGNED));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('streams a table cell that carries no type once its bytes show it is a video', async () => {
    mockBodies(3 * MB, 'video/mp4');
    mockApiGet.mockResolvedValue({ url: SIGNED });
    const asset = { _type: 'file' as const, id: ID, url: RAW, name: 'clip' };

    const { result } = renderHook(() => useInterfaceFileUrls({ clip: asset }, true));

    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toBe(SIGNED));
  });

  it('refuses a minted value that is not our own relative signed route (it is written into the iframe)', async () => {
    mockBodies(10, 'video/mp4');
    mockApiGet.mockResolvedValue({ url: 'https://attacker.example/x.mp4' });

    const { result } = renderHook(() => useInterfaceFileUrls({ clip: ref(ID, 'video/mp4', 18_370_402) }, true));

    await waitFor(() => expect(mockApiGet).toHaveBeenCalled());
    await waitFor(() => expect(result.current.resolveFileUrl(RAW)).toMatch(/^data:/));
    expect(result.current.resolveFileUrl(RAW)).not.toContain('attacker.example');
  });

  it('gives the budget back when a download fails, so the next file can still be inlined', async () => {
    let call = 0;
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => {
      call += 1;
      return call === 1
        ? Promise.reject(new Error('network'))
        : Promise.resolve({ ok: true, blob: () => Promise.resolve(new Blob([new Uint8Array(10)], { type: 'image/png' })) });
    }));
    // The failing file reserves almost the whole budget before its download fails. Only if that
    // reservation is released can the small file that comes after it be inlined.
    const { result } = renderHook(() => useInterfaceFileUrls({
      a: ref(idOf(1), 'image/png', INLINE_BUDGET_BYTES - 5),
    }, true));
    await waitFor(() => expect(call).toBe(1));

    const next = renderHook(() => useInterfaceFileUrls({ b: ref(idOf(2), 'image/png', 10) }, true));
    await waitFor(() => expect(next.result.current.resolveFileUrl(rawOf(idOf(2)))).toMatch(/^data:image\/png/));
    expect(result.current.resolveFileUrl(rawOf(idOf(1)))).toBe(rawOf(idOf(1)));
  });

  it('a refusal that is not a 503 does not stop the next file from asking for a link', async () => {
    mockBodies(10, 'video/mp4');
    mockApiGet.mockRejectedValueOnce(Object.assign(new Error('Not Found'), { status: 404 }))
      .mockResolvedValue({ url: SIGNED });

    renderHook(() => useInterfaceFileUrls({ a: ref(idOf(1), 'video/mp4', 18 * 1024 * 1024) }, true));
    await waitFor(() => expect(mockApiGet).toHaveBeenCalledTimes(1));
    const second = renderHook(() => useInterfaceFileUrls({ b: ref(idOf(2), 'video/mp4', 18 * 1024 * 1024) }, true));

    await waitFor(() => expect(second.result.current.resolveFileUrl(rawOf(idOf(2)))).toBe(SIGNED));
  });

  it('passes an already-signed marketplace link through untouched: never downloaded with the token', async () => {
    const fetchMock = mockBodies(10, 'video/mp4');
    const signedAsset = { _type: 'file' as const, url: SIGNED, name: 'clip.mp4', mimeType: 'video/mp4' };

    const { result } = renderHook(() => useInterfaceFileUrls({ clip: signedAsset }, true));

    await new Promise((r) => setTimeout(r, 30));
    expect(fetchMock).not.toHaveBeenCalled();
    expect(mockApiGet).not.toHaveBeenCalled();
    expect(result.current.resolveFileUrl(SIGNED)).toBe(SIGNED);
  });
});
