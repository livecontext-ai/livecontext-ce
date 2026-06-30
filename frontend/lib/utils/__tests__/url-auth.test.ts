// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { isInternalUrl, fetchAuthedBlobUrl, openAuthedFileInNewTab, downloadAuthedFile } from '../url-auth';

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { getTokenProvider: vi.fn() },
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: vi.fn(() => ({ 'X-Active-Organization-ID': 'org-7' })),
}));

import { apiClient } from '@/lib/api/api-client';
const mockGetTokenProvider = vi.mocked(apiClient.getTokenProvider);

beforeEach(() => {
  vi.resetAllMocks();
  mockGetTokenProvider.mockReturnValue(() => Promise.resolve('jwt-abc'));
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
    mockGetTokenProvider.mockReturnValue(null);
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
