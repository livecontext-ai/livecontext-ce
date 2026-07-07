/**
 * CE first-run detection, shared by the login page (redirect to /register) and
 * the register page (admin-setup copy).
 *
 * A CE install is "first-run" when NO account exists yet AND self-registration
 * is open (the V121 default on a virgin install). Both conditions must be
 * explicit booleans from GET /api/ce/status: a missing field (older backend),
 * a failed fetch, or a closed registration must all read as NOT first-run, so
 * an existing install can never bounce real users away from the sign-in form.
 */
export interface CeFirstRunStatus {
  hasUsers?: boolean;
  registrationOpen?: boolean;
}

export function isCeFirstRun(status: CeFirstRunStatus | null | undefined): boolean {
  return !!status && status.hasUsers === false && status.registrationOpen === true;
}
