// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';

// Translation stub: the key, plus its values when there are any, so a test can read what
// the assistant is asked without depending on the English wording.
const track = vi.hoisted(() => vi.fn());
vi.mock('@/lib/analytics/analytics', () => ({ track: (...a: unknown[]) => track(...a) }));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));

vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }) }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ loginWithRedirect: vi.fn() }) }));
vi.mock('@/components/settings', () => ({ PageHeader: ({ title }: any) => <h1>{title}</h1> }));
vi.mock('@/components/ui/service-icon', () => ({ ServiceIcon: ({ iconSlug }: any) => <span data-icon={iconSlug} /> }));
const addToast = vi.fn();
vi.mock('@/components/Toast', () => ({
  default: () => null,
  useToast: () => ({ toasts: [], addToast, removeToast: vi.fn() }),
}));
vi.mock('@/components/chat/ConfirmDeleteModal', () => ({
  ConfirmDeleteModal: ({ isOpen, onConfirm, message }: any) =>
    isOpen ? <div data-testid="confirm"><span>{message}</span><button onClick={onConfirm}>confirm</button></div> : null,
}));
vi.mock('@/components/app/ChatPanelContent', () => ({ ChatPanelContent: () => null }));

const openTab = vi.fn();
let sidePanelAvailable = true;
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => (sidePanelAvailable ? { openTab } : null),
  pathMatchesAnyPattern: () => false,
}));

const queueAiChatMessage = vi.fn();
vi.mock('@/lib/sidePanelChat', () => ({ queueAiChatMessage: (m: string) => queueAiChatMessage(m) }));

const refetch = vi.fn();
let listed: any[] = [];
let listFailed = false;
const queryKeys: unknown[] = [];
vi.mock('@/lib/hooks/useOrgScopedQuery', () => ({
  useOrgScopedQuery: ({ queryKey }: any) => {
    queryKeys.push(queryKey);
    return { data: listFailed ? undefined : { channels: listed }, isLoading: false, isError: listFailed, refetch };
  },
}));

const setDefault = vi.fn();
const disconnect = vi.fn();
vi.mock('@/lib/api/orchestrator', () => ({
  chatChannelService: {
    list: vi.fn(),
    setDefault: (id: string) => setDefault(id),
    disconnect: (id: string) => disconnect(id),
  },
}));

vi.mock('../components/ManualChannelSetup', () => ({
  ManualChannelSetup: ({ channel, connectedCredentialIds }: any) => (
    <div data-testid="manual-dialog" data-known={(connectedCredentialIds ?? []).join(',')}>{channel.id}</div>
  ),
}));

import ChannelsSettingsPage from '../page';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

const destination = (overrides: Record<string, unknown> = {}) => ({
  linkId: 'link-1', channel: 'slack', credentialId: 5, botUsername: 'ops', chatId: 'C1', chatTitle: '#ops',
  chatType: 'channel', isDefault: false, active: true, verifiedAt: '2026-09-20T10:00:00Z', lastError: null,
  allowedUserIds: [], ...overrides,
});

beforeEach(() => {
  listed = [];
  listFailed = false;
  queryKeys.length = 0;
  sidePanelAvailable = true;
  useCurrentOrgStore.setState({ currentOrgId: 'org-1', currentOrgRole: 'MEMBER' });
  vi.clearAllMocks();
});
afterEach(cleanup);

