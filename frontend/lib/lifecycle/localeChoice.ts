import { unifiedApiService } from '@/lib/api/unified-api-service';

/**
 * Tell the backend the person explicitly picked this language in the app.
 *
 * <p>The stored locale is what every message the backend writes is written in: the lifecycle
 * emails (cloud) AND the notification emails, which auth-service composes from
 * {@code users.locale} in BOTH editions. That is why this is no longer cloud-only: gating it on
 * the edition left a CE user who picked French reading English alerts, with no way to fix it -
 * the cookie that switches the UI never reaches the server.
 *
 * <p>It is also the only copy of the choice that follows them to another device; the
 * `NEXT_LOCALE` cookie stays on the one that set it.
 *
 * <p>Fire-and-forget: the caller switches the language immediately and never awaits this.
 * {@code reportExplicitLocale} swallows its own failure, which is where that belongs - a second
 * `.catch` here would add a path no test can tell apart from its absence, and would itself break
 * the language switch the day the service is stubbed with something that is not a promise. An
 * explicit pick is never overwritten by the implicit report of a later session, so the only cost
 * of a lost call is a stored value that lags until the next pick.
 */
export function reportExplicitLocaleChoice(locale: string): void {
  void unifiedApiService.reportExplicitLocale(locale);
}
