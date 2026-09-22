/**
 * The name a model is KNOWN BY in this install, resolved from the id a usage row
 * carries.
 *
 * <p><b>Why this exists.</b> A cloud admin can rename any catalogue row in
 * Settings > AI Providers, and that name (`ModelConfigOverrideEntity.displayName`)
 * is what the model pickers, the chat selector and every agent screen show. The
 * usage ledger does not store it: a credit row keeps the raw id the provider was
 * called with, which is the right thing to keep (it is the only identifier that
 * survives a rename and is comparable with an invoice). The consequence was a
 * Quota & Usage page listing every charge against an id nobody recognises,
 * beside surfaces naming the same model something else entirely.
 *
 * <p>So the raw id stays the stored fact and the name is resolved at render,
 * from the catalogue the rest of the app already reads.
 *
 * <p><b>Keyed by provider AND id, with an id-only fallback.</b> Since the bridge
 * ids lost their `-cc` suffix, the same id exists under several providers
 * (`anthropic/claude-opus-4-7` and `claude-code/claude-opus-4-7`), each free to
 * carry its own name. A ledger row names both, so the exact pair is tried first;
 * the id-only entry then covers a row whose provider spelling has drifted (a
 * relayed CE row, a provider renamed upstream) rather than leaving it unnamed.
 * That fallback is only offered when the id is UNAMBIGUOUS across providers:
 * picking one of two names for an id that has two would be a coin toss shown as
 * a fact.
 */

/** The fields of a catalogue model this resolution needs, and no more. */
export interface NamedModel {
  id: string;
  name?: string;
  provider?: string;
}

export interface ModelNameIndex {
  /** `"<provider>:<id>"` (both lower-cased) to display name. */
  byProviderAndId: Map<string, string>;
  /** Lower-cased id to display name, only where exactly one name claims it. */
  byId: Map<string, string>;
}

/** Lower-cased key for the pair, so a provider's casing cannot miss a match. */
function pairKey(provider: string | null | undefined, id: string): string {
  return `${(provider ?? '').toLowerCase()}:${id.toLowerCase()}`;
}

/**
 * Index a catalogue for lookup. A model whose name IS its id adds nothing to
 * resolve (the fallback already renders the id), so it is skipped: that keeps
 * the ambiguity test below about real disagreements between two names rather
 * than about a row that was never renamed.
 */
export function buildModelNameIndex(models: readonly NamedModel[]): ModelNameIndex {
  const byProviderAndId = new Map<string, string>();
  const namesPerId = new Map<string, Set<string>>();

  for (const model of models) {
    const id = model.id;
    const name = model.name;
    if (!id || !name || name === id) continue;
    byProviderAndId.set(pairKey(model.provider, id), name);
    const lowerId = id.toLowerCase();
    const names = namesPerId.get(lowerId) ?? new Set<string>();
    names.add(name);
    namesPerId.set(lowerId, names);
  }

  const byId = new Map<string, string>();
  for (const [id, names] of namesPerId) {
    if (names.size === 1) byId.set(id, [...names][0]);
  }
  return { byProviderAndId, byId };
}

/**
 * The display name for this usage row's model, or null when the catalogue has
 * nothing to add.
 *
 * <p>Null is a real answer and the common one: a model the install never
 * renamed, a row charged on a model since removed from the catalogue, a
 * non-LLM charge with no model at all. The caller renders the raw id then,
 * which is what the page did for every row before.
 */
export function resolveModelDisplayName(
  provider: string | null | undefined,
  modelId: string | null | undefined,
  index: ModelNameIndex | null | undefined
): string | null {
  if (!modelId || !index) return null;
  return index.byProviderAndId.get(pairKey(provider, modelId))
    ?? index.byId.get(modelId.toLowerCase())
    ?? null;
}
