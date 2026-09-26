// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));
vi.mock('@/i18n/navigation', () => ({
  Link: ({ href, children }: any) => <a href={href}>{children}</a>,
}));
// A plain <select> stands in for the Radix one: same value, same options, same change event.
// It takes the name its SelectTrigger carries, so each select can be found by its label.
vi.mock('@/components/ui/select', () => ({
  Select: ({ children, value, onValueChange, disabled }: any) => {
    const trigger = React.Children.toArray(children)
      .find((c: any) => c?.props?.['aria-label']) as any;
    return (
      <select aria-label={trigger?.props?.['aria-label']} value={value} disabled={disabled}
        onChange={(e) => onValueChange(e.target.value)}>
        {children}
      </select>
    );
  },
  SelectContent: ({ children }: any) => <>{children}</>,
  SelectItem: ({ children, value, disabled }: any) => <option value={value} disabled={disabled}>{children}</option>,
  SelectTrigger: () => null,
  SelectValue: () => null,
}));
const state = vi.hoisted(() => ({
  query: { data: undefined as any, isLoading: false, isError: false, refetch: vi.fn() },
  channels: { data: undefined as any, isLoading: false, isError: false, refetch: vi.fn() },
  keys: [] as unknown[],
  update: vi.fn(),
  setDefault: vi.fn(),
  cardProps: [] as any[],
  pickerProps: [] as any[],
}));
vi.mock('@/lib/hooks/useOrgScopedQuery', () => ({
  useOrgScopedQuery: ({ queryKey }: any) => {
    state.keys.push(queryKey);
    return queryKey[0] === 'chat-channels' ? state.channels : state.query;
  },
}));
vi.mock('@/lib/providers/smart-providers', () => ({ useOptionalAuth: () => ({ isAuthenticated: true }) }));
vi.mock('@/lib/api/orchestrator', () => ({
  notificationPreferencesService: { get: vi.fn(), update: (...args: unknown[]) => state.update(...args) },
  chatChannelService: { list: vi.fn(), setDefault: (id: string) => state.setDefault(id) },
}));
// The destination is drawn by the SAME component as Settings > Channels: stubbed here to record
// what it is handed (its own rendering is pinned in ChatDestinationCard.test.tsx).
vi.mock('@/components/app/ChatDestinationCard', () => ({
  ChatDestinationCard: (props: any) => {
    state.cardProps.push(props);
    return (
      <div data-testid={props['data-testid']}>
        <span>{props.destination.chatTitle}</span>
        {props.footer}
      </div>
    );
  },
}));
// The change control is the agent card's destination picker: stubbed to record its props and to
// fire its onChange exactly as the real one does (a linkId, or null for "the workspace default").
vi.mock('@/components/app/ChannelDestinationPicker', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/app/ChannelDestinationPicker')>()),
  ChannelDestinationPicker: (props: any) => {
    state.pickerProps.push(props);
    return (
      <div data-testid="picker">
        <button type="button" onClick={() => props.onChange('link-2', null)}>pick-link-2</button>
        <button type="button" onClick={() => props.onChange(null, null)}>pick-default</button>
      </div>
    );
  },
}));

import { NotificationPreferencesPanel, NOTIFICATION_PREFERENCES_QUERY_KEY } from '../NotificationPreferencesPanel';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

beforeAll(() => {
  // Radix positioning (@floating-ui), used by the "i" popover, needs ResizeObserver.
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
});

const topic = (name: string, delivery: string, emailAvailable: boolean) =>
  ({ topic: name, delivery, defaultDelivery: delivery, emailAvailable, personScoped: name === 'CREDITS' });

const prefs = (overrides: Record<string, unknown> = {}) => ({
  topics: [
    topic('FAILURES', 'BOTH', false),
    topic('CREDITS', 'BOTH', true),
    topic('ACCOUNT', 'BOTH', false),
    topic('TASKS', 'EMAIL', false),
  ],
  emailRequiredPlan: 'STARTER',
  channel: { connected: true, channel: 'telegram', title: 'Chat privé' },
  ...overrides,
});

