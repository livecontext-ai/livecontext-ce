import { existsSync, readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CHANGELOG_COPY_KEYS,
  CLOUD_CHANGELOG_ENTRY,
  LATEST_CHANGELOG_ENTRY,
  SELF_HOSTED_CHANGELOG_ENTRY,
  currentEntry,
  entryForEdition,
  isValidEntry,
  resolveEntry,
  selfHostedEntry,
  type ChangelogEntry,
} from '../latestEntry';

/** Both editions' entries: each is a hand edit that ships to every user of its edition. */
const SHIPPED = [
  ['self-hosted', SELF_HOSTED_CHANGELOG_ENTRY],
  ['cloud', CLOUD_CHANGELOG_ENTRY],
] as const;

const LOCALES = ['en', 'fr', 'de', 'es', 'pt', 'zh'] as const;
const REPO_FRONTEND = path.resolve(__dirname, '../../..');

/** Only the parts of a locale file this test reads. */
interface LocaleMessages {
  changelog?: {
    whatsNew?: string;
    dismiss?: string;
    learnMore?: string;
    latest?: Record<string, string>;
    latestCloud?: Record<string, string>;
  };
  sidebar?: Record<string, string>;
}

function messages(locale: string): LocaleMessages {
  return JSON.parse(readFileSync(path.join(REPO_FRONTEND, 'messages', `${locale}.json`), 'utf8'));
}

/**
 * The entries are edited by hand on every release, in three places that must agree: this file, the
 * media directory and six locale files. Every check below is one of those hand edits going wrong
 * in a way nobody sees until the panel is in front of every user.
 */
describe('the shipped changelog entries', () => {
  it.each(SHIPPED)('the %s entry is valid, so that edition actually announces something', (_, entry) => {
    // If this fails, the release ships a silent build on that edition: currentEntry() returns
    // null and no user ever sees the note that was written for them.
    expect(isValidEntry(entry)).toBe(true);
  });

  it('gives each edition its own key and its own copy block', () => {
    // A shared key would mark the other edition's news as read; a shared block would show one
    // edition the other's words (the partner program on a self-hosted install, where it does
    // not exist).
    expect(SELF_HOSTED_CHANGELOG_ENTRY?.key).not.toBe(CLOUD_CHANGELOG_ENTRY?.key);
    expect(SELF_HOSTED_CHANGELOG_ENTRY?.copy).not.toBe(CLOUD_CHANGELOG_ENTRY?.copy);
  });

  it('never sends a self-hosted reader to a page that exists on the managed cloud only', () => {
    // The partner program, its badge and the partner space are managed-cloud features: a link
    // there from a self-hosted install points at nothing.
    const links = [SELF_HOSTED_CHANGELOG_ENTRY?.action?.href, SELF_HOSTED_CHANGELOG_ENTRY?.learnMoreUrl]
      .filter((href): href is string => !!href);
    for (const href of links) {
      expect(href, `self-hosted entry links to ${href}`).not.toMatch(/^(\/[a-z]{2})?\/(partners|app\/settings\/partner)\b/);
    }
  });

  it('maps the managed cloud to the cloud entry and everything else to the self-hosted one', () => {
    expect(entryForEdition(true)).toBe(CLOUD_CHANGELOG_ENTRY);
    expect(entryForEdition(false)).toBe(SELF_HOSTED_CHANGELOG_ENTRY);
  });

  it.each(SHIPPED)('the %s entry points at a media file that exists in the repo', (_, entry) => {
    const media = entry?.media;
    if (!media) return;
    // A path typo passes every type check and every unit test, and renders as a broken image in
    // the panel. The file has to be on disk, under public/, or the entry is not shippable.
    expect(existsSync(path.join(REPO_FRONTEND, 'public', media.src))).toBe(true);
    if (media.poster) {
      expect(existsSync(path.join(REPO_FRONTEND, 'public', media.poster))).toBe(true);
    }
  });

  it('keeps no media file that no entry points at', () => {
    // Every image ships every file under public/, and only the latest entries are ever shown.
    const used = new Set(SHIPPED.flatMap(([, entry]) => [entry?.media?.src, entry?.media?.poster]).filter(Boolean));
    const onDisk = readdirSync(path.join(REPO_FRONTEND, 'public', 'changelog')).map((f) => `/changelog/${f}`);
    expect(onDisk.filter((src) => !used.has(src))).toEqual([]);
  });

  it.each(SHIPPED)('the %s entry has its copy translated in every locale, none left on the English string', (_, entry) => {
    const block = entry!.copy;
    const keys = entry!.action ? ['title', 'body', 'mediaAlt', 'action'] as const : ['title', 'body', 'mediaAlt'] as const;
    const en = messages('en').changelog?.[block];
    for (const key of keys) {
      expect(en?.[key], `en.changelog.${block}.${key} is missing`).toBeTruthy();
    }

    for (const locale of LOCALES.filter((l) => l !== 'en')) {
      const other = messages(locale).changelog?.[block];
      for (const key of keys) {
        expect(other?.[key], `${locale}.changelog.${block}.${key} is missing`).toBeTruthy();
        // A copied English string is the failure this catches: the key exists, parity tooling is
        // happy, and that language's users read English.
        expect(other![key], `${locale}.changelog.${block}.${key} is the English string`).not.toBe(en![key]);
      }
    }
  });

  it('has the shared strings in every locale and no menu entry', () => {
    for (const locale of LOCALES.filter((l) => l !== 'en')) {
      const other = messages(locale).changelog;
      for (const key of ['whatsNew', 'dismiss', 'learnMore'] as const) {
        expect(other?.[key], `${locale}.changelog.${key} is missing`).toBeTruthy();
      }
      // No sidebar strings: the panel deliberately has no entry point in the app chrome. It opens
      // by itself once and links to the changelog page; there is nothing to label in a menu.
      expect(messages(locale).sidebar?.whatsNew, `${locale}.sidebar.whatsNew should not exist`).toBeUndefined();
    }
  });

  it('carries no em-dash or en-dash in any locale, per the writing rule', () => {
    for (const locale of LOCALES) {
      const changelog = JSON.stringify(messages(locale).changelog);
      expect(changelog, `${locale} changelog copy contains a dash that reads as AI-generated`)
        .not.toMatch(/[--]/);
    }
  });
});

