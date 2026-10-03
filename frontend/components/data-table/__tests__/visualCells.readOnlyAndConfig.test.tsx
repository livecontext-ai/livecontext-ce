/**
 * @vitest-environment jsdom
 *
 * Two defects the visual cells shared, found while fixing the progress bar.
 *
 * 1. READ-ONLY WAS IGNORED. The grid drops every save on a read-only table, but the cells never
 *    read the flag: stars, thumbs, the checkbox, the slider and both dropdowns stayed live, took
 *    the click, and then nothing was written. A control that reacts and saves nothing is worse
 *    than one that is plainly off.
 *
 * 2. THE COLUMN CONFIG WAS TRUSTED. `display.max` and `display.decimals` can be written by an
 *    agent or typed past the input's `max` hint. `Intl` THROWS on a fraction-digit count outside
 *    its range, and a throw in one cell takes the table down; a rating max in the thousands draws
 *    that many buttons.
 */
import React from 'react';
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import { render, cleanup, screen, fireEvent, act } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { renderVisualCellContent } from '../cells';
import { RatingCell, ratingStarCount, RATING_MAX_STARS } from '../cells/RatingCell';
import { NumberCell, numberDecimals, NUMBER_RENDER_MAX_DECIMALS, NUMBER_CONFIG_MAX_DECIMALS } from '../cells/NumberCell';
import { renderPresetPreview } from '../visualHelpers';
import {
  ProgressCell, progressMaxOf, KEYBOARD_SAVE_DELAY_MS, PENDING_SAVE_HOLD_MS,
} from '../cells/ProgressCell';
import { EditColumnModal } from '../modals/EditColumnModal';
import { AddRowForm } from '../AddRowForm';

afterEach(cleanup);

const SELECT_OPTIONS = { options: [{ label: 'Open', value: 'open' }, { label: 'Done', value: 'done' }] };

function renderCell(type: string, value: unknown, readOnly: boolean, displayConfig?: Record<string, unknown>) {
  const onSaveAndExit = vi.fn();
  const onProgressSave = vi.fn();
  const result = renderVisualCellContent({
    value,
    rowKey: 'r1',
    field: 'f',
    type,
    displayConfig: displayConfig as never,
    isEditing: false,
    onSaveAndExit,
    onStartEditing: vi.fn(),
    onExitEditing: vi.fn(),
    readOnly,
    cellKey: 'r1:f',
    onProgressTempChange: vi.fn(),
    onProgressSave,
  });
  const utils = render(<>{result!.content}</>);
  return { ...utils, onSaveAndExit, onProgressSave };
}

describe('a read-only table: no visual cell takes an edit', () => {
  it.each([
    ['rating', 2, undefined],
    ['sentiment', 'up', undefined],
    ['checkbox', false, undefined],
    ['multi_select', '["open"]', SELECT_OPTIONS],
  ])('%s: every button is disabled and a click saves nothing', (type, value, display) => {
    const { container, onSaveAndExit } = renderCell(type, value, true, display);
    const buttons = Array.from(container.querySelectorAll('button'));
    expect(buttons.length).toBeGreaterThan(0);
    buttons.forEach((b) => {
      expect(b.disabled).toBe(true);
      fireEvent.click(b);
    });
    expect(onSaveAndExit).not.toHaveBeenCalled();
  });

  it.each([
    ['rating', 2, undefined],
    ['sentiment', 'up', undefined],
    ['checkbox', false, undefined],
    ['multi_select', '["open"]', SELECT_OPTIONS],
  ])('%s: the disabled cursor is forced past the global not-allowed rule', (type, value, display) => {
    // globals.css sets `button:disabled { cursor: not-allowed }` unlayered; only `!` beats it
    const { container } = renderCell(type, value, true, display);
    container.querySelectorAll('button').forEach((b) => expect(b.className).toContain('disabled:cursor-default!'));
  });

  it('rating and sentiment do not brighten on hover when read-only, and do when editable', () => {
    for (const type of ['rating', 'sentiment']) {
      expect(renderCell(type, 0, true).container.innerHTML).not.toContain('group-hover/cell');
      cleanup();
      expect(renderCell(type, 0, false).container.innerHTML).toContain('group-hover/cell');
      cleanup();
    }
  });

  it('select: a read-only value that is not text is printed, not thrown on', () => {
    expect(renderCell('select', 42, true, SELECT_OPTIONS).container.textContent).toBe('42');
    cleanup();
    expect(() => renderCell('select', { a: 1 }, true, SELECT_OPTIONS)).not.toThrow();
  });

  it('select: shows the value as plain content, with no dropdown to open and nothing faded', () => {
    const { container } = renderCell('select', 'open', true, SELECT_OPTIONS);
    expect(container.querySelector('button')).toBeNull();
    expect(container.textContent).toBe('Open');
    expect(container.innerHTML).not.toContain('opacity-50');
  });

  it('select: a coloured option keeps its badge colour when read-only', () => {
    const { container } = renderCell('select', 'done', true, {
      options: [{ label: 'Done', value: 'done', color: '#22c55e' }],
    });
    const badge = container.querySelector('span') as HTMLElement;
    expect(badge.textContent).toBe('Done');
    expect(badge.style.backgroundColor).toBe('rgb(34, 197, 94)');
  });

  it('select: an empty read-only value is a hyphen', () => {
    expect(renderCell('select', '', true, SELECT_OPTIONS).container.textContent).toBe('-');
  });

  it('multi_select: the hover chevron is not drawn when read-only', () => {
    expect(renderCell('multi_select', '["open"]', true, SELECT_OPTIONS).container.querySelector('svg')).toBeNull();
    cleanup();
    expect(renderCell('multi_select', '["open"]', false, SELECT_OPTIONS).container.querySelector('svg')).not.toBeNull();
  });

  it('progress: there is no slider, only the bar and its caption', () => {
    const { container } = renderCell('progress', 3, true, { max: 10 });
    expect(container.querySelector('input')).toBeNull();
    expect(container.querySelector('[data-progress-tier]')).not.toBeNull();
    expect(screen.getByText('3 / 10')).toBeTruthy();
  });

  it.each([
    ['rating', 2, undefined],
    ['sentiment', 'up', undefined],
    ['checkbox', false, undefined],
    ['multi_select', '["open"]', SELECT_OPTIONS],
    ['select', 'open', SELECT_OPTIONS],
  ])('%s: stays live on an editable table', (type, value, display) => {
    const { container } = renderCell(type, value, false, display);
    const buttons = container.querySelectorAll('button');
    expect(buttons.length).toBeGreaterThan(0);
    buttons.forEach((b) => expect(b.disabled).toBe(false));
  });

  it('progress: the slider is there on an editable table', () => {
    expect(renderCell('progress', 3, false, { max: 10 }).container.querySelector('input[type="range"]')).not.toBeNull();
  });

  it('an editable rating, sentiment and checkbox still save on click', () => {
    const rating = renderCell('rating', 2, false);
    fireEvent.click(rating.container.querySelectorAll('button')[3]);
    expect(rating.onSaveAndExit).toHaveBeenCalledWith(4);
    cleanup();
    const sentiment = renderCell('sentiment', 'neutral', false);
    fireEvent.click(sentiment.container.querySelectorAll('button')[1]);
    expect(sentiment.onSaveAndExit).toHaveBeenCalledWith('down');
    cleanup();
    const checkbox = renderCell('checkbox', false, false);
    fireEvent.click(checkbox.container.querySelector('button')!);
    expect(checkbox.onSaveAndExit).toHaveBeenCalledWith(true);
  });
});

