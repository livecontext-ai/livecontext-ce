// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock the auth/token + org-header deps so uploadGeneric runs without a real session.
const { getTokenProviderMock } = vi.hoisted(() => ({
  getTokenProviderMock: vi.fn(() => async () => 'test-token'),
}));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { getTokenProvider: getTokenProviderMock } }));
vi.mock('@/lib/stores/current-org-store', () => ({ getActiveOrgHeaderForRequest: () => ({}) }));

import { fileService } from '../file.service';

/**
 * Pins the frontend↔backend contract for V313 folder-aware upload: uploadGeneric
 * must put the current folder under the `parentFolderId` multipart field (the name
 * the backend FileController/MonolithFileController read), and omit it at root.
 */
describe('fileService.uploadGeneric - folder-aware upload (V313)', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: '1' }) });
    vi.stubGlobal('fetch', fetchMock);
  });

  const sentForm = (): FormData => fetchMock.mock.calls.at(-1)![1].body as FormData;
  const file = new File(['x'], 'f.txt', { type: 'text/plain' });

  it('appends parentFolderId when uploading into a folder', async () => {
    await fileService.uploadGeneric(file, 'files', 'folder-1');
    expect(sentForm().get('parentFolderId')).toBe('folder-1');
  });

  it('omits parentFolderId at the root (null)', async () => {
    await fileService.uploadGeneric(file, 'files', null);
    expect(sentForm().get('parentFolderId')).toBeNull();
  });

  it('omits parentFolderId when not provided (legacy callers)', async () => {
    await fileService.uploadGeneric(file, 'files');
    expect(sentForm().get('parentFolderId')).toBeNull();
  });
});
