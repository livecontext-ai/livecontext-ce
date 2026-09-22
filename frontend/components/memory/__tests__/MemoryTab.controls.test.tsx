// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * The Memory tab's header and row actions ARE the app's controls, asserted on
 * what is rendered rather than on what the source says.
 *
 * <p>Its sibling `memoryControlsAreTheDesignSystems.test.ts` can only prove an
 * absence: no hand-rolled `button`, no native `select`. That leaves the positive
 * half of the rule unguarded - a filter row rewritten as `Button` elements
 * carrying their own `bg-theme-secondary` pill classes would satisfy every one
 * of those checks while re-creating exactly the second button dialect the rule
 * exists to stop. `Button` stamps `data-variant` and `data-size` on the element,
 * so the variant a control was given is readable here, and that is the thing the
 * README's rule is actually about: `default` for the chosen filter, `outline`
 * for the rest.
 *
 * <p>The clear button is checked for the same reason: it is the one new
 * affordance the restyle ADDED, so it is the one piece of it that a source-text
 * scan cannot see working.
 */

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  search: vi.fn(),
}));

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

vi.mock('@/lib/api/orchestrator/memory.service', () => ({
  memoryService: {
    list: mocks.list,
    search: mocks.search,
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
  },
}));

// The workspace keying of this query is the subject of
// `MemoryTab.workspaceScope.test.tsx`, which uses the real hook to prove it.
// Here it is only a source of rows, so it is stubbed outright.
vi.mock('@/lib/hooks/useOrgScopedQuery', () => ({
  useOrgScopedQuery: (options: { queryFn: () => Promise<unknown>; enabled?: boolean }) => {
    const [data, setData] = React.useState<unknown>(undefined);
    const enabled = options.enabled !== false;
    React.useEffect(() => {
      if (!enabled) return;
      let cancelled = false;
      options.queryFn().then((d) => { if (!cancelled) setData(d); }, () => {});
      return () => { cancelled = true; };
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [enabled]);
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

const row = {
  id: 'm1',
  slug: 'release-cadence',
  title: 'Release cadence',
  summary: 'The team ships on Thursdays.',
  content: '',
  type: 'project',
  tags: [],
  pinned: false,
  source: 'agent',
  agentId: null,
  scope: 'workspace',
  isActive: true,
  recallCount: 3,
  lastRecalledAt: null,
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-05T00:00:00Z',
};

async function renderTab() {
  mocks.list.mockResolvedValue([row]);
  mocks.search.mockResolvedValue([]);
  render(<MemoryTab />);
  await waitFor(() => expect(screen.getByText('Release cadence')).toBeInTheDocument());
}

afterEach(cleanup);

describe('MemoryTab header and row controls', () => {
  it('draws the type filters as a segmented Button row, filled on the chosen one', async () => {
    await renderTab();

    const filters = screen.getByRole('group', { name: 'filterByType' });
    const all = screen.getByRole('button', { name: 'filterAll' });
    const project = screen.getByRole('button', { name: 'type.project' });

    expect(filters).toContainElement(all);
    // The README's rule, and the agenda's view switch: the chosen option is the
    // app's one solid fill and the others are outlined. A private pill class is
    // what makes one screen disagree with the rest of the product about its own
    // radius and hover.
    expect(all).toHaveAttribute('data-variant', 'default');
    expect(all).toHaveAttribute('aria-pressed', 'true');
    expect(project).toHaveAttribute('data-variant', 'outline');
    expect(project).toHaveAttribute('aria-pressed', 'false');
  });

  it('moves the fill to the filter that was clicked', async () => {
    await renderTab();

    fireEvent.click(screen.getByRole('button', { name: 'type.project' }));

    expect(screen.getByRole('button', { name: 'type.project' })).toHaveAttribute('data-variant', 'default');
    expect(screen.getByRole('button', { name: 'filterAll' })).toHaveAttribute('data-variant', 'outline');
  });

  it('gives every row action the ghost icon Button, not a bare element', async () => {
    await renderTab();

    // Hand-written, these had no focus ring and no disabled handling, and their
    // radius sat one rung off the card they are on.
    for (const label of ['pinAction', 'deactivateAction', 'editAction', 'deleteAction']) {
      const action = screen.getByLabelText(label);
      expect(action, label).toHaveAttribute('data-variant', 'ghost');
      expect(action, label).toHaveAttribute('data-size', 'icon');
    }
  });

  it('clears the search box from the field itself', async () => {
    await renderTab();
    const field = screen.getByPlaceholderText('searchPlaceholder');

    fireEvent.change(field, { target: { value: 'Kelpwood' } });
    expect(field).toHaveValue('Kelpwood');

    // The clear button only exists while there is something to clear, which is
    // why it is asserted after typing and not before.
    fireEvent.click(screen.getByRole('button', { name: 'clearSearch' }));

    expect(field).toHaveValue('');
  });
});
