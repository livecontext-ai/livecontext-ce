import { describe, it, expect } from 'vitest';
import { shouldAutoLoadConversations } from '../shouldAutoLoadConversations';

const base = {
  isAuthenticated: true,
  currentView: 'chat',
  urlConversationId: null as string | null,
  pathname: '/app/chat',
};

describe('shouldAutoLoadConversations', () => {
  // Regression: the aggregated Board surface blanked the sidebar because 'board' was
  // missing from the auto-load allowlist. These two pin it (view AND path).
  it('auto-loads on the Board view so conversation titles stay visible', () => {
    expect(
      shouldAutoLoadConversations({ ...base, currentView: 'board', pathname: '/app/other' }),
    ).toBe(true);
  });

  it('auto-loads on the /app/board path', () => {
    expect(
      shouldAutoLoadConversations({ ...base, currentView: 'unknown', pathname: '/app/board' }),
    ).toBe(true);
  });

  it('auto-loads on a known resource view (workflow)', () => {
    expect(
      shouldAutoLoadConversations({ ...base, currentView: 'workflow', pathname: '/x' }),
    ).toBe(true);
  });

  it('auto-loads when a conversation id is in the URL even on an unlisted surface', () => {
    expect(
      shouldAutoLoadConversations({
        ...base,
        currentView: 'unknown',
        pathname: '/somewhere',
        urlConversationId: 'conv-1',
      }),
    ).toBe(true);
  });

  it('does NOT auto-load on an unlisted view with no matching path / conversation', () => {
    expect(
      shouldAutoLoadConversations({ ...base, currentView: 'unknown', pathname: '/somewhere' }),
    ).toBe(false);
  });

  it('never auto-loads when unauthenticated, even on the Board', () => {
    expect(
      shouldAutoLoadConversations({
        ...base,
        isAuthenticated: false,
        currentView: 'board',
        pathname: '/app/board',
      }),
    ).toBe(false);
  });
});
