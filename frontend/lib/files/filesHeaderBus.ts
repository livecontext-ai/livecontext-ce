/**
 * Tiny window-event channel that lets the full-page Files browser drive the
 * app header (breadcrumb + back/prev/next/download buttons) and lets the
 * header drive the browser back - the same CustomEvent pattern the workflow,
 * data and interface views already use to talk to {@code AppHeader}.
 *
 * The Files detail view is local component state inside {@code FileBrowser}
 * (not a route), so the header can't derive it from the URL. {@code FileBrowser}
 * broadcasts {@link FilesDetailState} on every change; {@code useBreadcrumbs}
 * (consumed by the header) reflects it. The header emits the COMMAND events back
 * when the user clicks a header button. Keeping the names + payload here means
 * the three call sites can't drift on a magic string.
 */

/** One hop in the manual-folder breadcrumb trail (V313). */
export interface FilesFolderCrumb {
  id: string;
  name: string;
}

export interface FilesDetailState {
  /** True while a single file is open in the focused viewer (vs. the list). */
  open: boolean;
  /** Storage-entry id of the open file (so the header can drive a rename). */
  fileId?: string;
  /** Display name of the open file (for the breadcrumb tail). */
  fileName?: string;
  /** Whether a previous / next sibling exists on the current page. */
  canPrev?: boolean;
  canNext?: boolean;
  /** True while the open file is being downloaded (header spinner). */
  downloading?: boolean;
  /**
   * V313: the manual-folder trail the browser is currently inside (root → … →
   * current). Empty/absent at the top level. Drives the breadcrumb
   * (Files / FolderA / SubB) and the header back-up-one-folder behaviour.
   */
  folderTrail?: FilesFolderCrumb[];
}

/** FileBrowser → header: the focused-viewer state changed. */
export const FILES_DETAIL_CHANGED = 'filesDetailChanged';
/** header → FileBrowser: close the viewer (back to the list). */
export const FILES_DETAIL_BACK = 'filesDetailBack';
/** header → FileBrowser: open the previous sibling. */
export const FILES_DETAIL_PREV = 'filesDetailPrev';
/** header → FileBrowser: open the next sibling. */
export const FILES_DETAIL_NEXT = 'filesDetailNext';
/** header → FileBrowser: download the open file. */
export const FILES_DETAIL_DOWNLOAD = 'filesDetailDownload';
/**
 * header/breadcrumb → FileBrowser: navigate to a manual folder (V313). The
 * payload is the target folder id, or null to return to the root. Used by the
 * breadcrumb folder crumbs and the header back-up-one-folder action.
 */
export const FILES_FOLDER_NAVIGATE = 'filesFolderNavigate';

/** A header command (no payload) the FileBrowser listens for. */
export type FilesDetailCommand =
  | typeof FILES_DETAIL_BACK
  | typeof FILES_DETAIL_PREV
  | typeof FILES_DETAIL_NEXT
  | typeof FILES_DETAIL_DOWNLOAD;

/** FileBrowser: broadcast the current focused-viewer state to the header. */
export function emitFilesDetailState(state: FilesDetailState): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<FilesDetailState>(FILES_DETAIL_CHANGED, { detail: state }));
}

/** Header: subscribe to focused-viewer state changes. Returns an unsubscribe fn. */
export function onFilesDetailState(handler: (state: FilesDetailState) => void): () => void {
  if (typeof window === 'undefined') return () => {};
  const listener = (e: Event) => handler((e as CustomEvent<FilesDetailState>).detail);
  window.addEventListener(FILES_DETAIL_CHANGED, listener);
  return () => window.removeEventListener(FILES_DETAIL_CHANGED, listener);
}

/** Header: fire a command at the FileBrowser. */
export function emitFilesDetailCommand(command: FilesDetailCommand): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent(command));
}

/** FileBrowser: subscribe to a header command. Returns an unsubscribe fn. */
export function onFilesDetailCommand(command: FilesDetailCommand, handler: () => void): () => void {
  if (typeof window === 'undefined') return () => {};
  const listener = () => handler();
  window.addEventListener(command, listener);
  return () => window.removeEventListener(command, listener);
}

/** header/breadcrumb: navigate the FileBrowser to a manual folder (null = root). */
export function emitFilesFolderNavigate(folderId: string | null): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<string | null>(FILES_FOLDER_NAVIGATE, { detail: folderId }));
}

/** FileBrowser: subscribe to folder-navigation requests. Returns an unsubscribe fn. */
export function onFilesFolderNavigate(handler: (folderId: string | null) => void): () => void {
  if (typeof window === 'undefined') return () => {};
  const listener = (e: Event) => handler((e as CustomEvent<string | null>).detail);
  window.addEventListener(FILES_FOLDER_NAVIGATE, listener);
  return () => window.removeEventListener(FILES_FOLDER_NAVIGATE, listener);
}
