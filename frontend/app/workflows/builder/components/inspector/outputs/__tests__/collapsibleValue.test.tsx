// @vitest-environment jsdom
/**
 * How a long resolved value is bounded, and when the reader is told there is more.
 *
 * Two rules, and the interesting part is that they are NOT the same rule:
 *
 *  - what is BOUNDED is the box (six lines, `max-h-32`), never the text. Every
 *    character stays in the document, so find-in-page, select-all and copy reach
 *    the whole value - which is how a long value gets out of the panel;
 *  - what DECIDES whether a control is offered is a measurement of that box
 *    (`scrollHeight > clientHeight`), not the character count. The two disagree
 *    in both directions, and both disagreements are user-visible: 430 characters
 *    across a fullscreen column is three lines and hides nothing, while 300
 *    characters holding 25 newlines is 25 lines and hides most of itself.
 *
 * jsdom performs no layout, so the heights are stubbed here: that is precisely
 * the branch these tests exist to cover, and it is unreachable otherwise.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import * as React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light' }),
  useOptionalTheme: () => ({ theme: 'light' }),
}));
vi.mock('@/lib/services/file.service', () => ({
  fileRefToUrl: () => null,
  fileService: { downloadAndSave: vi.fn(), formatFileSize: () => '1 kB' },
}));
vi.mock('@/lib/utils/url-auth', () => ({ openAuthedFileInNewTab: vi.fn() }));

import { PrimitiveValue } from '../JsonDataTree';
import { LONG_STRING_CHARS } from '../runValueUtils';

/**
 * Stub the two heights the component reads. Applied to every element, which is
 * enough: the only reader is the value's own box.
 */
function stubHeights({ client, scroll }: { client: number; scroll: number }) {
  const proto = window.HTMLElement.prototype;
  const clientDesc = Object.getOwnPropertyDescriptor(proto, 'clientHeight');
  const scrollDesc = Object.getOwnPropertyDescriptor(proto, 'scrollHeight');
  Object.defineProperty(proto, 'clientHeight', { configurable: true, get: () => client });
  Object.defineProperty(proto, 'scrollHeight', { configurable: true, get: () => scroll });
  restore = () => {
    if (clientDesc) Object.defineProperty(proto, 'clientHeight', clientDesc);
    else delete (proto as unknown as Record<string, unknown>).clientHeight;
    if (scrollDesc) Object.defineProperty(proto, 'scrollHeight', scrollDesc);
    else delete (proto as unknown as Record<string, unknown>).scrollHeight;
  };
}
let restore: (() => void) | null = null;
afterEach(() => {
  restore?.();
  restore = null;
});

const box = () => screen.getByTestId('run-value-string').parentElement!;
const toggle = () => screen.queryByTestId('run-value-show-more');

describe('the box is what is bounded', () => {
  it('caps the collapsed box at six lines - the cap IS the bound, not the ellipsis', () => {
    // Asserted by name because it is the whole mechanism: keep `overflow-hidden`
    // and drop `max-h-32` and a 20 000-character prompt renders at full height in
    // a 300px panel, which is the failure the old character clamp prevented.
    stubHeights({ client: 128, scroll: 900 });
    render(<PrimitiveValue value={'x'.repeat(LONG_STRING_CHARS + 50)} />);
    expect(box().className).toContain('max-h-32');
    expect(box().className).toContain('overflow-hidden');
  });

  it('keeps every character in the document while collapsed', () => {
    stubHeights({ client: 128, scroll: 900 });
    const long = 'x'.repeat(LONG_STRING_CHARS + 50);
    render(<PrimitiveValue value={long} />);
    expect(screen.getByTestId('run-value-string').textContent).toContain(long);
  });

  it('lifts the cap when the reader expands, and puts it back', () => {
    stubHeights({ client: 128, scroll: 900 });
    render(<PrimitiveValue value={'x'.repeat(LONG_STRING_CHARS + 50)} />);

    fireEvent.click(toggle()!);
    expect(box().className).not.toContain('max-h-32');
    expect(screen.getByTestId('run-value-string').dataset.collapsed).toBe('false');

    fireEvent.click(toggle()!);
    expect(box().className).toContain('max-h-32');
  });
});

