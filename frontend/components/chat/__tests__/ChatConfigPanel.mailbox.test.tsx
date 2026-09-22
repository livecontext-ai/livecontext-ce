/**
 * @vitest-environment jsdom
 *
 * The mailbox switch in the chat settings panel, asserted on what it WRITES.
 *
 * <p>The sibling layout test counts switches and info icons, which proves the row renders
 * and nothing else. Every defect this control has had so far was on the write side: the
 * payload builder held the fields and assigned neither, then two hand-maintained key lists
 * dropped them again. A count would have passed through all of it.
 *
 * <p>The mode is asserted alongside the grant every time, because an absent mode reads as
 * FULL access on the server: a mailbox that arrives without one is a mailbox that can send.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import * as React from 'react';

const h = vi.hoisted(() => ({
  updateConfig: vi.fn(),
  config: { webSearch: true } as Record<string, unknown>,
  target: 'user-default' as string,
}));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/hooks/useChatConfig', () => ({
  useChatConfig: () => ({
    config: h.config,
    updateConfig: h.updateConfig,
    isLoading: false,
    isSaving: false,
    error: null,
    target: h.target,
  }),
}));
vi.mock('@/components/ui/slider', () => ({ Slider: () => <div data-testid="slider" /> }));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectValue: () => <div />,
}));
vi.mock('@/components/ui/tooltip', () => ({
  Tooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipContent: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipTrigger: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

import { ChatConfigPanel } from '../ChatConfigPanel';

const mailboxSwitch = () => screen.getByLabelText('mailboxLabel');
const modeSwitch = () => screen.queryByLabelText('mailboxAccessLabel');

describe('ChatConfigPanel - the mailbox switch', () => {
  afterEach(() => {
    cleanup();
    h.updateConfig.mockClear();
    h.config = { webSearch: true };
    h.target = 'user-default';
  });

  it('hides the permissions row until the mailbox is on', () => {
    render(<ChatConfigPanel userDefault />);

    expect(mailboxSwitch()).toBeInTheDocument();
    // A mode for a capability the chat has not got reads as a setting that does nothing.
    expect(modeSwitch()).toBeNull();
  });

  it('grants the mailbox AND seeds read-only, rather than leaving the mode unstated', () => {
    render(<ChatConfigPanel userDefault />);

    fireEvent.click(mailboxSwitch());

    expect(h.updateConfig).toHaveBeenCalledWith({
      mailbox: { enabled: true },
      mailboxAccessMode: 'read',
    });
  });

  it('keeps a mode the user already chose instead of pushing it back to read', () => {
    h.config = { mailbox: { enabled: false }, mailboxAccessMode: 'write' };
    render(<ChatConfigPanel userDefault />);

    fireEvent.click(mailboxSwitch());

    expect(h.updateConfig).toHaveBeenCalledWith({
      mailbox: { enabled: true },
      mailboxAccessMode: 'write',
    });
  });

  /**
   * Not `undefined`. buildConversationPatch falls back to the CURRENT value for an undefined
   * entry, so clearing the mode would leave a stale 'write' that comes back on the next
   * switch-on, widening what the chat may do without anyone asking for it.
   */
  it('writes the mode back to read on the way OFF, never clears it', () => {
    h.config = { mailbox: { enabled: true }, mailboxAccessMode: 'write' };
    render(<ChatConfigPanel userDefault />);

    fireEvent.click(mailboxSwitch());

    expect(h.updateConfig).toHaveBeenCalledWith({
      mailbox: { enabled: false },
      mailboxAccessMode: 'read',
    });
  });

  it('shows the permissions row once on, and flips the mode without touching the grant', () => {
    h.config = { mailbox: { enabled: true }, mailboxAccessMode: 'read' };
    render(<ChatConfigPanel userDefault />);

    const mode = modeSwitch();
    expect(mode).toBeInTheDocument();
    expect(mode).not.toBeChecked();

    fireEvent.click(mode!);

    expect(h.updateConfig).toHaveBeenCalledWith({ mailboxAccessMode: 'write' });
  });

  /**
   * The server reads an absent mode as WRITE, so the panel has to show write. Displaying
   * read-only here would report a restriction nothing is enforcing, on a control whose whole
   * job is to tell the user what the chat may do.
   */
  it('an enabled mailbox with no stored mode displays as FULL access, matching the server', () => {
    h.config = { mailbox: { enabled: true } };
    render(<ChatConfigPanel userDefault />);

    expect(modeSwitch()).toBeChecked();
  });
});