describe('multi_select: its two placeholders go through the translations', () => {
  // the test translator returns the KEY, so an English literal left in the cell would show as text
  it('no options and no tags shows the translated empty label', () => {
    expect(renderCell('multi_select', '', false, {}).container.textContent).toBe('noTags');
  });

  it('options but nothing selected shows the translated placeholder', () => {
    expect(renderCell('multi_select', '[]', false, SELECT_OPTIONS).container.textContent).toBe('selectPlaceholder');
  });
});

describe('rating: the star count survives any configured max', () => {
  it.each<[unknown, number]>([
    [undefined, 5],
    [null, 5],
    ['abc', 5],
    [0, 5],
    [-3, 5],
    [NaN, 5],
    [Infinity, 5],
    [3, 3],
    ['7', 7],
    [4.9, 4],
    [RATING_MAX_STARS, RATING_MAX_STARS],
    [5000, RATING_MAX_STARS],
  ])('max %s draws %i stars', (configured, expected) => {
    expect(ratingStarCount(configured)).toBe(expected);
  });

  it('a max in the thousands draws the cap, not thousands of buttons', () => {
    const { container } = render(
      <RatingCell value={3} rowKey="r" field="f" displayConfig={{ max: 5000 } as never} isEditing={false}
        onSaveAndExit={vi.fn()} onStartEditing={vi.fn()} onExitEditing={vi.fn()} />,
    );
    expect(container.querySelectorAll('button')).toHaveLength(RATING_MAX_STARS);
  });
});

describe('rating: the column-type preview draws the same star count as the cell', () => {
  it.each<[unknown, number]>([[5000, RATING_MAX_STARS], [3, 3], [undefined, 5], [Infinity, 5]])(
    'a preset with max %s previews %i stars',
    (max, expected) => {
      const { container } = render(<>{renderPresetPreview({ id: 'rating', visualType: 'rating', display: { max } } as never)}</>);
      expect(container.querySelectorAll('svg')).toHaveLength(expected);
    },
  );

  it('opening the edit modal on a rating with an enormous max does not throw or draw it', () => {
    expect(() => render(
      <EditColumnModal isOpen isSaving={false} onClose={() => {}} onSave={vi.fn()}
        column={{ field: 'data.c', header_name: 'col', type: 'rating', displayConfig: { max: 1e12 } } as never} />,
    )).not.toThrow();
    expect(document.querySelectorAll('svg').length).toBeLessThan(60);
  });
});

describe('number: a decimals setting outside what Intl accepts does not take the table down', () => {
  it.each<[unknown, number]>([
    [undefined, 0],
    [null, 0],
    ['abc', 0],
    [NaN, 0],
    [-1, 0],
    [2, 2],
    ['3', 3],
    [2.9, 2],
    [8, 8],
    [NUMBER_RENDER_MAX_DECIMALS, NUMBER_RENDER_MAX_DECIMALS],
    [200, NUMBER_RENDER_MAX_DECIMALS],
    [Infinity, 0],
  ])('decimals %s is rendered as %i', (configured, expected) => {
    expect(numberDecimals(configured)).toBe(expected);
  });

  it.each<[unknown, number]>([[8, NUMBER_CONFIG_MAX_DECIMALS], [200, NUMBER_CONFIG_MAX_DECIMALS], [4, 4], [-2, 0], ['', 0]])(
    'a config input holding %s is stored as %i',
    (typed, expected) => {
      expect(numberDecimals(typed, NUMBER_CONFIG_MAX_DECIMALS)).toBe(expected);
    },
  );

  const numberProps = {
    value: 12.5, rowKey: 'r', field: 'f', isEditing: false,
    onSaveAndExit: vi.fn(), onStartEditing: vi.fn(), onExitEditing: vi.fn(),
  };

  it.each<[unknown, string]>([
    [200, '12.' + '5'.padEnd(NUMBER_RENDER_MAX_DECIMALS, '0')],
    [-1, '13'],
    [NaN, '13'],
    ['abc', '13'],
    [8, '12.50000000'],
  ])('decimals=%s renders %s instead of throwing', (decimals, expected) => {
    expect(() => render(<NumberCell {...numberProps} displayConfig={{ decimals } as never} />)).not.toThrow();
    expect(screen.getByText(expected)).toBeTruthy();
  });

  it('the raw setting really does throw in Intl, which is what the guard is for', () => {
    expect(() => (12.5).toLocaleString('en', { minimumFractionDigits: 200, maximumFractionDigits: 200 })).toThrow(RangeError);
  });
});

