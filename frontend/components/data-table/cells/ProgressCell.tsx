'use client';

import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import type { VisualCellProps } from './types';

export interface ProgressCellExtraProps {
  cellKey: string;
  tempValue?: number;
  onTempChange: (cellKey: string, value: number) => void;
  /** May resolve `false` when the save was refused: the cell then stops showing that value. */
  onProgressSave: (value: number) => void | Promise<boolean | void>;
}

// Full class strings on purpose: Tailwind only emits classes it can read literally.
const TIERS = {
  low: {
    fill: 'bg-red-500',
    thumb: '[&::-webkit-slider-thumb]:bg-red-600 [&::-moz-range-thumb]:bg-red-600',
  },
  mid: {
    fill: 'bg-amber-400',
    thumb: '[&::-webkit-slider-thumb]:bg-amber-500 [&::-moz-range-thumb]:bg-amber-500',
  },
  high: {
    fill: 'bg-lime-500',
    thumb: '[&::-webkit-slider-thumb]:bg-lime-600 [&::-moz-range-thumb]:bg-lime-600',
  },
} as const;

/** Red in the first third of the scale, amber in the second, green in the last. */
export function progressTier(ratio: number): keyof typeof TIERS {
  // Written as a negation so an unknown ratio (NaN) is never painted green.
  if (!(ratio >= 1 / 3)) return 'low';
  if (ratio < 2 / 3) return 'mid';
  return 'high';
}

/** A keyboard user steps several times in a row: one save once they pause, not one per tap. */
export const KEYBOARD_SAVE_DELAY_MS = 300;
/** How long a saved value is held on screen while the stored one has not caught up yet. */
export const PENDING_SAVE_HOLD_MS = 4000;

/**
 * The scale of a progress column, read the way the server reads `display.max` when it stores a
 * value, so the bar can always be filled: a NUMBER is truncated (2.5 is enforced as 2), a TEXT is
 * accepted only if it is a whole number ("10"), and anything else is the default. A scale that is
 * not at least 1 cannot be drawn, so it is the default too. ONE reader, used by the cell and by
 * the edit modal: two spellings of this drifted apart once already.
 */
export const PROGRESS_DEFAULT_MAX = 100;
export function progressMaxOf(configured: unknown): number {
  let n: number;
  if (typeof configured === 'number') n = Math.trunc(configured);
  else if (typeof configured === 'string' && /^\s*\d+\s*$/.test(configured)) n = parseInt(configured, 10);
  else return PROGRESS_DEFAULT_MAX;
  return Number.isFinite(n) && n >= 1 ? n : PROGRESS_DEFAULT_MAX;
}

const SLIDER_KEYS = new Set([
  'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End', 'PageUp', 'PageDown',
]);

