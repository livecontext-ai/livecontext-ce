/**
 * Pure helper for the side-panel storage explorer's folder breadcrumb / back
 * affordance. Given the current folder trail (root -> ... -> current), it computes
 * the id to navigate to when the user clicks "back up one level": the
 * last-but-one crumb's id, or null (root) when there's at most one segment. Kept
 * tiny + pure so the back-button target is unit-testable without rendering the
 * whole explorer. Reuses the shared FilesFolderCrumb shape (no new crumb type).
 */
import type { FilesFolderCrumb } from '@/lib/files/filesHeaderBus';

/**
 * The navigation target for a single step up from the deepest folder.
 * - trail [] (already at root) -> null (no-op, stays at root)
 * - trail [A] -> null (root)
 * - trail [A, B, C] -> B.id (the parent of the current folder)
 */
export function backTarget(trail: FilesFolderCrumb[]): string | null {
  if (trail.length <= 1) return null;
  return trail[trail.length - 2].id;
}