describe('select: the empty-value placeholder is a hyphen', () => {
  it('a legacy palette badge with no value shows "-", never a long dash', () => {
    const { container } = renderCell('select', '', false, { palette: { open: '#f00' } });
    expect(container.textContent).toBe('-');
    expect(container.textContent).not.toMatch(/[\u2013\u2014]/);
  });
});

describe('progress: the slider itself', () => {
  const base = {
    rowKey: 'r', field: 'f', isEditing: false, cellKey: 'r:f',
    onSaveAndExit: vi.fn(), onStartEditing: vi.fn(), onExitEditing: vi.fn(),
  };
  const slider = (c: HTMLElement) => c.querySelector('input[type="range"]') as HTMLInputElement;
  const cell = (value: unknown, onProgressSave = vi.fn(), onTempChange: (k: string, v: number) => void = () => {}, max: unknown = 10) => (
    <ProgressCell {...base} value={value} displayConfig={{ max } as never} onTempChange={onTempChange} onProgressSave={onProgressSave} />
  );

  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it('moves on its own when the host keeps no drag state', () => {
    const { container } = render(cell(2));
    fireEvent.change(slider(container), { target: { value: '7' } });
    expect(slider(container).value).toBe('7');
    expect(screen.getByText('7 / 10')).toBeTruthy();
  });

  it('saves once on pointer-up, even if pointer-up fires twice', () => {
    const save = vi.fn();
    const { container } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    fireEvent.pointerUp(slider(container));
    expect(save).toHaveBeenCalledTimes(1);
    expect(save).toHaveBeenCalledWith(7);
  });

  it('a pointer-up that moved nothing writes nothing', () => {
    const save = vi.fn();
    const { container } = render(cell(2, save));
    fireEvent.pointerUp(slider(container));
    expect(save).not.toHaveBeenCalled();
  });

  it('holds the saved value while the stored one has not caught up, then follows the stored one', () => {
    const save = vi.fn();
    const { container, rerender } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    // the request is in flight: the prop is still 2, the slider must not snap back
    expect(slider(container).value).toBe('7');
    rerender(cell(4, save));
    expect(screen.getByText('4 / 10')).toBeTruthy();
    expect(slider(container).value).toBe('4');
  });

  it('a save that never lands gives the stored value back after the hold', () => {
    const { container } = render(cell(2));
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    act(() => { vi.advanceTimersByTime(PENDING_SAVE_HOLD_MS - 1); });
    expect(slider(container).value).toBe('7');
    act(() => { vi.advanceTimersByTime(1); });
    expect(slider(container).value).toBe('2');
    expect(screen.getByText('2 / 10')).toBeTruthy();
  });

  it('saving the same value again restarts the hold', () => {
    const { container } = render(cell(2));
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    act(() => { vi.advanceTimersByTime(PENDING_SAVE_HOLD_MS - 100); });
    // away and back, so the slider really reports a move that ends on the same value
    fireEvent.change(slider(container), { target: { value: '8' } });
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    act(() => { vi.advanceTimersByTime(PENDING_SAVE_HOLD_MS - 100); });
    expect(slider(container).value).toBe('7');
    act(() => { vi.advanceTimersByTime(100); });
    expect(slider(container).value).toBe('2');
  });

  it('an unsaved drag is NOT dropped by the hold timer', () => {
    const { container } = render(cell(2));
    fireEvent.change(slider(container), { target: { value: '7' } });
    act(() => { vi.advanceTimersByTime(PENDING_SAVE_HOLD_MS * 2); });
    expect(slider(container).value).toBe('7');
  });

  it.each(['ArrowRight', 'ArrowLeft', 'Home', 'End', 'PageUp', 'PageDown', 'ArrowUp', 'ArrowDown'])(
    'a keyboard move with %s is saved once the user pauses',
    (key) => {
      const save = vi.fn();
      const { container } = render(cell(2, save));
      fireEvent.change(slider(container), { target: { value: '3' } });
      fireEvent.keyUp(slider(container), { key });
      act(() => { vi.advanceTimersByTime(KEYBOARD_SAVE_DELAY_MS - 1); });
      expect(save).not.toHaveBeenCalled();
      act(() => { vi.advanceTimersByTime(1); });
      expect(save).toHaveBeenCalledTimes(1);
      expect(save).toHaveBeenCalledWith(3);
    },
  );

  it('several quick taps are ONE save, of the last value', () => {
    const save = vi.fn();
    const { container } = render(cell(2, save));
    for (const v of ['3', '4', '5']) {
      fireEvent.change(slider(container), { target: { value: v } });
      fireEvent.keyUp(slider(container), { key: 'ArrowRight' });
      act(() => { vi.advanceTimersByTime(50); });
    }
    expect(save).not.toHaveBeenCalled();
    act(() => { vi.advanceTimersByTime(KEYBOARD_SAVE_DELAY_MS); });
    expect(save).toHaveBeenCalledTimes(1);
    expect(save).toHaveBeenCalledWith(5);
  });

  it('a tap made while the previous save is in flight starts from the saved value and is saved too', () => {
    const save = vi.fn();
    const { container } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '3' } });
    fireEvent.keyUp(slider(container), { key: 'ArrowRight' });
    act(() => { vi.advanceTimersByTime(KEYBOARD_SAVE_DELAY_MS); });
    // stored value still 2: the slider must sit on 3 so the next step lands on 4, not on 3 again
    expect(slider(container).value).toBe('3');
    fireEvent.change(slider(container), { target: { value: '4' } });
    fireEvent.keyUp(slider(container), { key: 'ArrowRight' });
    act(() => { vi.advanceTimersByTime(KEYBOARD_SAVE_DELAY_MS); });
    expect(save.mock.calls).toEqual([[3], [4]]);
  });

  it('leaving the slider saves a pending keyboard move at once', () => {
    const save = vi.fn();
    const { container } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '3' } });
    fireEvent.keyUp(slider(container), { key: 'ArrowRight' });
    fireEvent.blur(slider(container));
    expect(save).toHaveBeenCalledWith(3);
    act(() => { vi.advanceTimersByTime(KEYBOARD_SAVE_DELAY_MS * 2); });
    expect(save).toHaveBeenCalledTimes(1);
  });

  it('a move with no key-up and no pointer-up is saved when focus leaves', () => {
    const save = vi.fn();
    const { container } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '6' } });
    fireEvent.blur(slider(container));
    expect(save).toHaveBeenCalledTimes(1);
    expect(save).toHaveBeenCalledWith(6);
  });

  it('a touch drag the browser cancels is saved', () => {
    const save = vi.fn();
    const { container } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '6' } });
    fireEvent.pointerCancel(slider(container));
    expect(save).toHaveBeenCalledWith(6);
  });

  it('moving back to the stored value WHILE another save is in flight is written', () => {
    // the in-flight save is about to store 7: going back to 2 is a real change, not a no-op
    const save = vi.fn();
    const { container } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    fireEvent.change(slider(container), { target: { value: '2' } });
    fireEvent.pointerUp(slider(container));
    expect(save.mock.calls).toEqual([[7], [2]]);
  });

  it('keyboard: a tap made while a save is in flight is still saved after that save lands', () => {
    // tap to 3, saved; tap to 4; the first save lands (stored value becomes 3); the pause ends.
    // The second move must not be orphaned by the stored value changing under it.
    const save = vi.fn();
    const { container, rerender } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '3' } });
    fireEvent.keyUp(slider(container), { key: 'ArrowRight' });
    act(() => { vi.advanceTimersByTime(KEYBOARD_SAVE_DELAY_MS); });
    fireEvent.change(slider(container), { target: { value: '4' } });
    fireEvent.keyUp(slider(container), { key: 'ArrowRight' });
    rerender(cell(3, save));
    expect(slider(container).value).toBe('4');
    act(() => { vi.advanceTimersByTime(KEYBOARD_SAVE_DELAY_MS); });
    expect(save.mock.calls).toEqual([[3], [4]]);
  });

  it('pointer: a second drag whose first save lands mid-gesture is still saved on release', () => {
    const save = vi.fn();
    const { container, rerender } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    fireEvent.change(slider(container), { target: { value: '5' } });
    rerender(cell(7, save));
    expect(slider(container).value).toBe('5');
    fireEvent.pointerUp(slider(container));
    expect(save.mock.calls).toEqual([[7], [5]]);
  });

  it('keyboard: two saves overlapping, the first landing AFTER the second was sent, does not pull the slider back', () => {
    // stored 2; tap to 3 (sent); tap to 4 (sent); the first save lands: the user is at 4, not 3
    const save = vi.fn();
    const { container, rerender } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '3' } });
    fireEvent.keyUp(slider(container), { key: 'ArrowRight' });
    act(() => { vi.advanceTimersByTime(KEYBOARD_SAVE_DELAY_MS); });
    fireEvent.change(slider(container), { target: { value: '4' } });
    fireEvent.keyUp(slider(container), { key: 'ArrowRight' });
    act(() => { vi.advanceTimersByTime(KEYBOARD_SAVE_DELAY_MS); });
    expect(save.mock.calls).toEqual([[3], [4]]);
    rerender(cell(3, save));
    expect(slider(container).value).toBe('4');
    rerender(cell(4, save));
    expect(slider(container).value).toBe('4');
  });

  it('pointer: two drags overlapping, the first save landing after the second release, stays on the second', () => {
    const save = vi.fn();
    const { container, rerender } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    fireEvent.change(slider(container), { target: { value: '5' } });
    fireEvent.pointerUp(slider(container));
    rerender(cell(7, save));
    expect(slider(container).value).toBe('5');
    expect(screen.getByText('5 / 10')).toBeTruthy();
  });

  it('a stored value that is none of our saves wins at once over the held one', () => {
    // the server stored something else than what was sent (or another writer got there first)
    const save = vi.fn();
    const { container, rerender } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    rerender(cell(9, save));
    expect(slider(container).value).toBe('9');
  });

  it('once the stored value has caught up, the hold is gone for good', () => {
    // stored 2, save 7, it lands; the value then changes by another route and comes back to 2:
    // the 7 must not reappear
    const save = vi.fn();
    const { container, rerender } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    rerender(cell(7, save));
    rerender(cell(5, save));
    expect(slider(container).value).toBe('5');
    rerender(cell(2, save));
    expect(slider(container).value).toBe('2');
  });

  it('a table that turns read-only under a pending move forgets it: no stale caption, no save', () => {
    const save = vi.fn();
    const editable = (ro: boolean) => (
      <ProgressCell {...base} value={2} readOnly={ro} displayConfig={{ max: 10 } as never} onTempChange={() => {}} onProgressSave={save} />
    );
    const { container, rerender, unmount } = render(editable(false));
    fireEvent.change(slider(container), { target: { value: '6' } });
    rerender(editable(true));
    expect(slider(container)).toBeNull();
    expect(screen.getByText('2 / 10')).toBeTruthy();
    act(() => { vi.advanceTimersByTime(KEYBOARD_SAVE_DELAY_MS * 2); });
    unmount();
    expect(save).not.toHaveBeenCalled();
  });

  it('a save the host reports as REFUSED gives the stored value back at once', async () => {
    // the error toast is on screen: the slider must not keep showing the value that was rejected
    const save = vi.fn().mockResolvedValue(false);
    const { container } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    expect(slider(container).value).toBe('7');
    await act(async () => { await Promise.resolve(); });
    expect(slider(container).value).toBe('2');
    expect(screen.getByText('2 / 10')).toBeTruthy();
  });

  it.each<[string, unknown]>([
    ['resolves true', Promise.resolve(true)],
    ['resolves nothing', Promise.resolve(undefined)],
    ['returns nothing at all', undefined],
  ])('a save that %s keeps the value held', async (_name, outcome) => {
    const save = vi.fn().mockReturnValue(outcome);
    const { container } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    await act(async () => { await Promise.resolve(); });
    expect(slider(container).value).toBe('7');
  });

  it('a save whose promise rejects does not throw, and the hold runs to its timeout', async () => {
    const save = vi.fn().mockReturnValue(Promise.reject(new Error('x')));
    const { container } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    await act(async () => { await Promise.resolve(); });
    expect(slider(container).value).toBe('7');
    act(() => { vi.advanceTimersByTime(PENDING_SAVE_HOLD_MS); });
    expect(slider(container).value).toBe('2');
  });

  it('an earlier save refused late does not take the hold from a later save', async () => {
    let refuseFirst: (v: boolean) => void = () => {};
    const save = vi.fn()
      .mockReturnValueOnce(new Promise<boolean>((resolve) => { refuseFirst = resolve; }))
      .mockReturnValueOnce(new Promise<boolean>(() => {}));
    const { container } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    fireEvent.change(slider(container), { target: { value: '5' } });
    fireEvent.pointerUp(slider(container));
    await act(async () => { refuseFirst(false); await Promise.resolve(); });
    expect(slider(container).value).toBe('5');
  });

  it('the same value saved twice: the first one refused does not release the hold of the second', async () => {
    let refuseFirst: (v: boolean) => void = () => {};
    let refuseSecond: (v: boolean) => void = () => {};
    const save = vi.fn()
      .mockReturnValueOnce(new Promise<boolean>((resolve) => { refuseFirst = resolve; }))
      .mockReturnValueOnce(new Promise<boolean>((resolve) => { refuseSecond = resolve; }));
    const { container } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    fireEvent.change(slider(container), { target: { value: '8' } });
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    expect(save.mock.calls).toEqual([[7], [7]]);
    await act(async () => { refuseFirst(false); await Promise.resolve(); });
    // the second save of 7 is still in flight and owns the hold
    expect(slider(container).value).toBe('7');
    await act(async () => { refuseSecond(false); await Promise.resolve(); });
    expect(slider(container).value).toBe('2');
  });

  /** A save whose outcome the test decides later. */
  function deferredSaves(count: number) {
    const settle: Array<(v: boolean) => void> = [];
    const save = vi.fn();
    for (let i = 0; i < count; i++) {
      save.mockReturnValueOnce(new Promise<boolean>((resolve) => { settle[i] = resolve; }));
    }
    return { save, settle };
  }
  const dragTo = (container: HTMLElement, v: string) => {
    fireEvent.change(slider(container), { target: { value: v } });
    fireEvent.pointerUp(slider(container));
  };

  it('A stored, then B refused: the slider lands on A, which is what the row holds', async () => {
    const { save, settle } = deferredSaves(2);
    const { container, rerender } = render(cell(2, save));
    dragTo(container, '7');
    dragTo(container, '5');
    rerender(cell(7, save));
    expect(slider(container).value).toBe('5');
    await act(async () => { settle[1](false); await Promise.resolve(); });
    expect(slider(container).value).toBe('7');
    expect(screen.getByText('7 / 10')).toBeTruthy();
  });

  it('B refused while A is still in flight: the slider goes to A, not down to the stored value', async () => {
    const { save, settle } = deferredSaves(2);
    const { container, rerender } = render(cell(2, save));
    dragTo(container, '7');
    dragTo(container, '5');
    await act(async () => { settle[1](false); await Promise.resolve(); });
    expect(slider(container).value).toBe('7');
    // A lands: nothing left to hold
    rerender(cell(7, save));
    expect(slider(container).value).toBe('7');
    rerender(cell(3, save));
    expect(slider(container).value).toBe('3');
  });

  it('A refused while B is in flight, then another writer stores A\'s value: the stored value wins at once', async () => {
    // a refused save must be forgotten: kept as "one of ours still to land", the 7 written by
    // someone else would be mistaken for it and B would stay drawn over it
    const { save, settle } = deferredSaves(2);
    const { container, rerender } = render(cell(2, save));
    dragTo(container, '7');
    dragTo(container, '5');
    await act(async () => { settle[0](false); await Promise.resolve(); });
    expect(slider(container).value).toBe('5');
    rerender(cell(7, save));
    expect(slider(container).value).toBe('7');
  });

  it('three saves, the first one refused: the latest stays held and the refused value is forgotten', async () => {
    const { save, settle } = deferredSaves(3);
    const { container, rerender } = render(cell(2, save));
    dragTo(container, '7');
    dragTo(container, '5');
    dragTo(container, '9');
    await act(async () => { settle[0](false); await Promise.resolve(); });
    expect(slider(container).value).toBe('9');
    // 7 is no longer one of ours: stored by another writer, it wins at once
    rerender(cell(7, save));
    expect(slider(container).value).toBe('7');
  });

  it('B refused, then A refused too: nothing rejected is left on screen', async () => {
    const { save, settle } = deferredSaves(2);
    const { container } = render(cell(2, save));
    dragTo(container, '7');
    dragTo(container, '5');
    await act(async () => { settle[1](false); await Promise.resolve(); });
    expect(slider(container).value).toBe('7');
    await act(async () => { settle[0](false); await Promise.resolve(); });
    expect(slider(container).value).toBe('2');
    expect(screen.getByText('2 / 10')).toBeTruthy();
  });

  it('three saves, the last one refused and carrying the same value as the first: falls back to the middle one', async () => {
    // removed by its own token: matched by value, the FIRST 7 would be dropped and the refused one kept
    const { save, settle } = deferredSaves(3);
    const { container } = render(cell(2, save));
    dragTo(container, '7');
    dragTo(container, '5');
    dragTo(container, '7');
    expect(save.mock.calls).toEqual([[7], [5], [7]]);
    await act(async () => { settle[2](false); await Promise.resolve(); });
    expect(slider(container).value).toBe('5');
  });

  it('three saves, the middle one refused: the latest stays held, and the first landing does not disturb it', async () => {
    const { save, settle } = deferredSaves(3);
    const { container, rerender } = render(cell(2, save));
    dragTo(container, '7');
    dragTo(container, '5');
    dragTo(container, '9');
    await act(async () => { settle[1](false); await Promise.resolve(); });
    expect(slider(container).value).toBe('9');
    rerender(cell(7, save));
    expect(slider(container).value).toBe('9');
    // the refused 5 is no longer "one of ours": stored by another writer, it wins at once
    rerender(cell(5, save));
    expect(slider(container).value).toBe('5');
  });

  it('a refusal after another writer already changed the stored value changes nothing', async () => {
    const { save, settle } = deferredSaves(1);
    const { container, rerender } = render(cell(2, save));
    dragTo(container, '7');
    rerender(cell(9, save));
    expect(slider(container).value).toBe('9');
    await act(async () => { settle[0](false); await Promise.resolve(); });
    expect(slider(container).value).toBe('9');
  });

  it('a refusal after the hold has already timed out changes nothing', async () => {
    const { save, settle } = deferredSaves(2);
    const { container } = render(cell(2, save));
    dragTo(container, '7');
    act(() => { vi.advanceTimersByTime(PENDING_SAVE_HOLD_MS); });
    expect(slider(container).value).toBe('2');
    dragTo(container, '6');
    await act(async () => { settle[0](false); await Promise.resolve(); });
    // the late refusal of the first save must not touch the hold of the second
    expect(slider(container).value).toBe('6');
  });

  it('a refusal that arrives after the cell is gone raises nothing', async () => {
    const { save, settle } = deferredSaves(1);
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { container, unmount } = render(cell(2, save));
    dragTo(container, '7');
    unmount();
    await act(async () => { settle[0](false); await Promise.resolve(); });
    expect(errors).not.toHaveBeenCalled();
    errors.mockRestore();
  });

  it('a refused save works the same under StrictMode', async () => {
    const save = vi.fn().mockResolvedValue(false);
    const { container } = render(<React.StrictMode>{cell(2, save)}</React.StrictMode>);
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    expect(save).toHaveBeenCalledTimes(1);
    await act(async () => { await Promise.resolve(); });
    expect(slider(container).value).toBe('2');
  });

  it('a save that never landed can be retried with the same value', () => {
    const save = vi.fn();
    const { container } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    act(() => { vi.advanceTimersByTime(PENDING_SAVE_HOLD_MS); });
    expect(slider(container).value).toBe('2');
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    expect(save.mock.calls).toEqual([[7], [7]]);
  });

  it('every move reported to the host ends in a save, so the host can always clear its copy', () => {
    // a move that comes back to where it started is still handed over: the host clears its temp
    // value on save, and a temp value never cleared is drawn over the stored one later
    const save = vi.fn();
    const onTempChange = vi.fn();
    const { container } = render(cell(2, save, onTempChange));
    fireEvent.change(slider(container), { target: { value: '6' } });
    fireEvent.change(slider(container), { target: { value: '2' } });
    fireEvent.pointerUp(slider(container));
    expect(onTempChange).toHaveBeenCalledTimes(2);
    expect(save.mock.calls).toEqual([[2]]);
  });

  it('a max lowered while a value is held clamps what is shown', () => {
    const save = vi.fn();
    const { container, rerender } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '9' } });
    fireEvent.pointerUp(slider(container));
    rerender(cell(2, save, () => {}, 5));
    expect(slider(container).value).toBe('5');
    expect(screen.getByText('5 / 5')).toBeTruthy();
  });

  it('a blur with nothing pending saves nothing', () => {
    const save = vi.fn();
    const { container } = render(cell(2, save));
    fireEvent.blur(slider(container));
    expect(save).not.toHaveBeenCalled();
  });

  it('an arrow at the end of the scale, which moves nothing, writes nothing', () => {
    const save = vi.fn();
    const { container } = render(cell(10, save));
    fireEvent.keyUp(slider(container), { key: 'ArrowRight' });
    fireEvent.keyUp(slider(container), { key: 'End' });
    act(() => { vi.advanceTimersByTime(KEYBOARD_SAVE_DELAY_MS * 2); });
    expect(save).not.toHaveBeenCalled();
  });

  it('a key that does not move the slider saves nothing', () => {
    const save = vi.fn();
    const { container } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '3' } });
    fireEvent.keyUp(slider(container), { key: 'Tab' });
    fireEvent.keyUp(slider(container), { key: 'a' });
    act(() => { vi.advanceTimersByTime(KEYBOARD_SAVE_DELAY_MS * 2); });
    expect(save).not.toHaveBeenCalled();
  });

  it('a cell that goes away with a keyboard move pending saves it, once', () => {
    // dropping it would lose the edit and leave its value in the grid's temp map
    const save = vi.fn();
    const { container, unmount } = render(cell(2, save));
    fireEvent.change(slider(container), { target: { value: '3' } });
    fireEvent.keyUp(slider(container), { key: 'ArrowRight' });
    unmount();
    expect(save).toHaveBeenCalledTimes(1);
    expect(save).toHaveBeenCalledWith(3);
    act(() => { vi.advanceTimersByTime(KEYBOARD_SAVE_DELAY_MS * 2); });
    expect(save).toHaveBeenCalledTimes(1);
  });

  it('a cell that goes away with nothing pending, or with its move already saved, saves nothing more', () => {
    const save = vi.fn();
    render(cell(2, save)).unmount();
    expect(save).not.toHaveBeenCalled();
    const second = render(cell(2, save));
    fireEvent.change(slider(second.container), { target: { value: '5' } });
    fireEvent.pointerUp(slider(second.container));
    second.unmount();
    expect(save).toHaveBeenCalledTimes(1);
  });

  it('still reports each move to a host that does keep drag state (the grid)', () => {
    const onTempChange = vi.fn();
    const { container } = render(cell(2, vi.fn(), onTempChange));
    fireEvent.change(slider(container), { target: { value: '6' } });
    expect(onTempChange).toHaveBeenCalledWith('r:f', 6);
  });

  it('a decimal max is read as the server reads it: truncated, so the bar can be filled', () => {
    const { container } = render(cell(2, vi.fn(), () => {}, 2.5));
    expect(slider(container).max).toBe('2');
    expect(screen.getByText('2 / 2')).toBeTruthy();
  });

  it('the thumb is revealed by a selector browsers accept: group-hover before the pseudo-element', () => {
    const cls = slider(render(cell(2)).container).className;
    expect(cls).toContain('group-hover/cell:[&::-webkit-slider-thumb]:opacity-100');
    expect(cls).toContain('group-hover/cell:[&::-moz-range-thumb]:opacity-100');
    expect(cls).toContain('focus-visible:[&::-webkit-slider-thumb]:opacity-100');
    // a device that cannot hover would otherwise never show the thumb at all
    expect(cls).toContain('[@media(hover:none)]:[&::-webkit-slider-thumb]:opacity-100');
    expect(cls).toContain('[@media(hover:none)]:[&::-moz-range-thumb]:opacity-100');
    // the dead spelling: pseudo-element first emits `::thumb:is(...)`, which browsers drop
    expect(cls).not.toMatch(/thumb\]:group-hover/);
  });
});

