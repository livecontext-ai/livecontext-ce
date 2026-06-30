import { describe, it, expect } from 'vitest';
import {
  buildFolderTree,
  collectExcludedSubtree,
  flattenVisibleTree,
  type MoveFolderRow,
} from '../moveFolderTree';

/**
 * Folder shape used across the tests:
 *   root-a            (no parent)
 *     a-child         (parent = root-a)
 *       a-grandchild  (parent = a-child)
 *   root-b            (no parent)
 */
const FOLDERS: MoveFolderRow[] = [
  { id: 'a-grandchild', name: 'A grandchild', parentFolderId: 'a-child' },
  { id: 'root-b', name: 'Root B', parentFolderId: null },
  { id: 'a-child', name: 'A child', parentFolderId: 'root-a' },
  { id: 'root-a', name: 'Root A', parentFolderId: null },
];

describe('buildFolderTree', () => {
  it('builds the hierarchy from parentFolderId, root = null parent, sorted by name per level', () => {
    const tree = buildFolderTree(FOLDERS);
    // Two roots, alphabetised: Root A before Root B.
    expect(tree.map((n) => n.folder.id)).toEqual(['root-a', 'root-b']);
    expect(tree[0].depth).toBe(0);
    // Root A → A child → A grandchild.
    const aChild = tree[0].children;
    expect(aChild.map((n) => n.folder.id)).toEqual(['a-child']);
    expect(aChild[0].depth).toBe(1);
    expect(aChild[0].children.map((n) => n.folder.id)).toEqual(['a-grandchild']);
    expect(aChild[0].children[0].depth).toBe(2);
    // Root B is a leaf.
    expect(tree[1].children).toEqual([]);
  });

  it('treats an absent/missing parentFolderId as a root node', () => {
    const orphan: MoveFolderRow[] = [{ id: 'x', name: 'X', parentFolderId: 'ghost' }];
    const tree = buildFolderTree(orphan);
    // 'ghost' is not a visible folder → 'x' is re-homed to the root so it's still pickable.
    expect(tree.map((n) => n.folder.id)).toEqual(['x']);
    expect(tree[0].depth).toBe(0);
  });
});

describe('collectExcludedSubtree', () => {
  it('expands a seed folder to itself + all descendants (fixpoint over nesting)', () => {
    const excluded = collectExcludedSubtree(FOLDERS, ['root-a']);
    expect([...excluded].sort()).toEqual(['a-child', 'a-grandchild', 'root-a']);
  });

  it('returns an empty set for no seeds (nothing excluded)', () => {
    expect(collectExcludedSubtree(FOLDERS, []).size).toBe(0);
  });

  it('does not mutate the seed iterable input', () => {
    const seed = new Set(['a-child']);
    const out = collectExcludedSubtree(FOLDERS, seed);
    expect([...seed]).toEqual(['a-child']); // untouched
    expect([...out].sort()).toEqual(['a-child', 'a-grandchild']);
  });
});

describe('buildFolderTree with exclusions', () => {
  it('hides a moved folder AND its whole subtree from the destination tree', () => {
    // Moving root-a: neither it, A child, nor A grandchild may be offered as a target.
    const excluded = collectExcludedSubtree(FOLDERS, ['root-a']);
    const tree = buildFolderTree(FOLDERS, excluded);
    expect(tree.map((n) => n.folder.id)).toEqual(['root-b']);
    expect(tree[0].children).toEqual([]);
  });

  it('hiding a middle folder also hides its descendants (transitively unreachable)', () => {
    const excluded = collectExcludedSubtree(FOLDERS, ['a-child']);
    const tree = buildFolderTree(FOLDERS, excluded);
    // Root A stays (it's not moved) but its excluded child + grandchild are gone.
    expect(tree.map((n) => n.folder.id)).toEqual(['root-a', 'root-b']);
    expect(tree[0].children).toEqual([]);
  });
});

describe('flattenVisibleTree', () => {
  it('includes children only for expanded nodes (collapsed branches contribute only their own row)', () => {
    const tree = buildFolderTree(FOLDERS);
    // Nothing expanded → only the two roots.
    expect(flattenVisibleTree(tree, new Set()).map((n) => n.folder.id)).toEqual(['root-a', 'root-b']);
    // Expand root-a → its child appears, but the grandchild stays hidden (a-child collapsed).
    expect(flattenVisibleTree(tree, new Set(['root-a'])).map((n) => n.folder.id)).toEqual([
      'root-a', 'a-child', 'root-b',
    ]);
    // Expand both → the whole branch unfolds in DFS order.
    expect(flattenVisibleTree(tree, new Set(['root-a', 'a-child'])).map((n) => n.folder.id)).toEqual([
      'root-a', 'a-child', 'a-grandchild', 'root-b',
    ]);
  });
});

describe('robustness - cyclic / self-referential parent chains never hang', () => {
  // A folder has exactly one parentFolderId, so a cycle is unreachable from the null root
  // bucket - buildFolderTree never descends into it (returns no node) rather than recursing
  // forever, and collectExcludedSubtree's fixpoint terminates. These lock that in.
  it('a 2-cycle (A.parent=B, B.parent=A) terminates and yields no reachable root node', () => {
    const cyclic: MoveFolderRow[] = [
      { id: 'A', name: 'A', parentFolderId: 'B' },
      { id: 'B', name: 'B', parentFolderId: 'A' },
    ];
    expect(buildFolderTree(cyclic)).toEqual([]);
  });

  it('a self-parent (A.parent=A) terminates and yields no root node', () => {
    expect(buildFolderTree([{ id: 'A', name: 'A', parentFolderId: 'A' }])).toEqual([]);
  });

  it('collectExcludedSubtree reaches a fixpoint on a cyclic chain (no infinite loop)', () => {
    const cyclic: MoveFolderRow[] = [
      { id: 'A', name: 'A', parentFolderId: 'B' },
      { id: 'B', name: 'B', parentFolderId: 'A' },
    ];
    // Seeding A pulls in B (its child), then the fixpoint stops - both, not a hang.
    expect(collectExcludedSubtree(cyclic, ['A'])).toEqual(new Set(['A', 'B']));
  });
});
