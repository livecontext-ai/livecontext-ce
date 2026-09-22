'use client';

import { useModels } from '@/hooks/useModels';
import { getProviderDisplayName } from '@/lib/ai-providers/providerIcons';
import {
  buildModelNameIndex,
  resolveModelDisplayName,
  type ModelNameIndex,
} from '@/lib/ai-providers/modelDisplayName';

/**
 * Naming the models the Quota & Usage page charges you for.
 *
 * <p>The ledger stores the raw provider and model ids a call was made with, and
 * must keep storing them: they are what a charge can be reconciled against.
 * But an admin can rename any model in Settings > AI Providers, and every other
 * screen in the app then calls it by that name. This page was the one place a
 * spend line still read `anthropic / claude-sonnet-5` while the picker that
 * spent it said "Claude Sonnet 5". The name is therefore resolved HERE, at
 * render, from the catalogue the rest of the app already reads.
 *
 * <p>The raw pair is kept on `title`, for the rarer moment when a figure has to
 * be matched against a provider's own invoice. That is a POINTER affordance and
 * nothing else reaches it, which is the right weight for it: the id is a
 * reconciliation detail, and the page's own filters already let the same rows be
 * narrowed by model. If it ever needs to be reachable without a pointer, the
 * place for it is a second line in the cell, not a wider tooltip.
 */

/**
 * The catalogue's id-to-name index, or null while it is loading or unavailable.
 *
 * <p>One fetch, shared: `useModels` keeps a module-level cache with a 5-minute
 * TTL and de-duplicates in-flight requests, so calling this from both the
 * history table and the analytics filters costs one request for the page, and
 * usually none at all (chat and the builder have already warmed it).
 *
 * <p>It inherits that hook's documented gap, and it is worth naming here: the
 * catalogue is per-tenant, and a component mounting during the async auth
 * bootstrap can cache the SIGNED-OUT one for the page. A hard refresh straight
 * onto this page is that shape. The consequence is bounded - an admin's rename
 * is missing, so a row reads as the id or as the default name, which is what
 * this page did for every row before - but it is a real drift while it lasts,
 * and it closes on the next catalogue refresh rather than needing a fix here.
 *
 * <p>Rebuilt on every render, and deliberately NOT wrapped in a `useMemo`:
 * `useModels` re-flattens its providers each time, so it hands back a new array
 * identity on every render and a memo keyed on it would recompute anyway while
 * reading as if it did not. Both the memo and the build are O(catalogue) over a
 * few hundred rows, and this page re-renders on data, not on keystrokes.
 */
export function useModelNameIndex(): ModelNameIndex | null {
  const { models } = useModels();
  return models.length > 0 ? buildModelNameIndex(models) : null;
}

/**
 * How a usage row's model should read: the admin's name for it when the
 * catalogue has one, the raw id otherwise.
 */
export function modelLabelFor(
  provider: string | null | undefined,
  model: string | null | undefined,
  index: ModelNameIndex | null
): string | null {
  if (!model) return null;
  return resolveModelDisplayName(provider, model, index) ?? model;
}

/**
 * Labels for a LIST of stored provider keys, keyed by the key itself.
 *
 * <p>Naming one provider is `getProviderDisplayName`. Naming a whole filter list
 * has one extra problem, created by the same case-insensitive lookup that fixes
 * the single case: the ledger stores whatever the calling service sent, so
 * `anthropic` and `Anthropic` are two distinct options that now render the same
 * word. Two identical-looking rows that filter differently is worse than a raw
 * key, so where a name collides, every key that claims it keeps its raw spelling
 * and the reader can tell them apart.
 *
 * <p>Collisions are the rare case: the loop below runs over the distinct
 * providers of one workspace's ledger, which is a handful of rows.
 */
export function providerOptionLabels(keys: readonly string[]): Map<string, string> {
  const claims = new Map<string, string[]>();
  for (const key of keys) {
    const label = getProviderDisplayName(key);
    claims.set(label, [...(claims.get(label) ?? []), key]);
  }
  const labels = new Map<string, string>();
  for (const [label, claimants] of claims) {
    for (const key of claimants) {
      labels.set(key, claimants.length === 1 ? label : key);
    }
  }
  return labels;
}

/**
 * The "Provider / Model" cell of the usage history.
 *
 * <p>Renders whichever half exists, so a charge with no model (a top-up, a
 * non-LLM row) still reads correctly instead of showing a stray separator.
 */
export function ProviderModelCell({
  provider,
  model,
  index,
}: {
  provider: string | null | undefined;
  model: string | null | undefined;
  index: ModelNameIndex | null;
}) {
  const providerLabel = provider ? getProviderDisplayName(provider) : '';
  const modelLabel = modelLabelFor(provider, model, index) ?? '';
  const shown = [providerLabel, modelLabel].filter(Boolean).join(' / ');
  if (!shown) return <>-</>;

  // The stored identifiers, for reconciling a line against a provider's bill.
  // Only attached when a label actually replaced one of them, so the tooltip
  // means "this is what was stored" rather than repeating the visible text.
  const raw = [provider, model].filter(Boolean).join(' / ');
  return <span title={raw !== shown ? raw : undefined}>{shown}</span>;
}
