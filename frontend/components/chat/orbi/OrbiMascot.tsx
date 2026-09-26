'use client';

import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { usePrefersReducedMotion } from '@/hooks/usePrefersReducedMotion';
import { OrbiMascotShape } from './OrbiShapes';
import { consumeOrbiGreeting } from './orbiGreeting';

/** What Orbi does when poked. Played once, then Orbi goes back to whatever it was doing. */
export type OrbiTrick = 'boop' | 'spin' | 'love' | 'wave' | 'dizzy';
export type OrbiMood = 'idle' | 'thinking' | 'hop' | 'wave' | 'sleep' | OrbiTrick;

/** Idle this long without pointer or typing and Orbi falls asleep. */
export const ORBI_SLEEP_AFTER_MS = 20_000;
/** Length of the hop played when a turn finishes (matches the CSS keyframes). */
export const ORBI_HOP_MS = 600;
/** Length of the hello wave played after a sign-in (matches the CSS keyframes): four full
 *  back-and-forth swings, long enough to be noticed, not so long it gets in the way. */
export const ORBI_WAVE_MS = 3200;
/** How far the eye may travel inside the ring, in viewBox units (ring inner edge leaves ~11). */
const EYE_MAX_TRAVEL = 6;
/** Distance, in CSS px, at which the eye reaches its full travel. */
const EYE_FULL_TRAVEL_AT_PX = 220;
/** Where the eye looks while the user types: down and left, toward the text. */
const LOOK_AT_TEXT = 'translate(-4px, 5px)';
/** Length of each poke reaction, matching its CSS keyframes. */
export const ORBI_TRICK_MS: Record<OrbiTrick, number> = {
  boop: 500, spin: 800, love: 1400, wave: ORBI_WAVE_MS, dizzy: 1600,
};
/** The reactions a poke picks from, at random. */
const TRICKS: OrbiTrick[] = ['boop', 'spin', 'love', 'wave'];
/** This many pokes inside the window below and Orbi gets dizzy instead. */
export const ORBI_DIZZY_POKES = 5;
const DIZZY_WINDOW_MS = 2000;

/**
 * Orbi perched on the composer. Every mood maps to something real: `thinking` while a turn
 * streams (the eye pulses red, like the animated logo), a `hop` when it ends, a `wave` the first
 * time it appears after a sign-in, `sleep` after a quiet spell; the eye follows the pointer and
 * looks at the text while the user types. Reduced motion keeps the moods (they carry meaning)
 * but drops the follow; the CSS drops the animations.
 */
