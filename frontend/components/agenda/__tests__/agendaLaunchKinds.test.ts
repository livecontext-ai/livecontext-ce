import { describe, expect, it } from 'vitest';
import { TRIGGER_KIND_ORDER } from '@/lib/workflows/triggerNodeIcons';
import {
  AGENDA_KIND_ORDER,
  AGENT_ONLY_KINDS,
  AGENT_ONLY_KIND_ICON,
  isAgentOnlyKind,
  kindNodeIconId,
  occurrenceKind,
} from '../agendaLaunchKinds';

/**
 * The calendar folds two vocabularies into one token space. These tests pin the two
 * halves of that: that nothing from the workflow side was lost, and that every kind
 * resolves to exactly one glyph source.
 */
describe('agenda launch kinds', () => {
  it('keeps every workflow trigger kind, in its established order, before the agent ones', () => {
    // The left-to-right order is learned from the notification bell. Re-sorting it would
    // move chips under the cursor of anyone who already knows where they are.
    expect(AGENDA_KIND_ORDER.slice(0, TRIGGER_KIND_ORDER.length)).toEqual([...TRIGGER_KIND_ORDER]);
    expect(AGENDA_KIND_ORDER.slice(TRIGGER_KIND_ORDER.length)).toEqual([...AGENT_ONLY_KINDS]);
  });

  it('holds no duplicates, so one kind cannot be toggled by two chips', () => {
    expect(new Set(AGENDA_KIND_ORDER).size).toBe(AGENDA_KIND_ORDER.length);
  });

  it('gives every kind exactly one glyph source', () => {
    // Exactly one: a kind with neither draws nothing, and the first version of the chip
    // silently rendered a NodeIcon with an undefined id for the agent-only kinds.
    for (const kind of AGENDA_KIND_ORDER) {
      const hasNodeIcon = Boolean(kindNodeIconId(kind));
      const hasLucide = isAgentOnlyKind(kind);
      expect(hasNodeIcon !== hasLucide).toBe(true);
    }
  });

  it('has a lucide glyph for each agent-only kind', () => {
    for (const kind of AGENT_ONLY_KINDS) {
      expect(AGENT_ONLY_KIND_ICON[kind]).toBeTruthy();
    }
  });

  describe('which vocabulary answers', () => {
    it('reads an agent run through its launch source', () => {
      expect(occurrenceKind({ launchSource: 'SUB_AGENT' })).toBe('SUB_AGENT');
    });

    it('reads a workflow entry through its trigger kind', () => {
      expect(occurrenceKind({ triggerType: 'WEBHOOK' })).toBe('WEBHOOK');
    });

    it('prefers the launch source when both are somehow present', () => {
      // The backend never sets both. If it ever did, the agent's own record of how it
      // started is the more specific answer.
      expect(occurrenceKind({ triggerType: 'CHAT', launchSource: 'SUB_AGENT' })).toBe('SUB_AGENT');
    });

    it('answers undefined rather than a default when neither is known', () => {
      expect(occurrenceKind({})).toBeUndefined();
    });
  });
});