describe('Settings > Channels', () => {
  it('offers the five services, each with a set-up-with-the-assistant action', () => {
    render(<ChannelsSettingsPage />);

    for (const id of ['telegram', 'slack', 'discord', 'whatsapp', 'teams']) {
      expect(screen.getByTestId(`chat-channel-service-${id}`)).toBeTruthy();
    }
    expect(screen.getAllByText('setUpWithAssistant')).toHaveLength(5);
  });

  it('set up opens the assistant with the request already sent, naming the service and its channel id', () => {
    render(<ChannelsSettingsPage />);

    const teams = screen.getByTestId('chat-channel-service-teams');
    fireEvent.click(teams.querySelector('button')!);

    expect(openTab).toHaveBeenCalledWith(expect.objectContaining({ id: 'ai-chat' }));
    expect(queueAiChatMessage).toHaveBeenCalledWith('setupPrompt:{"channel":"Microsoft Teams","id":"teams"}');
    expect(track).toHaveBeenCalledWith('channel_assistant_help_clicked', { channel: 'teams', intent: 'setup' });
  });

  it('shows the empty state when nothing is connected', () => {
    render(<ChannelsSettingsPage />);

    expect(screen.getByText('emptyTitle')).toBeTruthy();
  });

  it('reads the list through the workspace-scoped cache (the org id is prefixed by useOrgScopedQuery)', () => {
    render(<ChannelsSettingsPage />);

    expect(queryKeys[0]).toEqual(['chat-channels']);
  });

  it('a list that could not be read says so, instead of claiming nothing is connected', () => {
    listFailed = true;
    render(<ChannelsSettingsPage />);

    expect(screen.getByTestId('chat-channels-error').textContent).toBe('error');
    expect(screen.queryByText('emptyTitle')).toBeNull();
  });

  it('a working destination that is not the default can be made the default', async () => {
    listed = [destination()];
    render(<ChannelsSettingsPage />);

    fireEvent.click(screen.getByText('makeDefault'));

    await waitFor(() => expect(setDefault).toHaveBeenCalledWith('link-1'));
    await waitFor(() => expect(refetch).toHaveBeenCalled());
  });

  it('a destination that never received anything offers the assistant to fix it, not the default', () => {
    listed = [destination({ verifiedAt: null, lastError: 'not_in_channel' })];
    render(<ChannelsSettingsPage />);

    expect(screen.queryByText('makeDefault')).toBeNull();
    expect(screen.getByText('statusNotDelivered')).toBeTruthy();
    fireEvent.click(screen.getByText('troubleshoot'));
    expect(queueAiChatMessage).toHaveBeenCalledWith(
      'troubleshootPrompt:{"channel":"Slack","chat":"#ops","error":"not_in_channel"}');
    expect(track).toHaveBeenCalledWith('channel_assistant_help_clicked', { channel: 'slack', intent: 'troubleshoot' });
    expect(JSON.stringify(track.mock.calls)).not.toContain('#ops');
  });

  it('without a side panel, set up says the assistant cannot open instead of doing nothing', () => {
    sidePanelAvailable = false;
    render(<ChannelsSettingsPage />);

    fireEvent.click(screen.getByTestId('chat-channel-service-slack').querySelector('button')!);

    expect(queueAiChatMessage).not.toHaveBeenCalled();
    expect(addToast).toHaveBeenCalledWith(expect.objectContaining({ title: 'assistantUnavailable' }));
  });

  it('a destination on a service this build does not know is shown as itself, not as Telegram', () => {
    listed = [destination({ channel: 'matrix' })];
    render(<ChannelsSettingsPage />);

    const row = screen.getByTestId('chat-channel-destinations');
    expect(row.textContent).toContain('matrix');
    expect(row.textContent).not.toContain('Telegram');
  });

  it('every service can also be set up by hand, without the assistant (and so without credits)', () => {
    render(<ChannelsSettingsPage />);

    fireEvent.click(screen.getByTestId('manual-setup-whatsapp'));

    expect(screen.getByTestId('manual-dialog').textContent).toBe('whatsapp');
    expect(queueAiChatMessage).not.toHaveBeenCalled();
  });

  it('the manual form learns which accounts of that service are already connected (their saved key is kept)', () => {
    listed = [
      destination({ channel: 'discord', credentialId: 11 }),
      destination({ linkId: 'link-2', channel: 'discord', credentialId: 12 }),
      destination({ linkId: 'link-3', channel: 'slack', credentialId: 5 }),
      destination({ linkId: 'link-4', channel: 'discord', credentialId: null }),
    ];
    render(<ChannelsSettingsPage />);

    fireEvent.click(screen.getByTestId('manual-setup-discord'));

    expect(screen.getByTestId('manual-dialog').getAttribute('data-known')).toBe('11,12');
  });

  it('a read-only member sees where the workspace is reached, and no action that could only be refused', () => {
    useCurrentOrgStore.setState({ currentOrgRole: 'VIEWER' });
    listed = [destination(), destination({ linkId: 'link-2', verifiedAt: null, lastError: 'x' })];
    render(<ChannelsSettingsPage />);

    expect(screen.getByTestId('chat-channels-read-only').textContent).toBe('readOnly');
    expect(screen.getByTestId('chat-channel-destinations').textContent).toContain('#ops');
    expect(screen.queryByText('setUpWithAssistant')).toBeNull();
    expect(screen.queryByTestId('manual-setup-slack')).toBeNull();
    expect(screen.queryByText('makeDefault')).toBeNull();
    expect(screen.queryByText('troubleshoot')).toBeNull();
    expect(screen.queryByLabelText('disconnect')).toBeNull();
  });

  it('while the workspace role is still unknown, no action is offered and no read-only note is claimed', () => {
    useCurrentOrgStore.setState({ currentOrgId: 'org-1', currentOrgRole: null });
    listed = [destination()];
    render(<ChannelsSettingsPage />);

    expect(screen.queryByTestId('manual-setup-slack')).toBeNull();
    expect(screen.queryByLabelText('disconnect')).toBeNull();
    expect(screen.queryByTestId('chat-channels-read-only')).toBeNull();
  });

  it('in a personal workspace (no organization) every action is offered', () => {
    useCurrentOrgStore.setState({ currentOrgId: null, currentOrgRole: null });
    window.localStorage.removeItem('lc.activeOrg');
    listed = [destination()];
    render(<ChannelsSettingsPage />);

    expect(screen.getByTestId('manual-setup-slack')).toBeTruthy();
    expect(screen.getByLabelText('disconnect')).toBeTruthy();
  });

  it('a member with a write role gets the actions and no read-only note', () => {
    listed = [destination()];
    render(<ChannelsSettingsPage />);

    expect(screen.queryByTestId('chat-channels-read-only')).toBeNull();
    expect(screen.getByTestId('manual-setup-slack')).toBeTruthy();
    expect(screen.getByLabelText('disconnect')).toBeTruthy();
  });

  it('the default is badged, and disconnecting asks first', async () => {
    listed = [destination({ isDefault: true })];
    render(<ChannelsSettingsPage />);

    expect(screen.getByText('defaultBadge')).toBeTruthy();
    fireEvent.click(screen.getByLabelText('disconnect'));
    expect(disconnect).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText('confirm'));

    await waitFor(() => expect(disconnect).toHaveBeenCalledWith('link-1'));
  });
});
