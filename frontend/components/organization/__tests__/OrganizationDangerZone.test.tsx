// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Organization } from '@/lib/api/organization-api';
import { organizationApi } from '@/lib/api/organization-api';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useParams: () => ({ locale: 'en' }),
}));

vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: {
    leaveOrganization: vi.fn(),
    transferOwnership: vi.fn(),
    deleteOrganization: vi.fn().mockResolvedValue(undefined),
  },
}));

import OrganizationDangerZone from '../OrganizationDangerZone';

const org = {
  id: 'org-1',
  name: 'Acme',
  isPersonal: false,
} as Organization;

describe('OrganizationDangerZone', () => {
  afterEach(() => {
    cleanup();
    useCurrentOrgStore.setState({ currentOrgId: null, currentOrgRole: null });
    if (typeof localStorage !== 'undefined') localStorage.removeItem('lc.activeOrg');
    vi.clearAllMocks();
  });

  it('uses the shared collapsed section behavior for member destructive actions', () => {
    render(
      <OrganizationDangerZone
        org={org}
        members={[]}
        currentUserRole="MEMBER"
        onChanged={() => {}}
      />
    );

    const trigger = screen.getByRole('button', { name: /Danger zone/i });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('button', { name: 'Leave' })).not.toBeInTheDocument();

    fireEvent.click(trigger);

    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('button', { name: 'Leave' })).toBeInTheDocument();
  });

  it('shows the Delete workspace action for an owner of a non-personal workspace', () => {
    // Regression: workspace delete was re-enabled (canDelete = isOwner && !isPersonal). Before
    // that, an owner of a non-personal org saw an EMPTY danger zone; now they get Delete.
    render(
      <OrganizationDangerZone
        org={org}
        members={[]}
        currentUserRole="OWNER"
        onChanged={() => {}}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: /Danger zone/i }));
    expect(screen.getByText('Delete workspace')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete' })).toBeEnabled();
  });

  it('renders Delete as DISABLED for a personal/base workspace (the base is never deletable)', () => {
    render(
      <OrganizationDangerZone
        org={{ ...org, isPersonal: true } as Organization}
        members={[]}
        currentUserRole="OWNER"
        onChanged={() => {}}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: /Danger zone/i }));
    // The base/personal workspace surfaces a disabled Delete - it can never be removed.
    expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled();
    expect(screen.queryByText('Delete workspace')).not.toBeInTheDocument();
  });

  // ── Regression: deleting the ACTIVE workspace must switch the user out ──────
  // Prod bug (2026-06-06): deleting the workspace you are currently in left the
  // active-org store pointing at the now soft-deleted org, so the user was
  // stranded on an empty workspace. After a successful delete of the active
  // workspace, the store must no longer point at it (gateway then falls back to
  // the default/personal workspace).
  it('clears the active-org store when the deleted workspace is the current one', async () => {
    useCurrentOrgStore.getState().setCurrentOrg('org-1', 'OWNER');

    render(
      <OrganizationDangerZone
        org={org}
        members={[]}
        currentUserRole="OWNER"
        onChanged={() => {}}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: /Danger zone/i }));
    fireEvent.click(screen.getByRole('button', { name: 'Delete' })); // row → opens modal
    // GitHub-style confirm: type the exact name to enable the destructive action.
    fireEvent.change(screen.getByLabelText(/Type/i), { target: { value: 'Acme' } });
    // Modal is rendered after the row in the DOM, so its confirm is the last "Delete".
    const deleteButtons = screen.getAllByRole('button', { name: 'Delete' });
    fireEvent.click(deleteButtons[deleteButtons.length - 1]);

    await waitFor(() => expect(organizationApi.deleteOrganization).toHaveBeenCalledWith('org-1', 'Acme'));
    await waitFor(() => expect(useCurrentOrgStore.getState().currentOrgId).not.toBe('org-1'));
  });

  it('leaves the active-org store untouched when deleting a NON-active workspace', async () => {
    // Deleting a workspace you are not currently in must not disturb your session.
    useCurrentOrgStore.getState().setCurrentOrg('other-org', 'OWNER');

    render(
      <OrganizationDangerZone
        org={org}
        members={[]}
        currentUserRole="OWNER"
        onChanged={() => {}}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: /Danger zone/i }));
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
    fireEvent.change(screen.getByLabelText(/Type/i), { target: { value: 'Acme' } });
    const deleteButtons = screen.getAllByRole('button', { name: 'Delete' });
    fireEvent.click(deleteButtons[deleteButtons.length - 1]);

    await waitFor(() => expect(organizationApi.deleteOrganization).toHaveBeenCalled());
    expect(useCurrentOrgStore.getState().currentOrgId).toBe('other-org');
  });
});
