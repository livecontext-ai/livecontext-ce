// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

import LandingThemeProvider, { useLandingTheme } from '../LandingThemeProvider';

// Probe consumer that surfaces the current landing theme + a toggle trigger.
function Probe() {
  const { theme, toggle } = useLandingTheme();
  return (
    <button type="button" onClick={toggle}>
      theme:{theme}
    </button>
  );
}

beforeEach(() => {
  try {
    localStorage.clear();
  } catch {
    /* noop */
  }
});
afterEach(cleanup);

describe('LandingThemeProvider', () => {
  it('defaults to LIGHT for a first-time visitor (no stored preference)', () => {
    const { container } = render(
      <LandingThemeProvider>
        <Probe />
      </LandingThemeProvider>,
    );
    expect(container.querySelector('.landing-root')).not.toHaveClass('dark');
    expect(screen.getByRole('button')).toHaveTextContent('theme:light');
  });

  it('ignores a previously stored dark preference while the public site is light-only', () => {
    localStorage.setItem('landing-theme', 'dark');
    const { container } = render(
      <LandingThemeProvider>
        <Probe />
      </LandingThemeProvider>,
    );
    expect(container.querySelector('.landing-root')).not.toHaveClass('dark');
    expect(screen.getByRole('button')).toHaveTextContent('theme:light');
  });

  it('toggle flips the .landing-root class and persists under `landing-theme` WITHOUT touching the app `theme` key', () => {
    const { container } = render(
      <LandingThemeProvider>
        <Probe />
      </LandingThemeProvider>,
    );
    const root = () => container.querySelector('.landing-root');
    const btn = screen.getByRole('button');

    fireEvent.click(btn); // light -> dark
    expect(root()).toHaveClass('dark');
    expect(localStorage.getItem('landing-theme')).toBe('dark');
    expect(localStorage.getItem('theme')).toBeNull(); // app theme is left alone

    fireEvent.click(btn); // dark -> light
    expect(root()).not.toHaveClass('dark');
    expect(localStorage.getItem('landing-theme')).toBe('light');
  });

  it('restores a stored dark choice on mount when respectStored is set (docs surface)', () => {
    localStorage.setItem('docs-theme', 'dark');
    const { container } = render(
      <LandingThemeProvider storageKey="docs-theme" respectStored>
        <Probe />
      </LandingThemeProvider>,
    );
    // The docs toggle persists its own choice; flipping the site default to
    // light must not override a docs visitor's stored dark preference.
    expect(container.querySelector('.landing-root')).toHaveClass('dark');
    expect(screen.getByRole('button')).toHaveTextContent('theme:dark');
  });

  it('merges extra className onto the .landing-root wrapper', () => {
    const { container } = render(
      <LandingThemeProvider className="min-h-screen flex flex-col">
        <Probe />
      </LandingThemeProvider>,
    );
    const root = container.querySelector('.landing-root');
    expect(root).toHaveClass('min-h-screen');
    expect(root).toHaveClass('flex');
    expect(root).toHaveClass('flex-col');
  });
});