const destination = (overrides: Record<string, unknown> = {}) => ({
  linkId: 'link-1', channel: 'telegram', credentialId: 7, botUsername: 'lc_pro_bot', chatId: '42',
  chatTitle: 'Chat privé', chatType: 'private', isDefault: true, active: true,
  verifiedAt: '2026-09-20T10:00:00Z', lastError: null, allowedUserIds: [], ...overrides,
});

const loaded = (data: unknown) => {
  state.query = { data, isLoading: false, isError: false, refetch: vi.fn().mockResolvedValue(undefined) };
};
const connected = (...channels: unknown[]) => {
  state.channels = { data: { channels }, isLoading: false, isError: false, refetch: vi.fn().mockResolvedValue(undefined) };
};
const selects = () => ['topics.failures.title', 'topics.credits.title', 'topics.account.title', 'topics.tasks.title']
  .map((name) => screen.getByLabelText(name) as HTMLSelectElement);
const optionsOf = (select: HTMLSelectElement) => Array.from(select.querySelectorAll('option'))
  .map((o) => ({ value: o.value, disabled: o.disabled }));

function click(el: HTMLElement) {
  act(() => {
    fireEvent.pointerDown(el);
    fireEvent.mouseDown(el);
    fireEvent.pointerUp(el);
    fireEvent.mouseUp(el);
    fireEvent.click(el);
  });
}

beforeEach(() => {
  useCurrentOrgStore.setState({ currentOrgId: 'org-1', currentOrgRole: 'ADMIN' });
  connected(destination());
});

afterEach(() => {
  cleanup();
  state.keys.length = 0;
  state.cardProps.length = 0;
  state.pickerProps.length = 0;
  state.update.mockReset();
  state.setDefault.mockReset();
});

