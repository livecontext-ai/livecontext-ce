'use client';

import { FileBrowser } from '@/components/files/FileBrowser';
import { AuthenticatedView } from './AuthenticatedView';

/**
 * FilesView - full-page file browser listing every real file in the active
 * workspace (uploads, generated, chat, interface, downloaded). Flat (no folder
 * hierarchy) with rich filtering, mirroring the side-panel Storage Explorer but
 * laid out as a first-class page. Uses the standard page-scroll layout (NOT the
 * {@code overflow} mode) so the whole page scrolls under the app header - same as
 * the Agents / Interfaces / Data list pages - instead of an inner scrollbar
 * confined to the grid.
 */
export function FilesView() {
  return (
    <AuthenticatedView maxWidth="max-w-6xl">
      <FileBrowser />
    </AuthenticatedView>
  );
}
