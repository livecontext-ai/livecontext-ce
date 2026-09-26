import { IS_CE } from '@/lib/edition';
import { unifiedApiService } from '@/lib/api/unified-api-service';

/**
 * Tell the backend the person explicitly picked this language in the app (cloud only: the
 * stored locale drives which language the lifecycle e-mails are written in).
 *
 * <p>Fire-and-forget. The caller switches the language immediately and never awaits this;
 * the service method already swallows a failure.
 */
export function reportExplicitLocaleChoice(locale: string): void {
  if (IS_CE) return;
  void unifiedApiService.reportExplicitLocale(locale);
}
