export interface OnboardingStatus {
  needsOnboarding?: boolean;
  firstLogin?: boolean;
  profileIncomplete?: boolean;
  completed?: boolean;
  skipped?: boolean;
  emailVerified?: boolean;
  // Personalization choices (returned by /api/onboarding/status, see
  // OnboardingResponse) - consumed by the onboarding "suggested apps" modal.
  profession?: string;
  interests?: string[];
  useCases?: string[];
  experienceLevel?: string;
}

export const CE_COMPLETE_API_PATH = '/ce/complete';
export const CE_STATUS_API_PATH = '/ce/status';

export function localeFromPath(pathname: string | null): string {
  const localeMatch = pathname?.match(/^\/([a-z]{2})\//);
  return localeMatch ? localeMatch[1] : 'en';
}

export function needsOnboardingRedirect(status: OnboardingStatus | undefined): boolean {
  if (!status) return false;
  return Boolean(
    status.needsOnboarding
    || status.firstLogin
    || status.profileIncomplete
    || status.emailVerified === false
  );
}
