// @vitest-environment jsdom
/**
 * The resource-info popover: what it says about a resource, what it costs, and - the trap
 * this control was most likely to fall into - that it survives a tree with no
 * QueryClientProvider. It is rendered inside cards that mount in exactly such trees, and
 * `useQuery` does not degrade there, it throws and takes the whole page down.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key,
}));

const getOrganization = vi.fn();
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: { getOrganization: (...args: unknown[]) => getOrganization(...args) },
}));

let currentOrgId: string | null = 'org-1';
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgIdForRequest: () => currentOrgId,
}));

import { ResourceInfoPopover } from '../ResourceInfoPopover';
import { invalidateWorkspaceMembers } from '@/lib/api/workspaceMembers';

const OWNER = {
  userId: 42,
  email: 'ada@example.com',
  displayName: 'Ada Lovelace',
  avatarUrl: null,
  role: 'ADMIN',
  joinedAt: '2026-01-01T00:00:00Z',
  isOwner: false,
};

beforeEach(() => {
  currentOrgId = 'org-1';
  invalidateWorkspaceMembers();
  getOrganization.mockReset();
  getOrganization.mockResolvedValue({ id: 'org-1', members: [OWNER] });
});

afterEach(() => cleanup());

function open() {
  fireEvent.click(screen.getByTestId('resource-info-trigger'));
}

describe('ResourceInfoPopover - the no-query-client contract', () => {
  it('renders and opens with no provider of any kind in the tree', async () => {
    // A QueryClientProvider is deliberately absent: this is how the applications grid,
    // the project tabs and the publication preview mount their cards.
    render(
      <ResourceInfoPopover ownerId="42" createdAt="2026-03-12T14:32:00Z" updatedAt="2026-09-07T09:12:00Z" />,
    );

    open();

    expect(await screen.findByTestId('resource-info-popover')).toBeInTheDocument();
  });
});

describe('ResourceInfoPopover - the trigger actually toggles', () => {
  it('opens on click even though the trigger stops propagation for its host', async () => {
    // Regression: the trigger stops propagation so a click cannot reach the card or crumb
    // behind it. Adding preventDefault() alongside it looked equally defensive and broke
    // the button outright - Radix composes its own toggle AFTER the child handler and
    // skips it on an already-defaultPrevented event, so the popover opened nothing, with
    // no error anywhere.
    render(<ResourceInfoPopover ownerId="42" />);

    open();

    expect(await screen.findByTestId('resource-info-popover')).toBeInTheDocument();
  });
});

describe('ResourceInfoPopover - cost', () => {
  it('asks for nothing until it is opened', () => {
    render(<ResourceInfoPopover ownerId="42" createdAt="2026-03-12T14:32:00Z" />);

    // A grid of 24 cards paints 24 of these; none of them may cost a request.
    expect(getOrganization).not.toHaveBeenCalled();
  });

  it('does not call loadEditors until it is opened, and then only once', async () => {
    const loadEditors = vi.fn().mockResolvedValue([]);
    render(<ResourceInfoPopover ownerId="42" loadEditors={loadEditors} />);

    expect(loadEditors).not.toHaveBeenCalled();

    open();
    await waitFor(() => expect(loadEditors).toHaveBeenCalledTimes(1));

    // Close and reopen: the answer is already held, re-asking would be pure waste.
    fireEvent.keyDown(document.body, { key: 'Escape' });
    open();
    await waitFor(() => expect(screen.getByTestId('resource-info-popover')).toBeInTheDocument());
    expect(loadEditors).toHaveBeenCalledTimes(1);
  });

  it('resolves the workspace roster once for several popovers on the same page', async () => {
    const { container } = render(
      <>
        <ResourceInfoPopover ownerId="42" data-testid="info-a" />
        <ResourceInfoPopover ownerId="42" data-testid="info-b" />
      </>,
    );
    expect(container).toBeTruthy();

    fireEvent.click(screen.getByTestId('info-a'));
    await screen.findByText('Ada Lovelace');
    fireEvent.keyDown(document.body, { key: 'Escape' });

    fireEvent.click(screen.getByTestId('info-b'));
    await screen.findByText('Ada Lovelace');

    // The roster is per workspace, not per card.
    expect(getOrganization).toHaveBeenCalledTimes(1);
  });
});

describe('ResourceInfoPopover - naming the owner', () => {
  it('names the owner from the workspace roster', async () => {
    render(<ResourceInfoPopover ownerId="42" createdAt="2026-03-12T14:32:00Z" />);
    open();

    expect(await screen.findByText('Ada Lovelace')).toBeInTheDocument();
    // The owner row carries the creation date as its detail line.
    expect(screen.getByTestId('resource-info-owner')).toHaveTextContent('2026');
  });

  it('shows the owner as pending, NOT as a former member, while the roster is loading', async () => {
    let release: (value: unknown) => void = () => {};
    getOrganization.mockReturnValue(new Promise((resolve) => { release = resolve; }));

    render(<ResourceInfoPopover ownerId="42" />);
    open();

    const row = await screen.findByTestId('resource-info-owner');
    // Calling someone a former member for the split second before the roster lands is a
    // false statement about a real person, not a loading state.
    expect(row).toHaveAttribute('data-state', 'pending');
    expect(row).not.toHaveTextContent('formerMember');

    release({ id: 'org-1', members: [OWNER] });
    await waitFor(() => expect(screen.getByTestId('resource-info-owner')).toHaveAttribute('data-state', 'resolved'));
  });

  it('stops claiming an owner when the roster cannot be fetched, instead of pulsing forever', async () => {
    getOrganization.mockRejectedValue(new Error('boom'));

    render(<ResourceInfoPopover ownerId="42" createdAt="2026-03-12T14:32:00Z" />);
    open();

    // Neither "Former member" (a false claim about a real person) nor a skeleton that will
    // never resolve: the row falls back to the fact we do have, the creation date.
    await waitFor(() => expect(screen.getByTestId('resource-info-created')).toBeInTheDocument());
    expect(screen.queryByTestId('resource-info-owner')).not.toBeInTheDocument();
  });

  it('does not leave a skeleton pulsing when there is no workspace to ask', async () => {
    // Reachable pre-hydration, and whenever every enterable workspace is paused or pending
    // deletion. `pending` means "an answer is coming"; here none ever is.
    currentOrgId = null;

    render(<ResourceInfoPopover ownerId="42" createdAt="2026-03-12T14:32:00Z" />);
    open();

    await waitFor(() => expect(screen.getByTestId('resource-info-created')).toBeInTheDocument());
    expect(screen.queryByTestId('resource-info-owner')).not.toBeInTheDocument();
    expect(getOrganization).not.toHaveBeenCalled();
  });

  it('says an owner the roster does not know is not in this workspace, and does not call them departed', async () => {
    getOrganization.mockResolvedValue({ id: 'org-1', members: [{ ...OWNER, userId: 99 }] });

    render(<ResourceInfoPopover ownerId="42" />);
    open();

    // The roster answering "no" can also mean the id is recorded in a form the roster is not
    // keyed by. Calling a present colleague a former member is the worse way to be wrong.
    await waitFor(() =>
      expect(screen.getByTestId('resource-info-owner')).toHaveAttribute('data-state', 'unknown'));
    expect(screen.getByTestId('resource-info-owner')).toHaveTextContent('unknownPerson');
  });

  it('omits the owner row entirely when no owner id is known, and dates the resource instead', async () => {
    render(<ResourceInfoPopover createdAt="2026-03-12T14:32:00Z" updatedAt="2026-09-07T09:12:00Z" />);
    open();

    await screen.findByTestId('resource-info-popover');
    expect(screen.queryByTestId('resource-info-owner')).not.toBeInTheDocument();
    // Guessing an owner would be worse than not naming one.
    expect(screen.getByTestId('resource-info-created')).toBeInTheDocument();
    expect(screen.getByTestId('resource-info-modified')).toBeInTheDocument();
  });
});

describe('ResourceInfoPopover - recent editors', () => {
  it('lists editors, preferring the roster name and falling back to the resolved one', async () => {
    const loadEditors = vi.fn().mockResolvedValue([
      { userId: '42', displayName: 'stale name', version: 12, editedAt: '2026-09-07T09:12:00Z', editCount: 3 },
      { userId: '77', displayName: 'Grace Hopper', version: 11, editedAt: '2026-09-06T09:12:00Z', editCount: 1 },
    ]);

    render(<ResourceInfoPopover ownerId="42" loadEditors={loadEditors} />);
    open();

    // 42 is in the roster: the roster wins (it is the source with an avatar).
    await screen.findByTestId('resource-info-editor-42');
    expect(screen.getByTestId('resource-info-editor-42')).toHaveTextContent('Ada Lovelace');
    // 77 left the workspace: the backend-resolved name is the only thing that can name them.
    expect(screen.getByTestId('resource-info-editor-77')).toHaveTextContent('Grace Hopper');
  });

  it('shows a tally only for someone with more than one edit', async () => {
    const loadEditors = vi.fn().mockResolvedValue([
      { userId: '42', version: 12, editedAt: '2026-09-07T09:12:00Z', editCount: 3 },
      { userId: '77', displayName: 'Grace', version: 11, editedAt: '2026-09-06T09:12:00Z', editCount: 1 },
    ]);

    render(<ResourceInfoPopover loadEditors={loadEditors} />);
    open();

    await screen.findByTestId('resource-info-editor-42');
    expect(screen.getByTestId('resource-info-editor-42')).toHaveTextContent('editCount');
    // "1 edit" next to a single row is noise, not information.
    expect(screen.getByTestId('resource-info-editor-77')).not.toHaveTextContent('editCount');
  });

  it('omits the whole section when the resource has no recorded editor', async () => {
    const loadEditors = vi.fn().mockResolvedValue([]);

    render(<ResourceInfoPopover ownerId="42" loadEditors={loadEditors} />);
    open();

    await waitFor(() => expect(loadEditors).toHaveBeenCalled());
    await waitFor(() =>
      expect(screen.queryByTestId('resource-info-editors-loading')).not.toBeInTheDocument());
    expect(screen.queryByText('editorsTitle')).not.toBeInTheDocument();
  });

  it('never renders an editor as a blank name beside a blank avatar', async () => {
    // The state nobody had covered: no workspace to ask AND a person the backend could not
    // name either (a deleted account, or the batch resolver failing). The owner row is simply
    // dropped in that case - but an editor row cannot be, because they DID edit it. It has to
    // say something true, and the id is what is left.
    currentOrgId = null;
    const loadEditors = vi.fn().mockResolvedValue([
      { userId: '77', displayName: null, editedAt: '2026-09-07T09:12:00Z', editCount: 3 },
    ]);

    render(<ResourceInfoPopover ownerId="42" loadEditors={loadEditors} />);
    open();

    const row = await screen.findByTestId('resource-info-editor-77');
    expect(row).toHaveAttribute('data-state', 'unresolvable');
    expect(row.textContent ?? '', 'the row says who, however poorly').toContain('77');
  });

  it('keeps the owner and the dates when the editor lookup fails', async () => {
    const loadEditors = vi.fn().mockRejectedValue(new Error('500'));

    render(<ResourceInfoPopover ownerId="42" updatedAt="2026-09-07T09:12:00Z" loadEditors={loadEditors} />);
    open();

    expect(await screen.findByText('Ada Lovelace')).toBeInTheDocument();
    expect(screen.getByTestId('resource-info-modified')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('editorsTitle')).not.toBeInTheDocument());
  });

  it('offers no editors section at all when the caller supplies no loader', async () => {
    // Every resource except workflows: there is no edit history to show, and an empty
    // "recent editors" heading would imply nobody has ever edited it.
    render(<ResourceInfoPopover ownerId="42" updatedAt="2026-09-07T09:12:00Z" />);
    open();

    await screen.findByTestId('resource-info-popover');
    expect(screen.queryByText('editorsTitle')).not.toBeInTheDocument();
  });
});

describe('ResourceInfoPopover - describing a different resource', () => {
  it('drops the previous editors and asks again when resourceKey changes', async () => {
    // Regression: the breadcrumb keys its crumbs by POSITION and lives in a header that
    // survives in-app navigation, so ONE popover instance describes workflow A and then
    // workflow B. Without a reset it kept A's people on screen under B's name, while the
    // owner and dates (props) had already switched around them.
    const loadA = vi.fn().mockResolvedValue([
      { userId: '77', displayName: 'Alice From A', editedAt: '2026-09-07T09:12:00Z', editCount: 1 },
    ]);
    const loadB = vi.fn().mockResolvedValue([
      { userId: '88', displayName: 'Bob From B', editedAt: '2026-09-08T09:12:00Z', editCount: 1 },
    ]);

    const { rerender } = render(
      <ResourceInfoPopover resourceKey="workflow:A" ownerId="42" loadEditors={loadA} />,
    );
    open();
    expect(await screen.findByText('Alice From A')).toBeInTheDocument();
    fireEvent.keyDown(document.body, { key: 'Escape' });

    rerender(<ResourceInfoPopover resourceKey="workflow:B" ownerId="42" loadEditors={loadB} />);
    open();

    expect(await screen.findByText('Bob From B')).toBeInTheDocument();
    expect(loadB).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('Alice From A')).not.toBeInTheDocument();
  });

  it('drops the previous editors WITHOUT closing, the moment it starts describing another resource', async () => {
    // The two tests around this one close the popover before re-keying, so the synchronous
    // reset inside the open handler is what clears the list - the STAMP is never what is
    // exercised. Re-keying while it is open is the only way to see the derivation work, and
    // forcing `answersThisResource` to true must make this case red.
    const loadA = vi.fn().mockResolvedValue([
      { userId: '77', displayName: 'Alice From A', editedAt: '2026-09-07T09:12:00Z', editCount: 1 },
    ]);
    const loadB = vi.fn(() => new Promise<never>(() => {}));

    const { rerender } = render(
      <ResourceInfoPopover resourceKey="workflow:A" ownerId="42" loadEditors={loadA} />,
    );
    open();
    expect(await screen.findByText('Alice From A')).toBeInTheDocument();

    // Still OPEN, now pointed at B.
    rerender(<ResourceInfoPopover resourceKey="workflow:B" ownerId="42" loadEditors={loadB as never} />);

    await waitFor(() => expect(screen.queryByText('Alice From A')).not.toBeInTheDocument());
  });

  it('a late answer for the previous resource neither replaces nor erases the current one', async () => {
    // A slow load for A resolving after B has painted. Landing it would blank B AND leave the
    // one-request-per-resource marker on B, so B's editors would never come back.
    let releaseA: (value: unknown) => void = () => {};
    const loadA = vi.fn(() => new Promise((resolve) => { releaseA = resolve; }));
    const loadB = vi.fn().mockResolvedValue([
      { userId: '88', displayName: 'Bob From B', editedAt: '2026-09-08T09:12:00Z', editCount: 1 },
    ]);

    const { rerender } = render(
      <ResourceInfoPopover resourceKey="workflow:A" ownerId="42" loadEditors={loadA as never} />,
    );
    open();
    await waitFor(() => expect(loadA).toHaveBeenCalledTimes(1));
    fireEvent.keyDown(document.body, { key: 'Escape' });

    rerender(<ResourceInfoPopover resourceKey="workflow:B" ownerId="42" loadEditors={loadB} />);
    open();
    expect(await screen.findByText('Bob From B')).toBeInTheDocument();

    // A finally answers, too late to be about anything on screen.
    releaseA([{ userId: '77', displayName: 'Alice From A', editedAt: '2026-09-07T09:12:00Z', editCount: 1 }]);

    await waitFor(() => expect(screen.queryByText('Alice From A')).not.toBeInTheDocument());
    expect(screen.getByText('Bob From B'), 'B survived its predecessor answering late').toBeInTheDocument();
  });

  it('still asks only once while it keeps describing the same resource', async () => {
    const loadEditors = vi.fn().mockResolvedValue([]);
    const { rerender } = render(
      <ResourceInfoPopover resourceKey="workflow:A" ownerId="42" loadEditors={loadEditors} />,
    );

    open();
    await waitFor(() => expect(loadEditors).toHaveBeenCalledTimes(1));
    fireEvent.keyDown(document.body, { key: 'Escape' });
    // A re-render with the same key (a parent re-rendering for any reason) is not a change
    // of resource and must not re-ask.
    rerender(<ResourceInfoPopover resourceKey="workflow:A" ownerId="42" loadEditors={loadEditors} />);
    open();
    await screen.findByTestId('resource-info-popover');

    expect(loadEditors).toHaveBeenCalledTimes(1);
  });
});

describe('ResourceInfoPopover - hosting', () => {
  it('does not trigger the surrounding card when the button is clicked', async () => {
    const onCardClick = vi.fn();
    render(
      <div onClick={onCardClick}>
        <ResourceInfoPopover ownerId="42" />
      </div>,
    );

    open();

    await screen.findByTestId('resource-info-popover');
    // The card opens the resource; opening its info panel must not navigate away from it.
    expect(onCardClick).not.toHaveBeenCalled();
  });

  it('reports its open state so a hover-revealed host can keep it painted', async () => {
    const onOpenChange = vi.fn();
    render(<ResourceInfoPopover ownerId="42" onOpenChange={onOpenChange} />);

    open();
    await screen.findByTestId('resource-info-popover');

    expect(onOpenChange).toHaveBeenCalledWith(true);
  });

  it('does not trigger a host that listens on mousedown either', async () => {
    // Cards open on click, but a draggable card starts its drag on MOUSEDOWN. Only the click
    // half of the guard was covered, so removing the mousedown one was free.
    const onMouseDown = vi.fn();
    render(
      <div onMouseDown={onMouseDown}>
        <ResourceInfoPopover ownerId="42" />
      </div>,
    );

    fireEvent.mouseDown(screen.getByTestId('resource-info-trigger'));

    expect(onMouseDown).not.toHaveBeenCalled();
  });

  it('gives the panel and its editor tally an accessible name', async () => {
    const loadEditors = vi.fn().mockResolvedValue([
      { userId: '42', editedAt: '2026-09-07T09:12:00Z', editCount: 1 },
      { userId: '77', displayName: 'Grace', editedAt: '2026-09-06T09:12:00Z', editCount: 1 },
    ]);
    render(<ResourceInfoPopover ownerId="42" loadEditors={loadEditors} />);

    open();

    // Radix gives the panel role="dialog"; unnamed, a screen reader announces "dialog" and
    // nothing else.
    const panel = await screen.findByTestId('resource-info-popover');
    expect(panel).toHaveAttribute('aria-label', 'label');
    // The tally's words are IN the DOM (visually hidden), not an aria-label on a bare span:
    // that span is role=generic, which takes no name from the author, so most screen readers
    // would drop the label and read out a lone numeral.
    expect(await screen.findByText('editorCount:{"count":2}')).toBeInTheDocument();
  });

  it('says what counts as an edit, in text rather than a tooltip', async () => {
    // The hint is the only place the rule is stated ("saved a change", not "opened it"), and a
    // `title` reaches neither a touch screen nor a screen reader.
    const loadEditors = vi.fn().mockResolvedValue([
      { userId: '42', editedAt: '2026-09-07T09:12:00Z', editCount: 1 },
    ]);
    render(<ResourceInfoPopover ownerId="42" loadEditors={loadEditors} />);

    open();

    expect(await screen.findByText('editorsHint')).toBeInTheDocument();
  });

  it('gives every named person a face, resolved from their id', async () => {
    // The avatar comes from the app's PERSON component, which resolves from the user id - not
    // from the roster's `avatarUrl`. That is what lets a departed editor have a face too, and
    // it is a rendering change INSIDE the row every other assertion here reads, so it is worth
    // pinning rather than trusting.
    const loadEditors = vi.fn().mockResolvedValue([
      { userId: '77', displayName: 'Grace Hopper', editedAt: '2026-09-06T09:12:00Z', editCount: 1 },
    ]);
    render(<ResourceInfoPopover ownerId="42" loadEditors={loadEditors} />);

    open();

    const editor = await screen.findByTestId('resource-info-editor-77');
    // 77 is NOT in the roster (only 42 is), so the roster could offer nothing here.
    expect(editor).toHaveAttribute('data-state', 'resolved');
    expect(editor.querySelector('img')).toHaveAttribute('src', expect.stringContaining('/users/77/avatar'));
  });

  it('renders nothing when there is nothing to say about the resource', () => {
    const { container } = render(<ResourceInfoPopover />);

    expect(container).toBeEmptyDOMElement();
  });
});
