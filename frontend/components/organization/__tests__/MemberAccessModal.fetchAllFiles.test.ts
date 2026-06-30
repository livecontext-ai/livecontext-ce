import { describe, it, expect, vi, beforeEach } from 'vitest';

// Member access management must reach EVERY file/workflow (not just the first 100),
// so the modal pages through the API. Mock the clients to drive the paging loops.
const { getExplorerEntries, getWorkflowsPage } = vi.hoisted(() => ({
  getExplorerEntries: vi.fn(),
  getWorkflowsPage: vi.fn(),
}));
vi.mock('@/lib/api/storage-api', () => ({ storageApi: { getExplorerEntries }, S3_FILES_FILTER: { filesOnly: true, s3Only: true } }));
vi.mock('@/lib/api/orchestrator', () => ({ orchestratorApi: { getWorkflowsPage } }));

import { fetchAllFiles, fetchAllWorkflows } from '../MemberAccessModal';

function page(ids: number[], totalPages: number) {
  return {
    content: ids.map((n) => ({ id: `f${n}`, fileName: `file-${n}.txt`, contentType: 'text/plain' })),
    totalPages,
    totalElements: totalPages * 100,
  };
}

describe('MemberAccessModal.fetchAllFiles - full file sweep (past the 100-row cap)', () => {
  beforeEach(() => getExplorerEntries.mockReset());

  it('pages through every file across multiple pages', async () => {
    getExplorerEntries
      .mockResolvedValueOnce(page(Array.from({ length: 100 }, (_, i) => i), 2)) // page 0: full
      .mockResolvedValueOnce(page([100, 101, 102], 2)); // page 1: partial → stop
    const files = await fetchAllFiles();
    expect(files).toHaveLength(103);
    expect(files[0]).toEqual({ id: 'f0', name: 'file-0.txt' });
    expect(getExplorerEntries).toHaveBeenCalledTimes(2);
    // s3Only hides the observability TEXT blobs (tool_call_result.txt, …) - same as the Files page.
    expect(getExplorerEntries).toHaveBeenNthCalledWith(1, { page: 0, size: 100, filesOnly: true, s3Only: true });
    expect(getExplorerEntries).toHaveBeenNthCalledWith(2, { page: 1, size: 100, filesOnly: true, s3Only: true });
  });

  it('stops after a single partial page (fewer than a full page returned)', async () => {
    getExplorerEntries.mockResolvedValueOnce(page([1, 2, 3], 1));
    const files = await fetchAllFiles();
    expect(files).toHaveLength(3);
    expect(getExplorerEntries).toHaveBeenCalledTimes(1);
  });

  it('stops at the last page even when a full page is returned (page+1 >= totalPages)', async () => {
    getExplorerEntries.mockResolvedValueOnce(page(Array.from({ length: 100 }, (_, i) => i), 1));
    const files = await fetchAllFiles();
    expect(files).toHaveLength(100);
    expect(getExplorerEntries).toHaveBeenCalledTimes(1);
  });
});

function wfPage(ids: number[], totalCount: number) {
  return { workflows: ids.map((n) => ({ id: `w${n}`, name: `wf-${n}` })), count: ids.length, totalCount, page: 0, size: 100 };
}

describe('MemberAccessModal.fetchAllWorkflows - full workflow sweep (so Block all covers all)', () => {
  beforeEach(() => getWorkflowsPage.mockReset());

  it('pages through every workflow so "Block all" is not capped at 100', async () => {
    getWorkflowsPage
      .mockResolvedValueOnce(wfPage(Array.from({ length: 100 }, (_, i) => i), 130))
      .mockResolvedValueOnce(wfPage([100, 101, 102], 130)); // out.length 103 >= totalCount? no → but partial page stops
    const wf = await fetchAllWorkflows();
    expect(wf).toHaveLength(103);
    expect(wf[0]).toEqual({ id: 'w0', name: 'wf-0' });
    expect(getWorkflowsPage).toHaveBeenNthCalledWith(1, { page: 0, size: 100 });
    expect(getWorkflowsPage).toHaveBeenNthCalledWith(2, { page: 1, size: 100 });
  });

  it('stops once the accumulated count reaches totalCount', async () => {
    getWorkflowsPage
      .mockResolvedValueOnce(wfPage(Array.from({ length: 100 }, (_, i) => i), 100));
    const wf = await fetchAllWorkflows();
    expect(wf).toHaveLength(100);
    expect(getWorkflowsPage).toHaveBeenCalledTimes(1);
  });
});
