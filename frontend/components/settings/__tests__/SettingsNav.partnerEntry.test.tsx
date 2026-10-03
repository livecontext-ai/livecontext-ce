/**
 * @vitest-environment jsdom
 *
 * The Partner program entry of the settings nav leads to a partner's own space: the nav shows it
 * to partners and applicants (the partner-space hook says so) and to nobody else. The visibility
 * rule is pinned in settingsNavVisibility.test; this pins that the nav actually passes the hook's
 * answer to it.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import * as React from 'react';

const partnerSpace = vi.hoisted(() => ({ has: false }));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/settings/overview' }));
vi.mock('@/contexts/NavigationGuardContext', () => ({ useSafeNavigate: () => vi.fn() }));
vi.mock('@/components/NavigationLoader', () => ({ triggerSidebarNavigation: vi.fn() }));
vi.mock('@/lib/api', () => ({ organizationApi: { getOrganizations: vi.fn().mockResolvedValue([]) } }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ hasRole: () => false }) }));
vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_MANAGED_CLOUD: true }));
vi.mock('@/hooks/useAppVersion', () => ({ useAppVersion: () => ({ version: null, isLoading: false, isError: false }) }));
vi.mock('@/hooks/usePartnerSpace', () => ({ useHasPartnerSpace: () => partnerSpace.has }));

import { SettingsNav } from '../SettingsNav';

describe('SettingsNav: the Partner program entry', () => {
  afterEach(() => {
    cleanup();
    partnerSpace.has = false;
  });

  it('is shown to a partner or an applicant', () => {
    partnerSpace.has = true;
    render(<SettingsNav />);

    expect(screen.getByText('Partner program')).toBeTruthy();
  });

  it('is hidden from a user with no partner space (never applied, or refused)', () => {
    render(<SettingsNav />);

    expect(screen.queryByText('Partner program')).toBeNull();
    // The rest of the nav is unaffected.
    expect(screen.getByText('Overview')).toBeTruthy();
  });
});
