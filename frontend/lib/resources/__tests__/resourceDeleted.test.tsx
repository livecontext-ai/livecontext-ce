// @vitest-environment jsdom
/**
 * The broadcast that tells every surface a resource is gone.
 *
 * Before it, each delete entry point cleaned up only itself: deleting an agent
 * from the list left its side-panel tab open, deleting it from the tab left the
 * row in the list, and the conversation sidebar heard about neither. These are
 * the two primitives every surface now shares, so the rules they encode are
 * pinned here once rather than in each consumer.
 */
import React, { useState } from 'react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, act, screen } from '@testing-library/react';
import {
  RESOURCE_DELETED_EVENT,
  notifyResourceDeleted,
  useResourceDeleted,
  useResourceRowsDeleted,
  type ResourceDeletedDetail,
} from '@/lib/resources/resourceDeleted';

const AGENT = 'a0000000-0000-4000-8000-000000000001';
const OTHER = 'b0000000-0000-4000-8000-000000000002';

beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

/** Let the list's refetch coalescing window close. */
const settle = () => act(() => { vi.advanceTimersByTime(500); });

function Listener({ onDetail }: { onDetail: (d: ResourceDeletedDetail) => void }) {
  useResourceDeleted(onDetail);
  return null;
}

describe('notifyResourceDeleted', () => {
  it('announces the kind and the id on the namespaced window event', () => {
    const seen: ResourceDeletedDetail[] = [];
    render(<Listener onDetail={(d) => seen.push(d)} />);

    act(() => notifyResourceDeleted('agent', AGENT));

    expect(seen).toEqual([{ kind: 'agent', id: AGENT }]);
  });

  it('says nothing when there is no id', () => {
    // A delete that never named a resource cannot correct any surface, and a
    // blank id broadcast to every listener is an invitation to match on it.
    const seen: ResourceDeletedDetail[] = [];
    render(<Listener onDetail={(d) => seen.push(d)} />);

    act(() => notifyResourceDeleted('agent', ''));

    expect(seen).toEqual([]);
  });

  it('is a no-op with no window, so an API service can call it on the server', () => {
    // The delete methods that broadcast live in modules Next also loads server
    // side; a hard reference to `window` there is a crash, not a missed refresh.
    const saved = globalThis.window;
    try {
      delete (globalThis as { window?: unknown }).window;
      // Without this the assertion below passes even if the delete were a no-op:
      // with `window` present the function simply dispatches and throws nothing.
      expect(typeof window).toBe('undefined');
      expect(() => notifyResourceDeleted('agent', AGENT)).not.toThrow();
    } finally {
      (globalThis as { window?: unknown }).window = saved;
    }
  });

  it('stops listening on unmount', () => {
    const onDetail = vi.fn();
    const view = render(<Listener onDetail={onDetail} />);
    view.unmount();

    act(() => notifyResourceDeleted('agent', AGENT));

    expect(onDetail).not.toHaveBeenCalled();
  });

  it('ignores an event carrying no detail', () => {
    const onDetail = vi.fn();
    render(<Listener onDetail={onDetail} />);

    act(() => { window.dispatchEvent(new CustomEvent(RESOURCE_DELETED_EVENT)); });

    expect(onDetail).not.toHaveBeenCalled();
  });
});

interface Row { id: string; name: string }

function RowList({ kind, initial, onRemoved }: {
  kind: 'agent' | 'workflow' | 'datasource';
  initial: Row[];
  onRemoved: () => void;
}) {
  const [rows, setRows] = useState<Row[]>(initial);
  useResourceRowsDeleted(kind, rows, setRows, onRemoved);
  return <div data-testid="rows">{rows.map(r => r.id).join(',')}</div>;
}

