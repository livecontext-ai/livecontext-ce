// @vitest-environment jsdom
/**
 * Attribution rendered against the payloads the agent and interface APIs actually send.
 *
 * <p>`ResourceInfoCardHosts.test.tsx` scans the source and can tell that the card asks for
 * `agent.tenantId`. What it cannot tell is whether anything ever arrives under that name -
 * which is the exact bug that shipped on tables, where the wire says `tenant_id` and the card
 * silently rendered a date instead of a person. Nothing failed there either.
 *
 * <p>So this mounts the control the way each card does, with a row shaped like its service's
 * response, and asserts a person comes out. The fixtures are transcribed from the serialisers:
 * `AgentEntity` is returned directly (camelCase field names), and `InterfaceDto` is built by
 * `toListDto`/`toDto` (camelCase getters). If either service ever moves to snake_case, these
 * go red instead of the UI going quietly blank.
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

import { ResourceInfoPopover } from '../ResourceInfoPopover';
import { invalidateWorkspaceMembers } from '@/lib/api/workspaceMembers';

/** As `AgentController` returns it: the entity, serialized with its camelCase field names. */
const AGENT_ROW = {
  id: 'agent-1',
  tenantId: '42',
  name: 'Support triage',
  createdAt: '2026-03-12T14:32:00Z',
  updatedAt: '2026-09-07T09:12:00Z',
};

/** As `InterfaceDto` is built by toListDto/toDto: camelCase, from the entity's getters. */
const INTERFACE_ROW = {
  id: 'interface-1',
  tenantId: '42',
  name: 'Order form',
  createdAt: '2026-03-12T14:32:00Z',
  updatedAt: '2026-09-07T09:12:00Z',
};

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

describe.each([
  { host: 'agent card', row: AGENT_ROW },
  { host: 'interface card', row: INTERFACE_ROW },
])('$host', ({ row }) => {
  it('resolves a person from the row its API actually sends', async () => {
    render(
      <ResourceInfoPopover ownerId={row.tenantId} createdAt={row.createdAt} updatedAt={row.updatedAt} />,
    );

    fireEvent.click(screen.getByTestId('resource-info-trigger'));

    expect(await screen.findByText('Ada Lovelace')).toBeInTheDocument();
    expect(screen.getByTestId('resource-info-owner')).toHaveAttribute('data-state', 'resolved');
    // The no-owner fallback must NOT be what is showing: that is what a field-name mismatch
    // looks like, and it looks perfectly fine.
    expect(screen.queryByTestId('resource-info-created')).not.toBeInTheDocument();
  });
});
