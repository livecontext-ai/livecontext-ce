// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

// MessageHistory is shared by EVERY agent chat - these tests pin the two contracts of
// the DM additions: (1) chat behaviour is a strict no-op without the new props, and
// (2) DM mode renders per-message avatars, both-side attachments, and resolves
// attachment URLs through the injected (DM-scoped) resolver.
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
// Some transitive deps pull next-intl's createNavigation, which vitest can't resolve
// against next/navigation - stub the locale-aware navigation module entirely.
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/',
  Link: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/components/MarkdownRender', () => ({
  default: ({ text }: { text: string }) => <div data-testid="md">{text}</div>,
}));
vi.mock('@/components/chat/ActivityFeed', () => ({ ActivityFeed: () => null }));
vi.mock('@/components/chat/AuthenticatedImage', () => ({
  AuthenticatedImage: ({ src, alt }: { src: string; alt: string }) => (
    // eslint-disable-next-line @next/next/no-img-element
    <img data-testid="auth-img" src={src} alt={alt} />
  ),
}));
vi.mock('@/hooks/useCurrentView', () => ({ useCurrentView: () => ({ currentView: 'chat' }) }));
vi.mock('@/components/chat/WorkflowSuggestions', () => ({ WorkflowSuggestions: () => null }));
vi.mock('@/components/chat/DataSourceDisplayMode', () => ({ default: () => null }));
vi.mock('@/components/chat/MessageActions', () => ({ MessageActions: () => null }));
vi.mock('@/components/agents/AvatarPicker', () => ({
  AvatarDisplay: ({ name }: { name?: string }) => <div data-testid="msg-avatar">{name}</div>,
}));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { getTokenProvider: () => null } }));

import { MessageHistory } from '../MessageHistory';

beforeAll(() => {
  // jsdom has no scrollTo/scrollIntoView implementations.
  Element.prototype.scrollTo = vi.fn();
  Element.prototype.scrollIntoView = vi.fn();
});
afterEach(cleanup);

const message = (id: string, role: 'user' | 'assistant', content: string, attachments?: unknown[]) => ({
  id,
  conversationId: 'c1',
  role,
  content,
  ...(attachments ? { attachments } : {}),
  model: '',
  timestamp: '2026-06-10T10:00:00Z',
}) as never;

const IMG_ATT = { storageId: 'SID-1', type: 'IMAGE', fileName: 'cat.png', mimeType: 'image/png' };
const PDF_ATT = { storageId: 'SID-2', type: 'PDF', fileName: 'doc.pdf', mimeType: 'application/pdf' };

describe('MessageHistory - DM-mode props are a strict no-op for agent chats', () => {
  it('renders no avatars and no attachment chips without the new props (regression guard)', () => {
    render(
      <MessageHistory
        messages={[message('m1', 'user', 'hello'), message('m2', 'assistant', 'hi there')]}
      />,
    );

    expect(screen.getByText('hello')).toBeInTheDocument();
    expect(screen.getByText('hi there')).toBeInTheDocument();
    expect(screen.queryByTestId('msg-avatar')).not.toBeInTheDocument();
    expect(screen.queryByTestId('auth-img')).not.toBeInTheDocument();
  });

  it('keeps rendering USER attachments through the default chat download URL', () => {
    render(<MessageHistory messages={[message('m1', 'user', 'see this', [IMG_ATT])]} />);

    const img = screen.getByTestId('auth-img');
    expect(img).toHaveAttribute('src', '/api/proxy/v3/chat/attachments/SID-1');
  });
});

describe('MessageHistory - DM mode', () => {
  const dmProps = {
    attachmentUrlResolver: (sid: string) => `/api/proxy/dm/threads/t1/attachments/${sid}`,
    messageAvatars: {
      user: { avatarUrl: '/api/users/7/avatar', name: 'Me' },
      assistant: { avatarUrl: '/api/users/8/avatar', name: 'Bob' },
    },
  };

  it('renders the sender avatar in front of EVERY message (peer + mine)', () => {
    render(
      <MessageHistory
        messages={[message('m1', 'assistant', 'their msg'), message('m2', 'user', 'my msg')]}
        {...dmProps}
      />,
    );

    const avatars = screen.getAllByTestId('msg-avatar').map((el) => el.textContent);
    expect(avatars).toEqual(['Bob', 'Me']);
  });

  it('renders the OTHER participant\'s (assistant-side) attachments via the DM resolver', () => {
    render(
      <MessageHistory messages={[message('m1', 'assistant', 'sent you a pic', [IMG_ATT])]} {...dmProps} />,
    );

    expect(screen.getByTestId('auth-img')).toHaveAttribute(
      'src',
      '/api/proxy/dm/threads/t1/attachments/SID-1',
    );
  });

  it('renders an attachment-only message (no text) - previously dropped by the hasContent gate', () => {
    render(<MessageHistory messages={[message('m1', 'user', '', [PDF_ATT])]} {...dmProps} />);

    expect(screen.getByText('doc.pdf')).toBeInTheDocument();
    expect(screen.queryByTestId('md')).not.toBeInTheDocument(); // no empty markdown body
  });
});
