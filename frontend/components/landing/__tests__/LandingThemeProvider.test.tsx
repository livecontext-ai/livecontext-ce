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
  it('defaults to DARK for a first-time visitor (no stored preference)', () => {
    const { container } = render(
      <LandingThemeProvider>
        <Probe />
      </LandingThemeProvider>,
    );
    expect(container.querySelector('.landing-root')).toHaveClass('dark');
    expect(screen.getByRole('button')).toHaveTextContent('theme:dark');
  });

  it('ignores a previously stored light preference while the public site is dark-only', () => {
    localStorage.setItem('landing-theme', 'light');
    const { container } = render(
      <LandingThemeProvider>
        <Probe />
      </LandingThemeProvider>,
    );
    expect(container.querySelector('.landing-root')).toHaveClass('dark');
    expect(screen.getByRole('button')).toHaveTextContent('theme:dark');
  });

  it('toggle flips the .landing-root class and persists under `landing-theme` WITHOUT touching the app `theme` key', () => {
    const { container } = render(
      <LandingThemeProvider>
        <Probe />
      </LandingThemeProvider>,
    );
    const root = () => container.querySelector('.landing-root');
    const btn = screen.getByRole('button');

    fireEvent.click(btn); // dark -> light
    expect(root()).not.toHaveClass('dark');
    expect(localStorage.getItem('landing-theme')).toBe('light');
    expect(localStorage.getItem('theme')).toBeNull(); // app theme is left alone

    fireEvent.click(btn); // light -> dark
    expect(root()).toHaveClass('dark');
    expect(localStorage.getItem('landing-theme')).toBe('dark');
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
