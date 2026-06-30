/**
 * @vitest-environment jsdom
 *
 * The activity card open-state is shared between the header toggle (a different
 * subtree) and the card. These pin the provider/consumer contract and the
 * safe out-of-provider fallback.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';
import {
  ConversationActivityProvider,
  useConversationActivity,
} from '@/contexts/ConversationActivityContext';

function Probe() {
  const { isOpen, toggle, setOpen } = useConversationActivity();
  return (
    <div>
      <span data-testid="state">{isOpen ? 'open' : 'closed'}</span>
      <button onClick={toggle}>toggle</button>
      <button onClick={() => setOpen(false)}>close</button>
    </div>
  );
}

describe('ConversationActivityContext', () => {
  beforeEach(() => window.localStorage.clear());

  it('defaults to closed and toggles open/closed', () => {
    render(
      <ConversationActivityProvider>
        <Probe />
      </ConversationActivityProvider>,
    );
    expect(screen.getByTestId('state').textContent).toBe('closed');
    fireEvent.click(screen.getByText('toggle'));
    expect(screen.getByTestId('state').textContent).toBe('open');
    fireEvent.click(screen.getByText('toggle'));
    expect(screen.getByTestId('state').textContent).toBe('closed');
  });

  it('setOpen(false) is idempotent and does not throw', () => {
    render(
      <ConversationActivityProvider>
        <Probe />
      </ConversationActivityProvider>,
    );
    fireEvent.click(screen.getByText('close'));
    expect(screen.getByTestId('state').textContent).toBe('closed');
  });

  it('persists the open state to localStorage (one global key) and restores it on remount', () => {
    const { unmount } = render(
      <ConversationActivityProvider>
        <Probe />
      </ConversationActivityProvider>,
    );
    fireEvent.click(screen.getByText('toggle'));
    expect(screen.getByTestId('state').textContent).toBe('open');
    expect(window.localStorage.getItem('lc.conversationActivity.open')).toBe('1');
    unmount();

    // Remount (e.g. navigating to another conversation / reload): the GLOBAL
    // preference is restored, so the card stays open without per-conversation keys.
    render(
      <ConversationActivityProvider>
        <Probe />
      </ConversationActivityProvider>,
    );
    expect(screen.getByTestId('state').textContent).toBe('open');

    // Closing persists '0' and is restored as closed.
    fireEvent.click(screen.getByText('close'));
    expect(window.localStorage.getItem('lc.conversationActivity.open')).toBe('0');
  });

  it('falls back to a no-op (closed) when used outside the provider', () => {
    render(<Probe />);
    expect(screen.getByTestId('state').textContent).toBe('closed');
    // toggle is a no-op fallback - clicking must not throw or change state
    fireEvent.click(screen.getByText('toggle'));
    expect(screen.getByTestId('state').textContent).toBe('closed');
  });
});
