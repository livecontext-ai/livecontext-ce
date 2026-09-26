// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import {
  CATALOG_CLAIM_PENDING_GAP,
  CATALOG_CLAIM_THRESHOLD,
  CATALOG_INTEGRATIONS_CLAIM,
  CATALOG_OPERATIONS_CLAIM,
} from '../integrationCount';

/**
 * The advertised catalogue size must be backed by the corpus that is actually shipped, and
 * must appear in only one place.
 *
 * <p>The figure reaches the landing hero, the site metadata, the comparison copy, the
 * integrations documentation, the TERMS OF SERVICE, the public CE README, the Portainer
 * store listing, the text submitted to self-hosted directories and `llms.txt`. A number that
 * overstates the product in a contract document is not a marketing detail, and a number that
 * survives in a static file after the constant moves is the exact drift this suite exists to
 * end, so both are counted here rather than trusted.
 *
 * <p><strong>Counted by file, not by content, and that is deliberate.</strong> The obvious
 * implementation reads each seed's head and looks for `apiName`, the way
 * `wellKnownIntegrations.test.ts` resolves its slugs. It undercounts: `telegram.json`
 * declares `apiName` at byte 650,949, past any sane read window, so a head-based count
 * returns 976 against the 977 the live catalogue serves. Widening the window does not fix
 * it, and parsing 245 MB does not belong in a unit test. Counting files and naming the two
 * exceptions is exact, is cheap, and fails loudly if either assumption moves.
 */

const FRONTEND_ROOT = process.cwd();
const REPO_ROOT = join(FRONTEND_ROOT, '..');
const SEED_DIR = join(REPO_ROOT, 'scripts', 'api-migrations');

/**
 * The only top-level `*.json` in the seed directory that are not integrations: audit
 * artefacts carrying an `apis` map rather than an API definition. Asserted to exist below,
 * so this exclusion cannot quietly stop matching reality.
 */
const NON_API_SEEDS = ['audit-tracking.json', 'auth-variant-audit.json'];

/**
 * Integrations in the corpus.
 *
 * <p>Non-recursive, mirroring the importer's own single-level glob (`ApiMigrationImporter`
 * uses `Files.list`): the files under `scripts/api-migrations/tools/` are the generation
 * toolchain's recipes, have the same shape, are never imported, and would inflate the figure
 * by an eighth.
 */
function seededIntegrations(): number {
  return readdirSync(SEED_DIR, { withFileTypes: true }).filter(
    (entry) => entry.isFile() && entry.name.endsWith('.json') && !NON_API_SEEDS.includes(entry.name),
  ).length;
}

/**
 * Nouns that mean "an integration" in a claim. `endpoints` is deliberately NOT here: the
 * fact sheet states a measured 32,662 endpoints as prose, which is correct and is not the
 * advertised figure, so covering that noun would fail the build on an accurate sentence.
 */
const INTEGRATION_NOUNS = String.raw`integrations|API catalog|APIs|connectors`;

/**
 * The operations figure, in the three renderings the surfaces actually use.
 *
 * <p>A comma-grouped number may drop the trailing plus, which is how the real figures are
 * written (`+ 30,000 tools`, `more than 17,000 ready-to-call operations`). A plain run of
 * digits must carry a TRAILING plus.
 *
 * <p>Requiring the plus at all is what keeps `docs/` usable. That tree is full of engineering
 * prose about throughput and tool counts, and accepting a bare 4-to-6 digit number failed the
 * build on a sentence about sustaining 20000 operations per minute, which reads to whoever
 * hits it as a marketing regression that is not there.
 *
 * <p>A LEADING plus is deliberately not accepted, though `hero-flow.html` uses that
 * rendering. `(?<=\+\s{0,2})\d{3,6}` would cover a comma-less `+ 30000 tools` there, but
 * `+ ` also opens a Markdown bullet and a pasted diff line, so in the scanned `docs/` tree it
 * turns `+ 4096 tools registered at boot` into an offender. It buys nothing either: hero-flow
 * is pinned positively to contain the comma-grouped figure, so it cannot lose its comma
 * without failing that case. A guard that fails on true sentences teaches people to switch
 * guards off.
 */