describe('the scale of a progress column is read as the server reads it', () => {
  it.each<[unknown, number]>([
    [10, 10],
    [2.5, 2],
    [1, 1],
    ['10', 10],
    [' 7 ', 7],
    // a text that is not a whole number is refused by the server, which then uses its default
    ['2.5', 100],
    ['abc', 100],
    ['', 100],
    ['Infinity', 100],
    // a scale below 1 cannot be drawn
    [0.5, 100],
    [0, 100],
    [-5, 100],
    ['0', 100],
    [NaN, 100],
    [Infinity, 100],
    [undefined, 100],
    [null, 100],
    [{}, 100],
  ])('max %s is a scale of %i', (configured, expected) => {
    expect(progressMaxOf(configured)).toBe(expected);
  });

  it('the edit modal opens on the same scale the cell draws', () => {
    render(<EditColumnModal isOpen isSaving={false} onClose={() => {}} onSave={vi.fn()}
      column={{ field: 'data.c', header_name: 'col', type: 'progress', displayConfig: { max: 'Infinity' } } as never} />);
    expect(screen.getByDisplayValue('100')).toBeTruthy();
  });
});

describe('the add-row form keeps what its progress slider shows', () => {
  const SCORE_COLUMNS = [{ field: 'data.score', header_name: 'score', type: 'progress', displayConfig: { max: 10 } }];

  /** The form over REAL state, as the controller holds it: what it writes comes back as its value. */
  function Host({ onData }: { onData: (data: Record<string, unknown>) => void }) {
    const [open, setOpen] = React.useState(true);
    const [data, setData] = React.useState<Record<string, unknown>>({});
    React.useEffect(() => { onData(data); }, [data, onData]);
    return (
      <table><tbody>
        <AddRowForm
          {...({
            columns: SCORE_COLUMNS,
            getUniqueColumns: () => SCORE_COLUMNS,
            getDynamicColumns: () => SCORE_COLUMNS,
            getFieldPath: (field: string) => field,
            viewConfig: { hiddenColumns: [], fixedColumns: [] },
            checkboxColumnWidth: '40px',
            isAddingRowInline: open,
            isAddingRow: false,
            newRowPriority: 1,
            newRowData: data,
            onStartAddingRow: vi.fn(),
            // closing resets the row data, exactly like the controller does
            onCancelAddingRow: () => { setData({}); setOpen(false); },
            onAddRow: vi.fn(),
            onPriorityChange: vi.fn(),
            onRowDataChange: (field: string, value: string) => setData((d) => ({ ...d, [field]: value })),
          } as unknown as React.ComponentProps<typeof AddRowForm>)}
        />
        <tr><td><button data-testid="close" onClick={() => { setData({}); setOpen(false); }} /></td></tr>
      </tbody></table>
    );
  }

  const input = (c: HTMLElement) => c.querySelector('input[type="range"]') as HTMLInputElement;

  it('the slider moves, the row data follows every move, and the cell can be hovered for its thumb', () => {
    const seen: Record<string, unknown>[] = [];
    const { container } = render(<Host onData={(d) => seen.push(d)} />);
    expect(input(container).closest('td')!.className).toContain('group/cell');
    fireEvent.change(input(container), { target: { value: '6' } });
    expect(input(container).value).toBe('6');
    expect(seen.at(-1)).toEqual({ 'data.score': '6' });
    fireEvent.change(input(container), { target: { value: '8' } });
    fireEvent.pointerUp(input(container));
    expect(input(container).value).toBe('8');
    expect(seen.at(-1)).toEqual({ 'data.score': '8' });
  });

  it('closing the form with a move still pending leaves the reset row data empty', () => {
    // the cell saves a pending move as it unmounts; here that must not write into the next row
    const seen: Record<string, unknown>[] = [];
    const { container, getByTestId } = render(<Host onData={(d) => seen.push(d)} />);
    fireEvent.change(input(container), { target: { value: '6' } });
    fireEvent.click(getByTestId('close'));
    expect(input(container)).toBeNull();
    expect(seen.at(-1)).toEqual({});
  });
});

