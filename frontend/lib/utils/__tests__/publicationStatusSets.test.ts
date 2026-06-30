/**
 * Pins the bucketing that lets the agent/interface/table lists resolve every card's marker from a
 * single /publications/my sweep: type-scoped, keyed by agentConfigId (AGENT) or resourceId
 * (TABLE/INTERFACE), ACTIVE→published / PENDING_REVIEW→pending / REJECTED→rejected, everything else
 * (INACTIVE, other types, missing key) ignored → private.
 */
import { describe, it, expect } from 'vitest';
import { buildPublicationStatusSets, type MyPublicationLite } from '../publicationStatusSets';

const pubs: MyPublicationLite[] = [
  { publicationType: 'AGENT', status: 'ACTIVE', agentConfigId: 'a-live' },
  { publicationType: 'AGENT', status: 'PENDING_REVIEW', agentConfigId: 'a-pending' },
  { publicationType: 'AGENT', status: 'REJECTED', agentConfigId: 'a-rej', rejectionReason: 'nope' },
  { publicationType: 'AGENT', status: 'INACTIVE', agentConfigId: 'a-inactive' },
  { publicationType: 'TABLE', status: 'ACTIVE', resourceId: 't-live' },
  { publicationType: 'INTERFACE', status: 'ACTIVE', resourceId: 'i-live' },
  { publicationType: 'SKILL', status: 'ACTIVE', resourceId: 's-live' },
  { publicationType: 'SKILL', status: 'PENDING_REVIEW', resourceId: 's-pending' },
  { publicationType: 'AGENT', status: 'ACTIVE', agentConfigId: null }, // missing key → ignored
];

describe('buildPublicationStatusSets', () => {
  it('AGENT: buckets by agentConfigId and ignores other types / INACTIVE / null keys', () => {
    const { publishedIds, pendingIds, rejectedReasons } = buildPublicationStatusSets(pubs, 'AGENT');
    expect([...publishedIds]).toEqual(['a-live']);
    expect([...pendingIds]).toEqual(['a-pending']);
    expect(rejectedReasons.get('a-rej')).toBe('nope');
    expect(publishedIds.has('a-inactive')).toBe(false); // INACTIVE → private
    expect(publishedIds.has('t-live')).toBe(false);      // TABLE not in AGENT sets
  });

  it('TABLE: buckets by resourceId only', () => {
    const { publishedIds } = buildPublicationStatusSets(pubs, 'TABLE');
    expect([...publishedIds]).toEqual(['t-live']);
  });

  it('INTERFACE: buckets by resourceId only', () => {
    const { publishedIds } = buildPublicationStatusSets(pubs, 'INTERFACE');
    expect([...publishedIds]).toEqual(['i-live']);
  });

  it('SKILL: buckets by resourceId only (the standalone-resource path SkillTab now uses)', () => {
    const { publishedIds, pendingIds } = buildPublicationStatusSets(pubs, 'SKILL');
    expect([...publishedIds]).toEqual(['s-live']);
    expect([...pendingIds]).toEqual(['s-pending']);
    expect(publishedIds.has('i-live')).toBe(false); // INTERFACE not in SKILL sets
  });

  it('REJECTED with no reason maps to null', () => {
    const { rejectedReasons } = buildPublicationStatusSets(
      [{ publicationType: 'TABLE', status: 'REJECTED', resourceId: 't-rej' }],
      'TABLE',
    );
    expect(rejectedReasons.has('t-rej')).toBe(true);
    expect(rejectedReasons.get('t-rej')).toBeNull();
  });

  it('empty input → empty sets', () => {
    const { publishedIds, pendingIds, rejectedReasons } = buildPublicationStatusSets([], 'AGENT');
    expect(publishedIds.size).toBe(0);
    expect(pendingIds.size).toBe(0);
    expect(rejectedReasons.size).toBe(0);
  });
});