describe('the control is offered on what the box MEASURED', () => {
  it('offers nothing when a long-by-characters value fits the box', () => {
    // 430 characters across a fullscreen Params column: three lines, nothing
    // hidden. The character heuristic offered a control here whose click changed
    // nothing on screen.
    stubHeights({ client: 128, scroll: 60 });
    render(<PrimitiveValue value={'x'.repeat(LONG_STRING_CHARS + 30)} />);
    expect(toggle()).toBeNull();
  });

  it('offers it when a short-by-characters value overflows, which newlines do', () => {
    // 300 characters, 25 newlines: 25 lines tall. The character heuristic offered
    // nothing, so the row pushed every sibling out of the column with no way to
    // collapse it.
    stubHeights({ client: 128, scroll: 500 });
    render(<PrimitiveValue value={Array.from({ length: 25 }, (_, i) => `line ${i}`).join('\n')} />);
    expect(toggle()).toBeTruthy();
  });

  it('draws the fade only when something is actually hidden', () => {
    // The box shrinks to its content, so a fade keyed on its own height would
    // fade a value that fits to nothing.
    stubHeights({ client: 128, scroll: 60 });
    render(<PrimitiveValue value={'x'.repeat(LONG_STRING_CHARS + 30)} />);
    expect(box().className).not.toContain('mask-image');
  });

  it('draws the fade when there is more below', () => {
    stubHeights({ client: 128, scroll: 900 });
    render(<PrimitiveValue value={'x'.repeat(LONG_STRING_CHARS + 50)} />);
    expect(box().className).toContain('mask-image');
  });

  it('keeps the way back REACHABLE once expanded, not merely present', () => {
    // Presence is what this used to assert, and presence was true while the control
    // was underneath the hover-revealed copy button and could not be clicked at
    // all. What makes it reachable is that its line STICKS to the bottom of the
    // column while the value is on screen: a 20 000-character prompt lays out about
    // 2 800px tall, and a control that merely exists under its last line is 2 800px
    // of scrolling away. Capping the value instead was the other candidate, and the
    // cap is the trap - the value lives in a column whose height has nothing to do
    // with the viewport a vh unit measures.
    stubHeights({ client: 128, scroll: 900 });
    render(<PrimitiveValue value={'x'.repeat(LONG_STRING_CHARS + 50)} />);
    fireEvent.click(toggle()!);

    const line = screen.getByTestId('run-value-control-line');
    expect(toggle(), 'an expanded value must still be collapsible').toBeTruthy();
    expect(line.className, 'and the way back must follow the reader').toContain('sticky');
    expect(line.className).toContain('bottom-0');
  });

  // Both branches render in the same rows and both can be long, so the sticky rule
  // is asserted on each: a plain string value and one the engine failed to resolve.
  it.each([
    ['a plain string', 'x'.repeat(LONG_STRING_CHARS + 50)],
    ['an unresolved template', `INVALID_TEMPLATE: ${'x'.repeat(LONG_STRING_CHARS + 50)}`],
  ])('does not stick %s while collapsed, where the control is already under the box', (_case, value) => {
    // A 128px box puts its control on screen with it; sticking it there would pin a
    // button over a value for no reason.
    stubHeights({ client: 128, scroll: 900 });
    render(<PrimitiveValue value={value} />);
    expect(screen.getByTestId('run-value-control-line').className).not.toContain('sticky');
  });

  it('stacks the stuck line above the row\'s copy overlay', () => {
    // Load-bearing, not decoration. The copy overlay is a later sibling at
    // `z-index: auto`; with both on auto it paints above the stuck line and its
    // `pointer-events-auto` button takes the click - which is exactly how an
    // expanded value became impossible to collapse with the mouse once before.
    stubHeights({ client: 128, scroll: 900 });
    render(<PrimitiveValue value={'x'.repeat(LONG_STRING_CHARS + 50)} />);
    fireEvent.click(toggle()!);
    expect(screen.getByTestId('run-value-control-line').className).toContain('z-10');
  });

  it('paints the chevron opaque ONLY while it is stuck over the text', () => {
    // At rest the button sits on the panel with nothing behind it, and an opaque
    // chip there is a chip of the wrong colour waiting to be noticed - which is
    // what an unconditional background gave dark mode.
    stubHeights({ client: 128, scroll: 900 });
    render(<PrimitiveValue value={'x'.repeat(LONG_STRING_CHARS + 50)} />);
    expect(toggle()!.className, 'collapsed: nothing behind it').not.toContain('bg-white');

    fireEvent.click(toggle()!);
    // Matched to the surfaces it actually covers: the inspector panel, and the row
    // under the pointer.
    expect(toggle()!.className).toContain('bg-white');
    expect(toggle()!.className).toContain('dark:bg-gray-800');
    expect(toggle()!.className).toContain('group-hover/row:bg-slate-50');
  });

  it('sticks an unresolved value\'s way back too, once it is expanded', () => {
    stubHeights({ client: 128, scroll: 900 });
    render(<PrimitiveValue value={`INVALID_TEMPLATE: ${'x'.repeat(LONG_STRING_CHARS + 50)}`} />);
    fireEvent.click(toggle()!);
    expect(screen.getByTestId('run-value-control-line').className).toContain('sticky');
  });

  it('leaves the expanded value laid out in one piece, not in a nested scroller', () => {
    stubHeights({ client: 128, scroll: 900 });
    render(<PrimitiveValue value={'x'.repeat(LONG_STRING_CHARS + 50)} />);
    fireEvent.click(toggle()!);
    expect(box().className).not.toContain('overflow-auto');
    expect(box().className).not.toContain('max-h-');
  });

  it('falls back to the character count where no layout can be measured', () => {
    // Both heights read 0 in an unattached or headless tree. Concluding "nothing
    // is hidden" from an absence of information would hide the control on every
    // such render; the seed is kept instead.
    stubHeights({ client: 0, scroll: 0 });
    render(<PrimitiveValue value={'x'.repeat(LONG_STRING_CHARS + 50)} />);
    expect(toggle()).toBeTruthy();
  });
});

