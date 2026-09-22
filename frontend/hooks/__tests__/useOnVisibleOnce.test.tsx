/**
 * @vitest-environment jsdom
 *
 * The latch behind the palette's lazy sections.
 *
 * What it has to get right is not "does it observe" but the two boundaries around it:
 * a section nobody scrolled to must fetch NOTHING, and a section that HAS been revealed
 * must never un-reveal - a latch that flips back would refetch on every scroll pass and
 * unmount the list mid-read.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import { useOnVisibleOnce } from '../useOnVisibleOnce';

type Callback = (entries: { isIntersecting: boolean }[]) => void;

let observers: { callback: Callback; disconnected: boolean; observed: number }[] = [];

class FakeIntersectionObserver {
  private record: { callback: Callback; disconnected: boolean; observed: number };
  constructor(callback: Callback) {
    this.record = { callback, disconnected: false, observed: 0 };
    observers.push(this.record);
  }
  observe() { this.record.observed += 1; }
  unobserve() { /* not used by the hook */ }
  disconnect() { this.record.disconnected = true; }
}

function Probe({ enabled }: { enabled: boolean }) {
  const [ref, seen] = useOnVisibleOnce(enabled);
  return <div ref={ref} data-testid="probe">{seen ? 'seen' : 'unseen'}</div>;
}

const state = () => document.querySelector('[data-testid="probe"]')!.textContent;
const reveal = () => act(() => { observers[observers.length - 1].callback([{ isIntersecting: true }]); });

describe('useOnVisibleOnce', () => {
  beforeEach(() => {
    observers = [];
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('starts unseen, so a section nobody scrolled to fetches nothing', () => {
    render(<Probe enabled />);
    expect(state()).toBe('unseen');
    expect(observers[0].observed, 'it is watching for the reveal').toBe(1);
  });

  it('latches on the first intersection', () => {
    render(<Probe enabled />);
    reveal();
    expect(state()).toBe('seen');
  });

  it('stops observing once it has latched', () => {
    render(<Probe enabled />);
    reveal();
    expect(observers[0].disconnected, 'nothing left to watch for').toBe(true);
  });

  it('stays seen after the element scrolls back out of view', () => {
    render(<Probe enabled />);
    reveal();
    act(() => { observers[0].callback([{ isIntersecting: false }]); });
    expect(state(), 'un-latching would refetch on every scroll pass').toBe('seen');
  });

  it('does not observe at all while disabled', () => {
    render(<Probe enabled={false} />);
    expect(observers).toHaveLength(0);
    expect(state()).toBe('unseen');
  });

  it('reveals immediately where IntersectionObserver does not exist', () => {
    // Older embedded webviews and jsdom. Blank forever would be strictly worse than
    // eager: the section would render its title over nothing, permanently.
    vi.stubGlobal('IntersectionObserver', undefined);
    render(<Probe enabled />);
    expect(state()).toBe('seen');
  });
});
