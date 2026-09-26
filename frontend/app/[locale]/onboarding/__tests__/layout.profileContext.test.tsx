/**
 * @vitest-environment jsdom
 *
 * The onboarding route reports the lifecycle context too (cloud only): the welcome email
 * goes out minutes after signup, often before the person reaches /app, so the locale they
 * onboard in must already be on their contact. The signed-in / auth-ready guards live in
 * useProfileContextReport and are covered by its own test.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import * as React from 'react';

const edition = vi.hoisted(() => ({ IS_CE: false }));
vi.mock('@/lib/edition', () => edition);

vi.mock('@/components/lifecycle/ProfileContextReporter', () => ({
  default: () => <div data-testid="profile-context-reporter" />,
}));

async function renderLayout() {
  vi.resetModules();
  const { default: OnboardingLayout } = await import('../layout');
  render(
    <OnboardingLayout>
      <p>onboarding page</p>
    </OnboardingLayout>,
  );
}

describe('onboarding layout', () => {
  beforeEach(() => {
    edition.IS_CE = false;
  });

  it('mounts the profile-context reporter in the cloud edition, next to the page', async () => {
    await renderLayout();

    expect(screen.getByTestId('profile-context-reporter')).toBeTruthy();
    expect(screen.getByText('onboarding page')).toBeTruthy();
  });

  it('never mounts it in CE (self-hosted sends no lifecycle context)', async () => {
    edition.IS_CE = true;

    await renderLayout();

    expect(screen.queryByTestId('profile-context-reporter')).toBeNull();
    expect(screen.getByText('onboarding page')).toBeTruthy();
  });
});
