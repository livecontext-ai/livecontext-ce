// @vitest-environment jsdom
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));
vi.mock('next/link', () => ({
  default: ({ href, children }: any) => <a href={href}>{children}</a>,
}));
vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => 'fr' }));
// A plain <select> stands in for the Radix one: same value, same options, same change event.
vi.mock('@/components/ui/select', () => ({
  Select: ({ children, value, onValueChange, disabled }: any) => (
    <select data-testid="picker-select" value={value} disabled={disabled} onChange={(e) => onValueChange(e.target.value)}>
      {children}
    </select>
  ),
  SelectContent: ({ children }: any) => <>{children}</>,
  SelectItem: ({ children, value, disabled }: any) => <option value={value} disabled={disabled}>{children}</option>,
  SelectTrigger: () => null,
  SelectValue: () => null,
}));
const state = vi.hoisted(() => ({
  query: { data: undefined as any, isLoading: false, isError: false },
  keys: [] as unknown[],
  enabled: [] as unknown[],
  authenticated: true,
}));
vi.mock('@/lib/hooks/useOrgScopedQuery', () => ({
  useOrgScopedQuery: ({ queryKey, enabled }: any) => {
    state.keys.push(queryKey);
    state.enabled.push(enabled);
    return state.query;
  },
}));
vi.mock('@/lib/providers/smart-providers', () => ({ useOptionalAuth: () => ({ isAuthenticated: state.authenticated }) }));
vi.mock('@/lib/api/orchestrator', () => ({ chatChannelService: { list: vi.fn() } }));

import { ChannelDestinationPicker, DEFAULT_DESTINATION } from '../ChannelDestinationPicker';

const link = (overrides: Record<string, unknown> = {}) => ({
  linkId: 'l-ops', channel: 'slack', credentialId: 1, botUsername: 'b', chatId: 'C1', chatTitle: '#ops',
  chatType: 'channel', isDefault: false, active: true, verifiedAt: '2026-09-20T10:00:00Z', lastError: null,
  allowedUserIds: [], ...overrides,
});
const listed = (...channels: any[]) => { state.query = { data: { channels }, isLoading: false, isError: false }; };
const options = () => Array.from(screen.getByTestId('picker-select').querySelectorAll('option'))
  .map((o) => ({ value: o.value, text: o.textContent, disabled: o.disabled }));

afterEach(() => {
  cleanup();
  state.keys.length = 0;
  state.enabled.length = 0;
  state.authenticated = true;
});

