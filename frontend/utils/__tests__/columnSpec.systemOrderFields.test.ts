/**
 * `SYSTEM_ORDER_FIELDS` is a mirror of a backend list, so this test reads that
 * list rather than restating it.
 *
 * The constant decides when a bare `column_order` entry is ambiguous, and the
 * only reason it is ambiguous is the backend seed's skip rule: `generateColumnOrder`
 * writes its system names ahead of the data names AND omits any data field
 * sharing one of them, so a bare `id` entry there could be the lane or an
 * imported column called `id`. Any name the seed does NOT treat that way is
 * unambiguous and must not be in the constant: a data column really can be
 * called `value`, and refusing its rank would drop it wherever its name sorts.
 *
 * Restating the list in the assertion would make this a tautology that passes
 * on every edit, correct or not, which is how `value` and `array_index` got in
 * and stayed. Parsing the Java source is the only version that can disagree.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { SYSTEM_ORDER_FIELDS } from '../columnSpec';

// Resolved from this file, not from the working directory, so the test does not
// depend on where the runner was started.
const SEED_SOURCE = join(
  __dirname,
  '..',
  '..',
  '..',
  'backend/datasource-service/src/main/java/com/apimarketplace/datasource/utils/DataSourceDefaults.java',
);

/** The names of `DataSourceDefaults.SYSTEM_COLUMNS`, read from the Java source. */
const seedSystemColumns = (): string[] => {
  // Strip line comments from the WHOLE source before matching: the declaration
  // carries one per entry, and a comment could otherwise contribute a quoted
  // word as a system column, or truncate the capture with a stray `);`.
  const source = readFileSync(SEED_SOURCE, 'utf8').replace(/\/\/.*/g, '');
  const declaration = /SYSTEM_COLUMNS\s*=\s*List\.of\(([\s\S]*?)\);/.exec(source);
  if (!declaration) {
    throw new Error(`Could not find SYSTEM_COLUMNS in ${SEED_SOURCE}`);
  }
  return [...declaration[1].matchAll(/"([^"]+)"/g)].map(match => match[1]);
};

describe('SYSTEM_ORDER_FIELDS mirrors the backend seed', () => {
  it('reads a non-empty list out of the Java source', () => {
    // Guards the parse itself: a refactor that renames the constant or changes
    // its shape must fail here, not silently compare against nothing.
    expect(seedSystemColumns().length).toBeGreaterThan(0);
  });

  it('contains exactly the names the seed treats as system columns', () => {
    expect([...SYSTEM_ORDER_FIELDS].sort()).toEqual(seedSystemColumns().sort());
  });

  it('excludes the lane names only the frontend seed writes', () => {
    // `array_index` and `value` are lanes of the nested view. The seed that
    // writes them never reaches the database, and the one path that does store
    // them (a drag performed while navigating nested JSON, which PUTs whatever
    // it renders) has already overwritten the root order with nested column
    // names, so that array is meaningless either way. Treating the two as
    // ambiguous would only cost a data column of that name the rank the seeds
    // really do give it.
    expect(SYSTEM_ORDER_FIELDS.has('array_index')).toBe(false);
    expect(SYSTEM_ORDER_FIELDS.has('value')).toBe(false);
  });
});
