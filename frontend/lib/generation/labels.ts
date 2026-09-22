/**
 * The three things a label needs to know about a file slot.
 *
 * <p>Narrower than the field itself on purpose: the dialog builds its slots from the model listing
 * rather than from a StudioField, and asking it for a whole field would have been the third place
 * in the app to decide what a file slot is called.
 */
export interface AssetSlotShape {
  /** The unified parameter name, which is the fallback when no word exists for the role. */
  name: string;
  /** What the file IS to this model, as the catalogue declares it. */
  role?: string;
  /** How many files the slot takes. A slot taking exactly one is never numbered. */
  slots: number;
}

/**
 * What to call a generation parameter and a file slot, in the reader's language.
 *
 * <p><b>Why these take the translator instead of importing one.</b> The dictionary they read
 * (`assetRoles`, `params`) is shared by every surface that offers a generation, and there is
 * exactly one copy of it. A component passes its own namespace translator in, so adding a surface
 * never adds a second dictionary to keep in step with the first.
 *
 * <p><b>Why every lookup is guarded.</b> Both the role and the parameter name come from the API
 * catalogue, which ships new ones without the app being rebuilt. An unguarded lookup renders the
 * raw key path, or throws, the first time a provider declares something this build has no word for.
 * Falling back to the name itself is not elegant, but it is readable and it cannot break the
 * screen.
 */

/** A translator narrowed to what these helpers need, so any namespace can be passed in. */
export interface LabelTranslator {
  (key: string): string;
  has: (key: string) => boolean;
}

/**
 * What a parameter is called. Falls back to its contract name, which is what the platform calls it
 * and what any documentation about it will say.
 */
export function paramLabel(name: string, t: LabelTranslator): string {
  return t.has(`params.${name}`) ? t(`params.${name}`) : name;
}

/**
 * What a file slot is called: what the file IS to this model, not what type it is.
 *
 * <p>The role comes from the provider's own descriptor, so a model that animates from a still says
 * "First frame" and one that only borrows a look says "Reference image". One label for both
 * mis-describes one of them, and the reader finds out after paying.
 *
 * @param slot when given AND the parameter takes several files, the label is numbered. A parameter
 *        taking exactly one is never numbered: "Source image 1" invites a look for a second.
 */
export function assetRoleLabel(field: AssetSlotShape, t: LabelTranslator, slot?: number): string {
  const base = field.role && t.has(`assetRoles.${field.role}`)
    ? t(`assetRoles.${field.role}`)
    : paramLabel(field.name, t);
  return slot !== undefined && field.slots > 1 ? `${base} ${slot + 1}` : base;
}

/**
 * What this file will DO, in one line, or null when nothing is known.
 *
 * <p>The name of a slot says which file goes in it; it does not say what happens to the file. "First
 * frame" and "Reference image" are both images of the same thing and produce two different videos,
 * and the reader who guesses wrong finds out from a finished clip they have paid for. Null rather
 * than a placeholder, so a role this build has no sentence for simply shows nothing instead of a
 * key path.
 */
export function assetRoleHint(field: AssetSlotShape, t: LabelTranslator): string | null {
  if (!field.role) return null;
  const key = `assetRoleHints.${field.role}`;
  return t.has(key) ? t(key) : null;
}

/**
 * The refusal to put on screen.
 *
 * <p>The endpoint's own words are shown VERBATIM, and that is the design: every refusal this path
 * produces names a remedy the reader can act on, and a generic replacement would throw away the
 * only useful half.
 *
 * <p>The guard exists because this is the LAST hop before a person, and the string reaching it has
 * crossed six: the provider, catalog-service, the tool module, the generation module, the
 * controller and the client. Two of those quote a third party. The invariant is enforced where it
 * belongs, on the server, but "every refusal is a sentence" is not something a component can
 * verify, and the failure it was written for was a whole machine envelope printed at a reader:
 * internal ids, an endpoint path, a request id.
 *
 * <p>Deliberately narrow: only a payload that PARSES as JSON is replaced. No sentence a person
 * could act on is also a JSON document, so nothing useful can be swallowed here, and a backend
 * regression stays loud.
 *
 * <p>Moved here from the generation dialog when the studio replaced it. It is not the dialog's
 * rule, it is the rule for showing a refusal from this endpoint to a person, and the studio is the
 * surface that does that now.
 */
export function readableRefusal(error: string | undefined | null, fallback: string): string {
  const text = (error ?? '').trim();
  if (!text) return fallback;
  if (text.startsWith('{') || text.startsWith('[')) {
    try {
      JSON.parse(text);
      return fallback;
    } catch {
      // A sentence that merely opens with a brace is still a sentence.
    }
  }
  return text;
}
