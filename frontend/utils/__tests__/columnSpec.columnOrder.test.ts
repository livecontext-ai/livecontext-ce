/**
 * The saved-order ranking shared by the data grid and the data-source cards.
 *
 * A `column_order` array has TWO writers that disagree on how to name a column:
 * the backend appends the BARE name when a column is created, the grid saves the
 * rendered field, which carries the `data.` mapping-spec prefix. Both spellings
 * live in the same array in production, so a reader that compares them verbatim
 * matches nothing and silently falls back to the arbitrary key order Postgres
 * returns a JSONB object in. But stripping the prefix is not enough on its own:
 * `data.priority` and the `priority` lane collapse onto one key, so the exact
 * spelling has to win when the order carries it.
 */
import { describe, it, expect } from 'vitest';
import { buildColumnOrderRank, columnOrderKey } from '../columnSpec';

describe('columnOrderKey', () => {
  it('strips the mapping-spec prefix so the grid spelling matches the backend one', () => {
    expect(columnOrderKey('data.price')).toBe('price');
  });

  it('leaves a bare name untouched', () => {
    expect(columnOrderKey('price')).toBe('price');
  });

  it('leaves a system column untouched', () => {
    expect(columnOrderKey('created_at')).toBe('created_at');
  });

  it('strips only the LEADING prefix, so a column really called data.x survives', () => {
    expect(columnOrderKey('data.data.x')).toBe('data.x');
  });

  it('does not strip a longer word that merely ends with the prefix', () => {
    expect(columnOrderKey('metadata.source')).toBe('metadata.source');
  });
});

