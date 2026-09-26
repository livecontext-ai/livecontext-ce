// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';

// Translation stub - surfaces the key as the value so we can assert labels.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// ExpressionEditor pulls in heavy editor + portal logic. Stub it as a textarea so
// we can assert the template-capable fields route through the expression editor
// (not a plain Input/Textarea) and that isRequired flows to the right field.
vi.mock('@/components/ui/expression-editor', () => ({
  ExpressionEditor: (props: any) => (
    <textarea
      data-testid="expr-editor"
      data-required={props.isRequired ? 'true' : 'false'}
      placeholder={props.placeholder}
      value={props.value ?? ''}
      onChange={(e) => props.onChange(e.target.value)}
      readOnly={props.readOnly}
    />
  ),
}));

// CredentialSection pulls in apiClient. Stub with buttons that fire
// onCredentialSelect so we can assert the Number() coercion in the handler.
vi.mock('../../CredentialSection', () => ({
  CredentialSection: (props: any) => (
    <div
      data-testid="credential-section"
      data-required={props.toolCredentials?.[0]?.isRequired ? 'true' : 'false'}
      data-integration={props.integration}
    >
      <button type="button" data-testid="pick-numeric-string" onClick={() => props.onCredentialSelect('40')}>
        pick-string
      </button>
      <button type="button" data-testid="pick-number" onClick={() => props.onCredentialSelect(7)}>
        pick-number
      </button>
      <button type="button" data-testid="pick-null" onClick={() => props.onCredentialSelect(null)}>
        pick-null
      </button>
    </div>
  ),
}));

// Radix primitives need jsdom hacks. Thin stubs preserving the controlled contract.
vi.mock('@/components/ui/switch', () => ({
  Switch: ({ checked, onCheckedChange, disabled, ...rest }: any) => (
    <input
      type="checkbox"
      data-testid="delegation-toggle"
      checked={checked}
      disabled={disabled}
      onChange={(e) => onCheckedChange?.(e.target.checked)}
      {...rest}
    />
  ),
}));
vi.mock('@/components/ui/popover', () => ({
  Popover: ({ children }: any) => <div>{children}</div>,
  PopoverTrigger: ({ children }: any) => <div>{children}</div>,
  PopoverContent: ({ children }: any) => <div>{children}</div>,
}));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children, value, onValueChange }: any) => (
    <select data-testid="channel-select" value={value} onChange={(e) => onValueChange(e.target.value)}>
      {children}
    </select>
  ),
  SelectContent: ({ children }: any) => <>{children}</>,
  SelectItem: ({ children, value }: any) => <option value={value}>{children}</option>,
  SelectTrigger: () => null,
  SelectValue: () => null,
}));

vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => 'en' }));
const track = vi.hoisted(() => vi.fn());
vi.mock('@/lib/analytics/analytics', () => ({ track: (...a: unknown[]) => track(...a) }));

// The workspace's destinations, and a picker reduced to one button per choice: what is under
// test is what the section does with a choice, not how the select draws it.
const SLACK_OPS = {
  linkId: 'link-slack', channel: 'slack', credentialId: 5, botUsername: 'ops', chatId: 'C9', chatTitle: '#ops',
  chatType: 'channel', isDefault: false, active: true, verifiedAt: '2026-09-20T10:00:00Z', lastError: null,
  allowedUserIds: [],
};
const TELEGRAM_DEFAULT = { ...SLACK_OPS, linkId: 'link-tg', channel: 'telegram', chatId: '-100', chatTitle: 'Ops', isDefault: true };
const destinationsState = vi.hoisted(() => ({ destinations: [] as any[], workspaceDefault: null as any }));
vi.mock('@/components/app/ChannelDestinationPicker', () => ({
  useChatDestinations: () => ({ ...destinationsState, isLoading: false, isError: false }),
  ChannelDestinationPicker: ({ value, onChange, extraOption, extraSelected }: any) => (
    <div data-testid="destination-picker" data-value={String(value)} data-custom={String(!!extraSelected)}>
      <button type="button" onClick={() => onChange(null, destinationsState.workspaceDefault)}>pick-default</button>
      <button type="button" onClick={() => onChange('link-slack', destinationsState.destinations.find((d: any) => d.linkId === 'link-slack') ?? null)}>pick-slack</button>
      {extraOption && <button type="button" onClick={() => onChange(extraOption.value, null)}>pick-custom</button>}
    </div>
  ),
}));