describe('NotificationPreferencesPanel', () => {
  it('is keyed per workspace and lists the four topics with the stored choice', () => {
    loaded(prefs());
    render(<NotificationPreferencesPanel enabled />);

    expect(state.keys[0]).toEqual([NOTIFICATION_PREFERENCES_QUERY_KEY]);
    // The destinations come from the cache entry Settings > Channels and the pickers share.
    expect(state.keys).toContainEqual(['chat-channels']);
    expect(selects().map((s) => s.value)).toEqual(['BOTH', 'BOTH', 'BOTH', 'EMAIL']);
  });

  describe('the anti-flood rules', () => {
    it('are behind the shared click-only "i", not a paragraph on the page', () => {
      loaded(prefs());
      render(<NotificationPreferencesPanel enabled />);

      // Nothing in the page until the "i" is pressed.
      expect(screen.queryByText('antiSpamBreaks')).toBeNull();
      const info = screen.getByTestId('notification-anti-spam-info');
      expect(info.getAttribute('aria-label')).toBe('antiSpamInfo');

      click(info);

      const content = screen.getByTestId('notification-anti-spam-content');
      expect(content.textContent).toContain('antiSpamTitle');
      for (const rule of ['antiSpamBreaks', 'antiSpamReminder', 'antiSpamRecovered', 'antiSpamDigest', 'antiSpamCap']) {
        expect(content.textContent).toContain(rule);
      }
      // Portalled on the app's info layer, above modals and menus.
      expect(content.className).toContain('z-[100001]');
    });

    it('do not open on hover', () => {
      loaded(prefs());
      render(<NotificationPreferencesPanel enabled />);

      act(() => {
        fireEvent.pointerEnter(screen.getByTestId('notification-anti-spam-info'));
        fireEvent.mouseEnter(screen.getByTestId('notification-anti-spam-info'));
      });

      expect(screen.queryByTestId('notification-anti-spam-content')).toBeNull();
    });
  });

  describe('where channel alerts go', () => {
    it('draws the workspace default with the Channels destination card, naming its bot, with the privacy warning inside it', () => {
      loaded(prefs());
      connected(destination({ linkId: 'link-0', isDefault: false, chatTitle: 'Other' }), destination());
      render(<NotificationPreferencesPanel enabled />);

      const card = screen.getByTestId('notification-channel-card');
      expect(state.cardProps.at(-1)).toMatchObject({ showBot: true, destination: { linkId: 'link-1' } });
      expect(card.textContent).toContain('Chat privé');
      // The warning is the card's own footer, not a sentence elsewhere on the page.
      expect(card.contains(screen.getByTestId('notification-channel-privacy'))).toBe(true);
      expect(screen.getByTestId('notification-channel-privacy').textContent).toBe('channelPrivacy');
    });

    it('with a single chat, shows it and offers no picker, only the way to the Channels page', () => {
      loaded(prefs());
      render(<NotificationPreferencesPanel enabled />);

      expect(screen.getByTestId('notification-channel-card')).toBeTruthy();
      expect(screen.queryByTestId('picker')).toBeNull();
      expect(screen.getByText('manageChannels').closest('a')?.getAttribute('href')).toBe('/app/settings/channels');
    });

    it('a second chat that never received a message is not a choice: still no picker', () => {
      loaded(prefs());
      connected(destination(), destination({ linkId: 'link-2', isDefault: false, verifiedAt: null }));
      render(<NotificationPreferencesPanel enabled />);

      expect(screen.queryByTestId('picker')).toBeNull();
    });

    it('with several working chats, offers the destination picker and says it changes the workspace default', () => {
      loaded(prefs());
      connected(destination(), destination({ linkId: 'link-2', isDefault: false, chatTitle: 'Ops' }));
      render(<NotificationPreferencesPanel enabled />);

      expect(screen.getByTestId('picker')).toBeTruthy();
      // Follows the default (null), which is what the delivery uses.
      expect(state.pickerProps.at(-1)).toMatchObject({ value: null, ariaLabel: 'changeLabel', disabled: false });
      expect(screen.getByText('changeHint')).toBeTruthy();
    });

    it('picking another chat makes it the workspace default through the Channels API, then re-reads both lists', async () => {
      loaded(prefs());
      connected(destination(), destination({ linkId: 'link-2', isDefault: false, chatTitle: 'Ops' }));
      state.setDefault.mockResolvedValue({});
      render(<NotificationPreferencesPanel enabled />);

      fireEvent.click(screen.getByText('pick-link-2'));

      await waitFor(() => expect(state.setDefault).toHaveBeenCalledWith('link-2'));
      await waitFor(() => expect(state.channels.refetch).toHaveBeenCalled());
      await waitFor(() => expect(state.query.refetch).toHaveBeenCalled());
      expect(state.update).not.toHaveBeenCalled();
    });

    it('picking "the workspace default" again changes nothing', () => {
      loaded(prefs());
      connected(destination(), destination({ linkId: 'link-2', isDefault: false }));
      render(<NotificationPreferencesPanel enabled />);

      fireEvent.click(screen.getByText('pick-default'));

      expect(state.setDefault).not.toHaveBeenCalled();
    });

    it('says so when the default could not be changed', async () => {
      loaded(prefs());
      connected(destination(), destination({ linkId: 'link-2', isDefault: false }));
      state.setDefault.mockRejectedValue(new Error('403'));
      render(<NotificationPreferencesPanel enabled />);

      fireEvent.click(screen.getByText('pick-link-2'));

      await waitFor(() => expect(screen.getByText('changeFailed')).toBeTruthy());
      expect(state.query.refetch).not.toHaveBeenCalled();
    });

    it('a read-only member sees the chat and why it cannot be changed, never a picker that could only be refused', () => {
      useCurrentOrgStore.setState({ currentOrgRole: 'VIEWER' });
      loaded(prefs());
      connected(destination(), destination({ linkId: 'link-2', isDefault: false }));
      render(<NotificationPreferencesPanel enabled />);

      expect(screen.getByTestId('notification-channel-card')).toBeTruthy();
      expect(screen.queryByTestId('picker')).toBeNull();
      expect(screen.getByTestId('notification-channel-read-only').textContent).toBe('changeReadOnly');
    });

    it('a default that has not received a message yet is named as itself in the picker, not as "no default"', () => {
      loaded(prefs());
      connected(destination({ verifiedAt: null }), destination({ linkId: 'link-2', isDefault: false }));
      render(<NotificationPreferencesPanel enabled />);

      expect(state.pickerProps.at(-1)).toMatchObject({ value: 'link-1' });
    });

    it('with chats but no default, says channel alerts are not sent and lets an editor pick one', async () => {
      loaded(prefs({ channel: { connected: false, channel: null, title: null } }));
      connected(destination({ isDefault: false, linkId: 'link-2' }));
      state.setDefault.mockResolvedValue({});
      render(<NotificationPreferencesPanel enabled />);

      expect(screen.getByTestId('notification-channel-no-default').textContent).toBe('noDefault');
      expect(screen.queryByTestId('notification-channel-card')).toBeNull();
      fireEvent.click(screen.getByText('pick-link-2'));
      await waitFor(() => expect(state.setDefault).toHaveBeenCalledWith('link-2'));
    });

    it('with no chat connected, says so and links to the Channels page to connect one', () => {
      loaded(prefs({ channel: { connected: false, channel: null, title: null } }));
      connected();
      render(<NotificationPreferencesPanel enabled />);

      expect(screen.getByTestId('notification-channel-none').textContent).toContain('noChannel');
      expect(screen.getByText('connectChannel').closest('a')?.getAttribute('href')).toBe('/app/settings/channels');
      expect(screen.queryByTestId('notification-channel-card')).toBeNull();
      expect(screen.queryByTestId('picker')).toBeNull();
    });

    it('a default that is switched off is named as such, not as "no default", and shows no card', () => {
      loaded(prefs({ channel: { connected: false, channel: null, title: null } }));
      connected(destination({ active: false }), destination({ linkId: 'link-2', isDefault: false }));
      render(<NotificationPreferencesPanel enabled />);

      expect(screen.getByTestId('notification-channel-no-default').textContent).toBe('defaultOff');
      expect(screen.queryByTestId('notification-channel-card')).toBeNull();
      // Another working chat can still be made the default.
      expect(screen.getByTestId('picker')).toBeTruthy();
    });

    it('while the chats load, says so and shows neither a card nor a picker', () => {
      loaded(prefs());
      state.channels = { data: undefined, isLoading: true, isError: false, refetch: vi.fn() };
      render(<NotificationPreferencesPanel enabled />);

      expect(screen.getByText('channelLoading')).toBeTruthy();
      expect(screen.queryByTestId('notification-channel-card')).toBeNull();
      expect(screen.queryByTestId('picker')).toBeNull();
    });

    it('the picker is disabled while a change is in flight, and enabled again once it lands', async () => {
      loaded(prefs());
      connected(destination(), destination({ linkId: 'link-2', isDefault: false }));
      let resolve!: (v: unknown) => void;
      state.setDefault.mockReturnValue(new Promise((r) => { resolve = r; }));
      render(<NotificationPreferencesPanel enabled />);

      fireEvent.click(screen.getByText('pick-link-2'));

      await waitFor(() => expect(state.pickerProps.at(-1).disabled).toBe(true));
      await act(async () => { resolve({}); });
      await waitFor(() => expect(state.pickerProps.at(-1).disabled).toBe(false));
    });

    it('a member whose role cannot edit yet (role still unknown) gets no picker and no read-only claim', () => {
      useCurrentOrgStore.setState({ currentOrgRole: null });
      loaded(prefs());
      connected(destination(), destination({ linkId: 'link-2', isDefault: false }));
      render(<NotificationPreferencesPanel enabled />);

      expect(screen.queryByTestId('picker')).toBeNull();
      expect(screen.queryByTestId('notification-channel-read-only')).toBeNull();
      expect(screen.getByText('manageChannels')).toBeTruthy();
    });

    it('a chat list that could not be read says so, instead of claiming nothing is connected', () => {
      loaded(prefs());
      state.channels = { data: undefined, isLoading: false, isError: true, refetch: vi.fn() };
      render(<NotificationPreferencesPanel enabled />);

      expect(screen.getByText('channelLoadFailed')).toBeTruthy();
      expect(screen.queryByTestId('notification-channel-none')).toBeNull();
    });
  });

  it('on a plan without email, disables Email for failures but keeps it for credits, and says which plan has it', () => {
    loaded(prefs());
    render(<NotificationPreferencesPanel enabled />);

    const [failures, credits] = selects();
    expect(optionsOf(failures)).toContainEqual({ value: 'EMAIL', disabled: true });
    expect(optionsOf(failures)).toContainEqual({ value: 'CHANNEL', disabled: false });
    expect(optionsOf(credits)).toContainEqual({ value: 'EMAIL', disabled: false });
    expect(screen.getAllByText('emailNeedsPlan:{"plan":"Starter"}')).toHaveLength(3);
  });

  it('without a connected channel, disables the channel options', () => {
    loaded(prefs({ channel: { connected: false, channel: null, title: null } }));
    connected();
    render(<NotificationPreferencesPanel enabled />);

    const [failures, credits] = selects();
    expect(optionsOf(failures)).toContainEqual({ value: 'CHANNEL', disabled: true });
    // Credits go to the PERSONAL workspace's channel, which this screen cannot vouch for.
    expect(optionsOf(credits)).toContainEqual({ value: 'CHANNEL', disabled: false });
  });

  it('marks the credits row as applying in every workspace', () => {
    loaded(prefs());
    render(<NotificationPreferencesPanel enabled />);

    expect(screen.getAllByText('personScoped')).toHaveLength(1);
  });

  it('warns when the saved choice delivers nothing (Free plan, no channel, the default Both)', () => {
    loaded(prefs({ channel: { connected: false, channel: null, title: null } }));
    render(<NotificationPreferencesPanel enabled />);

    // Failures and Account (Both) and Tasks (Email) deliver nothing; Credits still emails.
    expect(screen.getAllByText('deliversNothing')).toHaveLength(3);
  });

  it('does not warn when at least one medium delivers', () => {
    loaded(prefs());
    render(<NotificationPreferencesPanel enabled />);

    // Channel connected: Both still delivers on the channel. Only Tasks (Email, no plan) is silent.
    expect(screen.getAllByText('deliversNothing')).toHaveLength(1);
  });

  it('saves one topic at a time and refetches', async () => {
    loaded(prefs());
    state.update.mockResolvedValue(prefs());
    render(<NotificationPreferencesPanel enabled />);

    fireEvent.change(selects()[0], { target: { value: 'OFF' } });

    await waitFor(() => expect(state.update).toHaveBeenCalledWith('FAILURES', 'OFF'));
    await waitFor(() => expect(state.query.refetch).toHaveBeenCalled());
  });

  it('says so when a change was not saved', async () => {
    loaded(prefs());
    state.update.mockRejectedValue(new Error('500'));
    render(<NotificationPreferencesPanel enabled />);

    fireEvent.change(selects()[0], { target: { value: 'OFF' } });

    await waitFor(() => expect(screen.getByText('saveFailed')).toBeTruthy());
  });

  it('offers a retry when the settings could not be loaded', () => {
    state.query = { data: undefined, isLoading: false, isError: true, refetch: vi.fn() };
    render(<NotificationPreferencesPanel enabled />);

    fireEvent.click(screen.getByText('retry'));

    expect(state.query.refetch).toHaveBeenCalled();
  });
});