describe('buildColumnOrderRank', () => {
  it('ranks both spellings of a name into one key space', () => {
    const rank = buildColumnOrderRank([
      { field: 'checkbox', order: 0 },
      { field: 'data.company', order: 1 },
      { field: 'email', order: 2 },
    ]);

    expect(rank.of('checkbox')).toBe(0);
    expect(rank.of('data.company')).toBe(1);
    expect(rank.of('company')).toBe(1);
    expect(rank.of('email')).toBe(2);
    expect(rank.of('data.email')).toBe(2);
  });

  it('gives a column its OWN position when the order names both it and a same-named lane', () => {
    // A user column called `priority` renders as `data.priority` and strips to
    // `priority`, the lane's key. Without exact-first the column would inherit
    // the lane's position and the drag that moved it would vanish.
    const rank = buildColumnOrderRank([
      { field: 'checkbox', order: 0 },
      { field: 'priority', order: 1 },
      { field: 'created_at', order: 2 },
      { field: 'data.x', order: 3 },
      { field: 'data.priority', order: 4 },
    ]);

    expect(rank.of('priority')).toBe(1);
    expect(rank.of('data.priority')).toBe(4);
    expect(rank.of('data.x')).toBe(3);
  });

  it('refuses the bare fallback when the bare name is a lane name', () => {
    // The ordinary backend seed: system names, then bare data names. `priority`
    // there is the LANE's entry, so a data column of the same name gets no
    // position from it - the alternative is handing it a position that was
    // never about it. A data column really can be called `priority` or `id`:
    // the reserved-name guard covers CRUD writes only, not a CSV import.
    const rank = buildColumnOrderRank([
      { field: 'checkbox', order: 0 },
      { field: 'index', order: 1 },
      { field: 'id', order: 2 },
      { field: 'priority', order: 3 },
      { field: 'created_at', order: 4 },
      { field: 'city', order: 5 },
    ]);

    // the lanes themselves still resolve
    expect(rank.of('checkbox')).toBe(0);
    expect(rank.of('priority')).toBe(3);
    // ... and no data column inherits one of them
    expect(rank.of('data.index')).toBeUndefined();
    expect(rank.of('data.id')).toBeUndefined();
    expect(rank.of('data.priority')).toBeUndefined();
    expect(rank.of('data.created_at')).toBeUndefined();
    // an ordinary bare data entry is still matched
    expect(rank.of('data.city')).toBe(5);
  });

  it('still honours an EXACT prefixed entry for a lane-named column', () => {
    // Once the grid has saved the arrangement, the order says exactly which is
    // which, and the column must get its own position back.
    const rank = buildColumnOrderRank([
      { field: 'priority', order: 0 },
      { field: 'data.priority', order: 1 },
    ]);

    expect(rank.of('priority')).toBe(0);
    expect(rank.of('data.priority')).toBe(1);
  });

  it('refuses the bridge in the other direction too, so a lane cannot inherit a data column', () => {
    // Only the data column is named. Answering with it would move the lane.
    const rank = buildColumnOrderRank([
      { field: 'data.priority', order: 0 },
      { field: 'data.city', order: 1 },
    ]);

    expect(rank.of('data.priority')).toBe(0);
    expect(rank.of('priority')).toBeUndefined();
    // and an ordinary name still bridges both ways
    expect(rank.of('city')).toBe(1);
  });

  it('keeps the rank of a data column the frontend seed names alongside its lane', () => {
    // `initializeColumnOrder` (root) writes the lane names AND every data
    // column's exact `data.<name>` spelling into the same array. `value` and
    // `array_index` are lanes there, so if they were treated as ambiguous this
    // column would lose a position nothing was ever unsure about.
    const rank = buildColumnOrderRank([
      { field: 'checkbox', order: 0 },
      { field: 'priority', order: 1 },
      { field: 'created_at', order: 2 },
      { field: 'array_index', order: 3 },
      { field: 'value', order: 4 },
      { field: 'data.value', order: 5 },
      { field: 'data.city', order: 6 },
    ]);

    expect(rank.of('data.value')).toBe(5);
    expect(rank.of('data.city')).toBe(6);
  });

  it('keeps the rank of a data column the BACKEND seed names bare, even a lane-shaped name', () => {
    // The backend seed's system list does not contain `value`, so it does not
    // skip a data field of that name: this bare entry is unambiguously the data
    // column's, and discarding it would put the column wherever its name sorts.
    const rank = buildColumnOrderRank([
      { field: 'checkbox', order: 0 },
      { field: 'index', order: 1 },
      { field: 'id', order: 2 },
      { field: 'priority', order: 3 },
      { field: 'created_at', order: 4 },
      { field: 'keyname', order: 5 },
      { field: 'value', order: 6 },
    ]);

    expect(rank.of('data.value')).toBe(6);
    expect(rank.of('data.keyname')).toBe(5);
    // ... while the five names the seed DOES skip stay ambiguous
    expect(rank.of('data.id')).toBeUndefined();
    expect(rank.of('data.index')).toBeUndefined();
  });

  it('returns undefined for a column the order has never heard of', () => {
    expect(buildColumnOrderRank([{ field: 'a' }]).of('data.b')).toBeUndefined();
  });

  it('ranks by ARRAY POSITION, not by the entry own order value', () => {
    // A stored `order` can be stale, duplicated or missing; the array itself is
    // always written in order by every producer.
    const rank = buildColumnOrderRank([
      { field: 'a', order: 99 },
      { field: 'b' },
      { field: 'c', order: 0 },
    ]);

    expect([rank.of('a'), rank.of('b'), rank.of('c')]).toEqual([0, 1, 2]);
  });

  it('keeps the FIRST position when one spelling appears twice', () => {
    const rank = buildColumnOrderRank([
      { field: 'data.price' },
      { field: 'other' },
      { field: 'data.price' },
    ]);

    expect(rank.of('data.price')).toBe(0);
  });

  it('reads the legacy name key when field is absent', () => {
    expect(buildColumnOrderRank([{ name: 'legacy' }]).of('legacy')).toBe(0);
  });

  it('ignores unusable entries while keeping the relative order of the rest', () => {
    const rank = buildColumnOrderRank([
      { field: 'first' },
      null,
      'not-an-object',
      { field: '   ' },
      { field: 42 },
      {},
      { field: 'last' },
    ]);

    expect(rank.size).toBe(2);
    expect(rank.of('first')!).toBeLessThan(rank.of('last')!);
  });

  it('reports an empty rank for a missing or empty order', () => {
    expect(buildColumnOrderRank(undefined).size).toBe(0);
    expect(buildColumnOrderRank(null).size).toBe(0);
    expect(buildColumnOrderRank([]).size).toBe(0);
  });
});
