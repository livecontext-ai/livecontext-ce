// @vitest-environment jsdom
/**
 * The studio's second look, and the control that chooses it.
 *
 * <p>The treatment itself is CSS: `.studio-darkroom` redefines the app's colour tokens for
 * everything inside the studio. What can be wrong in code is everything around that - whether the
 * class is applied at all, whether the choice survives a reload, and whether a browser that refuses
 * storage takes the surface down with it.
 *
 * <p>Rendered against the REAL dictionary: the control is icon-only, so its accessible name is the
 * only thing identifying it, and a stub translator returning key paths would let a control labelled
 * with nothing pass every assertion.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, renderHook, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import fs from 'node:fs';
import path from 'node:path';

import enMessages from '@/messages/en.json';
import { StudioLookSwitch } from '../StudioLookSwitch';
import { useStudioLook, studioLookClass } from '@/hooks/useStudioLook';

const STORAGE_KEY = 'lc.studio.look';

function withIntl(node: React.ReactNode) {
  return (
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      {node}
    </NextIntlClientProvider>
  );
}

beforeEach(() => { window.localStorage.clear(); });
afterEach(() => { vi.restoreAllMocks(); cleanup(); });

describe('useStudioLook', () => {
  it('starts on the app look, so a reader who never chose sees no change', () => {
    const { result } = renderHook(() => useStudioLook());
    expect(result.current[0]).toBe('app');
  });

  it('adopts a stored choice after mount', () => {
    window.localStorage.setItem(STORAGE_KEY, 'darkroom');
    const { result } = renderHook(() => useStudioLook());
    expect(result.current[0]).toBe('darkroom');
  });

  it('ignores a stored value that is not a look', () => {
    // Anything can be in storage: another build, a hand-edited key, a collision.
    window.localStorage.setItem(STORAGE_KEY, 'neon');
    const { result } = renderHook(() => useStudioLook());
    expect(result.current[0]).toBe('app');
  });

  it('remembers a choice for the next visit', () => {
    const { result } = renderHook(() => useStudioLook());
    act(() => result.current[1]('darkroom'));

    expect(result.current[0]).toBe('darkroom');
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe('darkroom');
  });

  it('still applies the choice when storage refuses to keep it', () => {
    // A private window throws on write. Losing the memory of a colour preference is acceptable;
    // losing the studio because of one is not.
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const { result } = renderHook(() => useStudioLook());

    act(() => result.current[1]('darkroom'));

    expect(result.current[0]).toBe('darkroom');
  });

  it('renders at all when storage refuses to be read', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const { result } = renderHook(() => useStudioLook());

    expect(result.current[0]).toBe('app');
  });
});

describe('the two halves of the treatment', () => {
  /**
   * Every token the light half redefines must be redefined by the dark half too.
   *
   * <p>Both selectors match the same element in dark mode, and the light block is not conditional,
   * so a token it declares and the dark block omits does NOT fall back to the app's value: it
   * leaks the light value onto the dark ground. That is how `--text-muted` came to render at
   * 3.38:1 in the darkroom, worse than the 3.62:1 the app has - a contrast failure created by a
   * treatment whose own comment claimed it had none to pay back. Nothing on screen says so, and no
   * amount of reading either block alone shows it: the bug is the RELATIONSHIP between them.
   */
  const css = fs.readFileSync(
    path.join(process.cwd(), 'app', 'globals.css'), 'utf8',
  );

  /**
   * The block a selector opens, found by a selector that must START a line.
   *
   * <p>This read `css.indexOf(`${selector} {`)`, and `.studio-darkroom {` is a SUBSTRING of
   * `.dark .studio-darkroom {`. Whenever the light block is absent (deleted, renamed, or simply
   * written after the dark one) the "light" lookup lands inside the DARK block and the parity
   * assertion compares that block against itself. Every test in this describe then passes with the
   * entire light half of the treatment gone, which is the definition of an assertion that survives
   * deleting the thing it guards.
   */
  function blockOf(selector: string): string {
    const anchored = new RegExp(`^${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{`, 'm');
    const match = anchored.exec(css);
    expect(match, `${selector} must exist in globals.css as a rule of its own`).not.toBeNull();
    const start = (match as RegExpExecArray).index;
    const end = css.indexOf('\n}', start);
    expect(end, `${selector} must be a closed block`).toBeGreaterThan(start);
    return css.slice(start, end);
  }

  function tokensOf(selector: string): string[] {
    return [...blockOf(selector).matchAll(/^\s*(--[\w-]+)\s*:/gm)].map((m) => m[1]);
  }

  it('reads two DIFFERENT blocks, which the old selector did not', () => {
    // Pinned on its own, because it is the failure that made every other assertion here vacuous.
    expect(blockOf('.studio-darkroom')).not.toEqual(blockOf('.dark .studio-darkroom'));
  });

  it('redefines in the dark half every token the light half sets', () => {
    const light = tokensOf('.studio-darkroom');
    const dark = new Set(tokensOf('.dark .studio-darkroom'));

    expect(light.length).toBeGreaterThan(0);
    expect(light.filter((token) => !dark.has(token))).toEqual([]);
  });

  it('sets in the light half every token the dark half redefines', () => {
    // The converse, which was never checked. A token defined only under `.dark .studio-darkroom`
    // is inert in light mode: the studio falls back to the app's value for it with nothing on
    // screen saying so, and the treatment is half applied on the ground most readers are on.
    const dark = tokensOf('.dark .studio-darkroom');
    const light = new Set(tokensOf('.studio-darkroom'));

    expect(dark.length).toBeGreaterThan(0);
    expect(dark.filter((token) => !light.has(token))).toEqual([]);
  });
});

describe('studioLookClass', () => {
  it('carries the treatment only for the studio look', () => {
    expect(studioLookClass('darkroom')).toBe('studio-darkroom');
    // Nothing at all for the app's own theme: the surface stays as transparent as it was, which is
    // what makes this an offer rather than a redecoration.
    expect(studioLookClass('app')).toBe('');
  });
});

describe('StudioLookSwitch', () => {
  it('names what pressing it DOES, not what is currently on', () => {
    // A toggle labelled with its own state reads as a claim about the button.
    render(withIntl(<StudioLookSwitch look="app" onChange={vi.fn()} />));
    expect(screen.getByRole('button', { name: 'Switch to the darkroom view' })).toBeInTheDocument();

    cleanup();
    render(withIntl(<StudioLookSwitch look="darkroom" onChange={vi.fn()} />));
    expect(screen.getByRole('button', { name: 'Switch back to the app view' })).toBeInTheDocument();
  });

  it('carries ONE signal, not a name and a pressed state that contradict it', () => {
    // With both, a screen reader announces "switch back to the app view, pressed", and the state
    // reads as a claim about the wrong half. The changing name is the signal that survives.
    render(withIntl(<StudioLookSwitch look="darkroom" onChange={vi.fn()} />));
    expect(screen.getByRole('button')).not.toHaveAttribute('aria-pressed');
  });

  it('asks for the other look when pressed', () => {
    const onChange = vi.fn();
    render(withIntl(<StudioLookSwitch look="app" onChange={onChange} />));

    fireEvent.click(screen.getByRole('button'));

    expect(onChange).toHaveBeenCalledWith('darkroom');
  });

  it('asks for the app look when it is already on the studio one', () => {
    const onChange = vi.fn();
    render(withIntl(<StudioLookSwitch look="darkroom" onChange={onChange} />));

    fireEvent.click(screen.getByRole('button'));

    expect(onChange).toHaveBeenCalledWith('app');
  });
});
