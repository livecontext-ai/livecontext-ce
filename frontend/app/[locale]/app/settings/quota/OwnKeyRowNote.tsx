'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { isOwnKeyEntry } from './ownKeyHistory';
import type { CreditHistoryEntry } from '@/lib/api/services/quota-api.service';

/**
 * Beside the amount of a history row billed on the tenant's own key: the badge, and nothing
 * else. Nothing at all for a platform-route row.
 *
 * <p><b>One line, one number.</b> The charge is the credits already in the cell; this only says
 * whose key produced them. What it replaced was a second line under the amount carrying the
 * provider-side cost in dollars - a different currency, a different biller, and an estimate
 * rather than a charge. Read quickly down a column of charges it looked like a second amount
 * owed, and it doubled the height of every own-key row for a figure the reader can only
 * reconcile on their provider's own invoice.
 *
 * <p>Its own module rather than a local of the page so that this stays testable: the rule it
 * encodes (badge yes, dollars never) is the whole point of the change, and the page it lives
 * on is a thousand lines of wallet, filters and analytics.
 */
export function OwnKeyRowNote({ entry }: { entry: CreditHistoryEntry }) {
  const t = useTranslations('quota');
  if (!isOwnKeyEntry(entry)) return null;
  return (
    <span
      className="ml-1.5 inline-block align-middle rounded px-1 font-normal text-xs bg-theme-secondary text-theme-secondary"
      data-testid="own-key-row-badge"
    >
      {t('history.ownKeyBadge')}
    </span>
  );
}