describe('isValidEntry', () => {
  const valid: ChangelogEntry = {
    key: '2026-09-whats-new',
    publishedAt: '2026-09-07',
    media: { type: 'image', src: '/changelog/x.svg', width: 1200, height: 630 },
    learnMoreUrl: '/changelog',
    copy: 'latest',
    action: null,
  };

  it('accepts a well-formed entry, with or without media', () => {
    expect(isValidEntry(valid)).toBe(true);
    expect(isValidEntry({ ...valid, media: null })).toBe(true);
    expect(isValidEntry({ ...valid, learnMoreUrl: null })).toBe(true);
  });

  it('rejects nothing to announce', () => {
    expect(isValidEntry(null)).toBe(false);
  });

  it('rejects a key the server would refuse to store', () => {
    // The server validates the same alphabet. A key rejected there would leave the panel
    // reopening forever, because the acknowledgement never lands.
    expect(isValidEntry({ ...valid, key: 'has space' })).toBe(false);
    expect(isValidEntry({ ...valid, key: '-leading' })).toBe(false);
    expect(isValidEntry({ ...valid, key: 'a'.repeat(121) })).toBe(false);
  });

  it('rejects a malformed publication date, which drives the new-account rule', () => {
    expect(isValidEntry({ ...valid, publishedAt: '2026-9-7' })).toBe(false);
    expect(isValidEntry({ ...valid, publishedAt: 'yesterday' })).toBe(false);
  });

  it('rejects remote media, which would break an offline install and leak the render', () => {
    expect(isValidEntry({ ...valid, media: { ...valid.media!, src: 'https://cdn.example.com/x.png' } })).toBe(false);
    expect(isValidEntry({ ...valid, media: { ...valid.media!, src: '/landing/x.png' } })).toBe(false);
    expect(isValidEntry({
      ...valid,
      media: { ...valid.media!, type: 'video', src: '/changelog/x.mp4', poster: 'https://evil.example/p.png' },
    })).toBe(false);
  });

  it('rejects media without usable dimensions, which would make the panel jump', () => {
    expect(isValidEntry({ ...valid, media: { ...valid.media!, width: 0 } })).toBe(false);
    expect(isValidEntry({ ...valid, media: { ...valid.media!, height: Number.NaN } })).toBe(false);
  });

  it('rejects an unknown media type', () => {
    expect(isValidEntry({ ...valid, media: { ...valid.media!, type: 'gif' as never } })).toBe(false);
  });

  it('rejects an external learn-more link', () => {
    expect(isValidEntry({ ...valid, learnMoreUrl: 'https://example.com' })).toBe(false);
  });

  it('rejects a protocol-relative learn-more link, which leaves the deployment entirely', () => {
    expect(isValidEntry({ ...valid, learnMoreUrl: '//evil.example.com' })).toBe(false);
  });

  it('accepts every copy block the locale files carry, and nothing else', () => {
    for (const copy of CHANGELOG_COPY_KEYS) {
      expect(isValidEntry({ ...valid, copy })).toBe(true);
    }
    // An unknown block renders the raw key path ("changelog.latestCe.title") as the title.
    expect(isValidEntry({ ...valid, copy: 'latestCe' as never })).toBe(false);
  });

  it('accepts an in-app call to action', () => {
    expect(isValidEntry({ ...valid, action: { href: '/partners' } })).toBe(true);
  });

  it('rejects a call to action that leaves the deployment, like the learn-more link', () => {
    expect(isValidEntry({ ...valid, action: { href: 'https://example.com' } })).toBe(false);
    expect(isValidEntry({ ...valid, action: { href: '//evil.example.com' } })).toBe(false);
  });
});

