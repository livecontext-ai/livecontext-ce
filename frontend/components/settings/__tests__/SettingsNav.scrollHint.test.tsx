/**
 * @vitest-environment jsdom
 *
 * Tests for the SettingsNav mobile scroll affordance: on narrow screens the
 * nav is a horizontal strip with a hidden scrollbar, so chevron hints must
 * appear when (and only when) more items exist off-screen, and clicking a
 * chevron must nudge the strip. jsdom has no layout, so scroll metrics are
 * stubbed per test via Object.defineProperty on the nav element.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import * as React from 'react';
import { SettingsNav } from '../SettingsNav';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) =>
    ({ scrollLeft: 'Scroll left', scrollRight: 'Scroll right' }[key] ?? key),
  useLocale: () => 'en',
}));

vi.mock('@/i18n/navigation', () => ({
  usePathname: () => '/app/settings/overview',
}));

vi.mock('@/contexts/NavigationGuardContext', () => ({
  useSafeNavigate: () => vi.fn(),
}));

vi.mock('@/components/NavigationLoader', () => ({
  triggerSidebarNavigation: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  organizationApi: { getOrganizations: vi.fn().mockResolvedValue([]) },
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ hasRole: () => false }),
}));

vi.mock('@/lib/edition', () => ({ IS_CE: false }));

vi.mock('@/hooks/useAppVersion', () => ({
  useAppVersion: () => ({ version: null, isLoading: false, isError: false }),
}));

/** Stub the nav element's scroll metrics (jsdom reports 0 for everything). */
function stubMetrics(nav: HTMLElement, { scrollLeft, clientWidth, scrollWidth }:
    { scrollLeft: number; clientWidth: number; scrollWidth: number }) {
  Object.defineProperty(nav, 'scrollLeft', { value: scrollLeft, writable: true, configurable: true });
  Object.defineProperty(nav, 'clientWidth', { value: clientWidth, configurable: true });
  Object.defineProperty(nav, 'scrollWidth', { value: scrollWidth, configurable: true });
}

function getNav(): HTMLElement {
  const nav = document.querySelector('nav');
  if (!nav) throw new Error('nav not rendered');
  return nav as HTMLElement;
}

describe('SettingsNav mobile scroll hint', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows no chevron when the strip does not overflow', async () => {
    render(<SettingsNav />);
    const nav = getNav();
    stubMetrics(nav, { scrollLeft: 0, clientWidth: 400, scrollWidth: 400 });
    await act(async () => { fireEvent.scroll(nav); });
    expect(screen.queryByLabelText('Scroll right')).toBeNull();
    expect(screen.queryByLabelText('Scroll left')).toBeNull();
  });

  it('shows only the right chevron when content overflows and the strip is at the start', async () => {
    render(<SettingsNav />);
    const nav = getNav();
    stubMetrics(nav, { scrollLeft: 0, clientWidth: 400, scrollWidth: 900 });
    await act(async () => { fireEvent.scroll(nav); });
    expect(screen.getByLabelText('Scroll right')).toBeTruthy();
    expect(screen.queryByLabelText('Scroll left')).toBeNull();
  });

  it('shows both chevrons mid-scroll and only the left one at the end', async () => {
    render(<SettingsNav />);
    const nav = getNav();
    stubMetrics(nav, { scrollLeft: 200, clientWidth: 400, scrollWidth: 900 });
    await act(async () => { fireEvent.scroll(nav); });
    expect(screen.getByLabelText('Scroll right')).toBeTruthy();
    expect(screen.getByLabelText('Scroll left')).toBeTruthy();

    stubMetrics(nav, { scrollLeft: 500, clientWidth: 400, scrollWidth: 900 });
    await act(async () => { fireEvent.scroll(nav); });
    expect(screen.queryByLabelText('Scroll right')).toBeNull();
    expect(screen.getByLabelText('Scroll left')).toBeTruthy();
  });

  it('clicking the right chevron nudges the strip forward (works without touch input)', async () => {
    render(<SettingsNav />);
    const nav = getNav();
    stubMetrics(nav, { scrollLeft: 0, clientWidth: 400, scrollWidth: 900 });
    const scrollBy = vi.fn();
    Object.defineProperty(nav, 'scrollBy', { value: scrollBy, configurable: true });
    await act(async () => { fireEvent.scroll(nav); });

    fireEvent.click(screen.getByLabelText('Scroll right'));
    expect(scrollBy).toHaveBeenCalledWith({ left: 240, behavior: 'smooth' });
  });
});
