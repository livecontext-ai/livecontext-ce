/*
 * No 'use client' directive, deliberately. The emitter is imported by the API
 * services (`agent.service.ts` and friends), which carry no directive of their
 * own; marking this module client-only would make every one of them a client
 * reference through that import. The hooks below are plain functions either way -
 * it is the COMPONENT that calls a hook which has to be a client component, and
 * every consumer here already is.
 */

import { useCallback, useEffect, useRef, type Dispatch, type SetStateAction } from 'react';

/**
 * "This resource no longer exists" - broadcast once, consumed by every surface
 * that is still showing it.
 *
 * A resource can be deleted from many places (a list's bulk bar, a side-panel
 * tab's 3-dot menu, the agent edit modal, a chat card, a canvas node), and each
 * of those used to clean up only ITSELF. So deleting an agent from the list left
 * its side-panel tab open, deleting it from the panel left the row in the list,
 * and neither told the conversation sidebar that the agent's conversations had
 * gone with it. Every new delete entry point reproduced the whole set.
 *
 * The delete METHODS broadcast instead of their callers ({@link
 * notifyResourceDeleted} is called from `orchestratorApi.deleteAgent` and
 * friends), so a call site cannot forget: the fact being announced is "the
 * server accepted the delete", which is exactly what those methods know and
 * nothing else has to be told about.
 *
 * Listeners must be idempotent and tolerate an id they never showed: the event
 * is a broadcast, not a handshake, and it is emitted once per successful delete
 * whatever the surface count.
 */

/**
 * Resources whose disappearance a surface can be showing.
 *
 * Every member has a producer (a delete method that emits it) and at least one
 * consumer. A kind nobody emits reads as coverage that does not exist, so add one
 * only together with the `notifyResourceDeleted` call that sends it.
 *
 * The side panel matches these against `TabResourceKind`; a test asserts every kind
 * here is one the tab grammar can parse, or the panel could never close its tab.
 */
export type DeletedResourceKind =
  | 'agent'
  | 'workflow'
  | 'interface'
  | 'datasource'
  | 'conversation';

export interface ResourceDeletedDetail {
  kind: DeletedResourceKind;
  id: string;
  /**
   * Set when this deletion was INFERRED from another one rather than performed.
   *
   * Deleting an agent deletes its conversations server-side, and the app has to
   * say so or their rows and tabs linger. But that cascade is best-effort on the
   * server, so the statement is weaker than an ordinary event's "the server
   * accepted this delete": it is "this went with its owner, as far as we know".
   * A consumer whose reaction is expensive or irreversible should read this and
   * decide; the ones that exist today (drop a row, close a tab) are both cheap
   * and recoverable, so they ignore it.
   */
  cascadedFrom?: { kind: DeletedResourceKind; id: string };
}

/**
 * Window event name. Namespaced so it cannot collide with the bare-verb events
 * this app already dispatches (`workflowNodeCreated`, `openApplicationMode`, ...).
 */
export const RESOURCE_DELETED_EVENT = 'lc:resource-deleted';

/**
 * Announce that `id` has been deleted server-side.
 *
 * Safe to call from a module that also runs on the server (the API services do):
 * with no `window` it is a no-op rather than a crash, because a delete issued
 * outside a browser has no UI to correct.
 */
export function notifyResourceDeleted(
  kind: DeletedResourceKind,
  id: string,
  cascadedFrom?: ResourceDeletedDetail['cascadedFrom'],
): void {
  if (typeof window === 'undefined') return;
  if (!id) return;
  window.dispatchEvent(
    new CustomEvent<ResourceDeletedDetail>(RESOURCE_DELETED_EVENT, {
      detail: cascadedFrom ? { kind, id, cascadedFrom } : { kind, id },
    }),
  );
}

/**
 * Subscribe to deletions for the lifetime of the calling component.
 *
 * `handler` is read through a ref-free dependency on purpose: pass a `useCallback`
 * or an inline function, and list it in the caller's own deps. Re-subscribing on
 * a changed handler is cheap (one `addEventListener`) and keeps the closure
 * honest, which matters here because the typical handler filters a list by id.
 */
