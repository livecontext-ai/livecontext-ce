// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor, fireEvent } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * InterfaceTable is server-paged (mirrors /data-sources/paged): the backend applies the visibility
 * filter + sort and inlines each row's publication badge into the page envelope
 * (`publicationStatuses`), so there is NO client-side fetch-all and NO separate getAllMyPublications
 * sweep. This pins:
 *  - the Globe/Lock marker comes straight from the page envelope (immediate, no gate);
 *  - the visibility filter + sort RE-QUERY the server (carry visibility / sort params);
 *  - the html view still pulls each visible card's heavy template lazily via getInterface;
 *  - the web_search view keeps templates+data inline (never calls getInterface).
 *
 * Real PublicationStatusIcon, next-intl echoed to keys, native-<select> stand-in for Radix.
 */

const mocks = vi.hoisted(() => ({
  getInterfacesPage: vi.fn(),
  getInterface: vi.fn(),
}));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/api', () => ({ orchestratorApi: { cloneInterface: vi.fn(), deleteInterface: vi.fn() } }));
vi.mock('@/lib/api/orchestrator/interface.service', () => ({
  interfaceService: { getInterfacesPage: mocks.getInterfacesPage, getInterface: mocks.getInterface },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { unpublishResource: vi.fn() },
}));
vi.mock('@/app/workflows/builder/components/interface/InterfaceThumbnail', () => ({ InterfaceThumbnail: () => null }));
vi.mock('@/components/chat/CreateInterfaceModal', () => ({ CreateInterfaceModal: () => null }));
vi.mock('@/components/marketplace/PublishResourceModal', () => ({ default: () => null }));
vi.mock('@/components/ui/EmptyState', () => ({ EmptyState: () => null }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/hooks/useSelectableItems', () => ({
  useSelectableItems: () => ({ selectedIds: new Set<string>(), toggle: vi.fn(), clear: vi.fn(), selectAll: vi.fn() }),
}));
vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({ children }: any) => children, DialogContent: ({ children }: any) => children,
  DialogHeader: ({ children }: any) => children, DialogTitle: ({ children }: any) => children,
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
    return ReactLib.createElement('select',
      { 'aria-label': acc.ariaLabel, value, onChange: (e: any) => onValueChange(e.target.value) },
      acc.options.map((o) => ReactLib.createElement('option', { key: o.value, value: o.value }, o.label)));
  };
  const SelectTrigger = ({ children, 'aria-label': ariaLabel }: any) => ReactLib.createElement('span', { 'aria-label': ariaLabel }, children);
  const SelectValue = () => null;
  const SelectContent = ({ children }: any) => children;
  const SelectItem: any = () => null;
  SelectItem.__isSelectItem = true;
  return { Select, SelectTrigger, SelectContent, SelectItem, SelectValue };
});

vi.mock('@/hooks/useResourceFavorites', () => ({
  useResourceFavorites: () => ({ favoriteIds: new Set(), toggleFavorite: vi.fn() }),
}));

import { InterfaceTable } from '../InterfaceTable';

const intf = (id: string, name: string) => ({ id, name, tenantId: 't', isPublic: false, isActive: true, updatedAt: '2026-06-01T00:00:00Z' });
const page = (
  items: any[],
  publicationStatuses: Record<string, { status: string; rejectionReason?: string }> = {},
) => ({ items, totalCount: items.length, page: 0, size: 25, publicationStatuses });

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('InterfaceTable - Globe/Lock marker comes from the page envelope', () => {
  it('paints Globe (shared) / Lock (private) immediately, with no separate sweep', async () => {
    // Status arrives WITH the page (i1 = ACTIVE/shared, i2 absent = private) - no async sweep, no gate.
    mocks.getInterfacesPage.mockResolvedValue(
      page([intf('i1', 'Shared Interface'), intf('i2', 'Private Interface')], { i1: { status: 'ACTIVE' } }),
    );

    render(<InterfaceTable />);
    await waitFor(() => expect(screen.getByText('Shared Interface')).toBeInTheDocument());

    // No gating: the markers are present as soon as the cards render.
    expect(screen.getByTitle('workflow.shared')).toBeInTheDocument();
    expect(screen.getByTitle('common.visibilityPrivate')).toBeInTheDocument();
  });
});