describe('the control takes no width from the text', () => {
  it('reserves no strip: the control is in flow and overlaps nothing', () => {
    // The floated form needed 56px of padding on every line of the value to stay
    // off it - on a compact Output column the value box is about 100px wide, so
    // that was five characters a line.
    stubHeights({ client: 128, scroll: 900 });
    render(<PrimitiveValue value={'x'.repeat(LONG_STRING_CHARS + 50)} />);
    expect(box().className).not.toContain('pr-14');
    expect(box().className).not.toContain('pr-');
  });

  it('lets the value box take the whole line, so no text sits beside the control', () => {
    // What makes the control a LINE is `basis-full` on its own wrapper, asserted in
    // JsonDataTree.test.tsx; `flex-1` here is the other half - the value box claims
    // the line rather than shrinking to its content.
    stubHeights({ client: 128, scroll: 900 });
    render(<PrimitiveValue value={'x'.repeat(LONG_STRING_CHARS + 50)} />);
    expect(box().className).toContain('flex-1');
  });
});

describe('the box is applied to EVERY collapsed value, not only long ones', () => {
  it('bounds a short value too - that is what makes newlines safe', () => {
    // The bound cannot be gated on the character count: 300 characters holding 25
    // newlines is 25 lines. Gate it and that row renders at full height again,
    // pushing every sibling out of the column.
    stubHeights({ client: 128, scroll: 60 });
    render(<PrimitiveValue value="short" />);
    expect(box().className).toContain('max-h-32');
    expect(box().className).toContain('overflow-hidden');
  });
});