describe('ChannelDestinationPicker', () => {
  it('offers the workspace default (named) and every destination; one that never received a message cannot be picked', () => {
    listed(link({ linkId: 'l-tg', channel: 'telegram', chatTitle: 'Ops', isDefault: true }), link(),
      link({ linkId: 'l-new', chatTitle: '#new', verifiedAt: null }));
    render(<ChannelDestinationPicker value={null} onChange={vi.fn()} ariaLabel="dest" />);

    // The default's own chat is not listed a second time: with one channel it read as two.
    expect(options()).toEqual([
      { value: DEFAULT_DESTINATION, text: 'defaultOf:{"destination":"Telegram · Ops"}', disabled: false },
      { value: 'l-ops', text: 'Slack · #ops', disabled: false },
      { value: 'l-new', text: 'notWorking:{"destination":"Slack · #new"}', disabled: true },
    ]);
    expect((screen.getByTestId('picker-select') as HTMLSelectElement).value).toBe(DEFAULT_DESTINATION);
    // Same workspace-keyed entry as Settings > Channels, only once signed in.
    expect(state.keys[0]).toEqual(['chat-channels']);
    expect(state.enabled[0]).toBe(true);
  });

  it('with a single channel there is a single choice: the default, which names it', () => {
    listed(link({ linkId: 'l-tg', channel: 'telegram', chatTitle: 'Ops', isDefault: true }));
    render(<ChannelDestinationPicker value={null} onChange={vi.fn()} ariaLabel="dest" />);

    expect(options()).toEqual([
      { value: DEFAULT_DESTINATION, text: 'defaultOf:{"destination":"Telegram · Ops"}', disabled: false },
    ]);
  });

  it('an agent pinned to the default chat keeps seeing that choice', () => {
    listed(link({ linkId: 'l-tg', channel: 'telegram', chatTitle: 'Ops', isDefault: true }), link());
    render(<ChannelDestinationPicker value="l-tg" onChange={vi.fn()} ariaLabel="dest" />);

    expect(options().map((o) => o.value)).toEqual([DEFAULT_DESTINATION, 'l-tg', 'l-ops']);
    expect((screen.getByTestId('picker-select') as HTMLSelectElement).value).toBe('l-tg');
  });

  it('picking a destination hands back its id and the destination itself; the default hands back null', () => {
    const def = link({ linkId: 'l-tg', channel: 'telegram', isDefault: true });
    listed(def, link());
    const onChange = vi.fn();
    render(<ChannelDestinationPicker value={null} onChange={onChange} ariaLabel="dest" />);

    fireEvent.change(screen.getByTestId('picker-select'), { target: { value: 'l-ops' } });
    expect(onChange).toHaveBeenLastCalledWith('l-ops', expect.objectContaining({ linkId: 'l-ops' }));

    fireEvent.change(screen.getByTestId('picker-select'), { target: { value: DEFAULT_DESTINATION } });
    expect(onChange).toHaveBeenLastCalledWith(null, expect.objectContaining({ linkId: 'l-tg' }));
  });

  it('a chosen destination that no longer exists stays visible as disconnected, never as the default', () => {
    listed(link());
    render(<ChannelDestinationPicker value="l-removed" onChange={vi.fn()} ariaLabel="dest" />);

    expect((screen.getByTestId('picker-select') as HTMLSelectElement).value).toBe('l-removed');
    expect(options()).toContainEqual({ value: 'l-removed', text: 'gone', disabled: true });
    expect(screen.getByTestId('channel-picker-gone').textContent).toBe('goneHint');
  });

  it('a chosen destination that was switched off is listed once, disabled, with the warning', () => {
    listed(link({ active: false }));
    render(<ChannelDestinationPicker value="l-ops" onChange={vi.fn()} ariaLabel="dest" />);

    expect(options().filter((o) => o.value === 'l-ops')).toHaveLength(1);
    expect(screen.getByTestId('channel-picker-gone')).toBeTruthy();
  });

  it('with no default yet, says that nothing is sent until one is connected', () => {
    listed(link());
    render(<ChannelDestinationPicker value={null} onChange={vi.fn()} ariaLabel="dest" />);

    expect(options()[0].text).toBe('defaultNone');
    expect(screen.getByTestId('channel-picker-no-default').textContent).toBe('defaultNoneHint');
  });

  it('with nothing connected at all, sends the person to connect a channel', () => {
    listed();
    render(<ChannelDestinationPicker value={null} onChange={vi.fn()} ariaLabel="dest" />);

    expect(screen.getByTestId('channel-picker-empty')).toBeTruthy();
    expect(screen.getByText('connect').getAttribute('href')).toBe('/fr/app/settings/channels');
  });

  it("offers the caller's extra choice, and shows it selected when the caller says so", () => {
    listed(link());
    const onChange = vi.fn();
    render(<ChannelDestinationPicker value={null} onChange={onChange} ariaLabel="dest"
      extraOption={{ value: '__custom__', label: 'Another chat' }} extraSelected />);

    expect((screen.getByTestId('picker-select') as HTMLSelectElement).value).toBe('__custom__');
    fireEvent.change(screen.getByTestId('picker-select'), { target: { value: 'l-ops' } });
    expect(onChange).toHaveBeenCalledWith('l-ops', expect.anything());
  });

  it('with nothing connected, the extra choice is still reachable', () => {
    listed();
    const onChange = vi.fn();
    render(<ChannelDestinationPicker value={null} onChange={onChange} ariaLabel="dest"
      extraOption={{ value: '__custom__', label: 'Another chat' }} />);

    fireEvent.click(screen.getByText('Another chat'));
    expect(onChange).toHaveBeenCalledWith('__custom__', null);
  });

  it('says so when the list cannot be read, instead of offering a default that may not exist', () => {
    state.query = { data: undefined, isLoading: false, isError: true };
    render(<ChannelDestinationPicker value={null} onChange={vi.fn()} ariaLabel="dest" />);

    expect(screen.getByRole('alert').textContent).toBe('error');
    expect(screen.queryByTestId('picker-select')).toBeNull();
  });

  it('asks nothing before the person is signed in', () => {
    state.authenticated = false;
    state.query = { data: undefined, isLoading: false, isError: false };
    render(<ChannelDestinationPicker value={null} onChange={vi.fn()} ariaLabel="dest" />);

    expect(state.enabled[0]).toBe(false);
  });
});