export function ProgressCell({
  value,
  displayConfig,
  cellKey,
  tempValue,
  onTempChange,
  onProgressSave,
  readOnly,
}: VisualCellProps & ProgressCellExtraProps) {
  const progressMax = progressMaxOf(displayConfig?.max);
  // Two pieces of state, kept apart on purpose.
  //
  // `move` is a slider move not saved yet. It lives here as well as in the grid's map because a
  // host that keeps no drag state (the add-row form) would hand the same `value` back on every
  // move and pin the slider in place. It is tied to NOTHING but the gesture: the stored value may
  // change under it (the previous save landing) and the move is still the user's, still to save.
  //
  // `held` is display only: the value the LAST save from this cell wrote, shown until the stored
  // value settles, so the slider does not snap back for the length of the request and the next
  // keyboard step starts from where the user is. It never decides whether something is saved.
  // `sentRef` lists the values saved while the hold is up, oldest first, to tell apart the three
  // things a change of the stored value can mean (see the effect below).
  const [move, setMove] = useState<number | undefined>(undefined);
  const [held, setHeld] = useState<number | undefined>(undefined);
  // Counts saves, so that saving the SAME value again restarts the hold instead of inheriting
  // what is left of the previous one.
  const [holdSeq, setHoldSeq] = useState(0);
  const moveRef = useRef<number | undefined>(undefined);
  const keyTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const sentRef = useRef<Array<{ token: number; v: number }>>([]);
  /** Numbers the saves of this cell: a refusal only speaks for the save it belongs to. */
  const saveSeqRef = useRef(0);
  const commitRef = useRef<() => void>(() => {});
  const clamp = (n: unknown) => Math.max(0, Math.min(progressMax, Number(n) || 0));
  const progressValue = clamp(value);
  // A cell that goes away (page change, sort) with a move still pending SAVES it. Dropping it
  // would lose the edit and leave its value in the grid's temp map, drawn over the stored one.
  useEffect(() => () => {
    clearTimeout(keyTimer.current);
    commitRef.current();
  }, []);
  // The stored value changed while a value is held. Three cases:
  //  - it reached the held value: the hold has done its job;
  //  - it reached an EARLIER save of ours (two saves overlapping, the first one landing): keep
  //    holding, or the slider is pulled back to a value the user has already left;
  //  - anything else (the server stored something different, another writer): the stored value
  //    wins at once, holding ours over it would be showing a value that is not there.
  const lastStoredRef = useRef(progressValue);
  useEffect(() => {
    if (lastStoredRef.current === progressValue) return;
    lastStoredRef.current = progressValue;
    if (held === undefined) return;
    const earlier = sentRef.current.findIndex((sent) => sent.v === progressValue);
    if (progressValue !== held && earlier !== -1) {
      sentRef.current = sentRef.current.slice(earlier + 1);
      return;
    }
    sentRef.current = [];
    setHeld(undefined);
  }, [held, progressValue]);
  // A save that never lands (refused, offline) must not leave its value on screen for good. The
  // clock restarts whenever the held value changes, including when it falls back to an earlier save.
  useEffect(() => {
    if (held === undefined) return;
    const timer = setTimeout(() => {
      sentRef.current = [];
      setHeld(undefined);
    }, PENDING_SAVE_HOLD_MS);
    return () => clearTimeout(timer);
  }, [held, holdSeq]);
  // A table that turns read-only under a pending move has no slider left to finish it with, and
  // its save would be dropped anyway: forget the move instead of captioning a value never stored.
  useEffect(() => {
    if (!readOnly || moveRef.current === undefined) return;
    clearTimeout(keyTimer.current);
    keyTimer.current = undefined;
    moveRef.current = undefined;
    setMove(undefined);
  }, [readOnly]);
  const inFlight = readOnly ? undefined : (move ?? held ?? tempValue);
  // The in-flight slider value is clamped like the stored one: a drag that outlives a lowered max
  // must not draw a bar wider than its track or caption it "12 / 10".
  const currentTemp = inFlight === undefined ? progressValue : clamp(inFlight);
  const displayValue = currentTemp;
  // The stored value is in the column's own unit (0..max), so it only reads as a percentage
  // when the scale IS 100. On any other max, "10%" under a full bar is simply wrong.
  const ratio = displayValue / progressMax;
  const tierName = progressTier(ratio);
  const tier = TIERS[tierName];
  const locale = getClientLocale();
  const format = (n: number) => n.toLocaleString(locale, { maximumFractionDigits: 1 });
  const label = progressMax === 100 ? `${format(displayValue)}%` : `${format(displayValue)} / ${format(progressMax)}`;

  // Saves the pending move, once. Every move that exists is written, with no attempt to guess
  // that it "changed nothing": while another save is in flight the stored value is not a reliable
  // thing to compare with, and a redundant write costs far less than a move shown and never saved.
  // A gesture that moved nothing (a click on the thumb, an arrow at the end of the scale, a second
  // pointer-up) has no pending move and writes nothing.
  const commit = () => {
    clearTimeout(keyTimer.current);
    keyTimer.current = undefined;
    const pending = moveRef.current;
    if (pending === undefined) return;
    const next = clamp(pending);
    moveRef.current = undefined;
    setMove(undefined);
    setHeld(next);
    setHoldSeq((n) => n + 1);
    // `sentRef` holds the saves still expected to land, oldest first, and its LAST entry is always
    // the value held. A refused save is taken out of it by its own token, never by its value (the
    // same value can be in flight twice). If it was the one held, the slider moves to the save
    // before it that is still in flight, where the row is about to be, or to the stored value when
    // there is none: never left on a value that was just rejected beside its error.
    const token = ++saveSeqRef.current;
    sentRef.current.push({ token, v: next });
    void Promise.resolve(onProgressSave(next)).then((saved) => {
      if (saved !== false) return;
      const at = sentRef.current.findIndex((sent) => sent.token === token);
      if (at === -1) return;
      const wasHeld = at === sentRef.current.length - 1;
      sentRef.current.splice(at, 1);
      if (wasHeld) setHeld(sentRef.current.at(-1)?.v);
    }, () => {});
  };
  // The unmount cleanup needs the commit of the LAST render (latest value, latest callbacks).
  useLayoutEffect(() => {
    commitRef.current = commit;
  });

  return (
    <div className="w-full relative" onClick={(e) => e.stopPropagation()}>
      <div className="flex w-full flex-col items-center gap-2">
        <div className="h-2 w-full rounded-full bg-slate-200 dark:bg-slate-700 relative">
          <div
            data-progress-tier={tierName}
            className={`h-full rounded-full transition-all ${tier.fill}`}
            style={{ width: `${ratio * 100}%` }}
          />
          {/* No slider at all on a read-only table: the bar and its caption are the whole cell. */}
          {!readOnly && (
            <input
              type="range"
              min={0}
              max={progressMax}
              step={1}
              value={currentTemp}
              aria-valuetext={label}
              onChange={(event) => {
                const next = Number(event.currentTarget.value);
                moveRef.current = next;
                setMove(next);
                onTempChange(cellKey, next);
              }}
              onPointerUp={commit}
              // A touch drag the browser takes over ends here, with no pointer-up at all.
              onPointerCancel={commit}
              // A keyboard move never raises pointerup, so without this it was shown and never saved.
              onKeyUp={(event) => {
                if (!SLIDER_KEYS.has(event.key)) return;
                clearTimeout(keyTimer.current);
                keyTimer.current = setTimeout(commit, KEYBOARD_SAVE_DELAY_MS);
              }}
              // Unconditional: whatever is still pending when focus leaves is saved, whichever
              // gesture produced it. With nothing pending this writes nothing.
              onBlur={commit}
              onClick={(e) => e.stopPropagation()}
              className={`absolute inset-0 w-full h-full cursor-pointer [&::-webkit-slider-track]:bg-transparent [&::-webkit-slider-track]:h-2 [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:w-4 [&::-webkit-slider-thumb]:h-4 [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:cursor-pointer [&::-webkit-slider-thumb]:shadow-sm [&::-webkit-slider-thumb]:-mt-1 [&::-webkit-slider-thumb]:opacity-0 group-hover/cell:[&::-webkit-slider-thumb]:opacity-100 focus-visible:[&::-webkit-slider-thumb]:opacity-100 [@media(hover:none)]:[&::-webkit-slider-thumb]:opacity-100 [&::-moz-range-track]:bg-transparent [&::-moz-range-track]:h-2 [&::-moz-range-thumb]:w-4 [&::-moz-range-thumb]:h-4 [&::-moz-range-thumb]:rounded-full [&::-moz-range-thumb]:cursor-pointer [&::-moz-range-thumb]:border-0 [&::-moz-range-thumb]:opacity-0 group-hover/cell:[&::-moz-range-thumb]:opacity-100 focus-visible:[&::-moz-range-thumb]:opacity-100 [@media(hover:none)]:[&::-moz-range-thumb]:opacity-100 ${tier.thumb}`}
              // On a device that cannot hover (touch) the thumb is always shown: `group-hover` is
              // emitted under `@media (hover: hover)`, so there it would never appear at all.
              // Variant order matters: `group-hover` BEFORE the pseudo-element. The other way round
              // emits `::-webkit-slider-thumb:is(...)`, which browsers drop, so the thumb never showed.
              style={{
                WebkitAppearance: 'none',
                background: 'transparent',
                pointerEvents: 'auto',
              }}
            />
          )}
        </div>
        <span className="text-[10px] font-semibold text-theme-secondary">{label}</span>
      </div>
    </div>
  );
}

ProgressCell.editable = false;
