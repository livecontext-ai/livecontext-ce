// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { CHAT_CHANNELS } from '@/lib/chatChannels';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));
vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({ children }: any) => <div>{children}</div>,
  DialogContent: ({ children }: any) => <div>{children}</div>,
  DialogHeader: ({ children }: any) => <div>{children}</div>,
  DialogFooter: ({ children }: any) => <div>{children}</div>,
  DialogTitle: ({ children }: any) => <h2>{children}</h2>,
  DialogDescription: ({ children }: any) => <p>{children}</p>,
}));
// The credential picker talks to the API; here it is a button that picks credential 7. The real
// one also picks on its own at mount when the user has exactly one credential (autoPick mimics it).
let autoPick: number | null = null;
vi.mock('@/app/workflows/builder/components/inspector/CredentialSection', () => ({
  CredentialSection: ({ onCredentialSelect, integration }: any) => {
    React.useEffect(() => {
      if (autoPick !== null) onCredentialSelect(autoPick, integration);
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    return (
      <button data-testid="pick-credential" data-integration={integration} onClick={() => onCredentialSelect(7, integration)}>
        pick
      </button>
    );
  },
}));

const track = vi.hoisted(() => vi.fn());
vi.mock('@/lib/analytics/analytics', () => ({ track: (...a: unknown[]) => track(...a) }));

const discover = vi.fn();
const connect = vi.fn();
vi.mock('@/lib/api/orchestrator', () => ({
  chatChannelService: {
    discover: (...a: unknown[]) => discover(...a),
    connect: (...a: unknown[]) => connect(...a),
  },
}));

import { ManualChannelSetup } from '../components/ManualChannelSetup';

const channel = (id: string) => CHAT_CHANNELS.find((c) => c.id === id)!;
const onClose = vi.fn();
const onConnected = vi.fn();
const summary = { linkId: 'l1', channel: 'slack', chatId: 'C1' };

beforeEach(() => {
  discover.mockResolvedValue({ channel: 'slack', credentialId: 7, botUsername: 'ops', chats: [
    { chatId: 'C1', title: '#ops', type: 'channel', fromUsername: null },
    { chatId: 'D1', title: 'Direct message', type: 'private', fromUsername: 'U1' },
  ] });
  connect.mockResolvedValue({ channel: summary, delivered: true, warning: null, setupInstructions: null });
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  autoPick = null;
});

const connectButton = () => screen.getByText('connect') as HTMLButtonElement;

describe('ManualChannelSetup', () => {
  it('Slack: pick the account, find the chats, pick one, connect with exactly those choices', async () => {
    render(<ManualChannelSetup channel={channel('slack')} onClose={onClose} onConnected={onConnected} />);
    expect(connectButton().disabled).toBe(true);

    fireEvent.click(screen.getByTestId('pick-credential'));
    fireEvent.click(screen.getByText('findChats'));
    await screen.findByTestId('manual-chats');
    expect(discover).toHaveBeenCalledWith('slack', 7);
    // Reported with the count only: chat titles are user content.
    expect(track).toHaveBeenCalledWith('channel_discovery_run', { channel: 'slack', chats_found: 2 });
    expect(JSON.stringify(track.mock.calls)).not.toContain('#ops');
    fireEvent.click(screen.getByText('#ops'));
    fireEvent.click(connectButton());

    await waitFor(() => expect(connect).toHaveBeenCalledWith({
      channel: 'slack', credentialId: 7, chatId: 'C1', chatTitle: '#ops', chatType: 'channel', accountSetting: null,
    }));
    expect(onConnected).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('delivered')).toBeTruthy();
  });

  it('the credential picker lists the service integration name (Teams is microsoftteams)', () => {
    render(<ManualChannelSetup channel={channel('teams')} onClose={onClose} onConnected={onConnected} />);

    expect(screen.getByTestId('pick-credential').getAttribute('data-integration')).toBe('microsoftteams');
  });

  it('WhatsApp: no chat list, a typed number and the phone number id, both required before connecting', async () => {
    connect.mockResolvedValue({ channel: summary, delivered: true, warning: null,
      setupInstructions: 'Set Callback URL https://x/approval-callback/whatsapp/b1 and Verify token abc' });
    render(<ManualChannelSetup channel={channel('whatsapp')} onClose={onClose} onConnected={onConnected} />);

    expect(screen.queryByText('findChats')).toBeNull();
    fireEvent.click(screen.getByTestId('pick-credential'));
    fireEvent.change(screen.getByLabelText('phoneLabel'), { target: { value: '+33 6 12 34 56 78' } });
    expect(connectButton().disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('accountSetting.phoneNumberId.label'), { target: { value: ' 106540352242922 ' } });
    fireEvent.click(connectButton());

    await waitFor(() => expect(connect).toHaveBeenCalledWith(expect.objectContaining({
      channel: 'whatsapp', chatId: '+33 6 12 34 56 78', accountSetting: '106540352242922',
    })));
    // What is left to do in the Meta console is shown, not lost.
    expect((await screen.findByTestId('manual-setup-steps')).textContent).toContain('Verify token abc');
  });

  it('an empty chat list says what to do on that service, instead of an empty box', async () => {
    discover.mockResolvedValue({ channel: 'telegram', credentialId: 7, botUsername: 'bot', chats: [] });
    render(<ManualChannelSetup channel={channel('telegram')} onClose={onClose} onConnected={onConnected} />);

    fireEvent.click(screen.getByTestId('pick-credential'));
    fireEvent.click(screen.getByText('findChats'));

    expect(await screen.findByText('noChats.telegram')).toBeTruthy();
  });

  it('a refusal is shown in the service own words, and nothing is reported as connected', async () => {
    connect.mockRejectedValue(new Error('That public key is not this bot\'s application key.'));
    render(<ManualChannelSetup channel={channel('discord')} onClose={onClose} onConnected={onConnected} />);

    fireEvent.click(screen.getByTestId('pick-credential'));
    fireEvent.change(screen.getByLabelText('accountSetting.publicKey.label'), { target: { value: 'a'.repeat(64) } });
    fireEvent.click(screen.getByText('findChats'));
    await screen.findByTestId('manual-chats');
    fireEvent.click(screen.getByText('#ops'));
    fireEvent.click(connectButton());

    expect((await screen.findByRole('alert')).textContent).toContain('not this bot');
    expect(onConnected).not.toHaveBeenCalled();
    expect(screen.queryByTestId('manual-result')).toBeNull();
  });

  it('a saved but undelivered destination says so, with the reason', async () => {
    connect.mockResolvedValue({ channel: summary, delivered: false,
      warning: 'The chat is saved but nothing was delivered to it: not_in_channel', setupInstructions: null });
    render(<ManualChannelSetup channel={channel('slack')} onClose={onClose} onConnected={onConnected} />);

    fireEvent.click(screen.getByTestId('pick-credential'));
    fireEvent.click(screen.getByText('findChats'));
    await screen.findByTestId('manual-chats');
    fireEvent.click(screen.getByText('#ops'));
    fireEvent.click(connectButton());

    expect(await screen.findByText('notDelivered')).toBeTruthy();
    expect(screen.getByText(/not_in_channel/)).toBeTruthy();
  });

  it('Telegram with its webhook set: discovery is refused, the reason is shown, and the chat id can be typed instead', async () => {
    discover.mockRejectedValue(new Error('Telegram refuses to list chats while the webhook is set. Disconnect the webhook first, or provide the chat id directly.'));
    connect.mockResolvedValue({ channel: { ...summary, channel: 'telegram' }, delivered: true, warning: null, setupInstructions: null });
    render(<ManualChannelSetup channel={channel('telegram')} onClose={onClose} onConnected={onConnected} />);

    fireEvent.click(screen.getByTestId('pick-credential'));
    fireEvent.click(screen.getByText('findChats'));
    expect((await screen.findByRole('alert')).textContent).toContain('provide the chat id directly');
    expect(screen.queryByTestId('manual-chats')).toBeNull();

    fireEvent.change(screen.getByLabelText('typeChatLabel'), { target: { value: '  @ops_channel ' } });
    fireEvent.click(connectButton());

    await waitFor(() => expect(connect).toHaveBeenCalledWith({
      channel: 'telegram', credentialId: 7, chatId: '@ops_channel', chatTitle: null, chatType: null, accountSetting: null,
    }));
  });

  it('a picked chat and a typed id replace each other: the last one chosen is sent', async () => {
    render(<ManualChannelSetup channel={channel('slack')} onClose={onClose} onConnected={onConnected} />);
    fireEvent.click(screen.getByTestId('pick-credential'));
    fireEvent.click(screen.getByText('findChats'));
    await screen.findByTestId('manual-chats');

    fireEvent.click(screen.getByText('#ops'));
    fireEvent.change(screen.getByLabelText('typeChatLabel'), { target: { value: 'C999' } });
    expect((screen.getByLabelText('#ops') as HTMLInputElement).checked).toBe(false);
    fireEvent.click(connectButton());
    await waitFor(() => expect(connect).toHaveBeenLastCalledWith(expect.objectContaining({ chatId: 'C999', chatTitle: null })));
  });

  it('picking from the list empties a typed id', async () => {
    render(<ManualChannelSetup channel={channel('slack')} onClose={onClose} onConnected={onConnected} />);
    fireEvent.click(screen.getByTestId('pick-credential'));
    fireEvent.change(screen.getByLabelText('typeChatLabel'), { target: { value: 'C999' } });
    fireEvent.click(screen.getByText('findChats'));
    await screen.findByTestId('manual-chats');

    fireEvent.click(screen.getByText('#ops'));

    expect((screen.getByLabelText('typeChatLabel') as HTMLInputElement).value).toBe('');
    fireEvent.click(connectButton());
    await waitFor(() => expect(connect).toHaveBeenCalledWith(expect.objectContaining({ chatId: 'C1', chatTitle: '#ops' })));
  });

  it('Discord: a new bot cannot connect without its public key, and the key is what is sent', async () => {
    render(<ManualChannelSetup channel={channel('discord')} onClose={onClose} onConnected={onConnected} />);
    fireEvent.click(screen.getByTestId('pick-credential'));
    fireEvent.click(screen.getByText('findChats'));
    await screen.findByTestId('manual-chats');
    fireEvent.click(screen.getByText('#ops'));

    expect(connectButton().disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('accountSetting.publicKey.label'), { target: { value: ` ${'b'.repeat(64)} ` } });
    expect(connectButton().disabled).toBe(false);
    fireEvent.click(connectButton());

    await waitFor(() => expect(connect).toHaveBeenCalledWith(expect.objectContaining({
      channel: 'discord', accountSetting: 'b'.repeat(64),
    })));
  });

  it('Discord: an account already connected keeps its saved key, so the field may stay empty', async () => {
    render(<ManualChannelSetup channel={channel('discord')} connectedCredentialIds={[7]} onClose={onClose} onConnected={onConnected} />);
    fireEvent.click(screen.getByTestId('pick-credential'));

    expect(screen.getByText('accountSettingKept')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('typeChatLabel'), { target: { value: '123456789012345678' } });
    expect(connectButton().disabled).toBe(false);
    fireEvent.click(connectButton());

    await waitFor(() => expect(connect).toHaveBeenCalledWith(expect.objectContaining({ accountSetting: null })));
  });

  it('an account picked on its own at mount (the only one the user has) is usable at once', async () => {
    autoPick = 7;
    render(<ManualChannelSetup channel={channel('slack')} onClose={onClose} onConnected={onConnected} />);

    fireEvent.click(screen.getByText('findChats'));

    await screen.findByTestId('manual-chats');
    expect(discover).toHaveBeenCalledWith('slack', 7);
  });

  it('after a test message that did not arrive, try again returns to the form with the choices kept', async () => {
    connect.mockResolvedValueOnce({ channel: summary, delivered: false, warning: 'not_in_channel', setupInstructions: null });
    render(<ManualChannelSetup channel={channel('slack')} onClose={onClose} onConnected={onConnected} />);
    fireEvent.click(screen.getByTestId('pick-credential'));
    fireEvent.change(screen.getByLabelText('typeChatLabel'), { target: { value: 'C1' } });
    fireEvent.click(connectButton());
    await screen.findByText('notDelivered');

    fireEvent.click(screen.getByText('tryAgain'));
    expect(screen.queryByTestId('manual-result')).toBeNull();
    expect((screen.getByLabelText('typeChatLabel') as HTMLInputElement).value).toBe('C1');
    fireEvent.click(connectButton());

    expect(await screen.findByText('delivered')).toBeTruthy();
    expect(connect).toHaveBeenCalledTimes(2);
    expect(screen.queryByText('tryAgain')).toBeNull();
  });

  it('close ends the dialog once the result is shown', async () => {
    render(<ManualChannelSetup channel={channel('slack')} onClose={onClose} onConnected={onConnected} />);
    fireEvent.click(screen.getByTestId('pick-credential'));
    fireEvent.change(screen.getByLabelText('typeChatLabel'), { target: { value: 'C1' } });
    fireEvent.click(connectButton());
    await screen.findByText('delivered');

    fireEvent.click(screen.getByText('close'));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('what is left to do in the service console can be copied', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    connect.mockResolvedValue({ channel: summary, delivered: true, warning: null,
      setupInstructions: 'Verify token abc' });
    render(<ManualChannelSetup channel={channel('whatsapp')} onClose={onClose} onConnected={onConnected} />);
    fireEvent.click(screen.getByTestId('pick-credential'));
    fireEvent.change(screen.getByLabelText('phoneLabel'), { target: { value: '+33612345678' } });
    fireEvent.change(screen.getByLabelText('accountSetting.phoneNumberId.label'), { target: { value: '1065' } });
    fireEvent.click(connectButton());

    fireEvent.click(await screen.findByTestId('manual-copy-steps'));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith('Verify token abc'));
    expect(await screen.findByText('copied')).toBeTruthy();
  });

  it('changing the account clears chats found for the previous one', async () => {
    render(<ManualChannelSetup channel={channel('slack')} onClose={onClose} onConnected={onConnected} />);
    fireEvent.click(screen.getByTestId('pick-credential'));
    fireEvent.click(screen.getByText('findChats'));
    await screen.findByTestId('manual-chats');

    fireEvent.click(screen.getByTestId('pick-credential'));

    expect(screen.queryByTestId('manual-chats')).toBeNull();
  });

  describe('the steps', () => {
    it.each([
      ['telegram', [1, 2], [4], true],
      ['slack', [1], [2, 3], false],
      ['discord', [1, 2, 3], [4], false],
      ['whatsapp', [1, 2], [3, 4], false],
      ['teams', [1], [2, 3], false],
    ] as const)('%s: each how-to line sits in the section it is about', (id, account, destination, hello) => {
      render(<ManualChannelSetup channel={channel(id)} onClose={onClose} onConnected={onConnected} />);

      const accountStep = screen.getByTestId('manual-step-account');
      const destinationStep = screen.getByTestId('manual-step-destination');
      account.forEach((n) => expect(accountStep.textContent).toContain(`guide.${id}.${n}`));
      destination.forEach((n) => expect(destinationStep.textContent).toContain(`guide.${id}.${n}`));
      // The credential picker is inside the account step, after its instructions.
      expect(accountStep.contains(screen.getByTestId('pick-credential'))).toBe(true);
      // Numbered in reading order, with the hello step only where it exists.
      expect(accountStep.textContent).toContain('1. stepAccount');
      expect(destinationStep.textContent).toContain(hello ? '3. stepDestination' : '2. stepDestination');
      expect(!!screen.queryByTestId('manual-step-hello')).toBe(hello);
    });

    it('Telegram: @BotFather is offered where the bot is created, @userinfobot next to the id field', () => {
      render(<ManualChannelSetup channel={channel('telegram')} onClose={onClose} onConnected={onConnected} />);

      const botFather = screen.getByTestId('manual-link-botFather');
      expect(screen.getByTestId('manual-step-account').contains(botFather)).toBe(true);
      expect(botFather.getAttribute('href')).toBe('https://t.me/BotFather');
      expect(botFather.getAttribute('target')).toBe('_blank');
      const userInfo = screen.getByTestId('manual-link-userInfoBot');
      expect(userInfo.getAttribute('href')).toBe('https://t.me/userinfobot');
      // Beside the field it helps fill, not in a block of links somewhere else.
      expect(userInfo.parentElement?.parentElement?.contains(screen.getByLabelText('typeChatLabel'))).toBe(true);
    });

    it('Get my id sits on its own line under the id field, which keeps the full width', () => {
      render(<ManualChannelSetup channel={channel('telegram')} onClose={onClose} onConnected={onConnected} />);

      const input = screen.getByLabelText('typeChatLabel');
      const button = screen.getByTestId('manual-link-userInfoBot');
      // Not in a shared flex row with the field: that squeezed the field and hid its example.
      expect(input.parentElement?.className ?? '').not.toMatch(/\bflex\b/);
      expect(input.className).not.toMatch(/basis-|flex-1/);
      expect(input.compareDocumentPosition(button) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('Telegram, bot not connected yet: Find my chats is offered, with what it does', () => {
      render(<ManualChannelSetup channel={channel('telegram')} onClose={onClose} onConnected={onConnected} />);
      fireEvent.click(screen.getByTestId('pick-credential'));

      const findChats = screen.getByTestId('manual-find-chats');
      expect(findChats.getAttribute('aria-describedby')).toBe('channel-find-chats-hint');
      expect(document.getElementById('channel-find-chats-hint')?.textContent).toContain('findChatsHint.telegram');
      expect(screen.getByLabelText('typeChatLabel')).toBeTruthy();
    });

    it('Telegram, bot already connected: no Find my chats (Telegram cannot list its chats), only the id', () => {
      render(<ManualChannelSetup channel={channel('telegram')} onClose={onClose} onConnected={onConnected}
        connectedCredentialIds={[7]} />);
      fireEvent.click(screen.getByTestId('pick-credential'));

      expect(screen.queryByTestId('manual-find-chats')).toBeNull();
      expect(screen.queryByText('findChatsHint.telegram')).toBeNull();
      // Labelled as the one way, not as "or type".
      expect(screen.getByLabelText('chatIdLabel')).toBeTruthy();
    });

    it('Discord says what it lists; WhatsApp has no list at all, so neither button nor hint', () => {
      render(<ManualChannelSetup channel={channel('discord')} onClose={onClose} onConnected={onConnected} />);
      fireEvent.click(screen.getByTestId('pick-credential'));
      expect(screen.getByText('findChatsHint.discord')).toBeTruthy();
      cleanup();

      render(<ManualChannelSetup channel={channel('whatsapp')} onClose={onClose} onConnected={onConnected} />);
      fireEvent.click(screen.getByTestId('pick-credential'));
      expect(screen.queryByTestId('manual-find-chats')).toBeNull();
      expect(screen.queryByText(/findChatsHint/)).toBeNull();
    });

    it('another service keeps Find my chats even when its account is already connected', () => {
      render(<ManualChannelSetup channel={channel('slack')} onClose={onClose} onConnected={onConnected}
        connectedCredentialIds={[7]} />);
      fireEvent.click(screen.getByTestId('pick-credential'));

      expect(screen.getByTestId('manual-find-chats')).toBeTruthy();
      expect(screen.getByText('findChatsHint.slack')).toBeTruthy();
    });

    it('Discord and WhatsApp send the person to their console from the account step', () => {
      render(<ManualChannelSetup channel={channel('discord')} onClose={onClose} onConnected={onConnected} />);
      expect(screen.getByTestId('manual-step-account').contains(screen.getByTestId('manual-link-discordPortal'))).toBe(true);
      cleanup();
      render(<ManualChannelSetup channel={channel('whatsapp')} onClose={onClose} onConnected={onConnected} />);
      expect(screen.getByTestId('manual-step-account').contains(screen.getByTestId('manual-link-metaApps'))).toBe(true);
    });

    it('offers to open the bot of a credential already connected, in the hello step, before any search', () => {
      render(<ManualChannelSetup channel={channel('telegram')} onClose={onClose} onConnected={onConnected}
        botUsernames={{ 7: 'Example_bot' }} />);
      expect(screen.queryByTestId('manual-open-bot')).toBeNull();

      fireEvent.click(screen.getByTestId('pick-credential'));

      const open = screen.getByTestId('manual-open-bot');
      expect(open.getAttribute('href')).toBe('https://t.me/Example_bot');
      expect(screen.getByTestId('manual-step-hello').contains(open)).toBe(true);
    });

    it('hands the setup to the assistant when asked, and has no such button without a handler', () => {
      const onAskAssistant = vi.fn();
      const { unmount } = render(<ManualChannelSetup channel={channel('telegram')} onClose={onClose}
        onConnected={onConnected} onAskAssistant={onAskAssistant} />);

      fireEvent.click(screen.getByTestId('manual-ask-assistant'));
      expect(onAskAssistant).toHaveBeenCalledTimes(1);

      unmount();
      render(<ManualChannelSetup channel={channel('telegram')} onClose={onClose} onConnected={onConnected} />);
      expect(screen.queryByTestId('manual-ask-assistant')).toBeNull();
    });
  });

  // Prod 2026-09-23: the bot was connected, "find my chats" came back as a red error saying to
  // disconnect a webhook, and the person then typed the bot's own @name as the destination.
  describe('a Telegram bot that is already connected', () => {
    beforeEach(() => {
      discover.mockResolvedValue({ channel: 'telegram', credentialId: 7, botUsername: 'Example_bot', chats: [],
        notice: 'Telegram cannot list this bot chats: the bot is already connected.' });
    });

    it('explains why there is no list, as information rather than an error, and names the bot to open', async () => {
      render(<ManualChannelSetup channel={channel('telegram')} onClose={onClose} onConnected={onConnected} />);
      fireEvent.click(screen.getByTestId('pick-credential'));
      fireEvent.click(screen.getByText('findChats'));

      expect((await screen.findByTestId('manual-notice')).textContent).toContain('listUnavailable.telegram');
      expect(screen.queryByRole('alert')).toBeNull();
      expect(screen.queryByText('noChats.telegram')).toBeNull();
      expect(screen.getByTestId('manual-open-bot').getAttribute('href')).toBe('https://t.me/Example_bot');
    });

    it.each(['@Example_bot', 'example_bot', ' @EXAMPLE_BOT '])(
      'refuses "%s" as the destination: it is the bot itself',
      async (typed) => {
        render(<ManualChannelSetup channel={channel('telegram')} onClose={onClose} onConnected={onConnected} />);
        fireEvent.click(screen.getByTestId('pick-credential'));
        fireEvent.click(screen.getByText('findChats'));
        await screen.findByTestId('manual-notice');

        fireEvent.change(screen.getByLabelText('typeChatLabel'), { target: { value: typed } });

        expect(screen.getByTestId('manual-bot-itself').textContent).toContain('Example_bot');
        expect(connectButton().disabled).toBe(true);
      },
    );

    it('accepts the person own numeric id', async () => {
      connect.mockResolvedValue({ channel: { ...summary, channel: 'telegram' }, delivered: true, warning: null, setupInstructions: null });
      render(<ManualChannelSetup channel={channel('telegram')} onClose={onClose} onConnected={onConnected} />);
      fireEvent.click(screen.getByTestId('pick-credential'));
      fireEvent.click(screen.getByText('findChats'));
      await screen.findByTestId('manual-notice');

      fireEvent.change(screen.getByLabelText('typeChatLabel'), { target: { value: '1827808523' } });
      expect(screen.queryByTestId('manual-bot-itself')).toBeNull();
      fireEvent.click(connectButton());

      await waitFor(() => expect(connect).toHaveBeenCalledWith(expect.objectContaining({ chatId: '1827808523' })));
    });
  });
});
