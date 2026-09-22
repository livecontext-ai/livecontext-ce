// @vitest-environment jsdom
/**
 * The table card's attribution, rendered against the payload the API ACTUALLY sends.
 *
 * This exists because the first version of the feature read `ds.tenantId` while the
 * datasource service serializes the record with snake_case `@JsonProperty` names. Nothing
 * failed: the owner id came back undefined, the popover fell through to its no-owner branch,
 * and every table card quietly showed a date where a person should have been. A fixture
 * written in the shape the component wanted would have certified the bug.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key,
}));

const getOrganization = vi.fn();
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: { getOrganization: (...args: unknown[]) => getOrganization(...args) },
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgIdForRequest: () => 'org-1',
}));

import { DataSourceCard } from '../DataSourceCard';
import { invalidateWorkspaceMembers } from '@/lib/api/workspaceMembers';

/**
 * A row as `DataSourceController` serializes it: snake_case for every column-backed field.
 * Do not "tidy" this into camelCase - being the real wire shape is the whole point.
 */
const API_ROW = {
  id: '7',
  name: 'Leads',
  tenant_id: '42',
  created_at: '2026-03-12T14:32:00Z',
  updated_at: '2026-09-07T09:12:00Z',
  mapping_spec: { email: { type: 'text' } },
} as never;

beforeEach(() => {
  invalidateWorkspaceMembers();
  getOrganization.mockReset();
  getOrganization.mockResolvedValue({
    id: 'org-1',
    members: [{
      userId: 42,
      email: 'ada@example.com',
      displayName: 'Ada Lovelace',
      avatarUrl: null,
      role: 'ADMIN',
      joinedAt: '2026-01-01T00:00:00Z',
      isOwner: false,
    }],
  });
});

afterEach(() => cleanup());

describe('DataSourceCard attribution', () => {
  it('names the owner from a snake_case API row', async () => {
    render(<DataSourceCard ds={API_ROW} rowCount={3} sampleRows={[]} onClick={vi.fn()} />);

    fireEvent.click(screen.getByTestId('table-info-7'));

    expect(await screen.findByText('Ada Lovelace')).toBeInTheDocument();
    // The owner row, not the anonymous "created" fallback that stands in when no owner is known.
    expect(screen.getByTestId('resource-info-owner')).toHaveAttribute('data-state', 'resolved');
    expect(screen.queryByTestId('resource-info-created')).not.toBeInTheDocument();
  });

  it('shows both dates from the same row', async () => {
    render(<DataSourceCard ds={API_ROW} rowCount={3} sampleRows={[]} onClick={vi.fn()} />);

    fireEvent.click(screen.getByTestId('table-info-7'));

    await screen.findByTestId('resource-info-popover');
    expect(screen.getByTestId('resource-info-owner')).toHaveTextContent('2026');
    expect(screen.getByTestId('resource-info-modified')).toBeInTheDocument();
  });

  it('offers no editors section: a table keeps no edit history', async () => {
    render(<DataSourceCard ds={API_ROW} rowCount={3} sampleRows={[]} onClick={vi.fn()} />);

    fireEvent.click(screen.getByTestId('table-info-7'));

    await screen.findByTestId('resource-info-popover');
    // Only workflows have one (their plan versions). A heading over an empty list would
    // read as "nobody has ever edited this", which is a claim we cannot make.
    expect(screen.queryByText('editorsTitle')).not.toBeInTheDocument();
  });

  it('does not open the table when its info button is clicked', async () => {
    const onClick = vi.fn();
    render(<DataSourceCard ds={API_ROW} rowCount={3} sampleRows={[]} onClick={onClick} />);

    fireEvent.click(screen.getByTestId('table-info-7'));

    await screen.findByTestId('resource-info-popover');
    expect(onClick).not.toHaveBeenCalled();
  });
});