const OPERATIONS_NUMBER = String.raw`\d{1,3},\d{3}\+?|\d{3,6}\+`;

/**
 * A catalogue figure written into prose, whatever adjectives sit between the number and the
 * noun, and whether or not the number is comma-grouped.
 *
 * <p>Both branches have been wrong once, in the same way, and the shape of the mistake is
 * worth recording. The first integrations pattern required the number to be followed
 * IMMEDIATELY by `integrations` and was three digits wide: it was blind to four of the ten
 * literals this change converted (the Terms of Service among them) and could never have
 * matched `1000+`, so it would have gone structurally blind the moment the threshold passed
 * 999. The operations pattern then repeated it as `1[0-9],000`, which matched the superseded
 * 14,000 and 17,000 and not the 30,000 actually advertised. Both are now written to match
 * the FORM of the claim rather than today's digits.
 *
 * <p>The lookbehind stops a match starting inside a comma-grouped number, which is what made
 * `1,000+ integrations` report as the phantom offender `000+ integrations`.
 *
 * <p>The two branches are NAMED because the filter compares each against its own constant.
 * A shared comparison passed "Browse all 30,000+ integrations", where every figure was
 * current and one of them was attached to the wrong noun.
 */
const CATALOGUE_FIGURE = String.raw`(?<![\d,])(?<integrations>\d{3,4}|\d{1,3},\d{3})\+\s+(?:[a-z-]+\s+){0,4}(?:${INTEGRATION_NOUNS})\b|(?<![\d,])(?<operations>${OPERATIONS_NUMBER})\s+(?:[a-z-]+\s+){0,3}(?:operations|tools)\b`;

/**
 * An inline opt-out, for a figure on the page that is not a claim about this product.
 *
 * <p>`docs/` is in scope and contains competitor comparisons: "n8n (250+ integrations
 * bundled)" is true, is not ours to update, and must not fail a build. The first version
 * exempted that whole FILE, which is a document about our own catalog seeding, so the next
 * figure of ours written there would have been invisible. A marker on the line scales to the
 * next competitor figure without widening what stops being checked.
 */
const NOT_OUR_FIGURE = 'catalog-figure: not ours';

/**
 * Every place the opt-out is used, as `path:line`, and the reason it is enumerated.
 *
 * <p>Line-scoping alone was not enough. A review reverted the Terms of Service to the
 * superseded figure, pasted the marker on that line, and the whole suite went green: the
 * exemption had moved out of the guard, where a reviewer reads it, and into arbitrary
 * product files where it looks like housekeeping. Enumerating them means adding one is an
 * edit to THIS list, which a human reviews, rather than a comment nobody sees again.
 */
const EXPECTED_MARKERS = ['the project docs:7'];

/** Trees whose text is read by a visitor, a self-hosted user, or a language model. */
const SCANNED_ROOTS = [
  join(FRONTEND_ROOT, 'app'),
  join(FRONTEND_ROOT, 'components'),
  join(FRONTEND_ROOT, 'lib'),
  join(FRONTEND_ROOT, 'public'),
  // Outside frontend/ and every bit as public: the CE README, the Portainer store listing and
  // the verbatim text submitted to self-hosted directories all carry the figure as prose.
  // Leaving the scan at the frontend boundary is how llms.txt was missed one directory down.
  join(REPO_ROOT, 'deploy', 'ce-export', 'public-assets'),
  join(REPO_ROOT, 'docs'),
  join(REPO_ROOT, 'AGENTS.md'),
  // AGENTS.md requires every user-facing string to live here, so this is where the figure
  // lands the day the landing is localised. Empty of figures today, in scope before it is not.
  join(FRONTEND_ROOT, 'messages'),
];