describe('renaming a column does not rewrite its display config', () => {
  function rename(column: Record<string, unknown>) {
    const onSave = vi.fn();
    render(<EditColumnModal isOpen isSaving={false} column={column as never} onClose={() => {}} onSave={onSave} />);
    fireEvent.change(screen.getByDisplayValue(column.header_name as string), { target: { value: 'renamed' } });
    fireEvent.click(screen.getByText('save'));
    return onSave;
  }

  it.each<[string, Record<string, unknown>]>([
    ['a rating whose stored max is above the star cap', { type: 'rating', displayConfig: { max: 50 } }],
    ['a rating whose max is stored as text', { type: 'rating', displayConfig: { max: '7' } }],
    ['a progress with a decimal max', { type: 'progress', displayConfig: { max: 2.5 } }],
    ['a progress with no max at all', { type: 'progress', displayConfig: {} }],
    ['a number with no format stored', { type: 'number', displayConfig: {} }],
    ['a number with out-of-range decimals', { type: 'number', displayConfig: { format: 'plain', decimals: 200, currencySymbol: '$' } }],
    ['a date with no format stored', { type: 'date', displayConfig: {} }],
    ['a select whose options are stored as plain strings', { type: 'select', displayConfig: { options: ['a', 'b'] } }],
  ])('%s: only the name is sent', (_name, column) => {
    const onSave = rename({ field: 'data.c', header_name: 'col', ...column });
    expect(onSave).toHaveBeenCalledTimes(1);
    expect(onSave).toHaveBeenCalledWith({ newName: 'renamed' });
  });

  it.each<[string, Record<string, unknown>]>([
    ['rating', { type: 'rating', displayConfig: { max: 50 } }],
    ['number', { type: 'number', displayConfig: {} }],
    ['date', { type: 'date', displayConfig: {} }],
    ['progress', { type: 'progress', displayConfig: { max: 2.5 } }],
  ])('%s: Save is not live before the user touches anything', (_name, column) => {
    render(<EditColumnModal isOpen isSaving={false} column={{ field: 'data.c', header_name: 'col', ...column } as never} onClose={() => {}} onSave={vi.fn()} />);
    expect((screen.getByText('save').closest('button') as HTMLButtonElement).disabled).toBe(true);
  });

  it('a progress max the user really changes is still written', () => {
    const onSave = vi.fn();
    render(<EditColumnModal isOpen isSaving={false} column={{ field: 'data.c', header_name: 'col', type: 'progress', displayConfig: { max: 100 } } as never} onClose={() => {}} onSave={onSave} />);
    fireEvent.change(screen.getByDisplayValue('100'), { target: { value: '10' } });
    fireEvent.click(screen.getByText('save'));
    expect(onSave).toHaveBeenCalledWith({ display: { max: 10 } });
  });

  it('a rating max the user really changes is still written', () => {
    const onSave = vi.fn();
    render(<EditColumnModal isOpen isSaving={false} column={{ field: 'data.c', header_name: 'col', type: 'rating', displayConfig: { max: 5 } } as never} onClose={() => {}} onSave={onSave} />);
    fireEvent.change(document.querySelector('input[type="range"]') as HTMLInputElement, { target: { value: '8' } });
    fireEvent.click(screen.getByText('save'));
    expect(onSave).toHaveBeenCalledWith({ display: { max: 8 } });
  });

  it('a number decimals the user really changes is still written, capped', () => {
    const onSave = vi.fn();
    render(<EditColumnModal isOpen isSaving={false} column={{ field: 'data.c', header_name: 'col', type: 'number', displayConfig: { format: 'plain', decimals: 0, currencySymbol: '$' } } as never} onClose={() => {}} onSave={onSave} />);
    fireEvent.change(screen.getByDisplayValue('0'), { target: { value: '200' } });
    fireEvent.click(screen.getByText('save'));
    expect(onSave).toHaveBeenCalledWith({ display: { format: 'plain', decimals: 6, currencySymbol: '$' } });
  });
});
