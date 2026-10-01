/**
 * @vitest-environment jsdom
 *
 * A side-panel chat that was on screen when the page went away by a reload comes back open.
 *
 * The reload that matters most is the one the product causes itself: an OAuth connect started
 * from a credential card in the panel sends the whole page to the provider and back, and the
 * panel used to come back closed, so the conversation waiting for that account never resumed.
 */
import * as React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';

const path = vi.hoisted(() => ({ current: '/fr/app/tables/5' }));
vi.mock('next/navigation', () => ({ usePathname: () => path.current }));

import { useMarkOnScreenAcrossReload, wasOnScreenBeforeReload } from '@/lib/sidePanel/onScreenAcrossReload';

function Chat({ tabId }: { tabId: string | null }) {
  useMarkOnScreenAcrossReload(tabId);
  return null;
}

beforeEach(() => {
  sessionStorage.clear();
  path.current = '/fr/app/tables/5';
});
afterEach(cleanup);

describe('onScreenAcrossReload', () => {
  it('a reload keeps the mark: nothing unmounts when the page goes away', () => {
    render(<Chat tabId="ai-chat" />);

    // No unmount: this is what a reload (or the OAuth redirect) leaves behind.
    expect(wasOnScreenBeforeReload('ai-chat', '/fr/app/tables/5')).toBe(true);
  });

  it('is keyed by the locale-free path, the one the OAuth round trip returns to', () => {
    render(<Chat tabId="ai-chat" />);

    expect(wasOnScreenBeforeReload('ai-chat', '/app/tables/5')).toBe(true);
    expect(wasOnScreenBeforeReload('ai-chat', '/en/app/tables/5')).toBe(true);
    expect(wasOnScreenBeforeReload('ai-chat', '/app/tables/6'), 'another page').toBe(false);
  });

  it('leaving the screen (panel closed, other tab, other page) removes the mark', () => {
    const view = render(<Chat tabId="ai-chat" />);

    view.unmount();

    expect(wasOnScreenBeforeReload('ai-chat', '/app/tables/5')).toBe(false);
  });

  it('follows the chat to the next page when it stays on screen across a navigation', () => {
    const view = render(<Chat tabId="ai-chat" />);

    path.current = '/fr/app/tables/9';
    view.rerender(<Chat tabId="ai-chat" />);

    expect(wasOnScreenBeforeReload('ai-chat', '/app/tables/5')).toBe(false);
    expect(wasOnScreenBeforeReload('ai-chat', '/app/tables/9')).toBe(true);
  });

  it('a null id marks nothing, and switching to null unmarks', () => {
    const view = render(<Chat tabId={null} />);
    expect(wasOnScreenBeforeReload('ai-chat', '/app/tables/5')).toBe(false);

    view.rerender(<Chat tabId="__chat_ia__" />);
    expect(wasOnScreenBeforeReload('__chat_ia__', '/app/tables/5')).toBe(true);

    view.rerender(<Chat tabId={null} />);
    expect(wasOnScreenBeforeReload('__chat_ia__', '/app/tables/5')).toBe(false);
  });

  it('only answers for the tab that was marked', () => {
    render(<Chat tabId="ai-chat" />);

    expect(wasOnScreenBeforeReload('__chat_ia__', '/app/tables/5')).toBe(false);
  });

  it('does not unmark a newer mark left by another chat on the same page', () => {
    const first = render(<Chat tabId="ai-chat" />);
    render(<Chat tabId="__chat_ia__" />);

    first.unmount();

    expect(wasOnScreenBeforeReload('__chat_ia__', '/app/tables/5')).toBe(true);
  });
});
