// @vitest-environment jsdom
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));
vi.mock('@/components/ui/service-icon', () => ({ ServiceIcon: ({ iconSlug }: any) => <span data-icon={iconSlug} /> }));

import { ChatDestinationCard, displayOf } from '../ChatDestinationCard';

const destination = (overrides: Record<string, unknown> = {}) => ({
  linkId: 'link-1', channel: 'teams', credentialId: 5, botUsername: 'lc_bot', chatId: 'C1', chatTitle: '#ops',
  chatType: 'channel', isDefault: true, active: true, verifiedAt: '2026-09-20T10:00:00Z', lastError: null,
  allowedUserIds: [] as string[], ...overrides,
});

afterEach(cleanup);

describe('ChatDestinationCard', () => {
  it('names the chat, its service (with the credential integration icon) and the default badge', () => {
    const { container } = render(<ChatDestinationCard destination={destination()} />);

    expect(screen.getByText('#ops')).toBeTruthy();
    expect(screen.getByText('Microsoft Teams')).toBeTruthy();
    expect(container.querySelector('[data-icon]')?.getAttribute('data-icon')).toBe('microsoftteams');
    expect(screen.getByText('defaultBadge')).toBeTruthy();
    expect(screen.getByText('statusWorking')).toBeTruthy();
  });

  it('names the bot that sends only when asked (Settings > Channels does not)', () => {
    const { rerender } = render(<ChatDestinationCard destination={destination()} />);
    expect(screen.queryByTestId('chat-destination-bot')).toBeNull();

    rerender(<ChatDestinationCard destination={destination()} showBot />);
    expect(screen.getByTestId('chat-destination-bot').textContent).toBe('sentByBot:{"bot":"lc_bot"}');
  });

  it('shows no bot line when the account has no known bot name', () => {
    render(<ChatDestinationCard destination={destination({ botUsername: null })} showBot />);

    expect(screen.queryByTestId('chat-destination-bot')).toBeNull();
  });

  it('says a destination nothing reached is not working, who may decide, and its last error', () => {
    render(<ChatDestinationCard
      destination={destination({ verifiedAt: null, isDefault: false, allowedUserIds: ['1', '2'], lastError: 'not_in_channel' })} />);

    expect(screen.getByText('statusNotDelivered · restricted:{"count":2}')).toBeTruthy();
    expect(screen.getByText('not_in_channel')).toBeTruthy();
    expect(screen.queryByText('defaultBadge')).toBeNull();
  });

  it('draws the actions and the footer inside the same frame', () => {
    render(<ChatDestinationCard destination={destination()} data-testid="card"
      actions={<button type="button">act</button>} footer={<span>note</span>} />);

    const card = screen.getByTestId('card');
    expect(card.contains(screen.getByText('act'))).toBe(true);
    expect(card.contains(screen.getByText('note'))).toBe(true);
  });

  it('falls back to the chat id without a title, and shows an unknown service as itself', () => {
    render(<ChatDestinationCard destination={destination({ chatTitle: null, channel: 'matrix' })} />);

    expect(screen.getByText('C1')).toBeTruthy();
    expect(screen.getByText('matrix')).toBeTruthy();
    expect(displayOf('matrix')).toEqual({ label: 'matrix', icon: 'matrix' });
  });
});
