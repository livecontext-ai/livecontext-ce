// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ThemeProvider, useTheme } from '../ThemeProvider';

type MediaListener = (event: MediaQueryListEvent) => void;

let prefersDark = false;
let listeners: Set<MediaListener>;

function installMatchMediaMock() {
  listeners = new Set();
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: prefersDark,
      media: query,
      onchange: null,
      addEventListener: (_event: 'change', listener: MediaListener) => listeners.add(listener),
      removeEventListener: (_event: 'change', listener: MediaListener) => listeners.delete(listener),
      dispatchEvent: () => true,
    })),
  });
}

function setSystemDark(nextValue: boolean) {
  prefersDark = nextValue;
  const event = { matches: nextValue, media: '(prefers-color-scheme: dark)' } as MediaQueryListEvent;
  listeners.forEach((listener) => listener(event));
}

function ThemeProbe() {
  const { theme, themePreference, setTheme } = useTheme();

  return (
    <>
      <span data-testid="theme">{theme}</span>
      <span data-testid="theme-preference">{themePreference}</span>
      <button type="button" onClick={() => setTheme('auto')}>auto</button>
      <button type="button" onClick={() => setTheme('light')}>light</button>
    </>
  );
}

describe('ThemeProvider', () => {
  beforeEach(() => {
    prefersDark = false;
    installMatchMediaMock();
    localStorage.clear();
    document.documentElement.className = '';
    document.body.className = '';
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('defaults to auto and follows system theme changes', async () => {
    prefersDark = true;

    render(
      <ThemeProvider>
        <ThemeProbe />
      </ThemeProvider>,
    );

    await waitFor(() => {
      expect(screen.getByTestId('theme-preference')).toHaveTextContent('auto');
    });
    expect(screen.getByTestId('theme')).toHaveTextContent('dark');
    expect(document.documentElement).toHaveClass('dark');
    expect(localStorage.getItem('theme')).toBe('auto');

    act(() => {
      setSystemDark(false);
    });

    expect(screen.getByTestId('theme')).toHaveTextContent('light');
    expect(document.documentElement).toHaveClass('light');
    expect(localStorage.getItem('theme')).toBe('auto');
  });

  it('keeps explicit light preference when system theme changes', async () => {
    localStorage.setItem('theme', 'light');
    prefersDark = true;

    render(
      <ThemeProvider>
        <ThemeProbe />
      </ThemeProvider>,
    );

    await waitFor(() => {
      expect(screen.getByTestId('theme-preference')).toHaveTextContent('light');
    });
    expect(screen.getByTestId('theme')).toHaveTextContent('light');

    act(() => {
      setSystemDark(false);
    });

    expect(screen.getByTestId('theme')).toHaveTextContent('light');
    expect(localStorage.getItem('theme')).toBe('light');
  });
});