const SCANNED_EXTENSIONS = ['.ts', '.tsx', '.txt', '.html', '.md', '.json'];

/**
 * Files exempt from the scan, each for a reason that is not "it was inconvenient".
 *
 * <p>`integrationCount.ts` is the source of the figure and quotes the superseded ones on
 * purpose; tests state the figures they assert. A dated live-test log records what was true
 * on its day, so rewriting its figures would be falsifying a record; the pattern is anchored
 * to that exact filename shape because an unanchored `live-test-campaign-` substring would
 * auto-exempt any future file whose path happened to contain it.
 *
 * <p>A competitor's figure is NOT handled here. That was a file-wide exemption for a single
 * line, in a document about our own catalog seeding, so the next figure of ours written there
 * would have gone unchecked. It uses the inline {@link NOT_OUR_FIGURE} marker instead.
 *
 * <p>The static public assets are NOT exempt: an earlier version allowlisted them and a
 * review showed that reverting either one left the whole suite green.
 */
const SCAN_EXEMPT =
  /integrationCount\.ts$|__tests__|\.test\.tsx?$|[\\/]live-test-campaign-\d{4}-\d{2}-\d{2}\.md$/;

/** Static public files that carry the figure as prose and cannot interpolate the constant. */
const STATIC_SURFACES = ['llms.txt', 'hero-flow.html'];

function walk(target: string): string[] {
  if (!existsSync(target)) return [];
  if (statSync(target).isFile()) return [target];
  return readdirSync(target, { withFileTypes: true }).flatMap((entry) => {
    const full = join(target, entry.name);
    if (entry.isDirectory()) return entry.name === 'node_modules' ? [] : walk(full);
    return SCANNED_EXTENSIONS.some((ext) => entry.name.endsWith(ext)) ? [full] : [];
  });
}

/**
 * The bare digits of a figure, with comma grouping removed.
 *
 * <p>Comparison is on this and not on the matched text, because the surfaces render the same
 * figure differently and none of those differences make it stale: `hero-flow.html` writes
 * "+ 30,000 tools" with no trailing plus, the constant is "30,000+", and a comma is a
 * rendering choice. Comparing the raw strings reported the CURRENT operations figure as an
 * offender.
 */
const digitsOf = (text: string) => text.replace(/[^\d]/g, '');

/** Where the opt-out marker appears in `text`, as 1-based line numbers. */
function markedLines(text: string): number[] {
  return text
    .split('\n')
    .map((line, index) => (line.includes(NOT_OUR_FIGURE) ? index + 1 : 0))
    .filter((lineNumber) => lineNumber > 0);
}

/**
 * Every catalogue figure in `text` that is not the figure currently advertised FOR ITS OWN
 * NOUN.
 *
 * <p>Matching each branch against its own constant is the difference between guarding drift
 * and guarding truth. An earlier version compared the number against a flat list of both
 * claims, so "Browse all 30,000+ integrations" passed the entire suite: every figure on the
 * page was current, just attached to the wrong noun. Both figures are rendered in one
 * sentence on the strip, separated by a middot, which makes transposing them the single most
 * likely copy error on that surface, and it would publish a claim of 30,000 integrations
 * against a catalogue of 977.
 */
function supersededFigures(text: string): string[] {
  const lines = text.split('\n');
  const offsets: number[] = [];
  let cursor = 0;
  for (const line of lines) {
    offsets.push(cursor);
    cursor += line.length + 1;
  }
  /** The source line a match sits on, so an inline opt-out can be read from it. */
  const lineAt = (index: number) => lines[offsets.filter((start) => start <= index).length - 1] ?? '';

  return Array.from(text.matchAll(new RegExp(CATALOGUE_FIGURE, 'gi')))
    .filter((m) => !lineAt(m.index ?? 0).includes(NOT_OUR_FIGURE))
    .filter((m) => {
      const current = m.groups?.integrations ? CATALOG_INTEGRATIONS_CLAIM : CATALOG_OPERATIONS_CLAIM;
      const found = m.groups?.integrations ?? m.groups?.operations ?? '';
      return digitsOf(found) !== digitsOf(current);
    })
    .map((m) => m[0]);
}

