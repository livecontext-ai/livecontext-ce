// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Feature #2 - the workflow list must surface a workflow's PUBLICATION moderation
 * state, not just the approved "shared" Globe. A workflow whose publication is
 * PENDING_REVIEW shows an orange "in review" chip; REJECTED shows a red chip;
 * ACTIVE keeps the plain Globe; a never-shared workflow shows none.
 *
 * next-intl is mocked to echo keys, so the visible text is the i18n key
 * (t('workflow.inReview') -> 'workflow.inReview', etc.).
 */

const mocks = vi.hoisted(() => ({
  getWorkflowsPage: vi.fn(),
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: () => undefined }) }));
vi.mock('@/lib/api', () => ({ orchestratorApi: mocks }));
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
  useSelectableItems: () => ({
    selectedIds: new Set<string>(),
    toggle: vi.fn(),
    clear: vi.fn(),
    selectAll: vi.fn(),
  }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));

vi.mock('@/hooks/useResourceFavorites', () => ({
  useResourceFavorites: () => ({ favoriteIds: new Set(), toggleFavorite: vi.fn() }),
}));

import WorkflowTable from '../WorkflowTable';

const wf = (over: Record<string, unknown>) => ({
  id: 'id',
  name: 'WF',
  updatedAt: '2026-06-01T00:00:00Z',
  isPublished: false,
  ...over,
});

describe('WorkflowTable - publication moderation badge', () => {
  beforeEach(() => {
    mocks.getWorkflowsPage.mockResolvedValue({
      workflows: [
        wf({ id: 'w1', name: 'Pending WF', publicationStatus: 'PENDING_REVIEW', isPublished: false }),
        wf({ id: 'w2', name: 'Active WF', publicationStatus: 'ACTIVE', isPublished: true }),
        wf({ id: 'w3', name: 'Rejected WF', publicationStatus: 'REJECTED', isPublished: false }),
        wf({ id: 'w4', name: 'Plain WF' }),
      ],
      count: 4,
      totalCount: 4,
      page: 0,
      size: 25,
    });
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  // Regression: the page header (title + description) used to be HIDDEN when the list was empty,
  // leaving a bare centered empty state with no title. It must now stay visible even with zero
  // workflows - same layout as the Applications page (title + subtitle above the empty state).
  it('keeps the title + description header visible when the workflow list is empty', async () => {
    mocks.getWorkflowsPage.mockReset();
    mocks.getWorkflowsPage.mockResolvedValue({
      workflows: [], count: 0, totalCount: 0, page: 0, size: 25,
    });

    const { container } = render(<WorkflowTable />);

    // i18n is echoed → the header renders the title + the new description key.
    await waitFor(() => expect(screen.getByRole('heading', { name: 'workflow.title' })).toBeInTheDocument());
    expect(container.textContent).toContain('workflow.subtitle');
  });

  it('renders an orange "in review" chip for PENDING_REVIEW, red for REJECTED, Globe for ACTIVE', async () => {
    const { container } = render(<WorkflowTable />);

    await waitFor(() => expect(container.textContent).toContain('Pending WF'));

    // PENDING_REVIEW → "in review" chip; REJECTED → "rejected" chip.
    expect(container.textContent).toContain('workflow.inReview');
    expect(container.textContent).toContain('workflow.rejected');

    // ACTIVE (isPublished) → the plain shared Globe (title === 'workflow.shared',
    // distinct from the 'workflow.sharedInReview' / 'workflow.sharedRejected'
    // tooltips on the other two chips). Exactly one.
    expect(screen.getAllByTitle('workflow.shared')).toHaveLength(1);

    // The in-review / rejected chips do NOT also render the approved Globe.
    expect(screen.queryByTitle('workflow.sharedInReview')).toBeInTheDocument();
    expect(screen.queryByTitle('workflow.sharedRejected')).toBeInTheDocument();
  });
});
