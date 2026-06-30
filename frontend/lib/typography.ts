// Centralized typography tokens used across the frontend.
// Each token maps to a Tailwind utility string so components stay consistent.
export const TYPOGRAPHY = {
  // Display titles
  heroTitle: 'text-5xl md:text-6xl font-bold text-theme-primary leading-tight tracking-tight',
  pageTitle: 'text-4xl md:text-5xl font-semibold text-theme-primary tracking-tight',
  sectionHeading: 'text-3xl md:text-4xl font-semibold text-theme-primary',
  subheading: 'text-2xl font-semibold text-theme-primary',

  // Main titles
  title: 'text-2xl font-semibold text-theme-primary',

  // Section headers
  sectionTitle: 'text-base font-medium text-theme-primary',

  // Form labels and descriptions
  label: 'text-sm font-medium text-theme-primary',
  description: 'text-sm text-theme-muted',

  // Form values (normal weight, not bold)
  value: 'text-sm text-theme-primary',
  valueEmphasized: 'text-sm font-medium text-theme-primary',
  valueNormal: 'text-sm text-theme-primary',

  // Code and technical data (distinct from regular values)
  code: 'text-xs font-mono text-theme-primary break-words',

  // Error and success messages
  error: 'text-sm text-red-600 dark:text-red-400',
  success: 'text-sm text-green-600 dark:text-green-400',
  warning: 'text-sm text-yellow-600 dark:text-yellow-400',

  // Small helper text
  helper: 'text-xs text-theme-muted',
} as const;

export type TypographyToken = keyof typeof TYPOGRAPHY;