import { ApprovalDelegationSection } from '../ApprovalDelegationSection';

const ENABLED_DELEGATION = { channel: 'telegram' as const, credentialId: 1, chatId: '-100123' };

function renderSection(overrides: Partial<React.ComponentProps<typeof ApprovalDelegationSection>> = {}) {
  const handleDelegationChange = vi.fn();
  render(
    <ApprovalDelegationSection
      approvalDelegation={ENABLED_DELEGATION}
      handleDelegationChange={handleDelegationChange}
      {...overrides}
    />
  );
  return { handleDelegationChange };
}

afterEach(() => {
  cleanup();
  track.mockReset();
  destinationsState.destinations = [];
  destinationsState.workspaceDefault = null;
});

describe('ApprovalDelegationSection - a destination picked like a credential', () => {
  const withDestinations = () => {
    destinationsState.destinations = [TELEGRAM_DEFAULT, SLACK_OPS];
    destinationsState.workspaceDefault = TELEGRAM_DEFAULT;
  };

  it('turning delegation on picks the workspace default, on the default\'s service', () => {
    destinationsState.destinations = [SLACK_OPS];
    destinationsState.workspaceDefault = { ...SLACK_OPS, isDefault: true };
    const { handleDelegationChange } = renderSection({ approvalDelegation: undefined });

    fireEvent.click(screen.getByTestId('delegation-toggle'));

    expect(handleDelegationChange).toHaveBeenCalledWith({ channel: 'slack', linkId: 'default' });
    expect(track).toHaveBeenCalledWith('approval_channel_configured', { enabled: true, destination: 'default', channel: 'slack' });
  });

  it('turning delegation off is reported with enabled=false only', () => {
    renderSection();
    fireEvent.click(screen.getByTestId('delegation-toggle'));

    expect(track).toHaveBeenCalledWith('approval_channel_configured', { enabled: false });
  });

  it('with a destination picked, the service, account and chat fields are gone: the destination decides', () => {
    withDestinations();
    renderSection({ approvalDelegation: { channel: 'telegram', linkId: 'default', messageTemplate: 'Ship it?' } });

    expect(screen.getByTestId('destination-picker').getAttribute('data-value')).toBe('null');
    expect(screen.queryByTestId('channel-select')).toBeNull();
    expect(screen.queryByText('chatIdLabel')).toBeNull();
    // The message is still the node's own to write.
    expect(screen.getByText('messageLabel')).toBeTruthy();
  });

  it('picking a destination drops the account and chat named by hand, keeps the message, takes its service', () => {
    withDestinations();
    const { handleDelegationChange } = renderSection({
      approvalDelegation: { channel: 'telegram', credentialId: 1, chatId: '-100123', messageTemplate: 'Ship it?' },
    });

    fireEvent.click(screen.getByText('pick-slack'));

    expect(handleDelegationChange).toHaveBeenCalledWith({
      channel: 'slack', linkId: 'link-slack', messageTemplate: 'Ship it?',
    });
    // The kind of destination is reported, never its link id nor the message.
    expect(track).toHaveBeenCalledWith('approval_channel_configured', { enabled: true, destination: 'specific', channel: 'slack' });
    expect(JSON.stringify(track.mock.calls)).not.toContain('link-slack');
  });

  it('picking the default stores the keyword, so it follows the workspace default at send time', () => {
    withDestinations();
    const { handleDelegationChange } = renderSection({ approvalDelegation: { channel: 'slack', linkId: 'link-slack' } });

    fireEvent.click(screen.getByText('pick-default'));

    expect(handleDelegationChange).toHaveBeenCalledWith({ channel: 'telegram', linkId: 'default' });
  });

  it('"another chat" goes back to naming a service and a chat, on the service that was picked', () => {
    withDestinations();
    const { handleDelegationChange } = renderSection({
      approvalDelegation: { channel: 'slack', linkId: 'link-slack', approveLabel: 'Go' },
    });

    fireEvent.click(screen.getByText('pick-custom'));

    expect(handleDelegationChange).toHaveBeenCalledWith({ channel: 'slack', approveLabel: 'Go' });
    expect(track).toHaveBeenCalledWith('approval_channel_configured', { enabled: true, destination: 'custom', channel: 'slack' });
  });

  it('a node that names its own chat (older plans) shows the picker on "another chat" with its fields', () => {
    withDestinations();
    renderSection();

    expect(screen.getByTestId('destination-picker').getAttribute('data-custom')).toBe('true');
    expect(screen.getByTestId('channel-select')).toBeTruthy();
    expect(screen.getByText('chatIdLabel')).toBeTruthy();
  });

  it('the image editor follows the picked destination\'s service (only Telegram sends images)', () => {
    withDestinations();
    renderSection({ approvalDelegation: { channel: 'telegram', linkId: 'link-slack' } });

    expect(screen.queryByText('imageLabel')).toBeNull();
  });
});

