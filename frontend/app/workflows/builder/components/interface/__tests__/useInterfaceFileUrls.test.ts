// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useInterfaceFileUrls } from '../useInterfaceFileUrls';

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { getTokenProvider: vi.fn() },
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: vi.fn(() => ({ 'X-Active-Organization-ID': 'org-7' })),
}));

import { apiClient } from '@/lib/api/api-client';
const mockGetTokenProvider = vi.mocked(apiClient.getTokenProvider);

const ID = '9a443915-a594-48a1-9760-e7a1b4b2eaf7';
const RAW = `/api/proxy/files/by-id/${ID}/raw?disposition=inline`;

function fileRef() {
  return { _type: 'file' as const, path: 'tenant1/run/abc.png', name: 'abc.png', mimeType: 'image/png', size: 3, id: ID };
}

beforeEach(() => {
  vi.resetAllMocks();
  mockGetTokenProvider.mockReturnValue(() => Promise.resolve('jwt-abc'));
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
});
