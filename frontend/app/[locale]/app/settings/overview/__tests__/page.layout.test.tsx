/**
 * @vitest-environment jsdom
 *
 * The overview's layout follows the room it is given, not the window.
 *
 * <p>Its tab labels used to switch on at the `sm` WINDOW breakpoint, but the app sidebar and the
 * settings menu take their share of the window: from a tablet to a 1280px screen the labelled bar
 * was wider than its column and "Notifications" and "Advanced" sat off screen behind a hidden
 * scrollbar. The bar now measures hidden copies against its own width: every label if they fit,
 * else only the active tab's, else icons alone (each tab keeping its name for a screen reader).
 */
import '@testing-library/jest-dom/vitest';
import * as React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, within } from '@testing-library/react';

vi.mock('@/lib/lifecycle/localeChoice', () => ({ reportExplicitLocaleChoice: vi.fn() }));

const push = vi.hoisted(() => vi.fn());
vi.mock('@/i18n/navigation', () => ({
  Link: ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a>,
  useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/app/settings/overview',
}));
// A router backed by a live address: the open tab is written to it, and a static stand-in
// would keep answering ?tab=preferences after the tab has moved.
vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';
const PAGE = '/en/app/settings/overview';
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
vi.mock('@/components/settings/TwoFactorSettingsCard', () => ({ TwoFactorSettingsCard: () => null }));
vi.mock('@/components/ui/info-popover', () => ({ InfoPopover: () => null }));

