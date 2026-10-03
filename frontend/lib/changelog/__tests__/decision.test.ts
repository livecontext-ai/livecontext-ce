import { describe, expect, it } from 'vitest';
import { decideChangelog, type ChangelogServerState } from '../decision';
import type { ChangelogEntry } from '../latestEntry';

/**
 * The client half of the policy: announce once, never replay a backlog, and honour the seal the
 * server decided. Each rule below has a failure mode that is invisible in a screenshot - the panel
 * that reopens forever, or the one that never opens at all.
 */
describe('decideChangelog', () => {
  const entry: ChangelogEntry = {
    key: '2026-09-whats-new',
    publishedAt: '2026-09-07',
    media: null,
    learnMoreUrl: '/changelog',
    copy: 'latest',
    action: null,
  };

  const state = (over: Partial<ChangelogServerState> = {}): ChangelogServerState => ({
    enabled: true,
    seenKey: null,
    seal: false,
    ...over,
  });

  it('announces to a user who has never acknowledged anything', () => {
    expect(decideChangelog(entry, state())).toBe('announce');
  });

  it('stays quiet once the user acknowledged THIS entry', () => {
    expect(decideChangelog(entry, state({ seenKey: '2026-09-whats-new' }))).toBe('hidden');
  });

  it('announces again when the acknowledged key is a different entry', () => {
    expect(decideChangelog(entry, state({ seenKey: 'previous-entry' }))).toBe('announce');
  });

  it('announces an OLDER entry after a rollback, because keys are compared by equality', () => {
    // The build now ships the August entry while the user acknowledged the September one. Ordering
    // would say "already seen"; equality correctly announces what the user is actually running.
    const olderEntry: ChangelogEntry = { ...entry, key: 'previous-entry', publishedAt: '2026-08-01' };
    expect(decideChangelog(olderEntry, state({ seenKey: '2026-09-whats-new' }))).toBe('announce');
  });

  it('says nothing when the deployment switched the feature off', () => {
    expect(decideChangelog(entry, state({ enabled: false }))).toBe('hidden');
  });

  it('says nothing while the server state has not arrived', () => {
    // Announcing before the answer would show the panel on every load to someone who dismissed it.
    expect(decideChangelog(entry, undefined)).toBe('hidden');
    expect(decideChangelog(entry, null)).toBe('hidden');
  });

  it('says nothing when this build ships no entry', () => {
    expect(decideChangelog(null, state())).toBe('hidden');
  });

  it('seals when the server says this account never lacked the announced change', () => {
    // Sealing rather than hiding: an unacknowledged entry would resurface later, at a random
    // moment, which is worse than not showing it at all.
    expect(decideChangelog(entry, state({ seal: true }))).toBe('seal');
  });

  it('prefers the acknowledgement over the seal flag', () => {
    // A sealed account that already has the row must not be re-sealed on every render: the
    // acknowledgement check comes first and the result is plain 'hidden'.
    expect(decideChangelog(entry, state({ seenKey: '2026-09-whats-new', seal: true }))).toBe('hidden');
  });

  it('ignores a seal flag when the deployment is off', () => {
    expect(decideChangelog(entry, state({ seal: true, enabled: false }))).toBe('hidden');
  });

  it('leaves the timing of the seal entirely to the server', () => {
    // The client deliberately owns no date arithmetic here. The rule ("was this account created
    // after THIS install started announcing the entry?") can only be answered where the install's
    // first sight of the entry is recorded, and the entry's publication date is not that: a
    // self-hosted box adopting a release six months late would seal every user it signed up in
    // between. Two entries with wildly different publication dates must decide identically.
    const oldEntry: ChangelogEntry = { ...entry, publishedAt: '2020-01-01' };
    expect(decideChangelog(oldEntry, state())).toBe('announce');
    expect(decideChangelog(oldEntry, state({ seal: true }))).toBe('seal');
  });
});