/**
 * The same control on the CONVERSATION surface, which is the one a person is actually
 * looking at while a chat runs. The account default only SEEDS a conversation, so without a
 * row here a chat that inherited a mailbox could never be narrowed or revoked from inside
 * it, while auto-authorize, the wildcard that only read-only survives, IS adjustable there.
 */
describe('ChatConfigPanel - the mailbox switch on a conversation', () => {
  afterEach(() => {
    cleanup();
    h.updateConfig.mockClear();
    h.config = { webSearch: true };
    h.target = 'user-default';
  });

  const renderConversation = () => {
    h.target = 'conversation';
    render(<ChatConfigPanel conversationId="conv-1" />);
  };

  it('offers the mailbox beside generation, not only in the account defaults', () => {
    renderConversation();

    expect(screen.getByText('mailboxLabel')).toBeInTheDocument();
    expect(screen.queryByText('mailboxAccessLabel')).toBeNull();
  });

  it('grants it and seeds read-only, the same contract as the defaults surface', () => {
    renderConversation();

    fireEvent.click(screen.getByLabelText('mailboxLabel'));

    expect(h.updateConfig).toHaveBeenCalledWith({
      mailbox: { enabled: true },
      mailboxAccessMode: 'read',
    });
  });

  it('lets a person REVOKE a mailbox the conversation inherited from the account default', () => {
    h.config = { mailbox: { enabled: true }, mailboxAccessMode: 'write' };
    renderConversation();

    fireEvent.click(screen.getByLabelText('mailboxLabel'));

    expect(h.updateConfig).toHaveBeenCalledWith({
      mailbox: { enabled: false },
      mailboxAccessMode: 'read',
    });
  });

  it('shows an unstated mode as FULL access here too, matching the server', () => {
    h.config = { mailbox: { enabled: true } };
    renderConversation();

    expect(screen.getByText('mailboxAccessWrite')).toBeInTheDocument();
  });

  /**
   * The mode button on this surface, which the tests above never clicked: they only read its
   * label. A control whose label is asserted and whose click is not is a control that can
   * stop writing without a single test noticing.
   */
  it('flips the mode from this surface too, without touching the grant', () => {
    h.config = { mailbox: { enabled: true }, mailboxAccessMode: 'read' };
    renderConversation();

    fireEvent.click(screen.getByLabelText('mailboxAccessLabel'));

    expect(h.updateConfig).toHaveBeenCalledWith({ mailboxAccessMode: 'write' });
  });

  it('narrows a full-access chat back to read-only', () => {
    h.config = { mailbox: { enabled: true }, mailboxAccessMode: 'write' };
    renderConversation();

    fireEvent.click(screen.getByLabelText('mailboxAccessLabel'));

    expect(h.updateConfig).toHaveBeenCalledWith({ mailboxAccessMode: 'read' });
  });
});

/**
 * The surface where this row must NOT appear.
 *
 * On an agent-backed chat the agent owns the setting and CreateAgentModal renders it, while
 * this panel's agent read/write path carries no mailbox at all: `configFromAgent` never reads
 * the keys and `buildAgentPatch` never writes them. A row here would therefore read OFF
 * whatever the agent actually holds, and save nothing when clicked, which is the exact
 * "moves, saves, changes nothing" defect this feature already shipped twice.
 */
describe('ChatConfigPanel - the mailbox row is absent on an agent-backed chat', () => {
  afterEach(() => {
    cleanup();
    h.updateConfig.mockClear();
    h.config = { webSearch: true };
    h.target = 'user-default';
  });

  it('does not render a control this surface can neither read nor write', () => {
    h.target = 'agent';
    h.config = { mailbox: { enabled: true }, mailboxAccessMode: 'read' };
    render(<ChatConfigPanel agentId="a-1" />);

    // Generation IS offered here, so the absence below is a scope decision, not a dead panel.
    expect(screen.getByText('generationLabel')).toBeInTheDocument();
    expect(screen.queryByText('mailboxLabel')).toBeNull();
    expect(screen.queryByText('mailboxAccessLabel')).toBeNull();
  });
});
