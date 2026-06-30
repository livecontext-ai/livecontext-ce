'use client';

import React, { createContext, useContext, useEffect, useState, useSyncExternalStore } from 'react';

export type Theme = 'dark' | 'light';
export type ThemePreference = Theme | 'auto';

interface ThemeContextType {
  theme: Theme;
  themePreference: ThemePreference;
  toggleTheme: () => void;
  setTheme: (theme: ThemePreference) => void;
}

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

export function useOptionalTheme() {
  return useContext(ThemeContext);
}

export function useTheme() {
  const context = useOptionalTheme();
  if (context === undefined) {
    throw new Error('useTheme must be used within a ThemeProvider');
  }
  return context;
}

interface ThemeProviderProps {
  children: React.ReactNode;
}

function isThemePreference(value: string | null): value is ThemePreference {
  return value === 'dark' || value === 'light' || value === 'auto';
}

function getSystemTheme(): Theme {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return 'light';
  }

  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function getStoredThemePreference(): ThemePreference {
  if (typeof window === 'undefined') {
    return 'auto';
  }

  const savedTheme = localStorage.getItem('theme');
  return isThemePreference(savedTheme) ? savedTheme : 'auto';
}

function applyThemeClasses(theme: Theme) {
  const root = document.documentElement;
  const body = document.body;

  root.classList.remove('light', 'dark');
  body.classList.remove('light', 'dark');

  root.classList.add(theme);
  body.classList.add(theme);
}

const subscribeToClientReady = () => () => {};
const getClientSnapshot = () => true;
const getServerSnapshot = () => false;

function useIsClient() {
  return useSyncExternalStore(subscribeToClientReady, getClientSnapshot, getServerSnapshot);
}

export function ThemeProvider({ children }: ThemeProviderProps) {
  const isClient = useIsClient();
  const [themePreference, setThemePreference] = useState<ThemePreference>(getStoredThemePreference);
  const [systemTheme, setSystemTheme] = useState<Theme>(getSystemTheme);
  const theme: Theme = themePreference === 'auto' ? systemTheme : themePreference;

  // Apply the resolved theme to the document and persist the selected preference.
  useEffect(() => {
    if (!isClient) return;

    applyThemeClasses(theme);
    localStorage.setItem('theme', themePreference);
  }, [theme, themePreference, isClient]);

  // Keep auto mode synchronized with the OS preference.
  useEffect(() => {
    if (!isClient) return;
    if (themePreference !== 'auto') return;

    const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
    const handleSystemThemeChange = (event: MediaQueryListEvent) => {
      setSystemTheme(event.matches ? 'dark' : 'light');
    };

    if (typeof mediaQuery.addEventListener === 'function') {
      mediaQuery.addEventListener('change', handleSystemThemeChange);
      return () => {
        mediaQuery.removeEventListener('change', handleSystemThemeChange);
      };
    }

    mediaQuery.addListener(handleSystemThemeChange);
    return () => {
      mediaQuery.removeListener(handleSystemThemeChange);
    };
  }, [themePreference, isClient]);

  const toggleTheme = () => {
    setThemePreference(theme === 'dark' ? 'light' : 'dark');
  };

  const setTheme = (newTheme: ThemePreference) => {
    if (newTheme === 'auto') {
      setSystemTheme(getSystemTheme());
    }
    setThemePreference(newTheme);
  };

  // Avoid rendering before the persisted preference has been resolved.
  if (!isClient) {
    return null;
  }

  return (
    <ThemeContext.Provider value={{ theme, themePreference, toggleTheme, setTheme }}>
      {children}
    </ThemeContext.Provider>
  );
}
