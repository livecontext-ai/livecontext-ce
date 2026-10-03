// @vitest-environment jsdom
/**
 * The Memory tab's search and type filter live in the address, so a reload reopens the list as
 * it was. Pinned both ways: an address carrying them starts filtered and runs the server search
 * for the restored text, and a change writes the address.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  search: vi.fn(),
}));

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/lib/api/orchestrator/memory.service', () => ({
  memoryService: {
    list: mocks.list, search: mocks.search,
    get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(),
  },
}));
// Only a source of rows here; the workspace keying has its own suite with the real hook.
vi.mock('@/lib/hooks/useOrgScopedQuery', () => ({
  useOrgScopedQuery: (options: { queryKey: unknown[]; queryFn: () => Promise<unknown>; enabled?: boolean }) => {
    const [data, setData] = React.useState<unknown>(undefined);
    const enabled = options.enabled !== false;
    const key = JSON.stringify(options.queryKey);
    React.useEffect(() => {
      if (!enabled) return;
      let cancelled = false;
      options.queryFn().then((d) => { if (!cancelled) setData(d); }, () => {});
      return () => { cancelled = true; };
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [enabled, key]);
    return { data, isError: false, isFetching: false, isLoading: false, refetch: vi.fn() };
  },
}));
vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => true,
  useCurrentOrgStore: (selector: (s: { currentOrgId: string | null }) => unknown) =>
    selector({ currentOrgId: 'org-alpha' }),
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <div>loading</div> }));
vi.mock('@/components/Toast', () => ({
  __esModule: true,
  default: () => null,
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));
vi.mock('@/components/memory/MemoryEditorModal', () => ({ MemoryEditorModal: () => null }));
vi.mock('@/lib/utils/dateFormatters', () => ({ formatUtcDate: () => '2026-09-06' }));

import { MemoryTab } from '@/components/MemoryTab';

const row = (id: string, title: string, type: string) => ({
  id, slug: id, title, summary: '', content: '', type, tags: [], pinned: false, source: 'agent',
  agentId: null, scope: 'workspace', isActive: true, recallCount: 0, lastRecalledAt: null,
  createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-05T00:00:00Z',
});

beforeEach(() => {
  fakeFolderRouter.reset('/en/app/agent');
  mocks.list.mockReset().mockResolvedValue([
    row('m1', 'Release cadence', 'project'),
    row('m2', 'Prefers short answers', 'feedback'),
  ]);
  mocks.search.mockReset().mockResolvedValue([row('m1', 'Release cadence', 'project')]);
});
afterEach(() => {
  vi.useRealTimers();
  cleanup();
});

describe('MemoryTab - search and type filter live in the address', () => {
  it('opens on the type filter the address carries', async () => {
    fakeFolderRouter.navigate('/en/app/agent?view=memory&type=feedback');
    render(<MemoryTab />);

    await waitFor(() => expect(screen.getByText('Prefers short answers')).toBeInTheDocument());
    expect(screen.queryByText('Release cadence')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'type.feedback' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('opens on the search the address carries and asks the server for it', async () => {
    fakeFolderRouter.navigate('/en/app/agent?view=memory&q=cadence');
    render(<MemoryTab />);

    await waitFor(() => expect(mocks.search).toHaveBeenCalledWith('cadence'));
    expect(screen.getByRole('textbox')).toHaveValue('cadence');
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());
    expect(screen.queryByText('Prefers short answers')).not.toBeInTheDocument();
  });

  it('falls back to every type on one that does not exist', async () => {
    fakeFolderRouter.navigate('/en/app/agent?view=memory&type=nope');
    render(<MemoryTab />);

    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'filterAll' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('writes the type filter, keeping the tab, and removes it on "all"', async () => {
    fakeFolderRouter.navigate('/en/app/agent?view=memory');
    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'type.project' }));
    expect(fakeFolderRouter.search()).toBe('view=memory&type=project');
    expect(fakeFolderRouter.navigations.at(-1)?.method).toBe('replace');

    fireEvent.click(screen.getByRole('button', { name: 'filterAll' }));
    expect(fakeFolderRouter.search()).toBe('view=memory');
  });

  it('writes the search once the typing pauses', async () => {
    fakeFolderRouter.navigate('/en/app/agent?view=memory');
    render(<MemoryTab />);
    await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());

    vi.useFakeTimers();
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'cadence' } });
    expect(fakeFolderRouter.search()).toBe('view=memory');
    act(() => { vi.advanceTimersByTime(350); });

    expect(fakeFolderRouter.search()).toBe('view=memory&q=cadence');
  });
});