describe('a column resize re-decides whether anything is hidden', () => {
  it('re-measures when the box changes size', () => {
    // The answer depends on the column's width as much as on the value, and the
    // column has a drag handle. Without the observer, widening the column leaves a
    // control behind that is hiding nothing, and narrowing it hides a line with no
    // control to reveal it.
    const observers: Array<(width: number) => void> = [];
    const observed: Element[] = [];
    let disconnects = 0;
    class FakeResizeObserver {
      constructor(private readonly cb: (entries: Array<{ contentRect: { width: number } }>) => void) {
        observers.push((width) => this.cb([{ contentRect: { width } }]));
      }
      observe(el: Element) { observed.push(el); }
      disconnect() { disconnects += 1; }
    }
    const previous = (globalThis as Record<string, unknown>).ResizeObserver;
    (globalThis as Record<string, unknown>).ResizeObserver = FakeResizeObserver;
    try {
      stubHeights({ client: 128, scroll: 900 });
      const view = render(<PrimitiveValue value={'x'.repeat(LONG_STRING_CHARS + 50)} />);
      expect(toggle(), 'seeded and measured as overflowing').toBeTruthy();
      expect(observers.length, 'the box must be observed').toBeGreaterThan(0);
      expect(observed, 'it must observe the VALUE BOX, not some ancestor').toContain(box());

      // The column was widened: the same value now fits.
      restore?.();
      restore = null;
      stubHeights({ client: 128, scroll: 60 });
      act(() => observers.forEach((fire) => fire(400)));

      expect(toggle(), 'a control that hides nothing must go away').toBeNull();

      // Every delivery is answered, including one that carries the same width: a
      // webfont swapping in changes what fits at a width that never moved. Safe to
      // answer because the control lives on its own line, so the callback's own
      // result cannot resize the box it observes.
      restore?.();
      restore = null;
      stubHeights({ client: 128, scroll: 900 });
      act(() => observers.forEach((fire) => fire(400)));
      expect(
        toggle(),
        'a same-width delivery is still a measurement',
      ).toBeTruthy();

      view.unmount();
      expect(disconnects, 'the observer must be released with the row').toBeGreaterThan(0);
    } finally {
      (globalThis as Record<string, unknown>).ResizeObserver = previous;
    }
  });
});

describe('a new value is a new collapsed state', () => {
  it('re-measures rather than carrying the previous item\'s answer', () => {
    // The instance is reused as the reader steps through items. Where no layout
    // can be measured the measurement bails, so without a re-seed the previous
    // value's answer would stand for the new one.
    stubHeights({ client: 0, scroll: 0 });
    const view = render(<PrimitiveValue value={'x'.repeat(LONG_STRING_CHARS + 50)} />);
    expect(toggle(), 'long: seeded as overflowing').toBeTruthy();

    view.rerender(<PrimitiveValue value="short" />);
    expect(toggle(), 'short: the previous answer must not survive').toBeNull();
  });

  it('collapses again, because "expanded" was a decision about the previous value', () => {
    // collapsed=false ALSO skips the measurement, so an expanded row handed the
    // next item's value renders it unbounded - two clicks in the item navigator
    // and a 20 000-character prompt pushes every sibling out of the column.
    stubHeights({ client: 128, scroll: 900 });
    const long = 'x'.repeat(LONG_STRING_CHARS + 50);
    const view = render(<PrimitiveValue value={long} />);
    fireEvent.click(toggle()!);
    expect(box().className).not.toContain('max-h-32');

    view.rerender(<PrimitiveValue value={long + 'y'} />);
    expect(box().className, 'the next value opens bounded').toContain('max-h-32');
  });
});

describe('newlines', () => {
  it('renders them, because a prompt is written in lines', () => {
    stubHeights({ client: 128, scroll: 900 });
    render(<PrimitiveValue value={'first\nsecond'} />);
    expect(screen.getByTestId('run-value-string').className).toContain('whitespace-pre-wrap');
  });
});
