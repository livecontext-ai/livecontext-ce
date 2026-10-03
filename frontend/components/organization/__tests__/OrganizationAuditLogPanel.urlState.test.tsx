// @vitest-environment jsdom
/**
 * The audit log keeps its filter and its page in the address, so a reload reopens it where
 * it was.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

const getAuditLog = vi.fn();
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: { getAuditLog: (...a: unknown[]) => getAuditLog(...a) },
}));

import OrganizationAuditLogPanel from '../OrganizationAuditLogPanel';

const PAGE = '/en/app/settings/organization';

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

const event = (id: number) => ({
  id,
  eventType: 'ORG_ROLE_CHANGED',
  actorUserId: 1,
  eventData: { targetUserId: 5, oldRole: 'MEMBER', newRole: 'ADMIN' },
  createdAt: '2026-06-01T22:35:00Z',
});

describe('OrganizationAuditLogPanel - view kept in the address', () => {
  beforeEach(() => {
    getAuditLog.mockReset();
    // The server echoes the page it was asked for.
    getAuditLog.mockImplementation(async (_org: string, opts: { page: number }) => ({
      items: [event(1)],
      totalCount: 80,
      page: opts.page,
      size: 25,
      userNames: {},
    }));
  });
  afterEach(cleanup);

  it('opens on the category and the page the address carries', async () => {
    openAt('tab=security&category=ORG_ROLE_CHANGED&page=3');
    render(<OrganizationAuditLogPanel orgId="org-1" currentUserRole="OWNER" />);

    expect(await screen.findByText('Page 3 of 4')).toBeInTheDocument();
    expect(getAuditLog).toHaveBeenCalledTimes(1);
    expect(getAuditLog).toHaveBeenCalledWith('org-1', { category: 'ORG_ROLE_CHANGED', page: 2, size: 25 });
    expect((screen.getByLabelText('Filter:') as HTMLSelectElement).value).toBe('ORG_ROLE_CHANGED');
  });

  it('regression - a restored filter with no match keeps the section on screen', async () => {
    getAuditLog.mockResolvedValue({ items: [], totalCount: 0, page: 0, size: 25, userNames: {} });
    openAt('category=ORG_DELETED');
    render(<OrganizationAuditLogPanel orgId="org-1" currentUserRole="OWNER" />);

    expect(await screen.findByText('No events match the current filter.')).toBeInTheDocument();
  });

  it('writes the category and the page as they change, keeping the other params', async () => {
    openAt('tab=security');
    render(<OrganizationAuditLogPanel orgId="org-1" currentUserRole="OWNER" />);
    await screen.findByText('Page 1 of 4');

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('tab=security&page=2'));

    fireEvent.change(screen.getByLabelText('Filter:'), { target: { value: 'ORG_MEMBER_LEFT' } });
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('tab=security&category=ORG_MEMBER_LEFT'));
    expect(getAuditLog).toHaveBeenLastCalledWith('org-1', { category: 'ORG_MEMBER_LEFT', page: 0, size: 25 });
  });

  it('ignores a category the filter does not offer', async () => {
    openAt('category=DROP_TABLE');
    render(<OrganizationAuditLogPanel orgId="org-1" currentUserRole="OWNER" />);

    await screen.findByText('Page 1 of 4');
    expect(getAuditLog).toHaveBeenCalledWith('org-1', { category: undefined, page: 0, size: 25 });
  });

  it('a page restored from the address that is past the end loads and shows the last page', async () => {
    // 45 events at 25 a page are 2 pages. Events were pruned since the address was saved, and
    // the server answers page 40 with no items and the real total.
    getAuditLog.mockImplementation(async (_org: string, opts: { page: number }) => ({
      items: opts.page <= 1 ? [event(100 + opts.page)] : [],
      totalCount: 45,
      page: opts.page,
      size: 25,
      userNames: {},
    }));
    openAt('tab=security&page=40');
    const { container } = render(<OrganizationAuditLogPanel orgId="org-1" currentUserRole="OWNER" />);

    // The pager names the page whose rows are on screen, not the one that was asked for.
    expect(await screen.findByText('Page 2 of 2')).toBeInTheDocument();
    expect(container.querySelectorAll('li')).toHaveLength(1);
    expect(screen.queryByText('No events match the current filter.')).not.toBeInTheDocument();
    expect(getAuditLog.mock.calls.map(([, opts]) => opts.page)).toEqual([39, 1]);
    // The address follows, so the next reload opens the last page directly.
    expect(fakeFolderRouter.search()).toBe('tab=security&page=2');
  });
});
