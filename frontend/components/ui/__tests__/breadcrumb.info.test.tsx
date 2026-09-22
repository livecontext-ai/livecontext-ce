// @vitest-environment jsdom
/**
 * The info control on a breadcrumb segment - the "i" beside the rename pencil.
 *
 * The two things worth pinning are structural, and both are invisible until they break:
 * the control must be a SIBLING of the crumb (an editable last crumb renders as a
 * <button>, and nesting a button in a button is invalid), and it must stay in the DOM
 * rather than being mounted on hover (the popover is portalled away from the crumb, so
 * unmounting on mouseleave would close it the moment the pointer reaches it).
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

import { Breadcrumb, type BreadcrumbItem } from '../breadcrumb';
import { invalidateWorkspaceMembers } from '@/lib/api/workspaceMembers';

beforeEach(() => {
  invalidateWorkspaceMembers();
  getOrganization.mockReset();
  getOrganization.mockResolvedValue({
    id: 'org-1',
    members: [{ userId: 42, email: 'ada@example.com', displayName: 'Ada Lovelace', avatarUrl: null, role: 'ADMIN', joinedAt: '2026-01-01T00:00:00Z', isOwner: false }],
  });
});

afterEach(() => cleanup());

const INFO: BreadcrumbItem['info'] = {
  ownerId: '42',
  createdAt: '2026-03-12T14:32:00Z',
  updatedAt: '2026-09-07T09:12:00Z',
};

function renderCrumbs(tail: Partial<BreadcrumbItem>) {
  const items: BreadcrumbItem[] = [
    { label: 'Workflows', onClick: vi.fn() },
    { label: 'My Workflow', truncate: true, ...tail },
  ];
  return render(<Breadcrumb items={items} />);
}

describe('Breadcrumb info control', () => {
  it('renders no info control when the segment carries no attribution', () => {
    renderCrumbs({});

    expect(screen.queryByTestId('resource-info-trigger')).not.toBeInTheDocument();
  });

  it('renders it on a plain segment', () => {
    renderCrumbs({ info: INFO });

    expect(screen.getByTestId('resource-info-trigger')).toBeInTheDocument();
  });

  it('renders it on an EDITABLE segment without nesting a button inside a button', () => {
    // This is the shape the workflow / table / interface crumbs actually take: editable
    // plus an onClick that opens the rename modal, which makes the crumb itself a <button>.
    renderCrumbs({ info: INFO, editable: true, onClick: vi.fn() });

    const info = screen.getByTestId('resource-info-trigger');
    expect(info).toBeInTheDocument();
    expect(info.closest('button')).toBe(info);
  });

  it('stays mounted while not hovered, so the popover survives the pointer reaching it', () => {
    renderCrumbs({ info: INFO });

    // Hidden by opacity, NOT by unmounting: the panel is portalled out of the crumb, so a
    // mount-on-hover control would vanish (taking the panel with it) on the way to it.
    const info = screen.getByTestId('resource-info-trigger');
    expect(info.className).toContain('opacity-0');
    expect(info.className).toContain('group-hover/crumb:opacity-100');
  });

  it('is inside an element that DECLARES the hover group it reveals on', () => {
    // The half that the class assertion above cannot see. `group-hover/crumb:` fires only if
    // some ancestor declares `group/crumb`; delete that from the wrapper and every one of
    // these tests still passes while the control becomes permanently invisible AND
    // unclickable (opacity-0 + pointer-events-none, forever) on every crumb of every page.
    // jsdom computes no Tailwind, so the relationship has to be asserted structurally.
    renderCrumbs({ info: INFO });

    // Walked by hand rather than with a selector: `group/crumb` is not a valid CSS class
    // selector (the slash), so `closest()` throws on it.
    const info = screen.getByTestId('resource-info-trigger');
    let ancestor: HTMLElement | null = info.parentElement;
    let declares = false;
    while (ancestor && !declares) {
      declares = ancestor.classList.contains('group/crumb');
      ancestor = ancestor.parentElement;
    }
    expect(declares, 'no ancestor declares group/crumb, so the reveal can never fire').toBe(true);
  });

  it('paints the control while its popover is open, whatever the pointer is doing', async () => {
    renderCrumbs({ info: INFO });

    fireEvent.click(screen.getByTestId('resource-info-trigger'));
    await screen.findByTestId('resource-info-popover');

    const info = screen.getByTestId('resource-info-trigger');
    expect(info.className).toContain('opacity-100');
    expect(info.className).not.toContain('opacity-0');
  });

  it('opens the attribution and names the owner', async () => {
    renderCrumbs({ info: INFO });

    fireEvent.click(screen.getByTestId('resource-info-trigger'));

    expect(await screen.findByText('Ada Lovelace')).toBeInTheDocument();
  });

  it('does not open the rename modal when the info control is clicked', async () => {
    const onClick = vi.fn();
    renderCrumbs({ info: INFO, editable: true, onClick });

    fireEvent.click(screen.getByTestId('resource-info-trigger'));
    await screen.findByTestId('resource-info-popover');

    expect(onClick).not.toHaveBeenCalled();
  });

  it('is withdrawn while the segment is being renamed inline', () => {
    // The inline editor replaces the label with an input plus confirm/cancel buttons;
    // a third control in that row would be one the user cannot act on.
    const { container } = render(
      <Breadcrumb items={[{ label: 'Workflows', onClick: vi.fn() }, { label: 'My Workflow', editable: true, info: INFO }]} />,
    );

    fireEvent.click(screen.getByText('My Workflow'));

    expect(container.querySelector('input')).toBeInTheDocument();
    expect(screen.queryByTestId('resource-info-trigger')).not.toBeInTheDocument();
  });

  it('leaves the favorite star working next to it', () => {
    const onToggle = vi.fn();
    renderCrumbs({ info: INFO, favorite: { isFavorite: true, onToggle } });

    fireEvent.click(screen.getByRole('button', { name: 'removeFromFavorites' }));

    expect(onToggle).toHaveBeenCalledTimes(1);
  });
});
