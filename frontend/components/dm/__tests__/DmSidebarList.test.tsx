// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({ useTranslations: (ns?: string) => (k: string) => `${ns}.${k}` }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));

const edition = vi.hoisted(() => ({ ce: false }));
vi.mock('@/lib/edition', () => ({ get IS_CE() { return edition.ce; }, get IS_MANAGED_CLOUD() { return !edition.ce; } }));
const myPartner = vi.hoisted(() => vi.fn());
vi.mock('@/lib/api/services/partner-program-api.service', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  partnerProgramApi: { myPartner: () => myPartner() },
}));

const { listThreads, openThread, deleteThread, getOrganization, getPublicProfileById, push, channelRef, nav } = vi.hoisted(() => ({
  listThreads: vi.fn(),
  openThread: vi.fn(),
  deleteThread: vi.fn(),
  getOrganization: vi.fn(),
  getPublicProfileById: vi.fn(),
  push: vi.fn(),
  channelRef: { handler: null as ((e: unknown) => void) | null },
  nav: { pathname: '/app/chat' },
}));
vi.mock('@/lib/api/dm-api', () => ({ dmApi: { listThreads, openThread, deleteThread } }));
vi.mock('@/lib/api/organization-api', () => ({ organizationApi: { getOrganization } }));
vi.mock('@/lib/api/unified-api-service', () => ({ unifiedApiService: { getPublicProfileById } }));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push }), usePathname: () => nav.pathname }));
vi.mock('@/hooks/useUserProfile', () => ({ useUserProfile: () => ({ profile: { id: '7' } }) }));
vi.mock('@/lib/stores/current-org-store', () => ({ useCurrentOrg: () => ({ currentOrgId: 'org-1' }) }));
vi.mock('@/lib/websocket/use-channel', () => ({
  useChannel: (_c: string | null, h: (e: unknown) => void) => { channelRef.handler = h; },
}));

import { DmSidebarList } from '../DmSidebarList';

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

const member = (id: string, name: string) => ({
  userId: Number(id), email: `${name}@x.com`, displayName: name, avatarUrl: null,
  role: 'MEMBER', joinedAt: '', isOwner: false,
});

const threadWith = (id: string, otherUserId: string, unread = 0) => ({
  id, otherUserId, lastMessageAt: null, lastMessagePreview: 'hey', unreadCount: unread, createdAt: '',
});

