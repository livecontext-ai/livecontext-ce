/**
 * The catalogue-size figures the public site advertises, in ONE place.
 *
 * <p><strong>Why this file exists.</strong> The figure used to be typed by hand into every
 * surface that says it: the landing hero, the page metadata, the site description, the
 * comparison copy, the integrations documentation, the terms of service, the CE README, the
 * distribution templates and `llms.txt`. Hand-copying drifted exactly the way hand-copying
 * does. On 2026-09-09 the same product was advertising "600+" on `main`, "700+" on `dev`,
 * and "more than 14,000 operations" in one file against "17,000" in another, while the
 * catalogue actually held 977 integrations and 32,662 endpoints. Every surface that can
 * import now reads from here, so the figure moves once.
 *
 * <p><strong>The rounding is declared, not hidden.</strong> The advertised figure is
 * `1000+` while the corpus holds 979, an owner decision of 2026-09-09: advertise the round
 * number now, seed the remaining integrations next.
 * {@link CATALOG_CLAIM_PENDING_GAP} carries that shortfall, and
 * `integrationCount.test.ts` asserts it against the real seed corpus on every run. Widening
 * the claim without seeding, letting the corpus shrink underneath it, or leaving the
 * acknowledgement behind once the corpus catches up all fail there.
 */

/** The round number the site advertises. Public copy interpolates the strings below. */
export const CATALOG_CLAIM_THRESHOLD = 1000;

/**
 * How far {@link CATALOG_CLAIM_THRESHOLD} currently runs ahead of the seed corpus.
 *
 * <p>Asserted to equal `max(0, threshold - corpus)` exactly, so it is not a comment that can
 * rot: it is a number CI recomputes. That does mean seeding integrations turns the build red
 * until this is lowered, and that is the intended cost. A figure the product does not yet
 * meet is worth exactly one line of maintenance per batch, and the alternative is the drift
 * this whole file exists to end. Set it to `0` when the corpus reaches the threshold, at
 * which point the claim is literally true and this constant can be deleted.
 */
export const CATALOG_CLAIM_PENDING_GAP = 20;

/** The integrations figure, as it appears in public copy. */
export const CATALOG_INTEGRATIONS_CLAIM = '1000+';

/**
 * Callable operations, i.e. endpoints across the corpus, each of which becomes exactly one
 * tool. Measured at 32,662 on 2026-09-09 and advertised as a deliberately conservative
 * floor: the exact total is a catalogue read with no single-source assertion (see
 * `docs/product/facts/integrations.md`) and parsing the 245 MB corpus does not belong in a
 * unit test. Re-measure by summing `endpoints.length` across the seed files.
 */
export const CATALOG_OPERATIONS_CLAIM = '30,000+';