describe('useResourceRowsDeleted', () => {
  it('drops the deleted row and refetches, so the total and the page fill come from the server', () => {
    const onRemoved = vi.fn();
    render(<RowList kind="agent" initial={[{ id: AGENT, name: 'A' }, { id: OTHER, name: 'B' }]} onRemoved={onRemoved} />);

    act(() => notifyResourceDeleted('agent', AGENT));

    expect(screen.getByTestId('rows').textContent).toBe(OTHER);
    settle();
    expect(onRemoved).toHaveBeenCalledTimes(1);
  });

  it('ignores another resource kind sharing the id', () => {
    // The event is a broadcast, so an agent list hears workflow deletions too.
    const onRemoved = vi.fn();
    render(<RowList kind="agent" initial={[{ id: AGENT, name: 'A' }]} onRemoved={onRemoved} />);

    act(() => notifyResourceDeleted('workflow', AGENT));

    expect(screen.getByTestId('rows').textContent).toBe(AGENT);
    settle();
    expect(onRemoved).not.toHaveBeenCalled();
  });

  it('costs nothing when the list was not showing that row', () => {
    // The guard reads `rows` as a VALUE. Asking the setter would have meant
    // deciding inside an updater, which React runs on the next render (twice
    // under StrictMode) - too late, and by then `onRemoved` has already fired.
    const onRemoved = vi.fn();
    render(<RowList kind="agent" initial={[{ id: OTHER, name: 'B' }]} onRemoved={onRemoved} />);

    act(() => notifyResourceDeleted('agent', AGENT));

    expect(screen.getByTestId('rows').textContent).toBe(OTHER);
    settle();
    expect(onRemoved).not.toHaveBeenCalled();
  });

  it('is idempotent: a second announcement of the same id changes nothing', () => {
    const onRemoved = vi.fn();
    render(<RowList kind="agent" initial={[{ id: AGENT, name: 'A' }, { id: OTHER, name: 'B' }]} onRemoved={onRemoved} />);

    act(() => notifyResourceDeleted('agent', AGENT));
    act(() => notifyResourceDeleted('agent', AGENT));

    expect(screen.getByTestId('rows').textContent).toBe(OTHER);
    settle();
    expect(onRemoved).toHaveBeenCalledTimes(1);
  });

  it('drops a row whose id the server sent as a NUMBER', () => {
    // `data_sources.id` is BIGSERIAL, so a table row arrives as `id: 42` while the
    // frontend type says `string` and every producer of this event sends '42'.
    // A strict `===` made the whole datasource branch a silent no-op, with the
    // type checker satisfied - which is why DataSourceTable wraps its own ids in
    // String() eighteen times and the other three lists never do.
    const onRemoved = vi.fn();
    const numericRows = [{ id: 42 as unknown as string, name: 'T' }, { id: 43 as unknown as string, name: 'U' }];
    // Under the kind that really has numeric ids: a workflow id is a UUID, so
    // running this under 'workflow' would assert a coercion that kind can never
    // exhibit.
    render(<RowList kind="datasource" initial={numericRows} onRemoved={onRemoved} />);

    act(() => notifyResourceDeleted('datasource', '42'));

    expect(screen.getByTestId('rows').textContent).toBe('43');
    settle();
    expect(onRemoved).toHaveBeenCalledTimes(1);
  });

  it('refetches ONCE for a burst, not once per row', () => {
    // A bulk delete resolves N deletes and fires N broadcasts. `rows` only updates
    // on render, so every handler still sees the pre-delete array: without
    // coalescing, deleting 50 rows meant 50 full page loads.
    const onRemoved = vi.fn();
    render(<RowList kind="agent" initial={[{ id: AGENT, name: 'A' }, { id: OTHER, name: 'B' }]} onRemoved={onRemoved} />);

    act(() => {
      notifyResourceDeleted('agent', AGENT);
      notifyResourceDeleted('agent', OTHER);
    });

    expect(screen.getByTestId('rows').textContent).toBe('');
    settle();
    expect(onRemoved).toHaveBeenCalledTimes(1);
  });

  it('does not refetch after unmount', () => {
    const onRemoved = vi.fn();
    const view = render(<RowList kind="agent" initial={[{ id: AGENT, name: 'A' }]} onRemoved={onRemoved} />);

    act(() => notifyResourceDeleted('agent', AGENT));
    view.unmount();
    settle();

    expect(onRemoved).not.toHaveBeenCalled();
  });
});

describe('notifyResourceDeleted cascade marking', () => {
  it('carries the deletion it was inferred from, when it was inferred', () => {
    // An agent's conversations are deleted server-side by a best-effort cascade, so
    // saying they are gone is weaker than reporting a delete this app performed.
    // The field is how a consumer can tell, and the default stays unmarked.
    const seen: ResourceDeletedDetail[] = [];
    render(<Listener onDetail={(d) => seen.push(d)} />);

    act(() => notifyResourceDeleted('conversation', OTHER, { kind: 'agent', id: AGENT }));
    act(() => notifyResourceDeleted('conversation', OTHER));

    expect(seen[0].cascadedFrom).toEqual({ kind: 'agent', id: AGENT });
    expect(seen[1].cascadedFrom).toBeUndefined();
  });
});
