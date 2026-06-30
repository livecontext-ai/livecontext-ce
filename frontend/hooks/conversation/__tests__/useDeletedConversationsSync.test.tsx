/**
 * @vitest-environment jsdom
 *
 * Regression tests for {@link useDeletedConversationsSync}.
 *
 * The headline regression target: refreshing a conversation page whose
 * conversation is *not* in the user's first cached page falls into a
 * `loadConversationById → setConversations(prev => [conv, ...prev])` flow
 * that grows the local list one render before the shared context catches up.
 *
 * The previous heuristic (`shared.length < list.length` ⇒ deletion) treated
 * that one-render lag as a deletion, removed the URL conversation from the
 * list and called `clearMessages` - which aborts the in-flight
 * `/messages/page` fetch. The user saw skeleton → blank.
 *
 * These tests exercise the new disappearance-based primitive on the four
 * scenarios called out in the audit:
 *   1. Local list grew first (the actual repro).
 *   2. Genuine external deletion via shared.
 *   3. Length-stable shared mutation (delete A + add B same tick).
 *   4. Initial mount with one side populating before the other.
 */

import { describe, it, expect, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useDeletedConversationsSync } from '../useDeletedConversationsSync';

interface RunArgs {
  shared: { id: string }[];
  list: { id: string }[];
  currentConversationId?: string | null;
}

function setup() {
  const removeFromList = vi.fn<(ids: Set<string>) => void>();
  const onCurrentDeleted = vi.fn<() => void>();

  const { rerender } = renderHook(
    (args: RunArgs) =>
      useDeletedConversationsSync({
        sharedConversations: args.shared,
        listConversations: args.list,
        removeFromList,
        currentConversationId: args.currentConversationId ?? null,
        onCurrentDeleted,
      }),
    {
      initialProps: { shared: [], list: [], currentConversationId: null },
    },
  );

  return { rerender, removeFromList, onCurrentDeleted };
}

