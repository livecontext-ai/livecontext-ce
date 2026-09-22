import type { PublicPublicationSummary } from './publicPublications';

/**
 * Decides which public marketplace pages search engines may index.
 *
 * Not every published listing deserves to be in the index. A page whose whole
 * content is a title and a one-line description is "thin content": Google
 * demotes it, and enough of them drag down the ranking of the entire domain,
 * including the pages that already perform (the landing, /compare, the docs).
 * Listings that fail this gate are still fully reachable and rendered, they
 * just carry `noindex` and stay out of the sitemap.
 *
 * This is intentionally a pure function on the view model: the same rule must
 * drive the page's robots meta AND the sitemap, and any divergence between the
 * two produces the worst outcome, a URL advertised in the sitemap that then
 * tells the crawler not to index it.
 */

/**
 * Minimum description length for an indexable listing.
 *
 * Sized so a real sentence about what the app does passes and a placeholder
 * ("test", "my workflow", "asdf") does not. Deliberately a floor rather than a
 * quality judgement: the goal is to filter out empties, not to referee prose.
 */
export const MIN_INDEXABLE_DESCRIPTION_LENGTH = 120;

/**
 * The same floor for a listing that shipped a finished application.
 *
 * The description length was only ever a PROXY for "is there anything on this
 * page". That proxy was right when a listing page was a title, a sentence and
 * an author line. It is not right any more, and note carefully that the
 * application PREVIEW is not the reason: that is an iframe, and no crawler
 * indexes what is inside one. What a `hasShowcase` listing's page actually
 * carries beyond its description is indexable text and structure the others do
 * not have: the workflow diagram with its node labels, the integrations it
 * uses, the publisher block, the reviews, and a `SoftwareApplication` node with
 * its own OpenGraph image. `hasShowcase` is the field that separates a listing
 * its author finished and froze from one they filed and left.
 *
 * So the floor drops for those, it does not disappear: this still has to reject
 * "test", "asdf" and "my workflow", which say nothing whatever the page renders
 * and are what the gate exists for.
 */
export const MIN_INDEXABLE_DESCRIPTION_LENGTH_WITH_SHOWCASE = 40;

/**
 * A listing that has a public URL.
 *
 * `publicSlug` is nullable on the view model because rows predating the slug
 * backfill have none, and every caller that builds a URL was asserting it away
 * with `as string`. That cast is silent when it is wrong: the page emits
 * `/marketplace/null` in a link or in its structured data, with no type error
 * and no failing test.
 *
 * <p><b>Do not mistake this for a compile-time guarantee.</b> This frontend
 * builds with `strict: false`, so `strictNullChecks` is OFF and a nullable
 * value is assignable to `string` anyway: the guard is worth having because
 * `filter(isLinkable)` actually removes those rows at RUNTIME, and because it
 * documents the requirement, not because the compiler enforces it. Where a
 * caller genuinely must not get this wrong, take the slug as a required
 * ARGUMENT (see `listingJsonLd`) - a missing property is an error whatever the
 * strictness setting.
 */
export type LinkableListing = PublicPublicationSummary & { publicSlug: string };

/** Whether a listing has a public URL at all, independent of whether it should be indexed. */
export function isLinkable(publication: PublicPublicationSummary): publication is LinkableListing {
  return typeof publication.publicSlug === 'string' && publication.publicSlug.length > 0;
}

export function isIndexable(publication: PublicPublicationSummary): publication is LinkableListing {
  // Without a slug there is no canonical URL to index: the row predates the
  // backfill and is only reachable by UUID.
  if (!isLinkable(publication)) return false;
  if (publication.title.trim().length === 0) return false;

  const described = publication.description.trim().length;
  if (described >= MIN_INDEXABLE_DESCRIPTION_LENGTH) return true;
  // A frozen showcase marks a finished listing, whose page carries indexable
  // content of its own beyond the description (see the constant above).
  return publication.hasShowcase && described >= MIN_INDEXABLE_DESCRIPTION_LENGTH_WITH_SHOWCASE;
}

/** Canonical path of a public listing. */
export function marketplacePath(slug: string): string {
  return `/marketplace/${slug}`;
}

/**
 * Build the one-line description used for `<meta name="description">` and the
 * OpenGraph card.
 *
 * Truncated on a word boundary with a real ellipsis character rather than three
 * dots, and never mid-word: search engines show roughly this much, and a
 * description cut through a word reads as broken to a human scanning results.
 * Falls back to a generic sentence so a listing never ships an empty meta
 * description (which search consoles flag).
 */
export function metaDescription(publication: PublicPublicationSummary, maxLength = 155): string {
  const description = publication.description.trim();
  if (description.length === 0) {
    return `${publication.title} on the LiveContext marketplace.`;
  }
  if (description.length <= maxLength) return description;

  const cut = description.slice(0, maxLength - 1);
  const lastSpace = cut.lastIndexOf(' ');
  return `${(lastSpace > 0 ? cut.slice(0, lastSpace) : cut).trimEnd()}…`;
}
