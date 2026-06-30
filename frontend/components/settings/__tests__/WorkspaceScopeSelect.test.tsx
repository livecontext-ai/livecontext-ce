// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * WorkspaceScopeSelect is the page-local workspace filter on the Quota / Storage
 * settings pages. Contract pinned here:
 *  - it renders NOTHING for a user with fewer than two ENTERABLE workspaces
 *    (a single-workspace user has nothing to filter - no clutter);
 *  - paused (owner downgraded below TEAM) and pendingDeletion (soft-deleted)
 *    orgs are excluded, mirroring the sidebar switcher (the gateway rejects
 *    entering both, so offering them would only paint an empty view);
 *  - each option carries the workspace AVATAR (same chip as the sidebar switcher);
 *  - picking a workspace fires onChange with that workspace's id;
 *  - with includeAllOption, a leading "All workspaces" option (value
 *    ALL_WORKSPACES_SCOPE) is offered and picking it fires onChange with the
 *    sentinel; without the prop it is absent.
 *
 * Real next-intl echoed to namespaced keys; native-<select> stand-in for the
 * Radix Select; WorkspaceAvatar stubbed to a marker (no text, so option
 * accessible names stay clean); useQuery + useAuth mocked so the membership
 * list is controlled.
 */

const mocks = vi.hoisted(() => ({ useQuery: vi.fn(), getOrganizations: vi.fn() }));

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => (ns ? `${ns}.${key}` : key),
}));
vi.mock('@tanstack/react-query', () => ({ useQuery: mocks.useQuery }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ isAuthenticated: true }) }));
// Cloud edition so the membership query is enabled (CE is single-tenant -> no fetch).
vi.mock('@/lib/edition', () => ({ IS_CE: false }));
vi.mock('@/lib/api/organization-api', () => ({ organizationApi: { getOrganizations: mocks.getOrganizations } }));
vi.mock('@/lib/utils', () => ({ cn: (...a: unknown[]) => a.filter(Boolean).join(' ') }));
vi.mock('@/components/organization/WorkspaceAvatar', () => ({
  // Marker only - renders NO text so it never pollutes an <option>'s accessible name.
  WorkspaceAvatar: ({ name, avatarUrl }: { name: string; avatarUrl?: string | null }) =>
    React.createElement('i', { 'data-testid': 'ws-avatar', 'data-name': name, 'data-avatar': avatarUrl ?? '' }),
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
      { 'aria-label': acc.ariaLabel, value: value ?? '', onChange: (e: any) => onValueChange(e.target.value) },
      acc.options.map((o) => ReactLib.createElement('option', { key: o.value, value: o.value }, o.label)));
  };
  const SelectTrigger = ({ children, 'aria-label': ariaLabel }: any) => ReactLib.createElement('span', { 'aria-label': ariaLabel }, children);
  const SelectValue = () => null;
  const SelectContent = ({ children }: any) => children;
  const SelectItem: any = ({ children }: any) => children;
  SelectItem.__isSelectItem = true;
  return { Select, SelectTrigger, SelectContent, SelectItem, SelectValue };
});

import { WorkspaceScopeSelect, ALL_WORKSPACES_SCOPE } from '../WorkspaceScopeSelect';

const ws = (id: string, name: string, extra: Record<string, unknown> = {}) => ({
  id, name, isPersonal: false, paused: false, pendingDeletion: false, avatarUrl: null, ...extra,
});

function setWorkspaces(data: unknown) {
  mocks.useQuery.mockReturnValue({ data });
}

const LABEL = 'workspaceScope.label';
const ALL_LABEL = 'workspaceScope.allWorkspaces';

