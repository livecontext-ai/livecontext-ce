// @vitest-environment jsdom
/**
 * The Skills tab keeps its search in the address (`?q=`), so a reload keeps it. The hook only
 * does so when asked: a copy embedded in another surface must not write to the address of a
 * page it does not own, nor read a `q` that belongs to that page.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, cleanup } from '@testing-library/react';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getAllSkillFolders: vi.fn().mockResolvedValue([]),
    getSkills: vi.fn().mockResolvedValue([]),
  },
}));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));

import { useSkillExplorer } from '../useSkillExplorer';

beforeEach(() => {
  vi.useFakeTimers();
  fakeFolderRouter.reset('/en/app/agent');
});
afterEach(() => {
  vi.useRealTimers();
  cleanup();
});

describe('useSkillExplorer - the search and the address', () => {
  it('starts on the search the address carries when it owns the address', () => {
    fakeFolderRouter.navigate('/en/app/agent?view=skills&q=invoice');
    const { result } = renderHook(() => useSkillExplorer({ urlState: true }));
    expect(result.current.searchQuery).toBe('invoice');
  });

  it('writes the search once the typing pauses, keeping the tab', () => {
    fakeFolderRouter.navigate('/en/app/agent?view=skills');
    const { result } = renderHook(() => useSkillExplorer({ urlState: true }));

    act(() => result.current.setSearchQuery('lead'));
    expect(result.current.searchQuery).toBe('lead');
    expect(fakeFolderRouter.search()).toBe('view=skills');
    act(() => { vi.advanceTimersByTime(350); });

    expect(fakeFolderRouter.search()).toBe('view=skills&q=lead');
  });

  it('neither reads nor writes the address by default', () => {
    fakeFolderRouter.navigate('/en/app/workflow?q=invoice');
    const { result } = renderHook(() => useSkillExplorer());
    expect(result.current.searchQuery).toBe('');

    act(() => result.current.setSearchQuery('lead'));
    act(() => { vi.advanceTimersByTime(350); });

    expect(result.current.searchQuery).toBe('lead');
    expect(fakeFolderRouter.search()).toBe('q=invoice');
  });
});