// Every panel is rendered, and each Select is a native <select> jsdom can drive.
vi.mock('@/components/ui/tabs', () => ({
  Tabs: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  TabsContent: ({ value, children }: { value: string; children: React.ReactNode }) =>
    <div data-panel={value}>{children}</div>,
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

// jsdom lays nothing out: the bar reports the width the test gives it, each hidden copy the
// width its form would need.
let barWidth = 1000;
const ALL_LABELS_WIDTH = 700;
const ACTIVE_LABEL_WIDTH = 370;
let resize: (() => void) | null = null;

beforeEach(() => {
  // Every test opens on ?tab=preferences unless it says otherwise.
  fakeFolderRouter.reset(PAGE);
  fakeFolderRouter.navigate(`${PAGE}?tab=preferences`, 'replace');
  barWidth = 1000;
  resize = null;
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function (this: HTMLElement) {
    return this.dataset.testid === 'settings-tab-bar' ? barWidth : 0;
  });
  const realRect = HTMLElement.prototype.getBoundingClientRect;
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    const width = this.dataset.testid === 'settings-tab-bar-measure-all' ? ALL_LABELS_WIDTH
      : this.dataset.testid === 'settings-tab-bar-measure-active' ? ACTIVE_LABEL_WIDTH
        : null;
    if (width !== null) {
      return { width, height: 48, left: 0, top: 0, right: width, bottom: 48, x: 0, y: 0 } as DOMRect;
    }
    return realRect.call(this);
  });
  const observers: Array<() => void> = [];
  resize = () => observers.forEach((cb) => cb());
  vi.stubGlobal('ResizeObserver', class {
    constructor(cb: () => void) { observers.push(cb); }
    observe() {}
    disconnect() {}
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

const bar = () => screen.getByTestId('settings-tab-bar');
const tabButton = (label: string) => within(bar()).getByRole('button', { name: label });

describe('settings overview: the open tab is kept in the address', () => {
  const hidden = (label: string) => within(tabButton(label)).getByText(label).className.includes('sr-only');

  it('picking a tab writes it to the address as a step Back can undo', () => {
    render(<SettingsOverviewPage />);

    act(() => { tabButton('tabs.advanced').click(); });

    expect(fakeFolderRouter.search()).toBe('tab=advanced');
    expect(fakeFolderRouter.navigations.at(-1)?.method).toBe('push');
  });

  it('the default tab is spelled by absence, so the address stays clean', () => {
    render(<SettingsOverviewPage />);

    act(() => { tabButton('tabs.profile').click(); });

    expect(fakeFolderRouter.search()).toBe('');
  });

  it('regression - a tab the page does not have falls back to the profile tab', () => {
    fakeFolderRouter.navigate(`${PAGE}?tab=gone`, 'replace');
    barWidth = 500; // the active-label form: only the open tab shows its label
    render(<SettingsOverviewPage />);

    expect(hidden('tabs.profile')).toBe(false);
  });
});

describe('settings overview: tab bar', () => {
  const ALL_TABS = ['tabs.profile', 'tabs.security', 'tabs.trophies', 'tabs.preferences', 'tabs.notifications', 'tabs.advanced'];
  const hidden = (label: string) => within(tabButton(label)).getByText(label).className.includes('sr-only');

  it('shows every label, with no redundant tooltip, when the labelled bar fits its column', () => {
    render(<SettingsOverviewPage />);
    expect(bar()).toHaveAttribute('data-labels', 'all');
    for (const label of ALL_TABS) {
      expect(hidden(label)).toBe(false);
      expect(tabButton(label)).not.toHaveAttribute('title');
    }
  });

  it('regression - a column narrower than the labels keeps only the active tab labelled', () => {
    barWidth = 500;
    render(<SettingsOverviewPage />);
    expect(bar()).toHaveAttribute('data-labels', 'active');
    // ?tab=preferences is the active tab (see the search params mock).
    expect(hidden('tabs.preferences')).toBe(false);
    // The others keep their name for assistive tech and as a tooltip, only hidden from sight.
    for (const label of ALL_TABS.filter((l) => l !== 'tabs.preferences')) {
      expect(hidden(label)).toBe(true);
      expect(tabButton(label)).toHaveAttribute('title', label);
    }
  });

  it('in the active-label form, the label follows the tab that gets picked', () => {
    barWidth = 500;
    render(<SettingsOverviewPage />);
    expect(hidden('tabs.profile')).toBe(true);

    act(() => { tabButton('tabs.profile').click(); });

    expect(hidden('tabs.profile')).toBe(false);
    expect(tabButton('tabs.profile')).not.toHaveAttribute('title');
    expect(hidden('tabs.preferences')).toBe(true);
    expect(tabButton('tabs.preferences')).toHaveAttribute('title', 'tabs.preferences');
  });

  it('regression - a phone column too narrow even for one label shows icons only, every tab still named', () => {
    barWidth = 351; // a 375px phone minus the page gutters
    render(<SettingsOverviewPage />);
    expect(bar()).toHaveAttribute('data-labels', 'none');
    for (const label of ALL_TABS) {
      expect(hidden(label)).toBe(true);
      expect(tabButton(label)).toHaveAttribute('title', label);
    }
  });

  it('follows its column when it is resized, with no window breakpoint involved', () => {
    render(<SettingsOverviewPage />);
    expect(bar()).toHaveAttribute('data-labels', 'all');

    barWidth = 500;
    act(() => resize?.());
    expect(bar()).toHaveAttribute('data-labels', 'active');

    barWidth = 300;
    act(() => resize?.());
    expect(bar()).toHaveAttribute('data-labels', 'none');

    barWidth = 900;
    act(() => resize?.());
    expect(bar()).toHaveAttribute('data-labels', 'all');
  });
});

describe('settings overview: rows and headers', () => {
  it('every preference is a SettingRow whose control column keeps one width', () => {
    render(<SettingsOverviewPage />);
    const panel = document.querySelector('[data-panel="preferences"]') as HTMLElement;
    const controls = panel.querySelectorAll('[data-slot="setting-control"]');
    // language, time zone, theme, panel position, workflow layout, inspector dock, node click,
    // bottom panel style, chat defaults: a row back on a hand-written layout drops out of this.
    expect(controls).toHaveLength(9);
    for (const control of controls) {
      expect(control.className).toContain('@lg:w-60');
      expect(control.className).toContain('shrink-0');
    }
  });

  it('the Advanced tab opens on a header like every other tab', () => {
    render(<SettingsOverviewPage />);
    const panel = document.querySelector('[data-panel="advanced"]') as HTMLElement;
    expect(within(panel).getByRole('heading', { level: 2, name: 'tabs.advanced' })).toBeInTheDocument();
    expect(within(panel).getByText('advanced.description')).toBeInTheDocument();
  });
});
