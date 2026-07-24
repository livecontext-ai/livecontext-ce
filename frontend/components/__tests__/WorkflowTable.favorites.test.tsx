// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor, fireEvent } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * End-to-end favorites wiring on the workflow list (the REAL useResourceFavorites hook +
 * favoritesFirst + the card star), with only favoriteService + the org store stubbed:
 *  - favorited workflows float to the top of the list (ahead of the default sort);
 *  - the per-card star reflects the favorited state and toggles it through the service.
 *
 * next-intl is echoed to keys, so the star's localized aria-label is the bare key
 * (addToFavorites / removeFromFavorites).
 */

const mocks = vi.hoisted(() => ({ getWorkflowsPage: vi.fn() }));
const favs = vi.hoisted(() => ({ getFavoriteIds: vi.fn(), addFavorite: vi.fn(), removeFavorite: vi.fn() }));

vi.mock('next-intl', () => ({
  // .raw mirrors the real next-intl API: template copy is read verbatim through it
  // so workflow expressions like {{item}} are not parsed as ICU arguments.
  useTranslations: () => Object.assign((key: string) => key, { raw: (key: string) => key }),
}));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: () => undefined }) }));
vi.mock('@/lib/api', () => ({ orchestratorApi: mocks }));
vi.mock('@/lib/api/orchestrator/favorite.service', () => ({ favoriteService: favs }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/Toast', () => ({
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/components/chat/CreateWorkflowModal', () => ({ CreateWorkflowModal: () => null }));
vi.mock('@/components/ui/BulkDeleteModal', () => ({ BulkDeleteModal: () => null }));
vi.mock('@/components/ui/EmptyState', () => ({ EmptyState: () => null }));
vi.mock('@/components/ui/CardSkeletonGrid', () => ({ CardSkeletonGrid: () => null }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/hooks/useSelectableItems', () => ({
  useSelectableItems: () => ({ selectedIds: new Set<string>(), toggle: vi.fn(), clear: vi.fn(), selectAll: vi.fn() }),
}));
// The real useResourceFavorites hook needs BOTH exports of the org store.
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => true,
  useCurrentOrgStore: (sel: any) => sel({ currentOrgId: null }),
  // Consumed by the TemplateGallery banner, which scopes its collapsed pref per workspace.
  useCurrentOrg: () => ({ currentOrgId: null }),
}));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: any) => children,
  SelectTrigger: ({ children }: any) => children,
  SelectValue: () => null,
  SelectContent: ({ children }: any) => children,
  SelectItem: () => null,
}));

import WorkflowTable from '../WorkflowTable';

const wf = (over: Record<string, unknown>) => ({
  id: 'id', name: 'WF', updatedAt: '2026-06-01T00:00:00Z', isPublished: false, ...over,
});

function expectOrder(...names: string[]) {
  const els = names.map((n) => screen.getByText(n));
  for (let i = 0; i < els.length - 1; i++) {
    expect(els[i].compareDocumentPosition(els[i + 1]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  }
}

beforeEach(() => {
  favs.addFavorite.mockResolvedValue(undefined);
  favs.removeFavorite.mockResolvedValue(undefined);
  // Alpha is newest (leads the default lastModified sort); Bravo is older.
  mocks.getWorkflowsPage.mockResolvedValue({
    workflows: [
      wf({ id: 'a', name: 'Alpha', updatedAt: '2026-06-18T00:00:00Z' }),
      wf({ id: 'b', name: 'Bravo', updatedAt: '2026-06-10T00:00:00Z' }),
    ],
    count: 2, totalCount: 2, page: 0, size: 100,
  });
});

afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('WorkflowTable favorites', () => {
  it('floats a favorited workflow to the top, ahead of the default sort', async () => {
    favs.getFavoriteIds.mockResolvedValue(['b']); // Bravo favorited
    render(<WorkflowTable />);
    await waitFor(() => expect(screen.getByText('Bravo')).toBeInTheDocument());
    // Default sort would put Alpha (06-18) first, but the favorite floats Bravo to the top.
    await waitFor(() => expectOrder('Bravo', 'Alpha'));
    expect(favs.getFavoriteIds).toHaveBeenCalledWith('WORKFLOW');
  });

  it('paints a filled star on the favorited card and an empty star on the rest', async () => {
    favs.getFavoriteIds.mockResolvedValue(['b']);
    render(<WorkflowTable />);
    await waitFor(() => expect(screen.getByText('Bravo')).toBeInTheDocument());
    // Bravo (favorited) → remove label; Alpha (not) → add label.
    await waitFor(() => expect(screen.getByLabelText('removeFromFavorites')).toBeInTheDocument());
    expect(screen.getByLabelText('addToFavorites')).toBeInTheDocument();
  });

  it('clicking an empty star favorites that workflow through the service', async () => {
    favs.getFavoriteIds.mockResolvedValue(['b']);
    render(<WorkflowTable />);
    await waitFor(() => expect(screen.getByLabelText('addToFavorites')).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText('addToFavorites')); // Alpha's star
    await waitFor(() => expect(favs.addFavorite).toHaveBeenCalledWith('WORKFLOW', 'a'));
    expect(favs.removeFavorite).not.toHaveBeenCalled();
  });

  it('clicking a filled star unfavorites that workflow through the service', async () => {
    favs.getFavoriteIds.mockResolvedValue(['b']);
    render(<WorkflowTable />);
    await waitFor(() => expect(screen.getByLabelText('removeFromFavorites')).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText('removeFromFavorites')); // Bravo's star
    await waitFor(() => expect(favs.removeFavorite).toHaveBeenCalledWith('WORKFLOW', 'b'));
  });
});
