// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import en from '@/messages/en.json';

// A persisted "[Error] RESTRICTED_DATA_PROVIDER_NOT_ALLOWED: ..." assistant turn must show the
// translated explanation (switch to an approved provider) above the raw backend text, and only
// for that refusal.
const lookup = (key: string): string =>
  key.split('.').reduce<unknown>((node, part) => (node as Record<string, unknown>)?.[part], en) as string ?? key;
vi.mock('next-intl', () => ({ useTranslations: () => lookup }));
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/',
  Link: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/components/MarkdownRender', () => ({
  default: ({ text }: { text: string }) => <div data-testid="md">{text}</div>,
}));
vi.mock('@/components/chat/ActivityFeed', () => ({ ActivityFeed: () => null }));
vi.mock('@/hooks/useCurrentView', () => ({ useCurrentView: () => ({ currentView: 'chat' }) }));
vi.mock('@/components/chat/WorkflowSuggestions', () => ({ WorkflowSuggestions: () => null }));
vi.mock('@/components/chat/DataSourceDisplayMode', () => ({ default: () => null }));
vi.mock('@/components/chat/MessageActions', () => ({ MessageActions: () => null }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { getTokenProvider: () => null, getAuthToken: async () => null } }));

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
  timestamp: '2026-09-23T10:00:00Z',
}) as never;

const REFUSAL =
  "[Error] RESTRICTED_DATA_PROVIDER_NOT_ALLOWED: Data from Gmail or Google Drive cannot be sent to the model provider 'deepseek'.";
const EXPLANATION = en.errors.restrictedDataProvider;

describe('MessageHistory - restricted-data refusal', () => {
  it('shows the translated explanation above a persisted [Error] refusal', () => {
    render(<MessageHistory messages={[message('m1', 'user', 'summarize my inbox'), message('m2', 'assistant', REFUSAL)]} />);

    expect(screen.getByTestId('restricted-data-refusal')).toHaveTextContent(EXPLANATION);
    // The raw backend text stays available below it.
    expect(screen.getByText(REFUSAL)).toBeInTheDocument();
  });

  it('does not show it for other assistant errors', () => {
    render(<MessageHistory messages={[message('m1', 'assistant', '[Error] Insufficient credits')]} />);

    expect(screen.queryByTestId('restricted-data-refusal')).not.toBeInTheDocument();
    expect(screen.queryByText(EXPLANATION)).not.toBeInTheDocument();
  });

  it('does not show it on a user message quoting the token', () => {
    render(<MessageHistory messages={[message('m1', 'user', 'what does RESTRICTED_DATA_PROVIDER_NOT_ALLOWED mean?')]} />);

    expect(screen.queryByTestId('restricted-data-refusal')).not.toBeInTheDocument();
  });
});
