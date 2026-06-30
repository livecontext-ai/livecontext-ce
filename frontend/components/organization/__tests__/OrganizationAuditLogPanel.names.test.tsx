// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const getAuditLog = vi.fn();
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: { getAuditLog: (...a: unknown[]) => getAuditLog(...a) },
}));

import OrganizationAuditLogPanel from '../OrganizationAuditLogPanel';

describe('OrganizationAuditLogPanel - display names', () => {
  afterEach(cleanup);

  it('renders names from the userNames map instead of "user #id"', async () => {
    getAuditLog.mockResolvedValue({
      items: [
        {
          id: 1,
          eventType: 'ORG_ROLE_CHANGED',
          actorUserId: 1,
          eventData: { targetUserId: 5, oldRole: 'MEMBER', newRole: 'ADMIN' },
          createdAt: '2026-06-01T22:35:00Z',
        },
      ],
      totalCount: 1,
      page: 0,
      size: 25,
      userNames: { '1': 'Alice Smith', '5': 'Bob' },
    });

    render(<OrganizationAuditLogPanel orgId="org-1" currentUserRole="OWNER" />);

    expect(await screen.findByText('Bob: MEMBER → ADMIN by Alice Smith')).toBeInTheDocument();
    expect(screen.queryByText(/user #/)).not.toBeInTheDocument();
  });

  it('falls back to "user #id" when a name is missing from the map', async () => {
    getAuditLog.mockResolvedValue({
      items: [
        {
          id: 2,
          eventType: 'ORG_ROLE_CHANGED',
          actorUserId: 1,
          eventData: { targetUserId: 9, oldRole: 'VIEWER', newRole: 'MEMBER' },
          createdAt: '2026-06-01T22:35:00Z',
        },
      ],
      totalCount: 1,
      page: 0,
      size: 25,
      userNames: { '1': 'Alice Smith' }, // 9 missing (e.g. deleted user)
    });

    render(<OrganizationAuditLogPanel orgId="org-1" currentUserRole="OWNER" />);

    expect(await screen.findByText('user #9: VIEWER → MEMBER by Alice Smith')).toBeInTheDocument();
  });
});
