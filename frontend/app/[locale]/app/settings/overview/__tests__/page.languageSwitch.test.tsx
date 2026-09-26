/**
 * @vitest-environment jsdom
 *
 * The settings language select is one of the two places a person PICKS a language. The pick
 * must reach the backend as an explicit choice (reportExplicitLocaleChoice), next to the
 * cookie and the locale navigation, or the lifecycle emails keep the language the app merely
 * displayed. The reporter's own contract (best-effort, never awaited) is covered by
 * lib/lifecycle/__tests__/localeChoice.test.ts; this proves the call site.
 */
import '@testing-library/jest-dom/vitest';
import * as React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

const reportExplicitLocaleChoice = vi.hoisted(() => vi.fn());
vi.mock('@/lib/lifecycle/localeChoice', () => ({ reportExplicitLocaleChoice }));

const push = vi.hoisted(() => vi.fn());
vi.mock('@/i18n/navigation', () => ({
  Link: ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a>,
  useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/app/settings/overview',
}));
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams('tab=preferences'),
}));
vi.mock('next-intl', () => ({
  useLocale: () => 'en',
  useTranslations: () => (key: string) => key,
}));

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({
    user: { sub: 'u1', email: 'ada@example.com', name: 'Ada', created_at: '2026-01-01T00:00:00Z' },
    isAuthenticated: true,
    isAuthChecking: false,
  }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ loginWithRedirect: vi.fn(), logout: vi.fn() }),
}));
vi.mock('@/hooks/useUserProfile', () => ({
  useUserProfile: () => ({
    profile: { id: 1, email: 'ada@example.com', displayName: 'Ada' },
    isLoading: false,
    updateUserProfile: vi.fn(),
    fetchUserProfile: vi.fn(),
  }),
}));
vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useSubscription: () => ({ subscription: null, isLoading: false, forceLoadSubscription: vi.fn() }),
}));
vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ themePreference: 'auto', setTheme: vi.fn() }),
}));
vi.mock('@/contexts/SidePanelLayoutContext', () => ({
  useSidePanelLayoutSafe: () => ({
    defaultPosition: 'right', setDefaultPosition: vi.fn(), bottomMode: 'content', setBottomMode: vi.fn(),
  }),
}));
vi.mock('@/contexts/WorkflowLayoutDirectionContext', () => ({
  useWorkflowLayoutDirection: () => ({ defaultDirection: 'LR', setDirection: vi.fn() }),
}));
vi.mock('@/contexts/InspectorDockContext', () => ({
  useInspectorDock: () => ({ dock: 'right', setDock: vi.fn() }),
}));
vi.mock('@/contexts/InspectorOpenModeContext', () => ({
  useInspectorOpenMode: () => ({ openMode: 'click', setOpenMode: vi.fn() }),
}));
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: {
    getDisplayNameStatus: vi.fn(() => Promise.resolve({ canChange: true })),
    checkDisplayName: vi.fn(() => Promise.resolve({ available: true })),
  },
}));
vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_CLOUD: true }));

// Heavy neighbours of the preferences tab, irrelevant here.
vi.mock('@/components/skeletons', () => ({ OverviewPageSkeleton: () => null }));
vi.mock('@/components/billing', () => ({ ScheduledChangeAlert: () => null }));
vi.mock('@/components/profile/PublicProfileSettingsCard', () => ({ PublicProfileSettingsCard: () => null }));
vi.mock('@/components/badges/BadgeCollection', () => ({ BadgeCollection: () => null }));
vi.mock('@/components/settings/NotificationPreferencesPanel', () => ({ NotificationPreferencesPanel: () => null }));
vi.mock('@/components/settings/MarketingConsentSetting', () => ({ MarketingConsentSetting: () => null }));
vi.mock('@/components/settings/AvatarGallery', () => ({ AvatarGallery: () => null }));
vi.mock('@/components/ui/info-popover', () => ({ InfoPopover: () => null }));

// Only the preferences tab is rendered, and each Select is a native <select> jsdom can drive.
vi.mock('@/components/ui/tabs', () => ({
  Tabs: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  TabsContent: ({ value, children }: { value: string; children: React.ReactNode }) =>
    value === 'preferences' ? <div>{children}</div> : null,
}));
vi.mock('@/components/ui/select', () => ({
  Select: ({ value, onValueChange, children }: {
    value?: string; onValueChange?: (v: string) => void; children: React.ReactNode;
  }) => (
    <select value={value} onChange={(e) => onValueChange?.(e.target.value)}>{children}</select>
  ),
  SelectTrigger: () => null,
  SelectValue: () => null,
  SelectContent: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  SelectItem: ({ value, children }: { value: string; children: React.ReactNode }) => (
    <option value={value}>{children}</option>
  ),
}));

import SettingsOverviewPage from '../page';

function languageSelect(): HTMLSelectElement {
  const option = screen.getByRole('option', { name: 'Français' });
  return option.closest('select') as HTMLSelectElement;
}

describe('settings overview: language select', () => {
  beforeEach(() => {
    document.cookie = 'NEXT_LOCALE=; path=/; max-age=0';
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('reports the pick as an explicit choice, sets the cookie and navigates to the new locale', () => {
    render(<SettingsOverviewPage />);

    fireEvent.change(languageSelect(), { target: { value: 'fr' } });

    expect(reportExplicitLocaleChoice).toHaveBeenCalledTimes(1);
    expect(reportExplicitLocaleChoice).toHaveBeenCalledWith('fr');
    expect(document.cookie).toContain('NEXT_LOCALE=fr');
    expect(push).toHaveBeenCalledWith('/app/settings/overview?tab=preferences', { locale: 'fr' });
  });

  it('reports nothing until a language is actually picked', () => {
    render(<SettingsOverviewPage />);

    expect(languageSelect()).toBeInTheDocument();
    expect(reportExplicitLocaleChoice).not.toHaveBeenCalled();
  });
});
