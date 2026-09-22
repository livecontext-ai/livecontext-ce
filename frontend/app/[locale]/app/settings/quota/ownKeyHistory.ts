import type { CreditHistoryEntry } from '@/lib/api/services/quota-api.service';

/** The route value the ledger stamps on a turn that ran on the tenant's own provider key. */
export const OWN_KEY_ROUTE = 'OWN_KEY';

/**
 * Whether a history row was billed on the tenant's own key (flat fee, provider bills the tokens).
 *
 * <p>All the row says about it, on purpose. It used to carry a second line with the provider-side
 * cost in dollars, which put two currencies in one cell - credits, then an estimate of a bill this
 * page does not hold and cannot reconcile - and doubled the height of every own-key row. What a
 * reader needs here is the charge, which is the credits already in the cell, and whose key ran,
 * which is the badge. The provider's own dollar figure lives on the provider's invoice.
 */
export function isOwnKeyEntry(entry: Pick<CreditHistoryEntry, 'keyRoute'>): boolean {
  return entry.keyRoute === OWN_KEY_ROUTE;
}
