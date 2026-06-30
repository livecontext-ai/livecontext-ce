// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({ useTranslations: (ns?: string) => (k: string) => `${ns}.${k}` }));

// Reuse the real chat components in the app; stub them here so the test asserts what DmThreadView
// FEEDS them (message shape, minimal composer) without pulling the full chat UI into jsdom.
vi.mock('@/components/chat/MessageHistory', () => ({
  MessageHistory: ({ messages, attachmentUrlResolver, messageAvatars }: any) => (
    <div
      data-testid="message-history"
      data-resolved-url={attachmentUrlResolver ? attachmentUrlResolver('SID') : ''}
      data-peer-avatar-name={messageAvatars?.assistant?.name ?? ''}
      data-my-avatar-name={messageAvatars?.user?.name ?? ''}
    >
      {messages.map((m: any) => (
        <div key={m.id} data-role={m.role} data-attachments={m.attachments ? m.attachments.length : 0}>{m.content}</div>
      ))}
    </div>
  ),
}));
vi.mock('@/components/chat/MessageComposer', () => ({
  MessageComposer: ({ inputValue, onInputChange, onSendMessage, minimal }: any) => (
    <div>
      <input
        data-testid="dm-composer"
        data-minimal={String(!!minimal)}
        value={inputValue}
        onChange={(e) => onInputChange(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') onSendMessage(); }}
      />
      {/* Simulates the composer sending an attachment-only message (paperclip flow). */}
      <button
        data-testid="dm-send-attachment"
        onClick={() => onSendMessage(undefined, [{ storageId: 'SID', type: 'IMAGE', fileName: 'cat.png', mimeType: 'image/png' }])}
      />
    </div>
  ),
}));

const { getMessages, sendMessage, markRead, getPublicProfileById, channelRef } = vi.hoisted(() => ({
  getMessages: vi.fn(),
  sendMessage: vi.fn(),
  markRead: vi.fn(),
  getPublicProfileById: vi.fn(),
  channelRef: { handler: null as ((e: unknown) => void) | null },
}));
const { setPeer, clearPeer } = vi.hoisted(() => ({ setPeer: vi.fn(), clearPeer: vi.fn() }));
vi.mock('@/lib/api/dm-api', () => ({
  dmApi: {
    getMessages,
    sendMessage,
    markRead,
    // Pure URL builder - mirror the real implementation so resolver assertions are meaningful.
    attachmentUrl: (threadId: string, storageId: string) =>
      `/api/proxy/dm/threads/${encodeURIComponent(threadId)}/attachments/${encodeURIComponent(storageId)}`,
  },
}));
vi.mock('@/lib/api/unified-api-service', () => ({ unifiedApiService: { getPublicProfileById } }));
vi.mock('@/lib/websocket/use-channel', () => ({
  useChannel: (_channel: string | null, handler: (e: unknown) => void) => {
    channelRef.handler = handler;
  },
}));
vi.mock('@/lib/stores/dm-peer-store', () => ({
  useDmPeerStore: (sel: (s: { peer: null; setPeer: typeof setPeer; clearPeer: typeof clearPeer }) => unknown) =>
    sel({ peer: null, setPeer, clearPeer }),
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ user: { name: 'Me', picture: null }, avatarUrl: '/api/users/7/avatar', isAuthenticated: true }),
}));

import DmThreadView from '../DmThreadView';

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

const msg = (id: string, sender: string, content: string, created: string) => ({
  id, threadId: 't1', senderUserId: sender, content, readAt: null, createdAt: created,
});

