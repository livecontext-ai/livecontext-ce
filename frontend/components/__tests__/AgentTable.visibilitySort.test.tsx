// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor, fireEvent } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * AgentTable is server-paged (mirrors /data-sources/paged): the backend applies the visibility
 * filter + sort and inlines each row's publication badge into the page envelope
 * (`publicationStatuses`), so there is NO client-side fetch-all and NO separate getAllMyPublications
 * sweep. This pins:
 *  - the Globe/Lock marker comes straight from the page envelope (immediate, no gate);
 *  - the visibility filter + sort RE-QUERY the server (carry visibility / sort params);
 *  - a SINGLE server page is loaded (never a fetch-all loop);
 *  - the webhook/schedule badges still come from ONE /agents/triggers batch (no per-agent fan-out).
 *
 * Real PublicationStatusIcon, next-intl echoed to keys, native-<select> stand-in for Radix.
 */

const mocks = vi.hoisted(() => ({
  getAgentsPage: vi.fn(),
  getFleetTriggers: vi.fn(),
  // Kept so we can assert the OLD per-agent fan-out is gone (never called).
  getWebhook: vi.fn(),
  getSchedule: vi.fn(),
}));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/app/agent',
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { cloneAgent: vi.fn(), deleteAgent: vi.fn() } }));
vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: {
    getAgentsPage: mocks.getAgentsPage,
    getFleetTriggers: mocks.getFleetTriggers,
    getWebhook: mocks.getWebhook,
    getSchedule: mocks.getSchedule,
  },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { unpublishAgent: vi.fn() },
}));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/components/chat/CreateAgentModal', () => ({ CreateAgentModal: () => null }));
vi.mock('@/components/marketplace/PublishAgentModal', () => ({ default: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({ AgentPanelContent: () => null, AGENT_CONFIGURATION_TAB: 'config' }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/components/ui/EmptyState', () => ({ EmptyState: () => null }));
vi.mock('@/components/ui/CardSkeletonGrid', () => ({ CardSkeletonGrid: () => null }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/hooks/useSelectableItems', () => ({
  useSelectableItems: () => ({ selectedIds: new Set<string>(), toggle: vi.fn(), clear: vi.fn(), selectAll: vi.fn() }),
}));
// Dialog is only reached by the rejected branch (not exercised here); stub it so the real
// PublicationStatusIcon import resolves cleanly.
vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({ children }: any) => children,
  DialogContent: ({ children }: any) => children,
  DialogHeader: ({ children }: any) => children,
  DialogTitle: ({ children }: any) => children,
}));
vi.mock('@/components/ui/select', async () => {
  const ReactLib = await vi.importActual<typeof import('react')>('react');
  const collect = (children: any, acc: { ariaLabel?: string; options: Array<{ value: string; label: any }> }) => {
    ReactLib.Children.forEach(children, (child: any) => {
      if (!child || typeof child !== 'object') return;
      if (child.props?.['aria-label']) acc.ariaLabel = child.props['aria-label'];
      if (child.type?.__isSelectItem) acc.options.push({ value: child.props.value, label: child.props.children });
      if (child.props?.children) collect(child.props.children, acc);
    });
  };
  const Select = ({ value, onValueChange, children }: any) => {
    const acc: { ariaLabel?: string; options: Array<{ value: string; label: any }> } = { options: [] };
    collect(children, acc);
    return ReactLib.createElement(
      'select',
      { 'aria-label': acc.ariaLabel, value, onChange: (e: any) => onValueChange(e.target.value) },
      acc.options.map((o) => ReactLib.createElement('option', { key: o.value, value: o.value }, o.label)),
    );
  };
  const SelectTrigger = ({ children, 'aria-label': ariaLabel }: any) =>
    ReactLib.createElement('span', { 'aria-label': ariaLabel }, children);
  const SelectValue = () => null;
  const SelectContent = ({ children }: any) => children;
  const SelectItem: any = () => null;
  SelectItem.__isSelectItem = true;
  return { Select, SelectTrigger, SelectContent, SelectItem, SelectValue };
});

