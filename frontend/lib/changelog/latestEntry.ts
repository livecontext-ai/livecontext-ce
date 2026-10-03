// Relative and straight to the module, not through `@/lib/edition`: the CE e2e suite imports
// this file in a plain Node process, which resolves no path alias and should not load the
// edition index's React hook.
import { IS_MANAGED_CLOUD } from '../edition/edition';

/**
 * The in-app changelog entry this build announces: one per edition, the newest.
 *
 * Why the entry lives in the bundle rather than in a feed: an install announces exactly what it
 * is RUNNING. A cloud tenant and an air-gapped CE box therefore see their panel with no network
 * call, no CDN and no version skew between "what the server advertises" and "what the user
 * actually has". The server owns only the per-user acknowledgement (GET/POST /api/changelog),
 * which is the one part that has to follow a user across devices.
 *
 * Why two entries: some news exists on one edition only. The partner program, its badge and the
 * partner space are managed-cloud features (the badge renders nothing on a self-hosted install),
 * so announcing them on a CE box would point at something that is not there. The managed cloud
 * reads {@link CLOUD_CHANGELOG_ENTRY}, every self-hosted deployment {@link SELF_HOSTED_CHANGELOG_ENTRY}.
 * Each has its own key and its own copy block, even when the news is the same: a shared key would
 * mark one edition's entry as read by the other's acknowledgement.
 *
 * Publishing a new entry (the whole procedure, per edition):
 *   1. drop the media under `frontend/public/changelog/` and DELETE the file no entry points at
 *      any more - only the latest entries are ever shown, so an older asset is dead weight in
 *      every image;
 *   2. point the edition's constant at it and give the entry a NEW `key`;
 *   3. rewrite its copy block (`changelog.<copy>.title` / `.body` / `.mediaAlt`, plus `.action`
 *      when the entry has one) in ALL SIX locale files (`frontend/messages/*.json`), each with a
 *      real translation.
 * A new key is what makes every user see it once. Reusing a key means nobody sees the new copy,
 * because acknowledgements are matched by key equality.
 *
 * Set a constant to `null` to ship a build that announces nothing on that edition. That is the
 * content-level off switch; the deployment-level one is `CHANGELOG_ENABLED=false` on the
 * backend, which needs no rebuild.
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
  /** Which block under `changelog` in the locale files holds the entry's words. */
  copy: ChangelogCopyKey;
  /**
   * A call to action, the panel's primary button, or null for none. Its label is the copy block's
   * `action` string. It is drawn in the gold of the partner program, the only entry that has one
   * so far; an entry that needs another look adds it here.
   */
  action: ChangelogAction | null;
}

/** The copy blocks the locale files carry: one per edition entry. */
export const CHANGELOG_COPY_KEYS = ['latest', 'latestCloud'] as const;
export type ChangelogCopyKey = (typeof CHANGELOG_COPY_KEYS)[number];

export interface ChangelogAction {
  /** In-app path, same rule as `learnMoreUrl`. */
  href: string;
}

/** What every self-hosted deployment (CE, self-hosted enterprise) announces. */
export const SELF_HOSTED_CHANGELOG_ENTRY: ChangelogEntry | null = {
  key: '2026-10-agent-notes-url-views',
  publishedAt: '2026-10-03',
  media: {
    type: 'image',
    src: '/changelog/2026-10-agent-notes-url-views.svg',
    width: 720,
    height: 260,
  },
  learnMoreUrl: '/changelog',
  copy: 'latest',
  action: null,
};

/** What the managed cloud announces: the partner program first, then the rest of the release. */
export const CLOUD_CHANGELOG_ENTRY: ChangelogEntry | null = {
  key: '2026-10-partner-program',
  publishedAt: '2026-10-03',
  media: {
    type: 'image',
    src: '/changelog/2026-10-partner-program.svg',
    width: 720,
    height: 260,
  },
  learnMoreUrl: '/changelog',
  copy: 'latestCloud',
  action: { href: '/partners' },
};

/** The entry of THIS build's edition, frozen at build time like the edition itself. */
export const LATEST_CHANGELOG_ENTRY: ChangelogEntry | null = entryForEdition(IS_MANAGED_CLOUD);

/** The candidate for an edition. Split out so both branches are reachable from a test. */
export function entryForEdition(managedCloud: boolean): ChangelogEntry | null {
  return managedCloud ? CLOUD_CHANGELOG_ENTRY : SELF_HOSTED_CHANGELOG_ENTRY;
}

/** In-app path only: `//evil.example.com` also starts with a slash and leaves the deployment. */
function isInAppPath(href: string): boolean {
  return href.startsWith('/') && !href.startsWith('//');
}

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
  if (entry.learnMoreUrl !== null && !isInAppPath(entry.learnMoreUrl)) return false;
  // An unknown block would render the raw key path as the panel's title.
  if (!(CHANGELOG_COPY_KEYS as readonly string[]).includes(entry.copy)) return false;
  if (entry.action && !isInAppPath(entry.action.href)) return false;
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

/**
 * The entry a self-hosted build announces, whatever edition THIS process resolves. For the CE
 * e2e suite, which runs in a Node process that does not carry the CE build's edition variables
 * and would otherwise resolve the cloud entry.
 */
export function selfHostedEntry(): ChangelogEntry | null {
  return resolveEntry(SELF_HOSTED_CHANGELOG_ENTRY);
}