describe('DmSidebarList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    nav.pathname = '/app/chat';
    listThreads.mockResolvedValue([]);
    edition.ce = false;
    myPartner.mockReset().mockResolvedValue({ partner: null });
    getPublicProfileById.mockResolvedValue({ userId: 99, displayName: 'Zoe External', avatarUrl: null });
    getOrganization.mockResolvedValue({ id: 'org-1', members: [member('7', 'Me'), member('8', 'Bob'), member('9', 'Carol')] });
  });
  afterEach(() => cleanup());

  it('shows teammates (excluding self) as default contacts when there are no threads', async () => {
    renderWithClient(<DmSidebarList />);
    expect(await screen.findByText('Bob')).toBeInTheDocument();
    expect(screen.getByText('Carol')).toBeInTheDocument();
    expect(screen.queryByText('Me')).not.toBeInTheDocument();
  });

  it('shows no "no conversations yet" empty-state message when there are neither threads nor teammates', async () => {
    // Only self in the org → no teammate contact rows, and no threads.
    getOrganization.mockResolvedValue({ id: 'org-1', members: [member('7', 'Me')] });
    renderWithClient(<DmSidebarList />);

    await waitFor(() => expect(getOrganization).toHaveBeenCalled());
    // The empty-state paragraph (dm.noThreads → "No conversations yet") was removed.
    expect(screen.queryByText('dm.noThreads')).not.toBeInTheDocument();
  });

  it('does not repeat a teammate as a contact row when a thread already exists with them', async () => {
    listThreads.mockResolvedValue([
      { id: 't1', otherUserId: '8', lastMessageAt: null, lastMessagePreview: 'hey', unreadCount: 2, createdAt: '' },
    ]);
    renderWithClient(<DmSidebarList />);

    expect(await screen.findByText('Bob')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument(); // unread badge
    expect(screen.getAllByText('Bob')).toHaveLength(1); // not duplicated as a teammate row
    expect(screen.getByText('Carol')).toBeInTheDocument(); // Carol still a default contact
  });

  it('clicking a teammate opens/gets a thread and navigates to it', async () => {
    openThread.mockResolvedValue({ id: 't-new', otherUserId: '8' });
    renderWithClient(<DmSidebarList />);

    fireEvent.click(await screen.findByText('Bob'));

    await waitFor(() => expect(openThread).toHaveBeenCalledWith('8'));
    await waitFor(() => expect(push).toHaveBeenCalledWith('/app/messages/t-new'));
  });

  it('refetches the thread list on a dm-inbox event', async () => {
    renderWithClient(<DmSidebarList />);
    await waitFor(() => expect(listThreads).toHaveBeenCalledTimes(1));

    await act(async () => {
      channelRef.handler?.({ type: 'dm:incoming', threadId: 't1', message: {} });
    });

    await waitFor(() => expect(listThreads).toHaveBeenCalledTimes(2));
  });

  it('regression: highlights the conversation whose thread route is open', async () => {
    nav.pathname = '/app/messages/t1';
    listThreads.mockResolvedValue([threadWith('t1', '8'), threadWith('t2', '9')]);
    renderWithClient(<DmSidebarList />);

    const active = await screen.findByTestId('dm-thread-t1');
    expect(active).toHaveAttribute('data-active');
    expect(screen.getByTestId('dm-thread-t2')).not.toHaveAttribute('data-active');
  });

  it('demarcates workspace teammates from other conversations (divider + section headers)', async () => {
    // '8' is a teammate; '99' is NOT an org member → "other conversations".
    listThreads.mockResolvedValue([threadWith('t1', '8'), threadWith('t9', '99')]);
    renderWithClient(<DmSidebarList />);

    expect(await screen.findByText('dm.teammates')).toBeInTheDocument();
    expect(await screen.findByText('dm.otherConversations')).toBeInTheDocument();
    expect(screen.getByTestId('dm-groups-divider')).toBeInTheDocument();
    // The Teammates group opens with the same border-top demarcation as the
    // one above "Other conversations".
    expect(screen.getByTestId('dm-teammates-divider')).toBeInTheDocument();
    expect(screen.getByTestId('dm-teammates-divider').className)
      .toBe(screen.getByTestId('dm-groups-divider').className);
    // Non-teammate name resolves through the public profile, not the org member list.
    expect(await screen.findByText('Zoe External')).toBeInTheDocument();
  });

  it('filter=teammates hides the other conversations; filter=others hides the teammates', async () => {
    listThreads.mockResolvedValue([threadWith('t1', '8'), threadWith('t9', '99')]);
    const { unmount } = renderWithClient(<DmSidebarList filter="teammates" />);

    expect(await screen.findByText('Bob')).toBeInTheDocument();
    expect(screen.queryByText('dm.otherConversations')).not.toBeInTheDocument();
    unmount();

    renderWithClient(<DmSidebarList filter="others" />);
    expect(await screen.findByText('Zoe External')).toBeInTheDocument();
    expect(screen.queryByText('dm.teammates')).not.toBeInTheDocument();
    expect(screen.queryByText('Bob')).not.toBeInTheDocument();
    // No teammates group → its border-top demarcation must not render either.
    expect(screen.queryByTestId('dm-teammates-divider')).not.toBeInTheDocument();
  });

  it('the search input filters conversations by name', async () => {
    listThreads.mockResolvedValue([threadWith('t1', '8'), threadWith('t9', '99')]);
    renderWithClient(<DmSidebarList searchOpen />);
    await screen.findByText('Bob');

    fireEvent.change(screen.getByTestId('dm-search-input'), { target: { value: 'zoe' } });

    expect(await screen.findByText('Zoe External')).toBeInTheDocument();
    expect(screen.queryByText('Bob')).not.toBeInTheDocument();
    expect(screen.queryByText('Carol')).not.toBeInTheDocument();
  });

  it('regression: closing the search drops the filter (no invisible filtering)', async () => {
    listThreads.mockResolvedValue([threadWith('t1', '8'), threadWith('t9', '99')]);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { rerender } = render(
      <QueryClientProvider client={client}><DmSidebarList searchOpen /></QueryClientProvider>,
    );
    await screen.findByText('Bob');

    fireEvent.change(screen.getByTestId('dm-search-input'), { target: { value: 'zoe' } });
    expect(screen.queryByText('Bob')).not.toBeInTheDocument();

    // Toggle the search closed - the full list must come back.
    rerender(
      <QueryClientProvider client={client}><DmSidebarList searchOpen={false} /></QueryClientProvider>,
    );
    expect(await screen.findByText('Bob')).toBeInTheDocument();
    expect(screen.getByText('Zoe External')).toBeInTheDocument();
  });

  it('regression: NO delete affordance on teammate conversations, present on others', async () => {
    // '8' (Bob) is a workspace member; '99' (Zoe) is not.
    listThreads.mockResolvedValue([threadWith('t1', '8'), threadWith('t9', '99')]);
    renderWithClient(<DmSidebarList />);
    await screen.findByText('Zoe External');

    expect(screen.queryByTestId('dm-thread-delete-t1')).not.toBeInTheDocument();
    expect(screen.getByTestId('dm-thread-delete-t9')).toBeInTheDocument();
  });

  it('deleting a conversation asks for confirmation, calls the API, and refreshes the list', async () => {
    listThreads.mockResolvedValue([threadWith('t9', '99')]);
    deleteThread.mockResolvedValue({ deleted: true });
    renderWithClient(<DmSidebarList />);
    await screen.findByText('Zoe External');

    fireEvent.click(screen.getByTestId('dm-thread-delete-t9'));
    // Confirm modal (portal) - nothing deleted until confirmed.
    expect(deleteThread).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText('common.delete'));

    await waitFor(() => expect(deleteThread).toHaveBeenCalledWith('t9'));
    await waitFor(() => expect(listThreads).toHaveBeenCalledTimes(2)); // invalidated → refetch
    expect(push).not.toHaveBeenCalled(); // thread wasn't the open one
  });

  it('deleting the OPEN conversation navigates back home', async () => {
    nav.pathname = '/app/messages/t9';
    listThreads.mockResolvedValue([threadWith('t9', '99')]);
    deleteThread.mockResolvedValue({ deleted: true });
    renderWithClient(<DmSidebarList />);
    await screen.findByText('Zoe External');

    fireEvent.click(screen.getByTestId('dm-thread-delete-t9'));
    fireEvent.click(screen.getByText('common.delete'));

    await waitFor(() => expect(push).toHaveBeenCalledWith('/app/chat'));
  });

  it('shows skeleton rows while the thread/member queries load (no avatar pop-in)', async () => {
    let resolveThreads: (v: unknown) => void = () => {};
    listThreads.mockReturnValue(new Promise((res) => { resolveThreads = res; }));
    renderWithClient(<DmSidebarList />);

    expect(screen.getByTestId('dm-list-skeleton')).toBeInTheDocument();

    await act(async () => resolveThreads([]));
    await waitFor(() => expect(screen.queryByTestId('dm-list-skeleton')).not.toBeInTheDocument());
  });
});