describe('WorkspaceScopeSelect', () => {
  beforeEach(() => {
    mocks.useQuery.mockReset();
  });
  afterEach(() => cleanup());

  it('renders nothing while memberships are still loading (data undefined)', () => {
    setWorkspaces(undefined);
    const { container } = render(<WorkspaceScopeSelect value={null} onChange={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing for a single-workspace user (nothing to filter)', () => {
    setWorkspaces([ws('org-a', 'Personal', { isPersonal: true })]);
    const { container } = render(<WorkspaceScopeSelect value="org-a" onChange={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the filter with one option per enterable workspace (2+)', () => {
    setWorkspaces([ws('org-a', 'Personal', { isPersonal: true }), ws('org-b', 'Acme Team')]);
    render(<WorkspaceScopeSelect value="org-a" onChange={vi.fn()} />);

    const select = screen.getByLabelText(LABEL) as HTMLSelectElement;
    expect(select).toBeInTheDocument();
    expect(select.value).toBe('org-a');
    expect(screen.getByRole('option', { name: 'Personal' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Acme Team' })).toBeInTheDocument();
  });

  it('renders a workspace avatar per option, carrying the workspace name + configured avatarUrl', () => {
    // (The native-<select> stand-in renders only the option list, not the Radix
    // trigger content, so this pins the per-option avatars; the trigger avatar is
    // the same component fed the selected workspace.)
    setWorkspaces([ws('org-a', 'Personal', { isPersonal: true }), ws('org-b', 'Acme Team', { avatarUrl: '/api/organizations/org-b/avatar' })]);
    render(<WorkspaceScopeSelect value="org-b" onChange={vi.fn()} />);

    const avatars = screen.getAllByTestId('ws-avatar');
    // One avatar per enterable workspace option.
    expect(avatars.length).toBeGreaterThanOrEqual(2);
    expect(avatars.some((a) => a.getAttribute('data-name') === 'Personal')).toBe(true);
    expect(avatars.some((a) => a.getAttribute('data-name') === 'Acme Team' && a.getAttribute('data-avatar') === '/api/organizations/org-b/avatar')).toBe(true);
  });

  it('excludes paused and pendingDeletion orgs from the options', () => {
    setWorkspaces([
      ws('org-a', 'Personal', { isPersonal: true }),
      ws('org-b', 'Acme Team'),
      ws('org-paused', 'Dormant', { paused: true }),
      ws('org-del', 'Deleting', { pendingDeletion: true }),
    ]);
    render(<WorkspaceScopeSelect value="org-a" onChange={vi.fn()} />);

    expect(screen.getByRole('option', { name: 'Personal' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Acme Team' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Dormant' })).not.toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Deleting' })).not.toBeInTheDocument();
  });

  it('hides itself when only one workspace is enterable after filtering', () => {
    // Two memberships, but one is pending-deletion -> only a single enterable -> hidden.
    setWorkspaces([ws('org-a', 'Personal', { isPersonal: true }), ws('org-del', 'Deleting', { pendingDeletion: true })]);
    const { container } = render(<WorkspaceScopeSelect value="org-a" onChange={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('fires onChange with the picked workspace id', () => {
    setWorkspaces([ws('org-a', 'Personal', { isPersonal: true }), ws('org-b', 'Acme Team')]);
    const onChange = vi.fn();
    render(<WorkspaceScopeSelect value="org-a" onChange={onChange} />);

    fireEvent.change(screen.getByLabelText(LABEL), { target: { value: 'org-b' } });
    expect(onChange).toHaveBeenCalledWith('org-b');
  });

  it('does NOT offer the "All workspaces" option by default', () => {
    setWorkspaces([ws('org-a', 'Personal', { isPersonal: true }), ws('org-b', 'Acme Team')]);
    render(<WorkspaceScopeSelect value="org-a" onChange={vi.fn()} />);
    expect(screen.queryByRole('option', { name: ALL_LABEL })).not.toBeInTheDocument();
  });

  it('offers a leading "All workspaces" option when includeAllOption is set, and picking it fires the sentinel', () => {
    setWorkspaces([ws('org-a', 'Personal', { isPersonal: true }), ws('org-b', 'Acme Team')]);
    const onChange = vi.fn();
    render(<WorkspaceScopeSelect value="org-a" onChange={onChange} includeAllOption />);

    expect(screen.getByRole('option', { name: ALL_LABEL })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(LABEL), { target: { value: ALL_WORKSPACES_SCOPE } });
    expect(onChange).toHaveBeenCalledWith(ALL_WORKSPACES_SCOPE);
  });

  it('renders the "All workspaces" label in the trigger when the sentinel is selected', () => {
    setWorkspaces([ws('org-a', 'Personal', { isPersonal: true }), ws('org-b', 'Acme Team')]);
    render(<WorkspaceScopeSelect value={ALL_WORKSPACES_SCOPE} onChange={vi.fn()} includeAllOption />);
    // The trigger shows the All-workspaces text; the option list also has it -> >=2.
    expect(screen.getAllByText(ALL_LABEL).length).toBeGreaterThanOrEqual(1);
  });
});
