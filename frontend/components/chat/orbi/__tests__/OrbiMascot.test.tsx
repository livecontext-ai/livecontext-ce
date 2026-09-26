// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const motion = vi.hoisted(() => ({ reduce: false }));
vi.mock('@/hooks/usePrefersReducedMotion', () => ({ usePrefersReducedMotion: () => motion.reduce }));

import { OrbiMascot, ORBI_DIZZY_POKES, ORBI_HOP_MS, ORBI_SLEEP_AFTER_MS, ORBI_TRICK_MS, ORBI_WAVE_MS } from '../OrbiMascot';
import { markOrbiGreeting } from '../orbiGreeting';

const mood = (c: HTMLElement) => c.querySelector('svg.orbi-mascot')!.getAttribute('data-mood');
const eye = (c: HTMLElement) => c.querySelector<SVGGElement>('.orbi-eye')!;

beforeEach(() => {
  vi.useFakeTimers();
  motion.reduce = false;
  sessionStorage.clear();
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('OrbiMascot moods follow the real chat state', () => {
  it('is idle at rest and thinking while a turn streams', () => {
    const { container, rerender } = render(<OrbiMascot isStreaming={false} inputValue="" />);
    expect(mood(container)).toBe('idle');

    rerender(<OrbiMascot isStreaming inputValue="" />);
    expect(mood(container)).toBe('thinking');
  });

  it('hops when the turn ends, then settles back to idle', () => {
    const { container, rerender } = render(<OrbiMascot isStreaming inputValue="" />);
    rerender(<OrbiMascot isStreaming={false} inputValue="" />);
    expect(mood(container)).toBe('hop');

    act(() => { vi.advanceTimersByTime(ORBI_HOP_MS); });
    expect(mood(container)).toBe('idle');
  });

  it('does not hop on mount when nothing was streaming', () => {
    const { container } = render(<OrbiMascot isStreaming={false} inputValue="" />);
    expect(mood(container)).toBe('idle');
  });

  it('falls asleep after the idle delay and wakes on pointer movement', () => {
    const { container } = render(<OrbiMascot isStreaming={false} inputValue="" />);
    act(() => { vi.advanceTimersByTime(ORBI_SLEEP_AFTER_MS - 1); });
    expect(mood(container)).toBe('idle');
    act(() => { vi.advanceTimersByTime(1); });
    expect(mood(container)).toBe('sleep');

    act(() => { fireEvent.pointerMove(window, { clientX: 10, clientY: 10 }); });
    expect(mood(container)).toBe('idle');
  });

  it('never falls asleep in the middle of a streaming turn', () => {
    const { container } = render(<OrbiMascot isStreaming inputValue="" />);
    act(() => { vi.advanceTimersByTime(ORBI_SLEEP_AFTER_MS * 2); });
    expect(mood(container)).toBe('thinking');
  });

  it('typing wakes it up and points the eye at the text', () => {
    const { container, rerender } = render(<OrbiMascot isStreaming={false} inputValue="" />);
    act(() => { vi.advanceTimersByTime(ORBI_SLEEP_AFTER_MS); });
    expect(mood(container)).toBe('sleep');

    rerender(<OrbiMascot isStreaming={false} inputValue="h" />);
    expect(mood(container)).toBe('idle');
    expect(eye(container).style.transform).toBe('translate(-4px, 5px)');
  });
});

describe('OrbiMascot waves hello after a sign-in', () => {
  it('waves for the whole wave length, then settles back to idle', () => {
    markOrbiGreeting();
    const { container } = render(<OrbiMascot isStreaming={false} inputValue="" />);
    expect(mood(container)).toBe('wave');
    expect(container.querySelector('[data-testid="orbi-arms"]')).not.toBeNull();

    // Still waving past the hop length: the wave is long enough to be seen.
    act(() => { vi.advanceTimersByTime(ORBI_WAVE_MS - 1); });
    expect(mood(container)).toBe('wave');
    act(() => { vi.advanceTimersByTime(1); });
    expect(mood(container)).toBe('idle');
  });

  it('waves only once: the next Orbi of the session does not', () => {
    markOrbiGreeting();
    const first = render(<OrbiMascot isStreaming={false} inputValue="" />);
    expect(mood(first.container)).toBe('wave');
    first.unmount();

    const { container } = render(<OrbiMascot isStreaming={false} inputValue="" />);
    expect(mood(container)).toBe('idle');
  });

  it('does not wave without a sign-in', () => {
    const { container } = render(<OrbiMascot isStreaming={false} inputValue="" />);
    expect(mood(container)).toBe('idle');
  });

  it('a turn starting mid-wave shows thinking, and ends on the hop, not the rest of the wave', () => {
    markOrbiGreeting();
    const { container, rerender } = render(<OrbiMascot isStreaming={false} inputValue="" />);
    rerender(<OrbiMascot isStreaming inputValue="" />);
    expect(mood(container)).toBe('thinking');
    rerender(<OrbiMascot isStreaming={false} inputValue="" />);
    expect(mood(container)).toBe('hop');
  });
});

describe('OrbiMascot under reduced motion', () => {
  it('renders static: no follow, no look-at-text, but the moods still apply', () => {
    motion.reduce = true;
    const raf = vi.spyOn(window, 'requestAnimationFrame');
    const { container, rerender } = render(<OrbiMascot isStreaming={false} inputValue="" />);

    expect(container.querySelector('svg.orbi-mascot')).toHaveClass('orbi-static');
    act(() => { fireEvent.pointerMove(window, { clientX: 500, clientY: 500 }); });
    expect(raf).not.toHaveBeenCalled();

    rerender(<OrbiMascot isStreaming={false} inputValue="hello" />);
    expect(eye(container).style.transform).toBe('');

    rerender(<OrbiMascot isStreaming inputValue="hello" />);
    expect(mood(container)).toBe('thinking');
    raf.mockRestore();
  });
});

describe('OrbiMascot cleanup', () => {
  it('removes its pointer listener on unmount', () => {
    const remove = vi.spyOn(window, 'removeEventListener');
    const { unmount } = render(<OrbiMascot isStreaming={false} inputValue="" />);
    unmount();
    expect(remove).toHaveBeenCalledWith('pointermove', expect.any(Function));
    remove.mockRestore();
  });
});

describe('OrbiMascot wears nothing, whatever model is selected', () => {
  it('draws no accessory', () => {
    const { container } = render(<OrbiMascot isStreaming={false} inputValue="" />);
    for (const id of ['orbi-bow-tie', 'orbi-monocle', 'orbi-cap']) {
      expect(container.querySelector(`[data-testid="${id}"]`), id).toBeNull();
    }
  });
});

describe('Poking Orbi', () => {
  const pokeButton = (c: HTMLElement) => c.querySelector<HTMLButtonElement>('[data-testid="orbi-poke"]')!;

  it('is decoration only without a poke label', () => {
    const { container } = render(<OrbiMascot isStreaming={false} inputValue="" />);
    expect(container.querySelector('[data-testid="orbi-poke"]')).toBeNull();
  });

  it('is a labelled button with one', () => {
    const { getByRole } = render(<OrbiMascot isStreaming={false} inputValue="" pokeLabel="Poke Orbi" />);
    expect(getByRole('button', { name: 'Poke Orbi' })).toBeInTheDocument();
  });

  it('plays a random reaction on each poke, never the same twice in a row, then settles back', () => {
    const { container } = render(<OrbiMascot isStreaming={false} inputValue="" pokeLabel="Poke" />);
    const seen: string[] = [];
    for (let i = 0; i < 12; i += 1) {
      fireEvent.click(pokeButton(container));
      const played = mood(container)!;
      seen.push(played);
      act(() => { vi.advanceTimersByTime(ORBI_TRICK_MS[played as keyof typeof ORBI_TRICK_MS]); });
      expect(mood(container)).toBe('idle');
      // Pokes spaced out, so this never counts as poking too fast.
      act(() => { vi.advanceTimersByTime(2000); });
    }
    for (const played of seen) expect(['boop', 'spin', 'love', 'wave']).toContain(played);
    for (let i = 1; i < seen.length; i += 1) expect(seen[i]).not.toBe(seen[i - 1]);
  });

  it('draws the reaction from the random source', () => {
    const random = vi.spyOn(Math, 'random');
    const { container } = render(<OrbiMascot isStreaming={false} inputValue="" pokeLabel="Poke" />);
    random.mockReturnValue(0.99);
    fireEvent.click(pokeButton(container));
    expect(mood(container)).toBe('wave');
    act(() => { vi.advanceTimersByTime(ORBI_TRICK_MS.wave + 2000); });
    random.mockReturnValue(0);
    fireEvent.click(pokeButton(container));
    expect(mood(container)).toBe('boop');
    random.mockRestore();
  });

  it('gets dizzy when poked too fast', () => {
    const { container } = render(<OrbiMascot isStreaming={false} inputValue="" pokeLabel="Poke" />);
    for (let i = 0; i < ORBI_DIZZY_POKES; i += 1) fireEvent.click(pokeButton(container));
    expect(mood(container)).toBe('dizzy');
    act(() => { vi.advanceTimersByTime(ORBI_TRICK_MS.dizzy); });
    expect(mood(container)).toBe('idle');
  });

  it('wakes a sleeping Orbi', () => {
    const random = vi.spyOn(Math, 'random').mockReturnValue(0);
    const { container } = render(<OrbiMascot isStreaming={false} inputValue="" pokeLabel="Poke" />);
    act(() => { vi.advanceTimersByTime(ORBI_SLEEP_AFTER_MS); });
    expect(mood(container)).toBe('sleep');
    fireEvent.click(pokeButton(container));
    act(() => { vi.advanceTimersByTime(ORBI_TRICK_MS.boop); });
    random.mockRestore();
    expect(mood(container)).toBe('idle');
  });

  it('reacts in the middle of a turn, then goes back to thinking', () => {
    const random = vi.spyOn(Math, 'random').mockReturnValue(0);
    const { container } = render(<OrbiMascot isStreaming inputValue="" pokeLabel="Poke" />);
    fireEvent.click(pokeButton(container));
    expect(mood(container)).toBe('boop');
    act(() => { vi.advanceTimersByTime(ORBI_TRICK_MS.boop); });
    expect(mood(container)).toBe('thinking');
    random.mockRestore();
  });
});
