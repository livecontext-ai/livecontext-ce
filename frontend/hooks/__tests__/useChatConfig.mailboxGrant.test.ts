/**
 * The `mailbox` grant and its read/write axis, across the surfaces that persist them.
 *
 * <p>Written after finding the same defect twice in one branch, which is what makes it
 * worth a file of its own. Both builders below keep a HAND-MAINTAINED list of keys, and a
 * key missing from one is not a compile error, not a runtime error, and not visible in the
 * UI: the switch moves, the panel saves, and the value never leaves the browser. The first
 * time it was `buildToolsConfigPayload` for agents; the second was these two, for the
 * general chat.
 *
 * <p>The axis is tested beside the grant on purpose. An absent mode reads as FULL access
 * everywhere on this platform, so a mailbox that arrives without its mode is a mailbox that
 * can send.
 */
import { describe, it, expect } from 'vitest';
import {
  buildDraftChatConfigBody,
  buildConversationPatch,
  configFromConversation,
  normalizeOptInGrants,
} from '../useChatConfig';
import type { ChatConfig } from '../useChatConfig';

describe('buildDraftChatConfigBody - seeding a new conversation', () => {
  it('carries the grant and the mode, so a workspace default reaches the first chat', () => {
    const body = buildDraftChatConfigBody({
      mailbox: { enabled: true },
      mailboxAccessMode: 'read',
    });

    expect(body).toMatchObject({ mailbox: { enabled: true }, mailboxAccessMode: 'read' });
  });

  it('carries an explicit enabled:false, because a default that DENIES has to travel too', () => {
    const body = buildDraftChatConfigBody({ mailbox: { enabled: false } });

    expect(body).toMatchObject({ mailbox: { enabled: false } });
  });

  it('invents neither key for a config that never mentioned the mailbox', () => {
    const body = buildDraftChatConfigBody({ temperature: 0.4 });

    expect(body).not.toHaveProperty('mailbox');
    expect(body).not.toHaveProperty('mailboxAccessMode');
  });
});

describe('buildConversationPatch - the composer Options tab', () => {
  const current: ChatConfig = { toolsMode: 'all' };

  /** The builder nests everything under chatConfig, which is what the PUT body expects. */
  const flat = (patch: Record<string, unknown>) => patch.chatConfig as Record<string, unknown>;

  it('sends the grant, or the switch in Options changes nothing at all', () => {
    const patch = buildConversationPatch(current, { mailbox: { enabled: true } });

    expect(flat(patch).mailbox).toEqual({ enabled: true });
  });

  it('sends the mode, or granting the mailbox always grants sending with it', () => {
    const patch = buildConversationPatch(current, {
      mailbox: { enabled: true },
      mailboxAccessMode: 'read',
    });

    expect(flat(patch).mailboxAccessMode).toBe('read');
  });

  it('keeps the stored value when the patch does not mention the key', () => {
    const patch = buildConversationPatch(
      { ...current, mailbox: { enabled: true }, mailboxAccessMode: 'read' },
      { temperature: 0.2 },
    );

    expect(flat(patch).mailbox).toEqual({ enabled: true });
    expect(flat(patch).mailboxAccessMode).toBe('read');
  });

  /**
   * The trap this builder sets, and why the panel writes 'read' on the way OFF instead of
   * clearing: an `undefined` entry falls back to the CURRENT value, so "clearing" a mode
   * leaves the old one in place. A stale 'write' would then come back the next time the
   * mailbox is switched on, silently widening what the chat may do.
   */
  it('an undefined mode does NOT clear the stored one, which is why the panel never sends undefined', () => {
    const patch = buildConversationPatch(
      { ...current, mailboxAccessMode: 'write' },
      { mailbox: { enabled: false }, mailboxAccessMode: undefined },
    );

    expect(flat(patch).mailboxAccessMode).toBe('write');
  });

  it('writing the mode back explicitly is what actually resets it', () => {
    const patch = buildConversationPatch(
      { ...current, mailboxAccessMode: 'write' },
      { mailbox: { enabled: false }, mailboxAccessMode: 'read' },
    );

    expect(flat(patch).mailboxAccessMode).toBe('read');
  });
});

/**
 * The read-back half. Without it the panel starts from an empty config, and since the
 * builders rebuild the WHOLE chatConfig from that, any unrelated edit (web search,
 * auto-authorize) drops a granted mailbox on the floor: it fails closed, and silently.
 */
