import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock the shared HTTP client so we can assert exactly what query params are sent.
// vi.hoisted lets the mock fn exist before the hoisted vi.mock factory runs.
const { getMock } = vi.hoisted(() => ({ getMock: vi.fn() }));
vi.mock('../api-client', () => ({ apiClient: { get: getMock } }));

import { storageApi, S3_FILES_FILTER } from '../storage-api';

describe('storageApi.getExplorerEntries - server-side file-type filter', () => {
  beforeEach(() => {
    getMock.mockReset();
    getMock.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 });
  });

  function lastParams(): Record<string, string> {
    const call = getMock.mock.calls.at(-1);
    return (call?.[1]?.params ?? {}) as Record<string, string>;
  }

  it('sends fileType to the server when a category is selected (filters the full DB, not the page)', async () => {
    await storageApi.getExplorerEntries({ fileType: 'images', filesOnly: true });
    expect(getMock).toHaveBeenCalledWith('/storage/explorer', expect.anything());
    expect(lastParams().fileType).toBe('images');
  });

  it('omits fileType for the "_all" sentinel (no filter)', async () => {
    await storageApi.getExplorerEntries({ fileType: '_all' });
    expect(lastParams().fileType).toBeUndefined();
  });

  it('omits fileType when unset', async () => {
    await storageApi.getExplorerEntries({ filesOnly: true });
    expect(lastParams().fileType).toBeUndefined();
  });
});

describe('storageApi.getExplorerEntries - s3Only filter (Files page shows only object-storage files)', () => {
  beforeEach(() => {
    getMock.mockReset();
    getMock.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 });
  });

  function lastParams(): Record<string, string> {
    const call = getMock.mock.calls.at(-1);
    return (call?.[1]?.params ?? {}) as Record<string, string>;
  }

  it('sends s3Only=true when the Files page restricts to real S3 files', async () => {
    await storageApi.getExplorerEntries({ filesOnly: true, s3Only: true });
    expect(lastParams().s3Only).toBe('true');
  });

  it('omits s3Only when unset - other surfaces keep DB-resident rows', async () => {
    await storageApi.getExplorerEntries({ filesOnly: true });
    expect(lastParams().s3Only).toBeUndefined();
  });

  it('omits s3Only when explicitly false', async () => {
    await storageApi.getExplorerEntries({ s3Only: false });
    expect(lastParams().s3Only).toBeUndefined();
  });
});

describe('S3_FILES_FILTER - single source of truth for file-selection surfaces', () => {
  beforeEach(() => {
    getMock.mockReset();
    getMock.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 });
  });

  function lastParams(): Record<string, string> {
    const call = getMock.mock.calls.at(-1);
    return (call?.[1]?.params ?? {}) as Record<string, string>;
  }

  it('is exactly { filesOnly: true, s3Only: true } - the Files-page (app/file) file set', () => {
    expect(S3_FILES_FILTER).toEqual({ filesOnly: true, s3Only: true });
  });

  it('spread into a request sends both filesOnly=true and s3Only=true to the server', async () => {
    await storageApi.getExplorerEntries({ size: 100, ...S3_FILES_FILTER });
    expect(lastParams().filesOnly).toBe('true');
    expect(lastParams().s3Only).toBe('true');
  });
});
