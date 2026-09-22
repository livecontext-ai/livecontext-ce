'use client';

/**
 * Whether the canvas camera follows the step that is currently running.
 *
 * A remembered preference rather than a one-shot action, for the same reason the
 * file-strip toggle is one: the moment you want to state "keep the running node in
 * front of me" is usually BEFORE the run starts, and a run is exactly when you have
 * no hands free to re-arm it.
 *
 * A module store rather than a context because the two sides of the feature live in
 * different trees: the toggle sits in the canvas toolbar, the thing that knows which
 * step is running sits in the run panel. Wrapping both in a shared provider would mean
 * threading a context through the workflow page for one boolean. This mirrors
 * `canvasNodesStore`, which exists for the same reason.
 */

// Same family and spelling as the other browser-scoped builder preferences
// (`workflow:fileStripExpanded`, `workflow:cursorMode`, `workflow:connectionType`).
// The `lc.workflow.*` prefix is the WORKSPACE-scoped family, whose keys all carry a
// `:<orgId|personal>` segment this preference has no business inventing.
const STORAGE_KEY = 'workflow:runCameraFollow';

let enabled = false;
let hydrated = false;

type Listener = (value: boolean) => void;
const listeners = new Set<Listener>();

/**
 * Reads the stored preference once, lazily.
 *
 * Every access is guarded: an artifact-style origin, a private window, blocked site
 * data or a thumbnail capture can make `localStorage` throw on read as well as on
 * write, and this preference must never be the reason a canvas fails to render.
 */
function hydrate(): void {
  if (hydrated || typeof window === 'undefined') return;
  hydrated = true;
  try {
    enabled = window.localStorage.getItem(STORAGE_KEY) === 'true';
  } catch {
    // Unreadable storage: stay with the default (off), which is the safe posture.
    // Following moves the viewport under the user, so it is opt-in, never inherited
    // from a failure.
  }
}

export function isRunCameraFollowEnabled(): boolean {
  hydrate();
  return enabled;
}

export function setRunCameraFollowEnabled(value: boolean): void {
  hydrate();
  if (enabled === value) return;
  enabled = value;
  try {
    window.localStorage.setItem(STORAGE_KEY, value ? 'true' : 'false');
  } catch {
    // The preference still applies for this session; only its persistence is lost.
  }
  listeners.forEach((listener) => listener(value));
}

export function subscribeRunCameraFollow(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Test seam: drops the in-memory state so each test starts from a known place. */
export function __resetRunCameraFollowForTests(): void {
  enabled = false;
  hydrated = false;
  listeners.clear();
}
