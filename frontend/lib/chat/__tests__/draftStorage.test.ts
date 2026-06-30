// @vitest-environment jsdom
/**
 * Regression tests for message-composer draft persistence.
 *
 * Pins the fix for the 2026-05-29 prod incident: the prompt
 * "as tu acces au fichier ?" created one conversation at 15:25, its `:new`
 * draft was orphaned in sessionStorage, and a reload 41 min later restored it
 * and re-sent it → a second, byte-identical conversation. The guard is a
 * freshness TTL on read plus an explicit clear when the conversation is
 * created.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import {
  draftStorageKey,
  readDraft,
  writeDraft,
  clearDraft,
  DRAFT_MAX_AGE_MS,
} from '../draftStorage';

const PROMPT = 'as tu acces au fichier ? si oui quel est mon dernier fichier images ?';

beforeEach(() => {
  sessionStorage.clear();
});

describe('draftStorageKey', () => {
  it('scopes the new-chat slot under :new', () => {
    expect(draftStorageKey(null)).toBe('messageComposer:draft:new');
    expect(draftStorageKey(undefined)).toBe('messageComposer:draft:new');
  });

  it('scopes an existing conversation under its id so slots never bleed', () => {
    expect(draftStorageKey('abc-123')).toBe('messageComposer:draft:abc-123');
  });
});

describe('writeDraft / readDraft round-trip', () => {
  it('persists then restores a fresh draft', () => {
    const now = 1_000_000;
    writeDraft(null, PROMPT, now);
    expect(readDraft(null, now + 1000)).toBe(PROMPT);
  });

  it('clears the slot when the draft becomes empty', () => {
    writeDraft(null, PROMPT, 1_000);
    writeDraft(null, '', 2_000);
    expect(sessionStorage.getItem(draftStorageKey(null))).toBeNull();
  });

  it('keeps new-chat and per-conversation drafts independent', () => {
    const now = 5_000;
    writeDraft(null, 'new chat draft', now);
    writeDraft('conv-1', 'existing conv draft', now);
    expect(readDraft(null, now)).toBe('new chat draft');
    expect(readDraft('conv-1', now)).toBe('existing conv draft');
  });
});

describe('freshness TTL - the duplicate-conversation guard', () => {
  it('restores a draft saved just under the TTL', () => {
    const t0 = 1_000_000;
    writeDraft(null, PROMPT, t0);
    expect(readDraft(null, t0 + DRAFT_MAX_AGE_MS - 1)).toBe(PROMPT);
  });

  it('does NOT restore the 41-minute-old orphaned draft (prod incident shape)', () => {
    const sentAt = 1_000_000; // ~15:25
    writeDraft(null, PROMPT, sentAt);
    const reloadAt = sentAt + 41 * 60 * 1000; // ~16:06
    expect(readDraft(null, reloadAt)).toBeNull();
  });

  it('purges the stale slot on read so it can never resurface', () => {
    const t0 = 1_000_000;
    writeDraft(null, PROMPT, t0);
    readDraft(null, t0 + DRAFT_MAX_AGE_MS + 1); // stale read
    expect(sessionStorage.getItem(draftStorageKey(null))).toBeNull();
  });
});

describe('legacy & corrupt entries', () => {
  it('does not restore a legacy raw-string draft (pre-TTL format) and purges it', () => {
    // Older builds stored the bare string, not {v,t}. Such a draft has no
    // timestamp, so its freshness is unknowable → never restore it.
    sessionStorage.setItem(draftStorageKey(null), PROMPT);
    expect(readDraft(null)).toBeNull();
    expect(sessionStorage.getItem(draftStorageKey(null))).toBeNull();
  });

  it('does not restore a corrupt JSON entry and purges it', () => {
    sessionStorage.setItem(draftStorageKey(null), '{not json');
    expect(readDraft(null)).toBeNull();
    expect(sessionStorage.getItem(draftStorageKey(null))).toBeNull();
  });

  it('ignores an entry missing the timestamp', () => {
    sessionStorage.setItem(draftStorageKey(null), JSON.stringify({ v: PROMPT }));
    expect(readDraft(null)).toBeNull();
  });
});

describe('clearDraft - consumed on conversation creation', () => {
  it('removes the :new slot so the consumed draft is never re-sent', () => {
    writeDraft(null, PROMPT, 1_000);
    clearDraft(null);
    expect(readDraft(null, 1_500)).toBeNull();
  });

  it('clearing :new leaves an unrelated conversation draft intact', () => {
    const now = 1_000;
    writeDraft(null, 'new chat draft', now);
    writeDraft('conv-1', 'keep me', now);
    clearDraft(null);
    expect(readDraft('conv-1', now)).toBe('keep me');
  });
});