export function useResourceDeleted(handler: (detail: ResourceDeletedDetail) => void): void {
  useEffect(() => {
    const onDeleted = (event: Event) => {
      const detail = (event as CustomEvent<ResourceDeletedDetail>).detail;
      if (!detail || !detail.kind || !detail.id) return;
      handler(detail);
    };
    window.addEventListener(RESOURCE_DELETED_EVENT, onDeleted);
    return () => window.removeEventListener(RESOURCE_DELETED_EVENT, onDeleted);
  }, [handler]);
}

/**
 * How long a list waits for more deletions before refetching.
 *
 * Long enough that a bulk delete lands as one refetch (its requests resolve over
 * several turns of the event loop, so a microtask latch would not catch them),
 * short enough to be invisible: the rows have already gone from the list, and
 * what arrives late is the corrected total.
 */
const REFETCH_COALESCE_MS = 250;

/**
 * The list half of the same rule: drop the deleted row, then refill from the server.
 *
 * Every resource list on this app is a plain `useState` array fed by an imperative
 * fetch, not React Query, so a sibling surface's `invalidateQueries` cannot reach
 * it. Each list therefore has to hear the deletion itself, and they were each
 * healing differently: agents and interfaces filtered without refetching (so the
 * row went but `totalCount` and the page fill stayed wrong), tables refetched
 * without filtering (so the row lingered for a round trip), and none of them
 * reacted at all to a delete performed anywhere else.
 *
 * `rows` is taken as a value, not read back out of the setter, so the hook can
 * answer "were we showing this one?" BEFORE touching state: a broadcast for a
 * resource this list never had must not cost a refetch, and a filter result is
 * not readable from inside a `setState` updater (React runs it during the next
 * render, and twice under StrictMode).
 *
 * @param onRemoved runs once per burst, only when a row actually went, and is
 *        where the list refetches to heal its total and refill the page.
 */
export function useResourceRowsDeleted<T extends { id: string }>(
  kind: DeletedResourceKind,
  rows: T[],
  setRows: Dispatch<SetStateAction<T[]>>,
  onRemoved?: () => void,
): void {
  // Written during render on purpose: the handler must see the CURRENT rows, not
  // the last committed ones, or a deletion arriving between a fetch and its paint
  // is judged against a stale list. Same idiom as the tables' own `reloadRef`.
  /* eslint-disable react-hooks/refs */
  const rowsRef = useRef(rows);
  rowsRef.current = rows;
  const onRemovedRef = useRef(onRemoved);
  onRemovedRef.current = onRemoved;
  /* eslint-enable react-hooks/refs */
  const refetchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => {
    if (refetchTimerRef.current) clearTimeout(refetchTimerRef.current);
  }, []);

  const handler = useCallback((detail: ResourceDeletedDetail) => {
    if (detail.kind !== kind) return;
    // Compare as text. A table's id arrives from the server as a JSON NUMBER
    // (`data_sources.id` is BIGSERIAL) while every producer of this event sends a
    // string, and the frontend type says `string` for both - so `42 === '42'` was
    // false and the whole datasource branch did nothing, silently, with the type
    // checker satisfied. That is also why DataSourceTable wraps its own ids in
    // String() eighteen times and the other three lists never do.
    const target = String(detail.id);
    if (!rowsRef.current.some(row => String(row.id) === target)) return;
    setRows(prev => prev.filter(row => String(row.id) !== target));

    // One refetch per burst, not per row. A bulk delete resolves N deletes and
    // therefore fires N broadcasts; `rowsRef` only updates on render, so every one
    // of them matches the pre-delete array and every one would refetch the whole
    // page. The rows are already gone locally, so nothing is waiting on this: the
    // refetch exists to correct the total and refill the page.
    if (refetchTimerRef.current) clearTimeout(refetchTimerRef.current);
    refetchTimerRef.current = setTimeout(() => {
      refetchTimerRef.current = null;
      onRemovedRef.current?.();
    }, REFETCH_COALESCE_MS);
  }, [kind, setRows]);

  useResourceDeleted(handler);
}
