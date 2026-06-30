/**
 * Pure tree helpers for the Files "Move to…" folder picker.
 *
 * The picker is fed the flat list of every manual folder in the workspace
 * (`{id, name, parentFolderId}` rows from `storageApi.getAllFolders`). It builds a
 * hierarchy from `parentFolderId` (null/absent = root) and HIDES any folder the
 * caller is moving - both the folder itself AND its whole subtree - so the user can
 * never pick a destination that would move a folder into itself or one of its own
 * descendants. (The backend rejects such a move too; the picker just doesn't offer
 * it.) Kept dependency-free so it can be unit-tested in isolation.
 */

/** A manual folder row as returned by `storageApi.getAllFolders`. */
export interface MoveFolderRow {
  id: string;
  name: string;
  parentFolderId: string | null;
}

/** A node of the picker's folder tree. `depth` is 0 at the root level. */
export interface MoveFolderNode {
  folder: MoveFolderRow;
  children: MoveFolderNode[];
  depth: number;
}

/**
 * Expand `seedIds` to also include every descendant folder id (walking
 * `parentFolderId` to a fixpoint). Used to exclude a folder being moved AND its
 * whole subtree from the destination picker. Returns a new Set; `seedIds` is not
 * mutated. Ids in `seedIds` that aren't folders are still returned (a moved FILE
 * has no descendants, so it contributes only itself - harmless to the picker,
 * which only renders folders).
 */
export function collectExcludedSubtree(
  allFolders: MoveFolderRow[],
  seedIds: Iterable<string>,
): Set<string> {
  const excluded = new Set<string>(seedIds);
  if (excluded.size === 0) return excluded;
  let changed = true;
  while (changed) {
    changed = false;
    for (const f of allFolders) {
      if (f.parentFolderId && excluded.has(f.parentFolderId) && !excluded.has(f.id)) {
        excluded.add(f.id);
        changed = true;
      }
    }
  }
  return excluded;
}

/**
 * Build the folder tree from the flat list, dropping every folder in `excludeIds`
 * (which should already include the subtrees - call {@link collectExcludedSubtree}
 * first). A node whose parent was excluded is itself excluded transitively because
 * its ancestor chain no longer reaches the root, so it never appears under any
 * rendered parent. Children at each level are ordered by `name` (locale-aware).
 */
export function buildFolderTree(
  allFolders: MoveFolderRow[],
  excludeIds: Set<string> = new Set(),
): MoveFolderNode[] {
  const visible = allFolders.filter((f) => !excludeIds.has(f.id));
  const visibleIds = new Set(visible.map((f) => f.id));

  const childrenByParent = new Map<string | null, MoveFolderRow[]>();
  for (const f of visible) {
    // A folder whose parent is excluded/missing is re-homed to the root so it is
    // still reachable in the picker (it is no longer a descendant of a hidden node).
    const parentKey = f.parentFolderId && visibleIds.has(f.parentFolderId) ? f.parentFolderId : null;
    const bucket = childrenByParent.get(parentKey);
    if (bucket) bucket.push(f);
    else childrenByParent.set(parentKey, [f]);
  }

  const build = (parentId: string | null, depth: number): MoveFolderNode[] =>
    (childrenByParent.get(parentId) ?? [])
      .slice()
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((folder) => ({ folder, children: build(folder.id, depth + 1), depth }));

  return build(null, 0);
}

/**
 * Depth-first flatten of the tree, including only the children of nodes whose id is
 * in `expandedIds` (collapsed branches contribute just their own row). Mirrors the
 * skills picker's expand/collapse rendering.
 */
export function flattenVisibleTree(
  tree: MoveFolderNode[],
  expandedIds: Set<string>,
): MoveFolderNode[] {
  const out: MoveFolderNode[] = [];
  const walk = (nodes: MoveFolderNode[]) => {
    for (const node of nodes) {
      out.push(node);
      if (expandedIds.has(node.folder.id)) walk(node.children);
    }
  };
  walk(tree);
  return out;
}
