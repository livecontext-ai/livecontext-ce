/**
 * Message-composer draft persistence (sessionStorage, scoped per conversation).
 *
 * The chat composer saves the in-progress draft so a user who navigates away
 * and comes back within the same tab keeps what they were typing. The "new
 * chat" draft lives under the `:new` slot until a conversation is created.
 *
 * Why a freshness TTL (this module's reason to exist):
 * sessionStorage survives page reloads - and server redeploys - for the whole
 * life of the tab. Without an age guard, a `:new` draft abandoned long ago is
 * restored on a much later visit and can be silently re-sent on Enter, creating
 * a *duplicate conversation*. This is exactly the prod incident of 2026-05-29:
 * the prompt "as tu acces au fichier ?" created one conversation at 15:25, the
 * draft was orphaned in sessionStorage, and a reload at 16:05 restored it and
 * re-sent it at 16:06 → a second, identical conversation. We therefore restore
 * only reasonably fresh drafts and purge stale / legacy entries on read.
 *
 * Mirrors the 5-minute guard already used for the pre-login pending message in
 * `useMessageHandlersV2`.
 */

const KEY_PREFIX = 'messageComposer:draft:';

/**
 * Maximum age of a restorable draft. Older drafts are treated as abandoned and
 * purged on read - long enough to survive "navigate away and come back",
 * short enough that a forgotten draft never resurfaces to be re-sent.
 */
export const DRAFT_MAX_AGE_MS = 30 * 60 * 1000; // 30 minutes

/** sessionStorage key for a conversation's draft. `null`/undefined → new chat. */
export function draftStorageKey(conversationId: string | null | undefined): string {
  return `${KEY_PREFIX}${conversationId ?? 'new'}`;
}

interface StoredDraft {
  /** Draft text. */
  v: string;
  /** Epoch ms of the last write (drives the freshness TTL). */
  t: number;
}

function getStore(): Storage | null {
  try {
    if (typeof window === 'undefined') return null;
    return window.sessionStorage;
  } catch {
    // sessionStorage may be disabled (incognito quota / security policy).
    return null;
  }
}

/**
 * Read a draft, honouring the freshness TTL. Returns `null` - and purges the
 * slot - for missing, stale, legacy (pre-TTL raw string), or corrupt entries,
 * so an unrestorable draft can never resurface.
 */
export function readDraft(
  conversationId: string | null | undefined,
  now: number = Date.now(),
): string | null {
  const store = getStore();
  if (!store) return null;
  const key = draftStorageKey(conversationId);

  let raw: string | null;
  try {
    raw = store.getItem(key);
  } catch {
    return null;
  }
  if (!raw) return null;

  try {
    const parsed = JSON.parse(raw) as Partial<StoredDraft> | null;
    if (
      parsed &&
      typeof parsed.v === 'string' &&
      parsed.v.length > 0 &&
      typeof parsed.t === 'number' &&
      now - parsed.t < DRAFT_MAX_AGE_MS
    ) {
      return parsed.v;
    }
  } catch {
    // Legacy raw-string draft (pre-TTL format) or corrupt JSON - not restorable.
  }

  // Stale / legacy / corrupt → purge so it never resurfaces.
  try {
    store.removeItem(key);
  } catch {
    /* silent */
  }
  return null;
}

/** Persist a draft stamped with the current time, or clear the slot when empty. */
export function writeDraft(
  conversationId: string | null | undefined,
  value: string,
  now: number = Date.now(),
): void {
  const store = getStore();
  if (!store) return;
  const key = draftStorageKey(conversationId);
  try {
    if (value) {
      store.setItem(key, JSON.stringify({ v: value, t: now } satisfies StoredDraft));
    } else {
      store.removeItem(key);
    }
  } catch {
    // Quota exceeded / disabled - silent skip.
  }
}

/**
 * Remove a draft slot. Called on send and - crucially - when a conversation is
 * created from the new-chat composer, so the consumed `:new` draft can never be
 * restored and re-sent on a later visit (defence-in-depth alongside the TTL).
 */
export function clearDraft(conversationId: string | null | undefined): void {
  const store = getStore();
  if (!store) return;
  try {
    store.removeItem(draftStorageKey(conversationId));
  } catch {
    /* silent */
  }
}
