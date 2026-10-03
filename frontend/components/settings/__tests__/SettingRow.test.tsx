// @vitest-environment jsdom
/**
 * The settings pages lay out from the width of their COLUMN, not of the window.
 *
 * <p>With the app sidebar open, a 768px window leaves the settings page ~270px once the settings
 * menu sat beside it at the `md` window breakpoint, and every row that went side by side at `sm`
 * broke its explanation one word per line. The layout now makes both levels containers and the
 * rows answer to them; the guard below keeps viewport breakpoints from coming back into the files
 * that were moved over.
 */
import React from 'react';
import fs from 'node:fs';
import path from 'node:path';
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { SettingRow } from '../SettingRow';

afterEach(cleanup);

describe('SettingRow', () => {
  it('goes side by side from its container width, never the window', () => {
    render(<SettingRow title="Theme" description="How the app looks" data-testid="row"><select aria-label="theme" /></SettingRow>);
    const row = screen.getByTestId('row');
    expect(row.className).toContain('@lg:flex-row');
    expect(row.className).not.toMatch(/(^|\s)(sm|md|lg):/);
    // h3: the section above it is titled by an h2 (PageHeader).
    expect(screen.getByRole('heading', { level: 3, name: 'Theme' })).toBeInTheDocument();
    expect(screen.getByText('How the app looks')).toBeInTheDocument();
  });

  it('regression - the control column has one width and does not shrink beside a long explanation', () => {
    render(<SettingRow title="Workflow layout" description={'x'.repeat(400)}><select aria-label="layout" /></SettingRow>);
    const control = screen.getByLabelText('layout').parentElement!;
    expect(control).toHaveAttribute('data-slot', 'setting-control');
    expect(control.className).toContain('shrink-0');
    expect(control.className).toContain('@lg:w-60');
    expect(control.className).toContain('w-full');
  });

  it('takes a node description for extra hint lines', () => {
    render(
      <SettingRow title="Credits" description={<><p>Running low</p><p>Applies everywhere</p></>}>
        <span>control</span>
      </SettingRow>,
    );
    expect(screen.getByText('Running low')).toBeInTheDocument();
    expect(screen.getByText('Applies everywhere')).toBeInTheDocument();
  });
});

describe('settings layout guard: container breakpoints only', () => {
  const root = path.resolve(__dirname, '../../..');
  const files = [
    'app/[locale]/app/settings/layout.tsx',
    'app/[locale]/app/settings/overview/page.tsx',
    'components/settings/SettingsNav.tsx',
    'components/settings/SettingRow.tsx',
    'components/settings/TimeZonePreferenceRow.tsx',
    'components/settings/NotificationPreferencesPanel.tsx',
    'components/badges/BadgeCollection.tsx',
    'components/profile/PublicProfileSettingsCard.tsx',
    'components/settings/MarketingConsentSetting.tsx',
    'components/settings/TwoFactorSettingsCard.tsx',
  ];

  it.each(files)('%s lays out without a window breakpoint', (file) => {
    const source = fs.readFileSync(path.join(root, file), 'utf8')
      .split(/\r?\n/)
      .filter((line) => !line.includes('<DialogFooter'))
      .join('\n');
    // Any window breakpoint, not preceded by `@` (`@lg:` is the container variant asked for).
    // `sm:px-6` on the page gutter is the one kept on purpose: it is about the screen edge, not
    // the room the column gets. The delete dialog is a portal to <body>, outside any container.
    const allowed = new Set(['sm:px-6']);
    const breakpoints = (source.match(/(?<![@\w-])(?:sm|md|lg|xl|2xl):[\w-[\]./]+/g) ?? [])
      .filter((m) => !allowed.has(m));
    expect(breakpoints).toEqual([]);
  });
});