describe('InterfaceTable - visibility filter + sort re-query the server', () => {
  it('the visibility filter narrows via a server query carrying visibility=public/private', async () => {
    mocks.getInterfacesPage.mockImplementation((opts: any = {}) => {
      if (opts.visibility === 'public') {
        return Promise.resolve(page([intf('i1', 'Shared Interface')], { i1: { status: 'ACTIVE' } }));
      }
      if (opts.visibility === 'private') {
        return Promise.resolve(page([intf('i2', 'Private Interface')]));
      }
      return Promise.resolve(page([intf('i1', 'Shared Interface'), intf('i2', 'Private Interface')], { i1: { status: 'ACTIVE' } }));
    });

    render(<InterfaceTable />);
    await waitFor(() => expect(screen.getByText('Shared Interface')).toBeInTheDocument());
    expect(screen.getByText('Private Interface')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('common.filterByVisibility'), { target: { value: 'public' } });
    await waitFor(() => expect(screen.queryByText('Private Interface')).not.toBeInTheDocument());
    expect(screen.getByText('Shared Interface')).toBeInTheDocument();
    expect(mocks.getInterfacesPage).toHaveBeenCalledWith(expect.objectContaining({ visibility: 'public' }));

    fireEvent.change(screen.getByLabelText('common.filterByVisibility'), { target: { value: 'private' } });
    await waitFor(() => expect(screen.getByText('Private Interface')).toBeInTheDocument());
    expect(screen.queryByText('Shared Interface')).not.toBeInTheDocument();
    expect(mocks.getInterfacesPage).toHaveBeenCalledWith(expect.objectContaining({ visibility: 'private' }));
  });

  it('changing the sort re-queries the server with the chosen sort key', async () => {
    mocks.getInterfacesPage.mockResolvedValue(page([intf('i1', 'Alpha'), intf('i2', 'Beta')]));

    render(<InterfaceTable />);
    await waitFor(() => expect(screen.getByText('Alpha')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('common.sortBy'), { target: { value: 'name' } });
    await waitFor(() =>
      expect(mocks.getInterfacesPage).toHaveBeenCalledWith(expect.objectContaining({ sort: 'name' })));
  });

  it('loads a SINGLE server page - never loops to fetch the whole list', async () => {
    // totalCount (50) far exceeds the returned page: the old client did a for(;;) loop until it had
    // all 50; the server-paged version must request page 0 ONCE and never page through the rest.
    mocks.getInterfacesPage.mockResolvedValue({
      items: [intf('i1', 'Only One')], totalCount: 50, page: 0, size: 25, publicationStatuses: {},
    });

    render(<InterfaceTable />);
    await waitFor(() => expect(screen.getByText('Only One')).toBeInTheDocument());

    expect(mocks.getInterfacesPage).toHaveBeenCalledTimes(1);
    expect(mocks.getInterfacesPage).toHaveBeenCalledWith(expect.objectContaining({ page: 0, size: 25 }));
    expect(mocks.getInterfacesPage).not.toHaveBeenCalledWith(expect.objectContaining({ page: 1 }));
  });
});

describe('InterfaceTable - html view loads templates lazily for the page only', () => {
  it('fetches the page WITHOUT templates and pulls each visible template via getInterface (deduped)', async () => {
    // Metadata-only list rows (no htmlTemplate) - the heavy templates are pulled per visible card.
    mocks.getInterfacesPage.mockResolvedValue(page([intf('i1', 'One'), intf('i2', 'Two')]));
    mocks.getInterface.mockImplementation((id: string) =>
      Promise.resolve({ id, name: id, htmlTemplate: `<h1>${id}</h1>`, cssTemplate: '', jsTemplate: '' }));

    render(<InterfaceTable interfaceTypeFilter="html" />);

    // The page request stays light: the html view requests the list WITHOUT templates...
    await waitFor(() => expect(mocks.getInterfacesPage).toHaveBeenCalledWith(
      expect.objectContaining({ includeTemplates: false, type: 'html' })));
    // ...then pulls each VISIBLE interface's template lazily, exactly once per id (deduped).
    await waitFor(() => expect(mocks.getInterface).toHaveBeenCalledWith('i1'));
    expect(mocks.getInterface).toHaveBeenCalledWith('i2');
    expect(mocks.getInterface).toHaveBeenCalledTimes(2);
  });
});

describe('InterfaceTable - web_search view keeps templates + data inline (no lazy fetch)', () => {
  it('fetches the page WITH templates (toListDto would null the data web_search needs) and never calls getInterface', async () => {
    mocks.getInterfacesPage.mockResolvedValue(page([
      { ...intf('w1', 'Search One'), interfaceType: 'web_search', data: { results: [] } },
    ]));

    render(<InterfaceTable interfaceTypeFilter="web_search" />);

    await waitFor(() => expect(mocks.getInterfacesPage).toHaveBeenCalledWith(
      expect.objectContaining({ includeTemplates: true, type: 'web_search' })));
    expect(mocks.getInterface).not.toHaveBeenCalled();
  });
});
