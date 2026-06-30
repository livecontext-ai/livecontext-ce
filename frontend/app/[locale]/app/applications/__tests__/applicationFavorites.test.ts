import { describe, it, expect } from 'vitest';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import { deriveFavoritePubIds, favoriteTargetFor, type AppFavItem } from '../applicationFavorites';

const pub = (id: string): WorkflowPublication => ({ id } as WorkflowPublication);

describe('favoriteTargetFor', () => {
  it('published apps toggle the publication favorite keyed by publication id', () => {
    expect(favoriteTargetFor({ pub: pub('P1'), source: 'published' }))
      .toEqual({ kind: 'publication', id: 'P1' });
  });

  it('published apps ignore any acquired-clone workflowId and still key on the publication', () => {
    // Self-acquired-own-publication carries the clone workflowId but stays a publication favorite.
    expect(favoriteTargetFor({ pub: pub('P1'), source: 'published', workflowId: 'W1' }))
      .toEqual({ kind: 'publication', id: 'P1' });
  });

  it('acquired apps toggle the NATIVE workflow favorite keyed by the local clone id', () => {
    // Regression: a cloud-acquired app must NOT favorite its remote publication id (FK-fails).
    expect(favoriteTargetFor({ pub: pub('CLOUD_PUB'), source: 'acquired', workflowId: 'CLONE_W' }))
      .toEqual({ kind: 'workflow', id: 'CLONE_W' });
  });

  it('acquired app without a clone workflowId is not favoritable (null)', () => {
    expect(favoriteTargetFor({ pub: pub('P1'), source: 'acquired' })).toBeNull();
  });
});

describe('deriveFavoritePubIds', () => {
  const items: AppFavItem[] = [
    { pub: pub('PUB_FAV'), source: 'published' },
    { pub: pub('PUB_NOFAV'), source: 'published' },
    { pub: pub('ACQ_FAV'), source: 'acquired', workflowId: 'W_FAV' },
    { pub: pub('ACQ_NOFAV'), source: 'acquired', workflowId: 'W_NOFAV' },
    { pub: pub('ACQ_NOCLONE'), source: 'acquired' },
  ];

  it('unifies publication favorites (published) and workflow favorites (acquired clones) keyed by publication id', () => {
    const result = deriveFavoritePubIds(
      items,
      new Set(['PUB_FAV']),   // publication favorites
      new Set(['W_FAV']),     // native workflow favorites (acquired clones)
    );
    expect(result).toEqual(new Set(['PUB_FAV', 'ACQ_FAV']));
  });

  it('a published id starred only in the WORKFLOW store is NOT favorited (stores do not cross)', () => {
    const result = deriveFavoritePubIds(
      [{ pub: pub('P1'), source: 'published' }],
      new Set<string>(),
      new Set(['P1']),
    );
    expect(result.has('P1')).toBe(false);
  });

  it('an acquired app whose clone is starred in the PUBLICATION store is NOT favorited', () => {
    const result = deriveFavoritePubIds(
      [{ pub: pub('CLOUD_PUB'), source: 'acquired', workflowId: 'CLONE_W' }],
      new Set(['CLOUD_PUB', 'CLONE_W']),
      new Set<string>(),
    );
    expect(result.size).toBe(0);
  });

  it('an acquired app with no clone id is never favorited', () => {
    const result = deriveFavoritePubIds(
      [{ pub: pub('P1'), source: 'acquired' }],
      new Set<string>(),
      new Set<string>(),
    );
    expect(result.size).toBe(0);
  });

  it('returns an empty set when nothing is starred', () => {
    expect(deriveFavoritePubIds(items, new Set(), new Set()).size).toBe(0);
  });
});
