import { describe, expect, it } from 'vitest';
import {
  CE_COMPLETE_API_PATH,
  CE_STATUS_API_PATH,
  localeFromPath,
  needsOnboardingRedirect,
} from '../onboardingStatus';

describe('onboardingStatus guard helpers', () => {
  it('redirects CE users when onboarding is required or email is unverified', () => {
    expect(needsOnboardingRedirect({ needsOnboarding: true, emailVerified: true })).toBe(true);
    expect(needsOnboardingRedirect({ completed: true, emailVerified: false })).toBe(true);
    expect(needsOnboardingRedirect({ firstLogin: true, emailVerified: true })).toBe(true);
    expect(needsOnboardingRedirect({ profileIncomplete: true, emailVerified: true })).toBe(true);
  });

  it('allows CE users through only when onboarding is done and email is verified', () => {
    expect(needsOnboardingRedirect({
      needsOnboarding: false,
      firstLogin: false,
      profileIncomplete: false,
      completed: true,
      emailVerified: true,
    })).toBe(false);
  });

  it('preserves locale when redirecting app routes to onboarding', () => {
    expect(localeFromPath('/fr/app/chat')).toBe('fr');
    expect(localeFromPath('/en/app/settings')).toBe('en');
    expect(localeFromPath('/app/chat')).toBe('en');
  });

  it('uses the apiClient-relative CE status path', () => {
    expect(CE_STATUS_API_PATH).toBe('/ce/status');
    expect(CE_STATUS_API_PATH.startsWith('/api/')).toBe(false);
  });

  it('uses the apiClient-relative CE complete path', () => {
    expect(CE_COMPLETE_API_PATH).toBe('/ce/complete');
    expect(CE_COMPLETE_API_PATH.startsWith('/api/')).toBe(false);
  });
});
