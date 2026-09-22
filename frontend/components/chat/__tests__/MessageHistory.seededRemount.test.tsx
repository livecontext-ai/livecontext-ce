// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

/**
 * The render gate the end-of-stream fix depends on - a GUARD, not a regression test.
 *
 * The condition it pins (skeleton only when the list is empty) already existed; both tests here
 * pass on the pre-change tree. What is new is that something now RELIES on it: when the reply
 * is persisted, the chat page started from /app/chat gets its own /app/c/{id} route, which
 * swaps the page component for a fresh instance that immediately reports `loading` while it
 * refetches. It does not blank only because its message list was seeded from the previous
 * mount. Every other test of that mechanism is at hook level; this is the one that would catch
 * someone "simplifying" the condition to a plain `if (loading)` and silently bringing the
 * blank frame back.
 */
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/',
  Link: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/components/MarkdownRender', () => ({
  default: ({ text }: { text: string }) => <div data-testid="md">{text}</div>,
}));
vi.mock('@/components/chat/ActivityFeed', () => ({ ActivityFeed: () => null }));
vi.mock('@/components/chat/AuthenticatedImage', () => ({ AuthenticatedImage: () => null }));
vi.mock('@/hooks/useCurrentView', () => ({ useCurrentView: () => ({ currentView: 'chat' }) }));
vi.mock('@/components/chat/WorkflowSuggestions', () => ({ WorkflowSuggestions: () => null }));
vi.mock('@/components/chat/DataSourceDisplayMode', () => ({ default: () => null }));
vi.mock('@/components/chat/MessageActions', () => ({ MessageActions: () => null }));
vi.mock('@/components/agents/AvatarPicker', () => ({ AvatarDisplay: () => null }));
vi.mock('@/lib/api/api-client', () => ({
  apiClient: { getTokenProvider: () => null, getAuthToken: async () => null },
}));
vi.mock('@/components/chat/MessageSkeleton', () => ({
  MessageSkeleton: () => <div data-testid="message-skeleton" />,
}));

import { MessageHistory } from '../MessageHistory';

beforeAll(() => {
  Element.prototype.scrollTo = vi.fn();
  Element.prototype.scrollIntoView = vi.fn();
});
afterEach(cleanup);

const message = (id: string, role: 'user' | 'assistant', content: string) => ({
  id,
  conversationId: 'c1',
  role,
  content,
  model: '',
  timestamp: '2026-09-12T10:00:00Z',
}) as never;

describe('MessageHistory - the remount after a stream must not blank', () => {
  it('shows the seeded transcript, not a skeleton, while the confirming fetch runs', () => {
    render(
      <MessageHistory
        messages={[message('m1', 'user', 'hello'), message('m2', 'assistant', 'hi there')]}
        loading
      />,
    );

    expect(screen.queryByTestId('message-skeleton')).not.toBeInTheDocument();
    expect(screen.getByText('hello')).toBeInTheDocument();
    expect(screen.getByText('hi there')).toBeInTheDocument();
  });

  it('still shows the skeleton when there is genuinely nothing to paint', () => {
    // The other half of the contract: opening a conversation cold has no seed, and hiding the
    // skeleton there would trade a flash for a blank screen.
    render(<MessageHistory messages={[]} loading />);

    expect(screen.getByTestId('message-skeleton')).toBeInTheDocument();
  });
});