describe('useDeletedConversationsSync', () => {
  it('loadConversationById prepend: list grows first, shared lags one render - no deletion is reported', () => {
    // Repro of the user-facing bug. The pre-fix heuristic would have called
    // removeFromList + onCurrentDeleted here, aborting the messages fetch.
    const { rerender, removeFromList, onCurrentDeleted } = setup();

    // 1) Initial conversation list page lands.
    rerender({
      shared: [{ id: 'a' }, { id: 'b' }],
      list: [{ id: 'a' }, { id: 'b' }],
      currentConversationId: null,
    });

    // 2) loadConversationById prepends the URL conversation to local list.
    //    Shared has not synced yet - this is the exact one-render lag window
    //    where the old heuristic mis-fired.
    rerender({
      shared: [{ id: 'a' }, { id: 'b' }],
      list: [{ id: 'X' }, { id: 'a' }, { id: 'b' }],
      currentConversationId: 'X',
    });

    expect(removeFromList).not.toHaveBeenCalled();
    expect(onCurrentDeleted).not.toHaveBeenCalled();

    // 3) Shared catches up next render. Still nothing disappeared.
    rerender({
      shared: [{ id: 'X' }, { id: 'a' }, { id: 'b' }],
      list: [{ id: 'X' }, { id: 'a' }, { id: 'b' }],
      currentConversationId: 'X',
    });

    expect(removeFromList).not.toHaveBeenCalled();
    expect(onCurrentDeleted).not.toHaveBeenCalled();
  });

  it('genuine external deletion: shared loses an ID → list is filtered and onCurrentDeleted fires when applicable', () => {
    const { rerender, removeFromList, onCurrentDeleted } = setup();

    rerender({
      shared: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
      list: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
      currentConversationId: 'b',
    });

    rerender({
      shared: [{ id: 'a' }, { id: 'c' }], // 'b' deleted externally
      list: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
      currentConversationId: 'b',
    });

    expect(removeFromList).toHaveBeenCalledTimes(1);
    const ids = removeFromList.mock.calls[0][0];
    expect(ids.has('b')).toBe(true);
    expect(ids.size).toBe(1);

    expect(onCurrentDeleted).toHaveBeenCalledTimes(1);
  });

  it('genuine external deletion of a NON-current conversation: list is filtered but onCurrentDeleted does NOT fire', () => {
    const { rerender, removeFromList, onCurrentDeleted } = setup();

    rerender({
      shared: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
      list: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
      currentConversationId: 'a',
    });

    rerender({
      shared: [{ id: 'a' }, { id: 'c' }], // 'b' deleted externally
      list: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
      currentConversationId: 'a',
    });

    expect(removeFromList).toHaveBeenCalledTimes(1);
    expect(onCurrentDeleted).not.toHaveBeenCalled();
  });

  it('length-stable shared mutation: delete A + add B in the same render is detected as A disappearing', () => {
    // The reason effect deps are array references rather than `.length`:
    // a swap leaves length unchanged but content changes. The previous
    // length-keyed deps would have skipped this tick entirely, letting the
    // baseline drift and fabricating a phantom disappearance later.
    const { rerender, removeFromList, onCurrentDeleted } = setup();

    rerender({
      shared: [{ id: 'a' }, { id: 'b' }],
      list: [{ id: 'a' }, { id: 'b' }],
      currentConversationId: 'b',
    });

    rerender({
      shared: [{ id: 'b' }, { id: 'c' }], // 'a' replaced by 'c'; length stable
      list: [{ id: 'a' }, { id: 'b' }],
      currentConversationId: 'b',
    });

    expect(removeFromList).toHaveBeenCalledTimes(1);
    const ids = removeFromList.mock.calls[0][0];
    expect(ids.has('a')).toBe(true);
    expect(ids.has('b')).toBe(false);
    expect(onCurrentDeleted).not.toHaveBeenCalled(); // 'b' still there
  });

  it('initial mount with shared populating before list: no false disappearance is reported', () => {
    const { rerender, removeFromList, onCurrentDeleted } = setup();

    // Shared loads first - list is still empty → init defers.
    rerender({
      shared: [{ id: 'a' }, { id: 'b' }],
      list: [],
      currentConversationId: null,
    });

    expect(removeFromList).not.toHaveBeenCalled();

    // Now list lands → init flips to true, baseline seeded.
    rerender({
      shared: [{ id: 'a' }, { id: 'b' }],
      list: [{ id: 'a' }, { id: 'b' }],
      currentConversationId: null,
    });

    expect(removeFromList).not.toHaveBeenCalled();
    expect(onCurrentDeleted).not.toHaveBeenCalled();
  });

  it('initial mount with list populating before shared: no false disappearance is reported', () => {
    const { rerender, removeFromList, onCurrentDeleted } = setup();

    // List lands first - shared is still empty → init defers.
    rerender({
      shared: [],
      list: [{ id: 'a' }, { id: 'b' }],
      currentConversationId: null,
    });

    expect(removeFromList).not.toHaveBeenCalled();

    // Now shared catches up → init flips to true, baseline seeded.
    rerender({
      shared: [{ id: 'a' }, { id: 'b' }],
      list: [{ id: 'a' }, { id: 'b' }],
      currentConversationId: null,
    });

    expect(removeFromList).not.toHaveBeenCalled();
    expect(onCurrentDeleted).not.toHaveBeenCalled();
  });

  it('reorder of shared without disappearance: no deletion reported', () => {
    const { rerender, removeFromList, onCurrentDeleted } = setup();

    rerender({
      shared: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
      list: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
      currentConversationId: 'a',
    });

    rerender({
      shared: [{ id: 'c' }, { id: 'a' }, { id: 'b' }], // reorder only
      list: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
      currentConversationId: 'a',
    });

    expect(removeFromList).not.toHaveBeenCalled();
    expect(onCurrentDeleted).not.toHaveBeenCalled();
  });
});
