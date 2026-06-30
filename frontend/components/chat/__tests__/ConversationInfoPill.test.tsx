/**
 * @vitest-environment jsdom
 *
 * Regression test for the sidebar hover pill.
 *
 * Bug 2026-05-20: every conversation row showed the same relative time
 * ("just now" / identical "Xm ago") because the compact projection in
 * useConversationList strips createdAt/updatedAt before pushing entries
 * into UnifiedAppContext, and ConversationSidebar's merge then synthesised
 * a placeholder Conversation with `new Date().toISOString()` for both
 * timestamps - so every row inherited the *current* moment regardless of
 * the actual last activity.
 *
 * This test guards the pill's contract: given two conversations with
 * different `updatedAt`s, the pill MUST render different relative times.
 * If the upstream fix regresses (timestamps collapse to a single value),
 * the rendered text falls back to "just now" for both inputs and the
 * second assertion fails.
 */
import { describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';
import * as React from 'react';
import { ConversationInfoPill } from '../ConversationInfoPill';
import type { Conversation } from '@/lib/api/conversation.types';

vi.mock('next-intl', () => ({
  useLocale: () => 'en',
  // One translator stands in for both useTranslations('sidebar.infoPill') and
  // useTranslations('runs'); it is key-aware so the relative-time keys render
  // real strings instead of colliding with the message-count {count}.
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => {
    switch (key) {
      case 'justNow':
        return 'just now';
      case 'minutesAgo':
        return `${vars?.count}m ago`;
      case 'hoursAgo':
        return `${vars?.count}h ago`;
      case 'daysAgo':
        return `${vars?.count}d ago`;
      case 'never':
        return 'never';
      case 'messageCount':
        return `${vars?.count} msg`;
      default:
        return key;
    }
  },
}));

function makeConv(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: overrides.id ?? 'conv-1',
    userId: 'user-1',
    title: 'A chat',
    model: 'deepseek-chat',
    provider: 'deepseek',
    createdAt: '2026-05-20T00:00:00Z',
    updatedAt: '2026-05-20T00:00:00Z',
    messageCount: 0,
    ...overrides,
  };
}

describe('ConversationInfoPill', () => {
  it('renders distinct relative times for two conversations with distinct updatedAt', () => {
    const now = new Date('2026-05-20T12:00:00Z').getTime();
    vi.spyOn(Date, 'now').mockReturnValue(now);
    // Date constructor used inside formatRelativeTime for `new Date()` -
    // jsdom's Date.now is what `new Date()` calls under the hood.
    vi.useFakeTimers();
    vi.setSystemTime(new Date(now));

    try {
      const fresh = makeConv({ id: 'fresh', updatedAt: '2026-05-20T11:59:30Z' }); // 30s ago → "just now"
      const stale = makeConv({ id: 'stale', updatedAt: '2026-05-20T09:00:00Z' }); // 3h ago

      const { container: c1 } = render(<ConversationInfoPill conversation={fresh} />);
      const { container: c2 } = render(<ConversationInfoPill conversation={stale} />);

      const text1 = c1.textContent ?? '';
      const text2 = c2.textContent ?? '';

      expect(text1).toContain('just now');
      expect(text2).toContain('3h ago');
      expect(text1).not.toEqual(text2);
    } finally {
      vi.useRealTimers();
    }
  });
});