describe('DmSidebarList: the partner a client came through', () => {
  const NORTHWIND = { user_id: '42', name: 'Northwind Studio', handle: 'northwind', tier: 'gold' };

  beforeEach(() => {
    vi.clearAllMocks();
    nav.pathname = '/app/chat';
    edition.ce = false;
    listThreads.mockResolvedValue([]);
    getOrganization.mockResolvedValue({ id: 'org-1', members: [member('7', 'Me'), member('8', 'Bob')] });
    getPublicProfileById.mockResolvedValue({ userId: 99, displayName: 'Zoe External', avatarUrl: null });
    myPartner.mockReset().mockResolvedValue({ partner: NORTHWIND });
    openThread.mockResolvedValue({ id: 'tp' });
  });
  afterEach(() => cleanup());

  it('is pinned on top with no conversation yet, and one click opens one', async () => {
    renderWithClient(<DmSidebarList />);

    const group = await screen.findByTestId('dm-partner-group');
    expect(group).toHaveTextContent('dm.yourPartner');
    expect(group).toHaveTextContent('Northwind Studio');
    // Above the teammates.
    expect(group.compareDocumentPosition(screen.getByText('Bob')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    fireEvent.click(screen.getByTestId('dm-partner-start'));
    await waitFor(() => expect(openThread).toHaveBeenCalledWith('42'));
    expect(push).toHaveBeenCalledWith('/app/messages/tp');
  });

  it('a conversation that cannot be opened leaves the row in place, to click again (no unhandled failure)', async () => {
    openThread.mockRejectedValueOnce(new Error('503'));
    renderWithClient(<DmSidebarList />);

    fireEvent.click(await screen.findByTestId('dm-partner-start'));
    await waitFor(() => expect(openThread).toHaveBeenCalledTimes(1));
    expect(push).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('dm-partner-start'));
    await waitFor(() => expect(push).toHaveBeenCalledWith('/app/messages/tp'));
  });

  it('with a conversation, the partner row is that thread, pinned, never under "other conversations" and never deletable', async () => {
    listThreads.mockResolvedValue([threadWith('tp', '42', 3), threadWith('t9', '99')]);
    renderWithClient(<DmSidebarList />);

    const group = await screen.findByTestId('dm-partner-group');
    await waitFor(() => expect(group).toHaveTextContent('Northwind Studio'));
    expect(group.querySelector('[data-testid="dm-thread-tp"]')).toBeTruthy();
    expect(screen.queryByTestId('dm-partner-start')).not.toBeInTheDocument();
    expect(screen.queryByTestId('dm-thread-delete-tp')).not.toBeInTheDocument();
    // The other conversation keeps its place and its delete button.
    expect(screen.getByTestId('dm-thread-delete-t9')).toBeInTheDocument();
    expect(screen.getAllByTestId('dm-thread-tp')).toHaveLength(1);
  });

  it('a partner with a private profile is still there, named "Your partner"', async () => {
    myPartner.mockResolvedValue({ partner: { user_id: '42', name: null, handle: null, tier: null } });
    renderWithClient(<DmSidebarList />);

    const group = await screen.findByTestId('dm-partner-group');
    expect(screen.getByTestId('dm-partner-start')).toHaveTextContent('dm.yourPartner');
    expect(group).toBeInTheDocument();
  });

  it('no partner, a self-hosted build, or the teammates filter: no partner row', async () => {
    myPartner.mockResolvedValue({ partner: null });
    const first = renderWithClient(<DmSidebarList />);
    await screen.findByText('Bob');
    expect(screen.queryByTestId('dm-partner-group')).not.toBeInTheDocument();
    first.unmount();

    edition.ce = true;
    myPartner.mockClear();
    myPartner.mockResolvedValue({ partner: NORTHWIND });
    const second = renderWithClient(<DmSidebarList />);
    await screen.findByText('Bob');
    expect(myPartner).not.toHaveBeenCalled();
    expect(screen.queryByTestId('dm-partner-group')).not.toBeInTheDocument();
    second.unmount();

    edition.ce = false;
    renderWithClient(<DmSidebarList filter="teammates" />);
    await screen.findByText('Bob');
    await waitFor(() => expect(myPartner).toHaveBeenCalled());
    expect(screen.queryByTestId('dm-partner-group')).not.toBeInTheDocument();
  });

  it('the search finds the partner by name, and leaves them out when it does not match', async () => {
    renderWithClient(<DmSidebarList searchOpen />);
    await screen.findByTestId('dm-partner-group');

    fireEvent.change(screen.getByTestId('dm-search-input'), { target: { value: 'north' } });
    expect(screen.getByTestId('dm-partner-group')).toBeInTheDocument();
    expect(screen.queryByText('Bob')).not.toBeInTheDocument();

    fireEvent.change(screen.getByTestId('dm-search-input'), { target: { value: 'bob' } });
    expect(screen.queryByTestId('dm-partner-group')).not.toBeInTheDocument();
  });

  it('a partner who is also a teammate is listed once, as the partner', async () => {
    getOrganization.mockResolvedValue({ id: 'org-1', members: [member('7', 'Me'), member('42', 'Northwind Studio')] });
    renderWithClient(<DmSidebarList />);

    await screen.findByTestId('dm-partner-group');
    expect(screen.getAllByText('Northwind Studio')).toHaveLength(1);
  });
});