describe('the advertised catalogue size', () => {
  it.each(NON_API_SEEDS)('still finds the excluded audit artefact %s', (name) => {
    // If one is renamed or removed, the count below silently gains an integration that does
    // not exist. Naming them is only safe while they are actually there.
    expect(existsSync(join(SEED_DIR, name))).toBe(true);
  });

  it('reads the seed corpus at all, so the cases below cannot pass vacuously', () => {
    expect(seededIntegrations()).toBeGreaterThan(500);
  });

  it('declares exactly how far the advertised figure runs ahead of the corpus', () => {
    // The load-bearing case, and it is deliberately an equality rather than a bound. An
    // earlier version asserted `corpus >= recorded` and `recorded + gap >= threshold`, which
    // a mutation test walked straight through: setting the recorded count to 12 and the gap
    // to 9000 kept every case green. Recomputing the gap from the corpus is the only form
    // that fails on all three real regressions, which are widening the claim without
    // seeding, the corpus shrinking underneath it, and the gap being left stale.
    expect(CATALOG_CLAIM_PENDING_GAP).toBe(
      Math.max(0, CATALOG_CLAIM_THRESHOLD - seededIntegrations()),
    );
  });

  it('renders the threshold as the "N+" string the public copy interpolates', () => {
    // Every importing surface interpolates this string, so a threshold moved without the
    // label would advertise the old figure everywhere while the constants say otherwise.
    expect(CATALOG_INTEGRATIONS_CLAIM).toBe(`${CATALOG_CLAIM_THRESHOLD}+`);
  });

  it('detects a superseded figure in prose, wherever the number sits in the sentence', () => {
    // This case guards the guard, on BOTH branches, because both have shipped blind once.
    // Every string below is a literal this change had to convert by hand, or a form the
    // pattern will have to catch after the next figure move.
    for (const stale of [
      'connect 700+ third-party integrations. The Service',
      'the ~700+ pre-built integrations, how tools',
      'a catalog of ~700+ pre-built third-party API integrations.',
      'the 700+ API catalog',
      '700+ integrations and every major LLM',
      '700+ ready-made pre-built third-party API integrations',
      'more than 17,000 ready-to-call operations',
      '+ 14,000 tools',
      // Forms that only appear after the current figures move. The three-digit and
      // 1[0-9],000 patterns could never have matched these, which is how each branch went
      // structurally blind in turn.
      '1200+ third-party integrations',
      '2,500+ third-party integrations',
      '45,000+ ready-to-call operations',
      '+ 60,000 tools',
    ]) {
      expect({ [stale]: supersededFigures(stale) }).not.toEqual({ [stale]: [] });
    }
  });

  it('passes the figures currently advertised, in either rendering', () => {
    // The mirror of the case above. If the pattern cannot match the current claim at all,
    // then "the filter excludes it" is untested and the branch is dead code, which is what
    // hid the broken operations pattern for a pass.
    expect(supersededFigures(`Browse all ${CATALOG_INTEGRATIONS_CLAIM} integrations`)).toEqual([]);
    expect(supersededFigures(`${CATALOG_OPERATIONS_CLAIM} ready-to-call operations`)).toEqual([]);
    expect(supersededFigures('1,000+ integrations')).toEqual([]);

    // And the pattern must genuinely reach them, so the emptiness above is the filter's doing.
    const raw = (text: string) => Array.from(text.matchAll(new RegExp(CATALOGUE_FIGURE, 'gi')));
    expect(raw(`Browse all ${CATALOG_INTEGRATIONS_CLAIM} integrations`)).toHaveLength(1);
    expect(raw(`${CATALOG_OPERATIONS_CLAIM} ready-to-call operations`)).toHaveLength(1);
  });

  it('keeps the static public surfaces in step, since they cannot import the constant', () => {
    // llms.txt is the site's GEO entry point: a stale figure there is not one page out of
    // date, it is the number every model that reads the site goes on to repeat. hero-flow is
    // a served asset that already drifted once. Neither can interpolate, so both are pinned
    // positively AND scanned negatively: an earlier version asserted only that llms.txt
    // contained the new sentence, and reverting its OTHER mention left the suite green.
    const llms = readFileSync(join(FRONTEND_ROOT, 'public', 'llms.txt'), 'utf8');
    expect(llms).toContain(`${CATALOG_INTEGRATIONS_CLAIM} API integrations`);
    expect(llms).toContain(`${CATALOG_OPERATIONS_CLAIM} operations`);

    // The hero lead no longer states the integration count (the integrations strip below it
    // does), so hero-flow is only pinned on the operations figure and scanned for stale ones.
    const heroFlow = readFileSync(join(FRONTEND_ROOT, 'public', 'hero-flow.html'), 'utf8');
    // hero-flow writes it as "+ 30,000 tools", so the trailing plus is dropped, not the comma.
    expect(heroFlow).toContain(CATALOG_OPERATIONS_CLAIM.replace(/\+$/, ''));

    for (const name of STATIC_SURFACES) {
      const text = readFileSync(join(FRONTEND_ROOT, 'public', name), 'utf8');
      expect({ [name]: supersededFigures(text) }).toEqual({ [name]: [] });
    }
  });

  it('holds each figure to its OWN noun, so the two cannot be transposed', () => {
    // Not a drift case, a correctness one. An earlier filter compared the number against a
    // flat list of both claims, so every figure below passed the whole suite while saying
    // something false: the strip renders both in one sentence separated by a middot, which
    // makes swapping them the likeliest copy error on that surface.
    const swapped = [
      `Browse all ${CATALOG_OPERATIONS_CLAIM} integrations`,
      `${CATALOG_INTEGRATIONS_CLAIM} ready-to-call operations`,
      '1,000+ tools',
    ];
    for (const claim of swapped) {
      expect({ [claim]: supersededFigures(claim) }).not.toEqual({ [claim]: [] });
    }
  });

  it('ignores an ordinary throughput number in engineering prose', () => {
    // `docs/` is in scope and is full of prose about queue depth and tools. An earlier
    // operations branch accepted a bare 4-to-6 digit number, so one sentence about
    // sustaining 20000 operations per minute failed the build as a marketing regression.
    // The real figures are always comma-grouped, so the branch requires that.
    expect(supersededFigures('sustains 20000 operations per minute on a warm cache')).toEqual([]);
    expect(supersededFigures('exposes 4096 tools to the agent')).toEqual([]);

    // A leading plus opens a Markdown bullet and a pasted diff line, both ordinary in the
    // scanned docs tree. Accepting it turned true engineering sentences into offenders, and
    // it covered nothing: hero-flow's own rendering is held by its positive pin instead.
    expect(supersededFigures('+ 4096 tools registered at boot')).toEqual([]);
    expect(supersededFigures('+ 12000 operations per second')).toEqual([]);

    // And it must still see the comma-grouped forms the surfaces actually use.
    expect(supersededFigures('more than 17,000 ready-to-call operations')).not.toEqual([]);
  });

  it('lets a line opt out of a figure that is not a claim about this product', () => {
    // Competitor figures are true and not ours to maintain. The marker is per LINE so it
    // cannot quietly exempt a whole document, which is how the first version handled it.
    const competitor = 'alternatives like n8n (250+ integrations bundled)';
    expect(supersededFigures(competitor)).not.toEqual([]);
    expect(supersededFigures(`${competitor} <!-- ${NOT_OUR_FIGURE} -->`)).toEqual([]);

    // The opt-out is line-scoped: a marker on one line must not cover the next.
    expect(supersededFigures(`${competitor} <!-- ${NOT_OUR_FIGURE} -->\nwe ship 700+ integrations`)).toEqual([
      '700+ integrations',
    ]);
  });

  it('enumerates every opt-out, so a marker cannot be added without review', () => {
    // Line-scoping the marker was not enough on its own. A review reverted the Terms of
    // Service to the superseded figure, pasted the marker on that line, and the whole suite
    // went green: the exemption had moved out of the guard, where a reviewer reads it, and
    // into a product file where it reads as housekeeping. Enumerating them puts adding one
    // back into a diff of THIS file, which is the only place it gets a second pair of eyes.
    const found: string[] = [];

    for (const file of SCANNED_ROOTS.flatMap(walk)) {
      if (SCAN_EXEMPT.test(file)) continue;
      for (const line of markedLines(readFileSync(file, 'utf8'))) {
        found.push(`${relative(REPO_ROOT, file).replace(/\\/g, '/')}:${line}`);
      }
    }

    expect(found.sort()).toEqual([...EXPECTED_MARKERS].sort());
  });

  it('caps how far the advertised figure may be pushed ahead of the corpus', () => {
    // Not a policy decision encoded in a test: rounding 977 up to 1000 is the owner's call
    // and the exact-gap case already forces it to be declared. This is the ratchet on top,
    // so widening the claim to something the corpus is nowhere near takes an edit to this
    // guard, which a human reviews, rather than a constant someone bumps on the way past.
    expect(CATALOG_CLAIM_PENDING_GAP).toBeLessThanOrEqual(50);
  });

  it('sees the figure under every noun and rendering the surfaces use', () => {
    // Each of these was invisible to some earlier version of the pattern, and each is a
    // form that appears on a real surface or is one edit away from doing so.
    expect(supersededFigures('bundles 700+ APIs')).toEqual(['700+ APIs']);
    expect(supersededFigures('ships 700+ pre-built connectors')).toEqual([
      '700+ pre-built connectors',
    ]);

    // A measured corpus figure stated as prose is correct and must stay silent, which is
    // why `endpoints` is not an integration noun.
    expect(supersededFigures('977 integrations and 32,662 endpoints')).toEqual([]);
  });

  it('leaves no superseded catalogue figure anywhere a reader can reach', () => {
    // The whole premise of the constant is "the figure moves once". Nothing asserted that
    // until this case: the first pass converted nine surfaces and still left 700+ in
    // llms.txt and hero-flow.html, and the second still stopped at the frontend boundary
    // while the CE README and the directory submissions carried their own copies.
    const offenders: string[] = [];

    for (const file of SCANNED_ROOTS.flatMap(walk)) {
      if (SCAN_EXEMPT.test(file)) continue;
      for (const hit of supersededFigures(readFileSync(file, 'utf8'))) {
        offenders.push(`${relative(REPO_ROOT, file)}: ${hit.trim()}`);
      }
    }

    expect(offenders).toEqual([]);
  });

  it('reaches the public surfaces outside frontend, so the case above is not local', () => {
    // The scan is only worth its name if it actually opens those files. This pins that the
    // walk crosses the frontend boundary and reads the CE distribution assets.
    const scanned = SCANNED_ROOTS.flatMap(walk).map((f) => relative(REPO_ROOT, f).replace(/\\/g, '/'));
    expect(scanned).toContain('deploy/ce-export/public-assets/README.public.md');
    expect(scanned).toContain('deploy/ce-export/public-assets/templates/portainer/livecontext-ce.json');
    expect(scanned).toContain('the project docs');

    // AGENTS.md is a FILE root rather than a directory, so it is the only entry exercising
    // the isFile() branch of the walk. A mistyped root there would fail silently.
    expect(scanned).toContain('AGENTS.md');
  });
});