describe('resolveEntry', () => {
  const valid: ChangelogEntry = {
    key: '2026-09-whats-new',
    publishedAt: '2026-09-07',
    media: null,
    learnMoreUrl: '/changelog',
    copy: 'latest',
    action: null,
  };

  it('announces a valid entry unchanged', () => {
    expect(resolveEntry(valid)).toBe(valid);
  });

  it('falls back to SILENCE on a malformed entry rather than announcing it', () => {
    // This branch is the safety property of hand-editing the entry on every release: a bad edit
    // ships a build that announces nothing, never one that puts a broken panel in front of
    // every user. It is reachable only through resolveEntry, which is why it exists.
    expect(resolveEntry({ ...valid, key: '' })).toBeNull();
    expect(resolveEntry({ ...valid, publishedAt: 'yesterday' })).toBeNull();
    expect(resolveEntry(null)).toBeNull();
  });

  it('is what currentEntry answers with, so the shipped entry goes through the same gate', () => {
    expect(currentEntry()).toEqual(resolveEntry(LATEST_CHANGELOG_ENTRY));
  });

  it('gives the CE e2e suite the self-hosted entry, never the cloud one', () => {
    // The suite runs in a Node process without the CE build's edition variables: reading the
    // build's own entry there would acknowledge the cloud key, and the panel would ambush every
    // CE spec again.
    expect(selfHostedEntry()?.key).toBe(SELF_HOSTED_CHANGELOG_ENTRY?.key);
    expect(selfHostedEntry()?.key).not.toBe(CLOUD_CHANGELOG_ENTRY?.key);
  });
});

/**
 * The edition is frozen at build time from NEXT_PUBLIC_* variables, so each case re-imports the
 * module under its own environment. The managed cloud is the only edition with the partner
 * program: a self-hosted ENTERPRISE build resolves EDITION to 'cloud' yet is not managed, and must
 * get the self-hosted entry. Keying the choice on IS_CLOUD instead of IS_MANAGED_CLOUD would show
 * those installs a partner button that leads nowhere, and only these cases would notice.
 */
describe('the entry a build announces, by deployment', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  async function entryUnder(appEdition: string, authMode: string) {
    vi.resetModules();
    vi.stubEnv('NEXT_PUBLIC_APP_EDITION', appEdition);
    vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', authMode);
    return import('../latestEntry');
  }

  it.each([
    ['managed cloud', 'cloud', 'cloud', 'oidc'],
    ['dedicated cloud', 'cloud', 'dedicated-cloud', 'oidc'],
    ['community edition', 'self-hosted', 'ce', 'embedded'],
    ['self-hosted enterprise', 'self-hosted', 'self-hosted-enterprise', 'keycloak'],
    ['cloud claimed with embedded auth', 'self-hosted', 'cloud', 'embedded'],
  ])('a %s build announces the %s entry', async (_, expected, appEdition, authMode) => {
    const mod = await entryUnder(appEdition, authMode);
    const want = expected === 'cloud' ? mod.CLOUD_CHANGELOG_ENTRY : mod.SELF_HOSTED_CHANGELOG_ENTRY;
    expect(mod.LATEST_CHANGELOG_ENTRY).toBe(want);
    expect(mod.currentEntry()?.key).toBe(want?.key);
    // Whatever the build, the e2e helper still names the self-hosted entry.
    expect(mod.selfHostedEntry()?.key).toBe(mod.SELF_HOSTED_CHANGELOG_ENTRY?.key);
  });
});
