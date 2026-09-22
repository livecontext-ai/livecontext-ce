import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import enMessages from '@/messages/en.json';
import {
  CREDIT_SOURCE_FILTERS,
  CREDIT_SOURCE_FILTERS_LOCAL_LEDGER,
  CREDIT_SOURCE_LABEL_KEYS,
} from '../creditSourceTypes';

/**
 * The names of the things a reader can spend credits on, and what they can filter by.
 *
 * <p>Two bugs live here, and neither shows up as a failure anywhere else: a kind of spend that has
 * no label renders as its raw wire name ({@code PLATFORM_MARKUP}) beside rows that render as
 * words, and a kind of spend that has a label but no filter entry is visible in the list and
 * impossible to isolate. Both were live: the chart named the platform-markup series
 * {@code PLATFORM_MARKUP} while the table under it called the same rows "Platform API call", and
 * web search and fetch were billed as their own types precisely so they could be filtered, then
 * left out of the only control that filters.
 */
describe('credit source types', () => {
  it('offers no filter it cannot name', () => {
    // A dropdown entry with no label falls back to the raw enum, which is the one place a reader
    // should never meet it: they chose it from a list.
    const unnamed = CREDIT_SOURCE_FILTERS.filter((type) => !CREDIT_SOURCE_LABEL_KEYS[type]);

    expect(unnamed).toEqual([]);
  });

  it('resolves every label against the shipped English messages', () => {
    // A key that names nothing prints its own path on screen, in every locale, because English is
    // the fallback every other locale deep-merges under.
    // The messages file is typed as its own literal shape, so the lookup goes through `unknown`:
    // this test asks a question ABOUT the shape and cannot assume it.
    const quota = enMessages.quota as unknown as Record<string, Record<string, string> | string>;
    const dangling = Object.entries(CREDIT_SOURCE_LABEL_KEYS)
      .filter(([, key]) => {
        const [group, leaf] = key.split('.');
        const bucket = quota[group];
        return typeof bucket !== 'object' || bucket === null || !bucket[leaf];
      })
      .map(([type]) => type);

    expect(dangling).toEqual([]);
  });

  it('lets a reader isolate every kind of spend the app has a NAME for, or says why not', () => {
    // Derived from the shipped messages rather than restated here, which is the whole point: a
    // hardcoded list of "the types that matter" cannot discover an eleventh, and the bug this
    // module was written for is exactly a type that HAD a label and no filter (web search and
    // fetch were billed as their own types so they could be isolated, and then were not offered).
    // A source type earns a `quota.types.*` label the moment it must render in the history, so the
    // labels are the outside list. Anything not filterable has to be named below, with a reason.
    const NOT_FILTERABLE: Record<string, string> = {
      workflow: 'names no source type at all: there is no per-run debit, billing is per node',
      planGrant: 'nothing in the platform writes it; the label is kept for a historical row',
      imageGeneration: 'legacy: nothing writes it any more, so the filter would return nothing',
      imageGenerationByok: 'legacy, same as above',
    };

    const labelled = Object.keys((enMessages.quota as unknown as { types: Record<string, string> }).types);
    const filterableByKey = new Set(
      CREDIT_SOURCE_FILTERS.map((type) => CREDIT_SOURCE_LABEL_KEYS[type]?.replace('types.', '')),
    );
    const orphans = labelled.filter((key) => !filterableByKey.has(key) && !(key in NOT_FILTERABLE));

    expect(orphans).toEqual([]);
  });

  it('drops the relay from a ledger that can never carry one, and keeps it where it can', () => {
    // A relayed row is written on the CLOUD account of a linked install: the self-hosted history
    // filter is rendered only when the cloud-mirrored view is NOT being read, so offering it there
    // is a control that returns nothing. The cloud page keeps it, where those rows exist.
    expect(CREDIT_SOURCE_FILTERS).toContain('CE_LLM_RELAY');
    expect(CREDIT_SOURCE_FILTERS_LOCAL_LEDGER).not.toContain('CE_LLM_RELAY');
    // And nothing else was lost on the way.
    expect(CREDIT_SOURCE_FILTERS_LOCAL_LEDGER).toHaveLength(CREDIT_SOURCE_FILTERS.length - 1);
  });

  it('names and offers the rows that are NOT a tool call, which the table shows too', () => {
    // Money moving the other way, and one debit that is not a tool call. Each is written today and
    // lands in the same table; before this list learned about them they rendered as their own wire
    // names in front of a reader, which is the web-tools bug wearing another hat.
    for (const type of ['MARKETPLACE_PURCHASE', 'PAYG_TOPUP', 'REWARD_REFERRAL',
      'REWARD_CLAWBACK', 'MANUAL_ADJUSTMENT']) {
      expect(CREDIT_SOURCE_LABEL_KEYS[type], `${type} has no label`).toBeTruthy();
      expect(CREDIT_SOURCE_FILTERS, `${type} is not filterable`).toContain(type);
    }
  });

  it('offers no filter that can only ever come back empty', () => {
    // Legacy types still need a LABEL so historical rows read as words, but offering them as a
    // filter is a dead end: nothing writes them any more. Same for a per-run type that has never
    // existed - billing happens per node.
    expect(CREDIT_SOURCE_FILTERS).not.toContain('IMAGE_GENERATION');
    expect(CREDIT_SOURCE_FILTERS).not.toContain('IMAGE_GENERATION_BYOK');
    expect(CREDIT_SOURCE_FILTERS).not.toContain('WORKFLOW_RUN');
    // The transient reserve row is filtered out server-side and never reaches a reader, so it is
    // not a question anyone can ask of the history.
    expect(CREDIT_SOURCE_FILTERS).not.toContain('PLATFORM_MARKUP_RESERVE');
    // Nothing writes PLAN_GRANT. It keeps its label for a historical row and loses its filter.
    expect(CREDIT_SOURCE_FILTERS).not.toContain('PLAN_GRANT');
    expect(CREDIT_SOURCE_LABEL_KEYS.PLAN_GRANT).toBeTruthy();
    // Still labelled, though - those rows exist in older histories.
    expect(CREDIT_SOURCE_LABEL_KEYS.IMAGE_GENERATION).toBeTruthy();
  });

  it('names the two kinds of spend that appear on a generation surface', () => {
    // The reason this module exists: a catalogue call on the platform key is what a generation
    // costs, and a released reservation is what a reader sees when one was refunded.
    expect(CREDIT_SOURCE_LABEL_KEYS.PLATFORM_MARKUP).toBe('types.platformMarkup');
    expect(CREDIT_SOURCE_LABEL_KEYS.PLATFORM_MARKUP_RELEASED).toBe('types.platformMarkupReleased');
  });
});

