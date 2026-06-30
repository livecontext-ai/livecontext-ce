import { useOptionalTheme } from '../components/ThemeProvider';

const DEFAULT_THEME_CONTEXT = {
  theme: 'light' as const,
  themePreference: 'light' as const,
  toggleTheme: () => {},
  setTheme: () => {}
};

/**
 * Safely reads the theme context.
 * Returns a default theme when ThemeProvider is not available.
 */
export function useThemeSafely() {
  return useOptionalTheme() ?? DEFAULT_THEME_CONTEXT;
}

/**
 * Safely reads only the resolved theme value.
 */
export function useThemeValue(): 'light' | 'dark' {
  const { theme } = useThemeSafely();
  return theme;
}
