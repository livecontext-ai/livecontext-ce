import { describe, it, expect, vi, beforeEach } from 'vitest';

// Messages is a pure sidebar view, not a standalone page: the bare /app/messages route must
// NOT render a "select a conversation" placeholder. It redirects any stale link (old bookmark,
// deep link) back to the Home new-chat view. Pre-fix this page rendered a centered placeholder
// and never called redirect - so this test fails on the old code and passes on the new one.
const redirect = vi.fn();
vi.mock('next/navigation', () => ({ redirect: (...args: unknown[]) => redirect(...args) }));

import MessagesIndexPage from '../page';

describe('MessagesIndexPage (bare /app/messages)', () => {
  beforeEach(() => redirect.mockClear());

  it('redirects to the Home new-chat view instead of rendering a landing placeholder', () => {
    MessagesIndexPage();
    expect(redirect).toHaveBeenCalledTimes(1);
    expect(redirect).toHaveBeenCalledWith('/app/chat');
  });
});