describe('configFromConversation - reading a stored chat back', () => {
  it('reads both shapes of the grant, like the backend does', () => {
    expect(configFromConversation({ chatConfig: { mailbox: { enabled: true } } }).mailbox)
      .toEqual({ enabled: true });
    expect(configFromConversation({ chatConfig: { mailbox: true } }).mailbox)
      .toEqual({ enabled: true });
    expect(configFromConversation({ chatConfig: { mailbox: { enabled: false } } }).mailbox)
      .toEqual({ enabled: false });
  });

  it('reads the mode back, or an unrelated edit would rewrite a read-only chat as sending', () => {
    expect(configFromConversation({ chatConfig: { mailboxAccessMode: 'read' } }).mailboxAccessMode)
      .toBe('read');
    expect(configFromConversation({ chatConfig: { mailboxAccessMode: 'write' } }).mailboxAccessMode)
      .toBe('write');
  });

  it('leaves both undefined for a chat that never had a mailbox, and for a junk value', () => {
    const empty = configFromConversation({ chatConfig: {} });
    expect(empty.mailbox).toBeUndefined();
    expect(empty.mailboxAccessMode).toBeUndefined();
    // Not 'read': inventing a restriction is as wrong as dropping one.
    expect(configFromConversation({ chatConfig: { mailboxAccessMode: 'readonly' } }).mailboxAccessMode)
      .toBeUndefined();
  });

  it('survives the full round trip a panel edit actually performs', () => {
    const stored = { chatConfig: { mailbox: { enabled: true }, mailboxAccessMode: 'read' } };
    const read = configFromConversation(stored);

    // An edit that touches something else entirely must not strip the mailbox.
    const patch = buildConversationPatch(read, { webSearch: false });
    const back = patch.chatConfig as Record<string, unknown>;

    expect(back.mailbox).toEqual({ enabled: true });
    expect(back.mailboxAccessMode).toBe('read');
  });
});

/**
 * The shape the SERVER may hand back. UserChatDefaultsService copies an ALLOWED_KEYS value
 * verbatim, so a grant saved as a bare boolean comes back as one. Every reader in the UI
 * tests `?.enabled`, so an unnormalised boolean renders the switch OFF on a grant that is
 * ON, and the next save from that screen then revokes it.
 */
describe('normalizeOptInGrants - what the defaults endpoint may return', () => {
  it('turns a boolean grant into the object shape every reader expects', () => {
    const normalized = normalizeOptInGrants({ mailbox: true, generation: true } as unknown as ChatConfig);

    expect(normalized.mailbox).toEqual({ enabled: true });
    expect(normalized.generation).toEqual({ enabled: true });
  });

  it('leaves the object shape alone, and keeps the fields it carries', () => {
    const normalized = normalizeOptInGrants({
      generation: { enabled: true, model: 'seedance-2.0-fast' },
      mailbox: { enabled: false },
    });

    expect(normalized.generation).toEqual({ enabled: true, model: 'seedance-2.0-fast' });
    expect(normalized.mailbox).toEqual({ enabled: false });
  });

  it('invents nothing for a config that never mentioned either grant', () => {
    const normalized = normalizeOptInGrants({ temperature: 0.4 });

    expect(normalized.mailbox).toBeUndefined();
    expect(normalized.generation).toBeUndefined();
    expect(normalized.temperature).toBe(0.4);
  });
});

/**
 * The field `extractMailbox` newly reads back. `toEqual` ignores undefined properties, so a
 * test that never stores one would pass against a reader that dropped it: the assertion has
 * to carry the field to mean anything.
 */
describe('extractMailbox - the fields the grant carries', () => {
  it('keeps a stored model alongside enabled, as the declared type says it may', () => {
    const read = configFromConversation({
      chatConfig: { mailbox: { enabled: true, model: 'imap-fast' } },
    });

    expect(read.mailbox).toEqual({ enabled: true, model: 'imap-fast' });
  });

  it('survives the round trip with that field intact', () => {
    const read = configFromConversation({
      chatConfig: { mailbox: { enabled: true, model: 'imap-fast' }, mailboxAccessMode: 'read' },
    });
    const patch = buildConversationPatch(read, { webSearch: false });

    expect((patch.chatConfig as Record<string, unknown>).mailbox)
      .toEqual({ enabled: true, model: 'imap-fast' });
  });
});