describe('ApprovalDelegationSection - expression editor for template-capable fields', () => {
  it('renders chatId, messageTemplate, image, approveLabel and rejectLabel through the ExpressionEditor (5 editors), not plain inputs', () => {
    renderSection();
    const editors = screen.getAllByTestId('expr-editor');
    expect(editors).toHaveLength(5);
  });

  it('propagates approveLabel and rejectLabel edits from their expression editors to node data', () => {
    const { handleDelegationChange } = renderSection();
    const [, , , approveLabel, rejectLabel] = screen.getAllByTestId('expr-editor');
    fireEvent.change(approveLabel, { target: { value: '👍 Ship it' } });
    expect(handleDelegationChange).toHaveBeenCalledWith(
      expect.objectContaining({ approveLabel: '👍 Ship it' })
    );
    fireEvent.change(rejectLabel, { target: { value: '👎 Hold' } });
    expect(handleDelegationChange).toHaveBeenCalledWith(
      expect.objectContaining({ rejectLabel: '👎 Hold' })
    );
  });

  it('leaves every editor optional: a blank chatId goes to the destination connected on that service', () => {
    renderSection();
    const [chatId, messageTemplate, image] = screen.getAllByTestId('expr-editor');
    expect(chatId.getAttribute('data-required')).toBe('false');
    expect(messageTemplate.getAttribute('data-required')).toBe('false');
    expect(image.getAttribute('data-required')).toBe('false');
  });

  it('propagates chatId edits from the expression editor to node data', () => {
    const { handleDelegationChange } = renderSection();
    const [chatId] = screen.getAllByTestId('expr-editor');
    fireEvent.change(chatId, { target: { value: '{{trigger:form.output.chat_id}}' } });
    expect(handleDelegationChange).toHaveBeenCalledWith(
      expect.objectContaining({ chatId: '{{trigger:form.output.chat_id}}' })
    );
  });

  it('propagates messageTemplate edits from the expression editor to node data', () => {
    const { handleDelegationChange } = renderSection();
    const [, messageTemplate] = screen.getAllByTestId('expr-editor');
    fireEvent.change(messageTemplate, { target: { value: 'Approve {{trigger:form.output.amount}}?' } });
    expect(handleDelegationChange).toHaveBeenCalledWith(
      expect.objectContaining({ messageTemplate: 'Approve {{trigger:form.output.amount}}?' })
    );
  });

  it('propagates image edits from the expression editor to node data', () => {
    const { handleDelegationChange } = renderSection();
    const [, , image] = screen.getAllByTestId('expr-editor');
    fireEvent.change(image, { target: { value: '{{interface:card.output.screenshot}}' } });
    expect(handleDelegationChange).toHaveBeenCalledWith(
      expect.objectContaining({ image: '{{interface:card.output.screenshot}}' })
    );
  });
});

describe('ApprovalDelegationSection - required/optional presentation', () => {
  it('marks only the channel as required; the destination is optional', () => {
    renderSection();
    expect(screen.getByText('channelLabel').textContent).toContain('*');
    expect(screen.getByText('chatIdLabel').textContent).not.toContain('*');
  });

  it('leaves messageTemplate, image and allowedUserIds labels without a required marker', () => {
    renderSection();
    expect(screen.getByText('messageLabel').textContent).not.toContain('*');
    expect(screen.getByText('imageLabel').textContent).not.toContain('*');
    expect(screen.getByText('allowedUserIdsLabel').textContent).not.toContain('*');
  });

  it('marks the credential picker as OPTIONAL (isRequired false descriptor, no asterisk from CredentialSection)', () => {
    renderSection();
    expect(screen.getByTestId('credential-section').getAttribute('data-required')).toBe('false');
  });

  it('shows the default-credential fallback hint under the credential picker', () => {
    renderSection();
    expect(screen.getByText('credentialHint')).toBeTruthy();
  });
});