describe('DmThreadView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    markRead.mockResolvedValue({ markedRead: 0 });
    getPublicProfileById.mockResolvedValue({ userId: 8, displayName: 'Bob', avatarUrl: null });
  });
  afterEach(() => cleanup());

  it('renders history via MessageHistory (mine→user, other→assistant) and marks the thread read', async () => {
    getMessages.mockResolvedValue({
      items: [
        msg('m2', '8', 'hi from bob', '2026-01-02T00:00:01Z'),
        msg('m1', '7', 'hi from me', '2026-01-02T00:00:00Z'),
      ],
      totalCount: 2, page: 0, size: 50,
    });

    renderWithClient(<DmThreadView threadId="t1" otherUserId="8" />);

    const mine = await screen.findByText('hi from me');
    const theirs = screen.getByText('hi from bob');
    expect(mine).toHaveAttribute('data-role', 'user'); // my message → right bubble
    expect(theirs).toHaveAttribute('data-role', 'assistant'); // their message → left
    await waitFor(() => expect(markRead).toHaveBeenCalledWith('t1'));
  });

  it('renders the composer in minimal (DM) mode - no local header', async () => {
    getMessages.mockResolvedValue({ items: [], totalCount: 0, page: 0, size: 50 });
    renderWithClient(<DmThreadView threadId="t1" otherUserId="8" />);
    expect(await screen.findByTestId('dm-composer')).toHaveAttribute('data-minimal', 'true');
    // The peer (avatar + name) lives in the AppHeader now, so there is no local <header>.
    expect(document.querySelector('header')).toBeNull();
  });

  it('publishes the other participant to the dm-peer store and clears it on unmount', async () => {
    getMessages.mockResolvedValue({ items: [], totalCount: 0, page: 0, size: 50 });
    const { unmount } = renderWithClient(<DmThreadView threadId="t1" otherUserId="8" />);

    await waitFor(() =>
      expect(setPeer).toHaveBeenCalledWith({ userId: '8', displayName: 'Bob' }),
    );
    unmount();
    expect(clearPeer).toHaveBeenCalled();
  });

  it('appends a live WS message and de-dups a repeat of the same id', async () => {
    getMessages.mockResolvedValue({ items: [], totalCount: 0, page: 0, size: 50 });

    renderWithClient(<DmThreadView threadId="t1" otherUserId="8" />);
    await waitFor(() => expect(channelRef.handler).toBeTruthy());

    await act(async () => {
      channelRef.handler!({ type: 'message:new', threadId: 't1', message: msg('m9', '8', 'live hello', '2026-01-03T00:00:00Z') });
    });
    expect(await screen.findByText('live hello')).toBeInTheDocument();

    await act(async () => {
      channelRef.handler!({ type: 'message:new', threadId: 't1', message: msg('m9', '8', 'live hello', '2026-01-03T00:00:00Z') });
    });
    expect(screen.getAllByText('live hello')).toHaveLength(1);
  });

  it('sends a message from the composer (Enter) and appends the result', async () => {
    getMessages.mockResolvedValue({ items: [], totalCount: 0, page: 0, size: 50 });
    sendMessage.mockResolvedValue(msg('m5', '7', 'typed message', '2026-01-04T00:00:00Z'));

    renderWithClient(<DmThreadView threadId="t1" otherUserId="8" />);
    const composer = await screen.findByTestId('dm-composer');

    fireEvent.change(composer, { target: { value: 'typed message' } });
    fireEvent.keyDown(composer, { key: 'Enter' });

    expect(await screen.findByText('typed message')).toBeInTheDocument();
    expect(sendMessage).toHaveBeenCalledWith('t1', 'typed message', undefined);
  });

  it('sends an attachment-only message (no text) through dmApi with the refs', async () => {
    getMessages.mockResolvedValue({ items: [], totalCount: 0, page: 0, size: 50 });
    sendMessage.mockResolvedValue({
      ...msg('m6', '7', '', '2026-01-04T00:00:01Z'),
      attachments: [{ storageId: 'SID', type: 'IMAGE', fileName: 'cat.png', mimeType: 'image/png' }],
    });

    renderWithClient(<DmThreadView threadId="t1" otherUserId="8" />);
    fireEvent.click(await screen.findByTestId('dm-send-attachment'));

    await waitFor(() =>
      expect(sendMessage).toHaveBeenCalledWith('t1', '', [
        { storageId: 'SID', type: 'IMAGE', fileName: 'cat.png', mimeType: 'image/png' },
      ]),
    );
    // The saved message carries its attachment into the mapped history entry.
    await waitFor(() => {
      const rows = screen.getByTestId('message-history').querySelectorAll('[data-attachments="1"]');
      expect(rows).toHaveLength(1);
    });
  });

  it('resolves attachment URLs through the DM-scoped endpoint (not the tenant-scoped chat one)', async () => {
    getMessages.mockResolvedValue({ items: [], totalCount: 0, page: 0, size: 50 });
    sendMessage.mockResolvedValue(msg('m5', '7', 'x', '2026-01-04T00:00:00Z'));
    renderWithClient(<DmThreadView threadId="t1" otherUserId="8" />);

    // Force at least one message so MessageHistory renders.
    const composer = await screen.findByTestId('dm-composer');
    fireEvent.change(composer, { target: { value: 'x' } });
    fireEvent.keyDown(composer, { key: 'Enter' });

    const history = await screen.findByTestId('message-history');
    expect(history.getAttribute('data-resolved-url')).toBe('/api/proxy/dm/threads/t1/attachments/SID');
  });

  it('passes per-message avatars (peer + me) to MessageHistory', async () => {
    getMessages.mockResolvedValue({
      items: [msg('m1', '8', 'hello', '2026-01-02T00:00:00Z')], totalCount: 1, page: 0, size: 50,
    });
    renderWithClient(<DmThreadView threadId="t1" otherUserId="8" />);

    const history = await screen.findByTestId('message-history');
    await waitFor(() => expect(history.getAttribute('data-peer-avatar-name')).toBe('Bob'));
    expect(history.getAttribute('data-my-avatar-name')).toBe('Me');
  });

  it('shows a skeleton while the history loads (no empty-state flash)', async () => {
    let resolveHistory: (v: unknown) => void = () => {};
    getMessages.mockReturnValue(new Promise((res) => { resolveHistory = res; }));
    renderWithClient(<DmThreadView threadId="t1" otherUserId="8" />);

    expect(screen.getByTestId('dm-thread-skeleton')).toBeInTheDocument();
    expect(screen.queryByText('dm.threadEmpty')).not.toBeInTheDocument();

    await act(async () => resolveHistory({ items: [], totalCount: 0, page: 0, size: 50 }));
    await waitFor(() => expect(screen.queryByTestId('dm-thread-skeleton')).not.toBeInTheDocument());
    expect(screen.getByText('dm.threadEmpty')).toBeInTheDocument();
  });
});
