'use client';

/**
 * Whether the canvas camera follows the work in progress: the step that is running (or
 * waiting on the user) during a run, and the nodes an agent adds while it builds.
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

/**
 * ON unless the user switched it off. Following is what makes a run and an agent build
 * watchable without hunting for the node that moved, so it is the default; a stored
 * `'false'` is the only thing that turns it off.
 */
const DEFAULT_ENABLED = true;

let enabled = DEFAULT_ENABLED;
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
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (stored === 'true') enabled = true;
    else if (stored === 'false') enabled = false;
  } catch {
    // Unreadable storage: stay with the default. The toggle still switches it off for
    // the session.
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
  enabled = DEFAULT_ENABLED;
  hydrated = false;
  listeners.clear();
}