vi.mock('@/hooks/useResourceFavorites', () => ({
  useResourceFavorites: () => ({ favoriteIds: new Set(), toggleFavorite: vi.fn() }),
}));

import { AgentTable } from '../AgentTable';

const agent = (id: string, name: string, updatedAt = '2026-06-01T00:00:00Z') => ({
  id, name, tenantId: 't', isPublic: false, isActive: true, modelProvider: 'openai', modelName: 'gpt', updatedAt,
});
const page = (
  items: any[],
  publicationStatuses: Record<string, { status: string; rejectionReason?: string }> = {},
) => ({ items, totalCount: items.length, page: 0, size: 25, publicationStatuses });

/** Assert the named texts appear in the given DOM order. */
function expectOrder(...names: string[]) {
  const els = names.map((n) => screen.getByText(n));
  for (let i = 0; i < els.length - 1; i++) {
    expect(els[i].compareDocumentPosition(els[i + 1]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  }
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('AgentTable - Globe/Lock marker comes from the page envelope', () => {
  it('paints Globe (shared) / Lock (private) immediately, with no separate sweep', async () => {
    // Status arrives WITH the page (a1 = ACTIVE/shared, a2 absent = private) - no async sweep, no gate.
    mocks.getAgentsPage.mockResolvedValue(
      page([agent('a1', 'Shared Agent'), agent('a2', 'Private Agent')], { a1: { status: 'ACTIVE' } }),
    );
    mocks.getFleetTriggers.mockResolvedValue([]);

    render(<AgentTable />);
    await waitFor(() => expect(screen.getByText('Shared Agent')).toBeInTheDocument());

    // No gating: the markers are present as soon as the cards render.
    expect(screen.getByTitle('workflow.shared')).toBeInTheDocument();
    expect(screen.getByTitle('common.visibilityPrivate')).toBeInTheDocument();
  });
});

describe('AgentTable - visibility filter + sort re-query the server', () => {
  it('the visibility filter narrows via a server query carrying visibility=public/private', async () => {
    mocks.getAgentsPage.mockImplementation((opts: any = {}) => {
      if (opts.visibility === 'public') {
        return Promise.resolve(page([agent('a1', 'Shared Agent')], { a1: { status: 'ACTIVE' } }));
      }
      if (opts.visibility === 'private') {
        return Promise.resolve(page([agent('a2', 'Private Agent')]));
      }
      return Promise.resolve(page([agent('a1', 'Shared Agent'), agent('a2', 'Private Agent')], { a1: { status: 'ACTIVE' } }));
    });
    mocks.getFleetTriggers.mockResolvedValue([]);

    render(<AgentTable />);
    await waitFor(() => expect(screen.getByText('Shared Agent')).toBeInTheDocument());
    expect(screen.getByText('Private Agent')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('common.filterByVisibility'), { target: { value: 'public' } });
    await waitFor(() => expect(screen.queryByText('Private Agent')).not.toBeInTheDocument());
    expect(screen.getByText('Shared Agent')).toBeInTheDocument();
    expect(mocks.getAgentsPage).toHaveBeenCalledWith(expect.objectContaining({ visibility: 'public' }));

    fireEvent.change(screen.getByLabelText('common.filterByVisibility'), { target: { value: 'private' } });
    await waitFor(() => expect(screen.getByText('Private Agent')).toBeInTheDocument());
    expect(screen.queryByText('Shared Agent')).not.toBeInTheDocument();
    expect(mocks.getAgentsPage).toHaveBeenCalledWith(expect.objectContaining({ visibility: 'private' }));
  });

  it('changing the sort re-queries the server with the chosen sort key', async () => {
    mocks.getAgentsPage.mockResolvedValue(page([agent('a1', 'Alpha'), agent('a2', 'Beta')]));
    mocks.getFleetTriggers.mockResolvedValue([]);

    render(<AgentTable />);
    await waitFor(() => expect(screen.getByText('Alpha')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('common.sortBy'), { target: { value: 'name' } });
    await waitFor(() =>
      expect(mocks.getAgentsPage).toHaveBeenCalledWith(expect.objectContaining({ sort: 'name' })));
  });

  it('loads a SINGLE server page - never loops to fetch the whole list', async () => {
    // totalCount (50) far exceeds the returned page: the old client did a for(;;) loop until it had
    // all 50; the server-paged version must request page 0 ONCE and never page through the rest.
    mocks.getAgentsPage.mockResolvedValue({
      items: [agent('a1', 'Only One')], totalCount: 50, page: 0, size: 25, publicationStatuses: {},
    });
    mocks.getFleetTriggers.mockResolvedValue([]);

    render(<AgentTable />);
    await waitFor(() => expect(screen.getByText('Only One')).toBeInTheDocument());

    expect(mocks.getAgentsPage).toHaveBeenCalledTimes(1);
    expect(mocks.getAgentsPage).toHaveBeenCalledWith(expect.objectContaining({ page: 0, size: 25 }));
    expect(mocks.getAgentsPage).not.toHaveBeenCalledWith(expect.objectContaining({ page: 1 }));
  });
});

describe('AgentTable - sort select reorders the rendered agents', () => {
  it('switching from Last modified to Name reorders the cards alphabetically (server-sorted)', async () => {
    // The server applies the sort; mock returns the order the chosen key implies.
    mocks.getAgentsPage.mockImplementation((opts: any = {}) =>
      opts.sort === 'name'
        ? Promise.resolve(page([agent('a2', 'Ann'), agent('a1', 'Zed')]))
        : Promise.resolve(page([agent('a1', 'Zed'), agent('a2', 'Ann')])));
    mocks.getFleetTriggers.mockResolvedValue([]);

    render(<AgentTable />);
    await waitFor(() => expect(screen.getByText('Zed')).toBeInTheDocument());
    // Default lastModified: Zed before Ann.
    expectOrder('Zed', 'Ann');
    // Switch to Name -> server re-query returns alphabetical: Ann before Zed.
    fireEvent.change(screen.getByLabelText('common.sortBy'), { target: { value: 'name' } });
    await waitFor(() => expectOrder('Ann', 'Zed'));
  });
});

describe('AgentTable - trigger badges come from the /agents/triggers batch (no per-agent fan-out)', () => {
  it('renders webhook/schedule icons from getFleetTriggers in ONE call, never getWebhook/getSchedule per agent', async () => {
    mocks.getAgentsPage.mockResolvedValue(page([agent('a1', 'Hooked Agent'), agent('a2', 'Plain Agent')]));
    // ONE batch carries the whole workspace's triggers; a2 is absent → no badge. (The endpoint only
    // returns agents that HAVE a webhook/schedule.)
    mocks.getFleetTriggers.mockResolvedValue([
      { agentId: 'a1', hasWebhook: true, hasSchedule: true, cronExpression: '0 9 * * *', timezone: 'UTC' },
    ]);

    render(<AgentTable />);
    await waitFor(() => expect(screen.getByText('Hooked Agent')).toBeInTheDocument());

    // a1's webhook + schedule icons render straight from the batch.
    await waitFor(() => expect(screen.getByLabelText('Webhook')).toBeInTheDocument());
    expect(screen.getByLabelText('Schedule')).toBeInTheDocument();

    // Exactly ONE /agents/triggers call; the old per-agent getWebhook + getSchedule fan-out is gone.
    expect(mocks.getFleetTriggers).toHaveBeenCalledTimes(1);
    expect(mocks.getWebhook).not.toHaveBeenCalled();
    expect(mocks.getSchedule).not.toHaveBeenCalled();
  });
});