/**
 * That the two surfaces on the quota page actually READ this module.
 *
 * <p>Invisible to any rendering test, and proven necessary by mutation: re-introduce a private
 * label map inside {@code UsageAnalyticsPanel.formatSourceType} and every suite in the repo stays
 * green while the chart goes back to naming a series {@code PLATFORM_MARKUP} over a table calling
 * the same rows "Platform API Call". That drift IS the bug this module was created to end, so it
 * has to be checked where it happens - in the source - rather than through a render that would
 * agree with either version.
 */
describe('the quota page and its chart read one source of names', () => {
  const frontendRoot = join(__dirname, '..', '..', '..');
  const read = (rel: string) => readFileSync(join(frontendRoot, rel), 'utf-8');

  const SURFACES = [
    'app/[locale]/app/settings/quota/page.tsx',
    'app/[locale]/app/settings/quota/components/UsageAnalyticsPanel.tsx',
  ];

  it.each(SURFACES)('%s imports the shared names', (rel) => {
    expect(read(rel)).toMatch(/import \{[^}]*CREDIT_SOURCE_LABEL_KEYS[^}]*\} from '@\/lib\/billing\/creditSourceTypes'/);
  });

  it.each(SURFACES)('%s keeps no label map of its own', (rel) => {
    // Any `types.something` literal in these files is a label being named locally, which is the
    // shape both copies had before. The import line carries no such literal, so a match here is a
    // second map coming back.
    const localLabels = read(rel).match(/'types\.[A-Za-z]+'/g) ?? [];

    expect(localLabels).toEqual([]);
  });

  it('the history filter is the shared list, not a second one', () => {
    const page = read('app/[locale]/app/settings/quota/page.tsx');

    expect(page).toMatch(/CREDIT_SOURCE_FILTERS\.map/);
    // A local array of shouting-case names is the other copy re-appearing.
    expect(page).not.toMatch(/=\s*\[\s*'[A-Z_]{4,}'/);
  });
});