export function OrbiMascot({
  isStreaming,
  inputValue,
  pokeLabel,
  className = '',
}: {
  isStreaming: boolean;
  inputValue: string;
  /** Accessible name of the poke button. Without it Orbi is decoration only and cannot be poked. */
  pokeLabel?: string;
  className?: string;
}) {
  const reduceMotion = usePrefersReducedMotion();
  // `thinking` is not stored: it IS the stream. The stored mood is what Orbi does otherwise.
  const [restMood, setRestMood] = useState<Exclude<OrbiMood, 'thinking'>>('idle');
  // A poke reaction wins over everything, including a running turn, for its short length.
  const [trick, setTrick] = useState<OrbiTrick | null>(null);
  const mood: OrbiMood = trick ?? (isStreaming ? 'thinking' : restMood);
  const eyeRef = useRef<SVGGElement>(null);
  // Timers and the pointer handler read the live mood without re-subscribing on each change.
  const moodRef = useRef(mood);
  useLayoutEffect(() => { moodRef.current = mood; }, [mood]);
  const sleepTimer = useRef<number | undefined>(undefined);

  // The falling edge of the stream plays the hop. Adjusted during render (React's pattern for
  // "state derived from a previous prop"), so the hop is never one frame late.
  const [prevStreaming, setPrevStreaming] = useState(isStreaming);
  if (prevStreaming !== isStreaming) {
    setPrevStreaming(isStreaming);
    if (!isStreaming) setRestMood('hop');
  }

  // One place arms the sleep timer; any sign of life re-arms it and wakes Orbi up.
  const wake = useCallback(() => {
    window.clearTimeout(sleepTimer.current);
    if (moodRef.current === 'sleep') setRestMood('idle');
    sleepTimer.current = window.setTimeout(() => {
      if (moodRef.current === 'idle') setRestMood('sleep');
    }, ORBI_SLEEP_AFTER_MS);
  }, []);

  useEffect(() => {
    wake();
    return () => window.clearTimeout(sleepTimer.current);
  }, [wake]);

  // Just signed in: say hello. Consumed once, so only the first Orbi of the session waves.
  useEffect(() => {
    if (consumeOrbiGreeting()) setRestMood('wave');
  }, []);

  // After the hop or the wave, back to idle, with a fresh sleep countdown.
  useEffect(() => {
    if (restMood !== 'hop' && restMood !== 'wave') return;
    const played = restMood;
    const id = window.setTimeout(() => {
      setRestMood(m => (m === played ? 'idle' : m));
      wake();
    }, played === 'wave' ? ORBI_WAVE_MS : ORBI_HOP_MS);
    return () => window.clearTimeout(id);
  }, [restMood, wake]);

  // Poked: play a random reaction, or get dizzy when poked too fast.
  const pokes = useRef<number[]>([]);
  const lastTrick = useRef<OrbiTrick | null>(null);
  const trickTimer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(trickTimer.current), []);
  const poke = useCallback(() => {
    wake();
    const now = Date.now();
    pokes.current = [...pokes.current.filter(t => now - t < DIZZY_WINDOW_MS), now];
    let played: OrbiTrick;
    if (pokes.current.length >= ORBI_DIZZY_POKES) {
      pokes.current = [];
      played = 'dizzy';
    } else {
      // At random, but never the same one twice in a row, or a poke can look like it did nothing.
      const pool = TRICKS.filter(t => t !== lastTrick.current);
      played = pool[Math.floor(Math.random() * pool.length)];
    }
    lastTrick.current = played;
    setTrick(played);
    window.clearTimeout(trickTimer.current);
    trickTimer.current = window.setTimeout(() => {
      setTrick(null);
      wake();
    }, ORBI_TRICK_MS[played]);
  }, [wake]);

  // Typing: look at the text.
  useEffect(() => {
    if (!inputValue) return;
    wake();
    if (!reduceMotion && eyeRef.current) eyeRef.current.style.transform = LOOK_AT_TEXT;
  }, [inputValue, reduceMotion, wake]);

  // Pointer follow: one window listener, at most one DOM write per frame, no React render.
  useEffect(() => {
    if (reduceMotion) return;
    let frame = 0;
    let x = 0;
    let y = 0;
    const apply = () => {
      frame = 0;
      const eye = eyeRef.current;
      const svg = eye?.ownerSVGElement;
      if (!eye || !svg || (moodRef.current !== 'idle' && moodRef.current !== 'hop')) return;
      const r = svg.getBoundingClientRect();
      const dx = x - (r.left + r.width / 2);
      const dy = y - (r.top + r.height / 2);
      const reach = Math.min(1, Math.hypot(dx, dy) / EYE_FULL_TRAVEL_AT_PX) * EYE_MAX_TRAVEL;
      const a = Math.atan2(dy, dx);
      eye.style.transform = `translate(${(Math.cos(a) * reach).toFixed(2)}px, ${(Math.sin(a) * reach).toFixed(2)}px)`;
    };
    const onMove = (e: PointerEvent) => {
      x = e.clientX;
      y = e.clientY;
      wake();
      if (!frame) frame = window.requestAnimationFrame(apply);
    };
    window.addEventListener('pointermove', onMove, { passive: true });
    return () => {
      window.removeEventListener('pointermove', onMove);
      if (frame) window.cancelAnimationFrame(frame);
    };
  }, [reduceMotion, wake]);

  const shape = (
    <OrbiMascotShape
      mood={mood}
      eyeRef={eyeRef}
      className={`${reduceMotion ? 'orbi-static ' : ''}${pokeLabel ? 'h-full w-full' : className}`}
    />
  );
  if (!pokeLabel) return shape;
  return (
    <button
      type="button"
      onClick={poke}
      aria-label={pokeLabel}
      title={pokeLabel}
      data-testid="orbi-poke"
      // The perch is click-through so it never blocks the composer; Orbi itself takes the click.
      className={`pointer-events-auto block cursor-pointer rounded-full focus:outline-none focus-visible:ring-2 focus-visible:ring-theme-accent ${className}`}
    >
      {shape}
    </button>
  );
}

export default OrbiMascot;
