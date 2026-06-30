// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it } from 'vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';

import {
  DataSourceColumnIcons,
  getDataSourceColumns,
  normalizeColumnType,
} from '../DataSourceColumnIcons';
import { COLUMN_TYPE_META } from '@/components/data-table/visualHelpers';

afterEach(() => cleanup());

describe('getDataSourceColumns', () => {
  it('counts user-facing columns and reads types from snake_case mapping_spec', () => {
    const info = getDataSourceColumns({
      mapping_spec: {
        title: { type: 'text' },
        score: { type: 'number' },
        embedding: { type: 'vector' },
      },
    } as any);
    expect(info.count).toBe(3);
    expect(info.types).toEqual(['text', 'number', 'vector']);
  });

  it('excludes bookkeeping/system columns from the count + icons', () => {
    const info = getDataSourceColumns({
      mapping_spec: {
        id: { type: 'number' },
        tenant_id: { type: 'text' },
        created_at: { type: 'date' },
        updated_at: { type: 'date' },
        priority: { type: 'number' },
        data_source_id: { type: 'number' },
        name: { type: 'text' },
      },
    } as any);
    expect(info.count).toBe(1);
    expect(info.types).toEqual(['text']);
  });

  it('orders columns by column_order when its fields line up with the spec', () => {
    const info = getDataSourceColumns({
      mapping_spec: { a: { type: 'text' }, b: { type: 'number' }, c: { type: 'date' } },
      column_order: [{ field: 'c' }, { field: 'a' }, { field: 'b' }],
    } as any);
    expect(info.types).toEqual(['date', 'text', 'number']);
  });

  it('strips a leading "data." prefix from column_order fields when matching', () => {
    const info = getDataSourceColumns({
      mapping_spec: { a: { type: 'text' }, b: { type: 'number' } },
      column_order: [{ field: 'data.b' }, { field: 'data.a' }],
    } as any);
    expect(info.types).toEqual(['number', 'text']);
  });

  it('falls back to camelCase mappingSpec when snake_case is absent', () => {
    const info = getDataSourceColumns({ mappingSpec: { x: { type: 'email' } } } as any);
    expect(info.count).toBe(1);
    expect(info.types).toEqual(['email']);
  });

  it('returns an empty result for a table with no columns', () => {
    expect(getDataSourceColumns({} as any)).toEqual({ count: 0, types: [] });
  });
});

describe('normalizeColumnType', () => {
  it('lowercases known types so an uppercase backend enum name still maps to an icon', () => {
    expect(normalizeColumnType('VECTOR')).toBe('vector');
    expect(normalizeColumnType('Text')).toBe('text');
  });

  it('falls back to "text" for unknown or missing types', () => {
    expect(normalizeColumnType(undefined)).toBe('text');
    expect(normalizeColumnType('not-a-real-type')).toBe('text');
  });
});

describe('DataSourceColumnIcons', () => {
  it('renders icon bubbles up to the cap, then a single "+N" overflow bubble', () => {
    render(
      <DataSourceColumnIcons
        types={['text', 'number', 'date', 'email', 'phone', 'url', 'vector']}
        maxDisplay={5}
      />,
    );
    // 7 types, cap 5 -> first 5 icons + "+2"; 'url' and 'vector' are collapsed.
    expect(screen.getByText('+2')).toBeInTheDocument();
    expect(screen.getByTitle('+2 more')).toBeInTheDocument();
    expect(screen.getByTitle('text')).toBeInTheDocument(); // displayed (1st)
    expect(screen.queryByTitle('vector')).not.toBeInTheDocument(); // collapsed (7th)
  });

  it('renders no overflow bubble when the column set fits under the cap', () => {
    render(<DataSourceColumnIcons types={['text', 'number']} maxDisplay={5} />);
    expect(screen.queryByText(/^\+\d/)).not.toBeInTheDocument();
  });

  it('renders nothing for an empty column set', () => {
    const { container } = render(<DataSourceColumnIcons types={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('tints each chip by its column type (reuses the COLUMN_TYPE_META.badgeClass palette)', () => {
    render(<DataSourceColumnIcons types={['date', 'number', 'email']} maxDisplay={5} />);
    for (const type of ['date', 'number', 'email'] as const) {
      const chip = screen.getByTitle(type);
      // every class of that type's badgeClass (e.g. bg-blue-100 + text-blue-700) is applied
      for (const cls of COLUMN_TYPE_META[type].badgeClass.split(' ')) {
        expect(chip.className).toContain(cls);
      }
    }
    // the chips are genuinely differently coloured (not all the old uniform slate)
    expect(screen.getByTitle('date').className).not.toBe(screen.getByTitle('number').className);
  });

  it('keeps the "+N" overflow chip neutral (not type-coloured)', () => {
    render(
      <DataSourceColumnIcons types={['date', 'number', 'date', 'number', 'date', 'email', 'phone']} maxDisplay={5} />,
    );
    const overflow = screen.getByTitle('+2 more');
    expect(overflow.className).toContain('bg-white');
    expect(overflow.className).not.toContain('bg-blue-100');
  });
});
