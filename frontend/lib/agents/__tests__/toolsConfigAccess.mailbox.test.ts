import { describe, it, expect } from 'vitest';
import {
  buildToolsConfigPayload,
  isMailboxEnabled,
  getMailboxAccessMode,
} from '../toolsConfigAccess';

/**
 * The mailbox grant and its read/write axis, on the payload side.
 *
 * This is where the feature first shipped broken, and the shape of the break is worth
 * keeping in mind: the modal held both pieces of state, passed both to
 * `buildToolsConfigPayload`, and the builder assigned neither. Nothing failed. The
 * toggles rendered, the form saved, and the row came back without the keys, so the
 * tool could not be enabled from the product at all and the read-only mode could
 * never be stored.
 *
 * TypeScript could not catch it: `ToolsConfigShape` carries an index signature
 * (`[k: string]: unknown`), so an unassigned optional field is perfectly legal. Only
 * an assertion on the built object sees it, which is what these are.
 */
describe('toolsConfigAccess - mailbox grant and access mode', () => {
  const base = {
    mode: 'all' as const,
    workflows: [],
    tables: [],
    interfaces: [],
    agents: [],
    applications: [],
  };

  describe('reading a stored config', () => {
    it('is off when the key is absent: the mailbox is opt-in, unlike web search', () => {
      expect(isMailboxEnabled({})).toBe(false);
      expect(isMailboxEnabled(null)).toBe(false);
      expect(isMailboxEnabled(undefined)).toBe(false);
    });

    it('accepts both persisted shapes, matching AgentModuleResolver.isMailboxEnabled', () => {
      // A bare boolean is what the modal writes; the richer object is what the backend
      // tolerates. Reading only the boolean would show an enabled agent as disabled,
      // and then SAVE that lie back over the real value.
      expect(isMailboxEnabled({ mailbox: true })).toBe(true);
      expect(isMailboxEnabled({ mailbox: { enabled: true } })).toBe(true);
      expect(isMailboxEnabled({ mailbox: false })).toBe(false);
      expect(isMailboxEnabled({ mailbox: { enabled: false } })).toBe(false);
    });

    it('defaults the access mode to full write, which is what the backend does with no key', () => {
      expect(getMailboxAccessMode({})).toBe('write');
      expect(getMailboxAccessMode(null)).toBe('write');
      expect(getMailboxAccessMode({ mailboxAccessMode: 'readonly' })).toBe('write');
    });

    it('reads back a stored read-only mode', () => {
      expect(getMailboxAccessMode({ mailboxAccessMode: 'read' })).toBe('read');
    });
  });

  describe('building the payload', () => {
    it('emits the grant, or the toggle is decorative and the tool can never be enabled', () => {
      expect(buildToolsConfigPayload({ ...base, mailbox: true }).mailbox).toBe(true);
    });

    it('emits an explicit false, because the backend MERGES and an omission means "keep"', () => {
      // Omitting it on a turn-OFF would leave a granted mailbox granted forever: the
      // one direction where a dropped key hands out access instead of withholding it.
      expect(buildToolsConfigPayload({ ...base, mailbox: false }).mailbox).toBe(false);
    });

    it('emits the access mode in both directions', () => {
      expect(buildToolsConfigPayload({ ...base, mailboxAccessMode: 'read' }).mailboxAccessMode)
        .toBe('read');
      expect(buildToolsConfigPayload({ ...base, mailboxAccessMode: 'write' }).mailboxAccessMode)
        .toBe('write');
    });

    it('invents neither key for a form that never touched them', () => {
      const payload = buildToolsConfigPayload({ ...base });
      expect(payload.mailbox).toBeUndefined();
      expect(payload.mailboxAccessMode).toBeUndefined();
    });

    it('carries the grant and the mode independently of the other families', () => {
      const payload = buildToolsConfigPayload({
        ...base,
        mailbox: true,
        mailboxAccessMode: 'read',
        generation: true,
        memoryAccessMode: 'write',
      });
      expect(payload.mailbox).toBe(true);
      expect(payload.mailboxAccessMode).toBe('read');
      expect(payload.generation).toBe(true);
      expect(payload.memoryAccessMode).toBe('write');
    });

    it('survives a round trip, which is what an edit-then-save actually does', () => {
      // The real regression was a one-way loss: read the agent, render, save, and the
      // restriction is gone. Asserting the round trip is the only version of this test
      // that covers the whole motion rather than one half of it.
      const stored = buildToolsConfigPayload({ ...base, mailbox: true, mailboxAccessMode: 'read' });
      expect(isMailboxEnabled(stored)).toBe(true);
      expect(getMailboxAccessMode(stored)).toBe('read');

      const resaved = buildToolsConfigPayload({
        ...base,
        mailbox: isMailboxEnabled(stored),
        mailboxAccessMode: getMailboxAccessMode(stored),
      });
      expect(resaved.mailbox).toBe(true);
      expect(resaved.mailboxAccessMode).toBe('read');
    });
  });
});
