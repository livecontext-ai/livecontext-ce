import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Every place the Quota & Usage page prints a model, and whether it prints the
 * NAME or the raw id the ledger stored.
 *
 * <p><b>Why a source scan.</b> The cell itself is covered
 * (`modelLabels.test.tsx`) and the two filter dropdowns are covered by opening
 * them (`UsageAnalyticsPanel.names.test.tsx`). The usage HISTORY table is not,
 * and cannot be cheaply: it lives inside `page.tsx`, twice (a CE branch and a
 * cloud one), behind auth, a credit summary, a wallet, a PAYG tier list, a
 * workspace store and a cloud-link probe. Rendering it to assert a table cell
 * would be a page harness held together by eight mocks, and the first of them
 * to drift would take the assertion with it.
 *
 * <p>So this asserts the wiring instead, which is exactly what regressed: the
 * table used to interpolate `${entry.provider} / ${entry.model}` straight into
 * the cell, and reverting to that template is invisible to every test above.
 * A scan cannot prove what a reader SEES - that is the cell's own test's job -
 * but it can prove the page still routes through the cell that was proven.
 */

const HERE = __dirname;
const QUOTA_DIR = path.resolve(HERE, '..', '..');
const PAGE = path.join(QUOTA_DIR, 'page.tsx');
const ANALYTICS = path.join(QUOTA_DIR, 'components', 'UsageAnalyticsPanel.tsx');

function read(file: string): string {
  expect(fs.existsSync(file), `${file} has moved; this guard is now scanning nothing`).toBe(true);
  return fs.readFileSync(file, 'utf8');
}

describe('the quota page routes every model it prints through the naming cell', () => {
  it('renders the history model column with ProviderModelCell, in BOTH branches', () => {
    // CE and cloud each draw their own table. The CE one was written first and
    // is the one a self-hosted install reads, so "fixed on cloud only" is a
    // real and invisible half-fix.
    const source = read(PAGE);
    const cells = source.match(/<ProviderModelCell\b/g) ?? [];
    expect(cells.length, 'both the CE and the cloud history tables must use it').toBe(2);
  });

  it('interpolates no raw model id into that column any more', () => {
    // The exact shape this change replaced. It renders perfectly, it just calls
    // the model something no other screen in the app calls it.
    const source = read(PAGE);
    expect(source).not.toMatch(/\$\{entry\.provider\}\s*\/\s*\$\{entry\.model\}/);
    expect(source).not.toMatch(/entry\.provider \|\| entry\.model/);
  });

  it('builds the index once per table rather than once per row', () => {
    // A hook per row would put a query observer behind every line of a paged
    // table. Both branches take it at the top of the component.
    const source = read(PAGE);
    expect((source.match(/useModelNameIndex\(\)/g) ?? []).length).toBe(2);
  });

  it('labels both analytics filters rather than listing wire values', () => {
    const source = read(ANALYTICS);
    expect(source, 'the provider filter lists raw provider keys')
      .toMatch(/<SelectItem key=\{p\} value=\{p\}>\{providerLabels\.get\(p\)/);
    expect(source, 'the model filter lists raw model ids')
      .toMatch(/<SelectItem key=\{m\} value=\{m\}>\{modelLabelFor\(/);
  });

  it('keeps the stored id as the filter VALUE, because that is what the API filters on', () => {
    // Labelling is display only. Sending the display name would filter on a
    // string the ledger has never held, and the chart would go empty with no
    // error anywhere.
    const source = read(ANALYTICS);
    expect(source).toMatch(/<SelectItem key=\{p\} value=\{p\}>/);
    expect(source).toMatch(/<SelectItem key=\{m\} value=\{m\}>/);
  });
});