describe('ApprovalDelegationSection - credential id Number() coercion', () => {
  it('stores a numeric-string credential id ("40") as the NUMBER 40', () => {
    const { handleDelegationChange } = renderSection();
    fireEvent.click(screen.getByTestId('pick-numeric-string'));
    expect(handleDelegationChange).toHaveBeenCalledWith(
      expect.objectContaining({ credentialId: 40 })
    );
    const stored = handleDelegationChange.mock.calls[0][0].credentialId;
    expect(typeof stored).toBe('number');
  });

  it('stores a numeric credential id unchanged', () => {
    const { handleDelegationChange } = renderSection();
    fireEvent.click(screen.getByTestId('pick-number'));
    expect(handleDelegationChange).toHaveBeenCalledWith(
      expect.objectContaining({ credentialId: 7 })
    );
  });

  it('clears the credential id (undefined) when the selection is null', () => {
    const { handleDelegationChange } = renderSection();
    fireEvent.click(screen.getByTestId('pick-null'));
    expect(handleDelegationChange).toHaveBeenCalledWith(
      expect.objectContaining({ credentialId: undefined })
    );
  });
});

describe('ApprovalDelegationSection - the five chat services', () => {
  it('offers exactly the services the backend has a connector for', () => {
    renderSection();
    const options = Array.from(screen.getByTestId('channel-select').querySelectorAll('option')).map((o) => o.value);
    expect(options).toEqual(['telegram', 'slack', 'discord', 'whatsapp', 'teams']);
  });

  it('switching service drops the account and destination of the previous one, and keeps the message', () => {
    const { handleDelegationChange } = renderSection({
      approvalDelegation: { channel: 'telegram', credentialId: 1, chatId: '-100123', messageTemplate: 'Ship?' },
    });

    fireEvent.change(screen.getByTestId('channel-select'), { target: { value: 'slack' } });

    // A Telegram credential id and a Telegram chat id mean nothing on Slack.
    expect(handleDelegationChange).toHaveBeenCalledWith({ channel: 'slack', messageTemplate: 'Ship?' });
  });

  it('switching away from Telegram drops the image, which only Telegram sends', () => {
    const { handleDelegationChange } = renderSection({
      approvalDelegation: { channel: 'telegram', image: '{{interface:card.output.screenshot}}' },
    });

    fireEvent.change(screen.getByTestId('channel-select'), { target: { value: 'discord' } });

    expect(handleDelegationChange.mock.calls[0][0]).not.toHaveProperty('image');
  });

  it('switching to Teams drops allowed people, which a Teams link cannot enforce', () => {
    const { handleDelegationChange } = renderSection({
      approvalDelegation: { channel: 'slack', allowedUserIds: ['U1'] },
    });

    fireEvent.change(screen.getByTestId('channel-select'), { target: { value: 'teams' } });

    expect(handleDelegationChange.mock.calls[0][0]).toEqual({ channel: 'teams' });
  });

  it('keeps allowed people when switching between services that identify the presser', () => {
    const { handleDelegationChange } = renderSection({
      approvalDelegation: { channel: 'slack', allowedUserIds: ['U1'] },
    });

    fireEvent.change(screen.getByTestId('channel-select'), { target: { value: 'discord' } });

    expect(handleDelegationChange.mock.calls[0][0]).toEqual({ channel: 'discord', allowedUserIds: ['U1'] });
  });

  it('on Slack: no image editor, and the account picker lists Slack connections', () => {
    renderSection({ approvalDelegation: { channel: 'slack' } });

    expect(screen.queryByText('imageLabel')).toBeNull();
    expect(screen.getAllByTestId('expr-editor')).toHaveLength(4);
    expect(screen.getByTestId('credential-section').getAttribute('data-integration')).toBe('slack');
  });

  it('on Teams: the picker uses the microsoftteams integration and the allow-list is replaced by an explanation', () => {
    renderSection({ approvalDelegation: { channel: 'teams' } });

    expect(screen.getByTestId('credential-section').getAttribute('data-integration')).toBe('microsoftteams');
    expect(screen.queryByText('allowedUserIdsLabel')).toBeNull();
    expect(screen.getByText('anonymousPressNote')).toBeTruthy();
  });

  it('links to Settings > Channels, where a service is connected', () => {
    renderSection();
    expect(screen.getByText('manageChannels').getAttribute('href')).toBe('/en/app/settings/channels');
  });
});
