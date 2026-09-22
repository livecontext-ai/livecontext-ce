/**
 * The single in-app changelog entry this build announces.
 *
 * Why the entry lives in the bundle rather than in a feed: an install announces exactly what it
 * is RUNNING. A cloud tenant and an air-gapped CE box therefore see the same panel with no
 * network call, no CDN and no version skew between "what the server advertises" and "what the
 * user actually has". The server owns only the per-user acknowledgement (GET/POST
 * /api/changelog), which is the one part that has to follow a user across devices.
 *
 * Publishing a new entry (the whole procedure):
 *   1. drop the media under `frontend/public/changelog/` and DELETE the previous file - only the
 *      latest entry is ever shown, so an older asset is dead weight in every image;
 *   2. point {@link LATEST_CHANGELOG_ENTRY} at it and give the entry a NEW `key`;
 *   3. rewrite `changelog.latest.title` / `.body` / `.mediaAlt` in ALL SIX locale files
 *      (`frontend/messages/*.json`), each with a real translation.
 * A new key is what makes every user see it once. Reusing a key means nobody sees the new copy,
 * because acknowledgements are matched by key equality.
 *
 * Set the constant to `null` to ship a build that announces nothing. That is the content-level
 * off switch; the deployment-level one is `CHANGELOG_ENABLED=false` on the backend, which also
 * removes the sidebar entry and needs no rebuild.
 */

/** An image or a short video illustrating the entry. */
export interface ChangelogMedia {
  /** `image` renders an <img>; `video` renders a muted, looping, autoplaying <video>. */
  type: 'image' | 'video';
  /** Absolute path under `frontend/public`, e.g. `/changelog/whats-new.webp`. */
  src: string;
  /**
   * Still frame for a video, also what a viewer who asked for reduced motion gets instead of the
   * animation. Videos without one degrade to a paused, controllable player.
   */
  poster?: string;
  /** Intrinsic size. Both are required: they reserve the box, so the panel never jumps. */
  width: number;
  height: number;
}

export interface ChangelogEntry {
  /**
   * Stable identity of this entry, and the value stored per user once acknowledged. Compared by
   * EQUALITY, never by ordering: after a rollback an older build asks about its own older key,
   * finds a mismatch and correctly announces what it actually ships.
   */
  key: string;
  /**
   * Publication date (ISO `YYYY-MM-DD`). Used for one decision only: an account created after it
   * is sealed silently instead of being greeted, on its first minute, with news about a
   * capability it never lacked.
   */
  publishedAt: string;
  /** Illustration, or null for a text-only entry. */
  media: ChangelogMedia | null;
  /**
   * Where "see all updates" goes. The full history lives on the public changelog page, which is
   * built from the published releases - this panel deliberately keeps no archive of its own.
   */
  learnMoreUrl: string | null;
}

export const LATEST_CHANGELOG_ENTRY: ChangelogEntry | null = {
  key: '2026-09-classify-jev-own-key',
  publishedAt: '2026-09-21',
  media: {
    type: 'image',
    src: '/changelog/2026-09-classify-jev-own-key.svg',
    width: 720,
    height: 260,
  },
  learnMoreUrl: '/changelog',
};

/**
 * Guards the shape at runtime rather than trusting the type alone: this object is edited by hand
 * on every release, and a typo in `src` or a missing size would ship a broken panel to everyone.
 * Anything malformed announces NOTHING, which is the right failure direction for a courtesy
 * notice - a silent release beats a broken modal in front of every user.
 */
export function isValidEntry(entry: ChangelogEntry | null): entry is ChangelogEntry {
  if (!entry) return false;
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$/.test(entry.key)) return false;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(entry.publishedAt)) return false;
  if (entry.media) {
    const { type, src, poster, width, height } = entry.media;
    if (type !== 'image' && type !== 'video') return false;
    // Local assets only. A remote URL would break an offline install and hand the panel's
    // contents to a third party on every render.
    if (!src.startsWith('/changelog/')) return false;
    if (poster !== undefined && !poster.startsWith('/changelog/')) return false;
    if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return false;
  }
  if (entry.learnMoreUrl !== null) {
    // In-app path only. `//evil.example.com` also starts with a slash and is a fully external
    // URL, so the leading-slash test alone would let the panel link off the deployment.
    if (!entry.learnMoreUrl.startsWith('/') || entry.learnMoreUrl.startsWith('//')) return false;
  }
  return true;
}

/**
 * The entry to announce, or null when the candidate is malformed.
 *
 * Split from {@link currentEntry} so the fallback-to-silence branch is reachable from a test:
 * that branch is the whole safety property of hand-editing this file on every release, and
 * {@link currentEntry} reads a module constant no test can make malformed.
 */
export function resolveEntry(candidate: ChangelogEntry | null): ChangelogEntry | null {
  return isValidEntry(candidate) ? candidate : null;
}

/** The entry this build should announce, or null when there is none to announce. */
export function currentEntry(): ChangelogEntry | null {
  return resolveEntry(LATEST_CHANGELOG_ENTRY);
}
