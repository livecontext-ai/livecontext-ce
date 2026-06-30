/**
 * @vitest-environment jsdom
 *
 * Tests for {@code AboutMenuVersion}: renders the version it is given, an amber
 * update dot only for a self-hosted install that is behind, and nothing until the
 * version is known. (Edition gating is done upstream by the CE-gated useAppVersion
 * hook, so this component is given a null version in cloud and renders nothing.)
 */
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import * as React from 'react';
import AboutMenuVersion from '../AboutMenuVersion';
import type { AppVersionInfo } from '@/hooks/useAppVersion';

function info(overrides: Partial<AppVersionInfo> = {}): AppVersionInfo {
  return {
    version: '0.1.0-SNAPSHOT',
    gitSha: null,
    buildTime: null,
    edition: 'ce',
    selfHosted: true,
    managedCloud: false,
    updateAvailable: false,
    latestVersion: null,
    releaseUrl: null,
    securityFix: false,
    checkedAt: null,
    ...overrides,
  };
}

/** The amber update dot is a presentational span; query it structurally. */
function amberDot(container: HTMLElement): Element | null {
  return container.querySelector('span.bg-amber-500');
}

describe('AboutMenuVersion', () => {
  it('renders nothing until the version is known', () => {
    const { container } = render(<AboutMenuVersion version={null} />);
    expect(container.firstChild).toBeNull();
  });

  it('shows the version with an amber dot when a self-hosted install is behind', () => {
    const { container } = render(<AboutMenuVersion version={info({ updateAvailable: true })} />);
    expect(screen.getByText('0.1.0-SNAPSHOT')).toBeTruthy();
    expect(amberDot(container)).not.toBeNull();
  });

  it('shows the version without a dot when self-hosted and up to date', () => {
    const { container } = render(<AboutMenuVersion version={info({ updateAvailable: false })} />);
    expect(screen.getByText('0.1.0-SNAPSHOT')).toBeTruthy();
    expect(amberDot(container)).toBeNull();
  });

  it('shows the version without a dot for a non-self-hosted payload (no update affordance)', () => {
    const { container } = render(
      <AboutMenuVersion
        version={info({ version: '1.4.2', edition: 'cloud', selfHosted: false, managedCloud: true, updateAvailable: false })}
      />,
    );
    expect(screen.getByText('1.4.2')).toBeTruthy();
    expect(amberDot(container)).toBeNull();
  });
});
