/// <reference types="@testing-library/jest-dom" />
// @vitest-environment jsdom
import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, act } from '@testing-library/react';
import {
  createSnapshotLookups,
  findInPlans,
  getActivePublicPreview,
  hasSnapshotPrefix,
  PublicationSnapshotProvider,
} from '../PublicationSnapshotContext';

describe('PublicationSnapshotContext lookups', () => {
  describe('hasSnapshotPrefix', () => {
    it('returns true when at least one key on the object has the prefix', () => {
      const obj = { id: 'i1', _snapshot_htmlTemplate: '<p/>' };
      expect(hasSnapshotPrefix(obj, '_snapshot_')).toBe(true);
    });

    it('returns true even when every prefixed value is null - only the marker matters', () => {
      // This is the path the publication backend takes when an interface
      // entity could not be loaded: it still writes _snapshot_* keys with null
      // values so the frontend renders empty instead of falling back to the
      // auth'd /api/interfaces/{id} endpoint (which 401s for anonymous viewers
      // and leaks publisher data for authenticated ones).
      const obj = {
        id: 'i1',
        _snapshot_htmlTemplate: null,
        _snapshot_cssTemplate: null,
        _snapshot_jsTemplate: null,
      };
      expect(hasSnapshotPrefix(obj, '_snapshot_')).toBe(true);
    });

    it('returns false on a fresh interface entry that was never enriched', () => {
      const obj = { id: 'i1', label: 'My Form' };
      expect(hasSnapshotPrefix(obj, '_snapshot_')).toBe(false);
    });

    it('returns false on null / non-object inputs', () => {
      expect(hasSnapshotPrefix(null, '_snapshot_')).toBe(false);
      expect(hasSnapshotPrefix(undefined, '_snapshot_')).toBe(false);
      expect(hasSnapshotPrefix('string', '_snapshot_')).toBe(false);
    });
  });

  describe('createSnapshotLookups.getInterfaceSnapshot', () => {
    it('returns the snapshot when the interface is enriched in plan.interfaces[]', () => {
      const planSnapshot = {
        interfaces: [
          {
            id: 'iface-1',
            _snapshot_htmlTemplate: '<h1>Hi</h1>',
            _snapshot_cssTemplate: 'h1 { color: red; }',
            _snapshot_jsTemplate: 'console.log(1)',
            _snapshot_data: { foo: 'bar' },
          },
        ],
      };
      const { getInterfaceSnapshot } = createSnapshotLookups(planSnapshot);
      const snap = getInterfaceSnapshot('iface-1');
      expect(snap).toEqual({
        htmlTemplate: '<h1>Hi</h1>',
        cssTemplate: 'h1 { color: red; }',
        jsTemplate: 'console.log(1)',
        data: { foo: 'bar' },
      });
    });

    it('returns the snapshot with null fields when the entity was missing at publish time', () => {
      // Regression: when InterfaceResourceStrategy / enrichPlanWithInterfaceData
      // could not load the entity (deleted, agent-only reference, …) it still
      // writes _snapshot_* keys with null values. Reading must return the
      // null'd snapshot rather than null itself, so InterfacePanelContent
      // doesn't fall back to a live fetch.
      const planSnapshot = {
        interfaces: [
          {
            id: 'iface-orphan',
            _snapshot_htmlTemplate: null,
            _snapshot_cssTemplate: null,
            _snapshot_jsTemplate: null,
            _snapshot_data: null,
          },
        ],
      };
      const { getInterfaceSnapshot } = createSnapshotLookups(planSnapshot);
      const snap = getInterfaceSnapshot('iface-orphan');
      expect(snap).not.toBeNull();
      expect(snap).toEqual({
        htmlTemplate: null,
        cssTemplate: null,
        jsTemplate: null,
        data: null,
      });
    });

    it('returns null when the interface id is unknown', () => {
      const { getInterfaceSnapshot } = createSnapshotLookups({ interfaces: [] });
      expect(getInterfaceSnapshot('does-not-exist')).toBeNull();
    });

    it('returns null when the interface is in the plan but lacks the _snapshot_ marker', () => {
      // This is what would happen for an OLD publication created before the
      // enrichment step existed. The lookup must NOT return a stub - callers
      // detect null and either render empty or refuse the live fallback.
      const planSnapshot = {
        interfaces: [{ id: 'iface-legacy', label: 'Legacy form' }],
      };
      const { getInterfaceSnapshot } = createSnapshotLookups(planSnapshot);
      expect(getInterfaceSnapshot('iface-legacy')).toBeNull();
    });

    it('walks into _snapshot_subworkflows so a sub-workflow interface is reachable', () => {
      const planSnapshot = {
        interfaces: [],
        _snapshot_subworkflows: {
          'sub-1': {
            plan: {
              interfaces: [
                {
                  id: 'sub-iface',
                  _snapshot_htmlTemplate: '<sub/>',
                },
              ],
            },
          },
        },
      };
      const { getInterfaceSnapshot } = createSnapshotLookups(planSnapshot);
      expect(getInterfaceSnapshot('sub-iface')).toEqual({
        htmlTemplate: '<sub/>',
        cssTemplate: undefined,
        jsTemplate: undefined,
        data: undefined,
      });
    });
  });

  describe('findInPlans', () => {
    it('returns null on null/non-object inputs without throwing', () => {
      expect(findInPlans(null, () => 'x')).toBeNull();
      expect(findInPlans(undefined, () => 'x')).toBeNull();
      expect(findInPlans('string' as any, () => 'x')).toBeNull();
    });
  });
});

describe('PublicationSnapshotProvider - store lifecycle', () => {
  // Regression: when chat cards mount back-to-back the SECOND card's mount
  // effect can fire before the FIRST card's cleanup. With an unguarded
  // cleanup, A's unmount wipes the store while B is already published - the
  // tree thinks it's no longer in a preview and re-engages live fetches.
  // PublicationSnapshotProvider must only clear when the LIVE store identity
  // still matches the cleanup's published id.

  it('cleanup of card A does NOT wipe the store when card B is the active publication', () => {
    const planA = { interfaces: [] };
    const planB = { interfaces: [] };

    const cardA = render(
      <PublicationSnapshotProvider planSnapshot={planA} publicationId="pub-A" showcaseRunId="showcase_A">
        <span>A</span>
      </PublicationSnapshotProvider>
    );

    expect(getActivePublicPreview()).toEqual({ publicationId: 'pub-A', showcaseRunId: 'showcase_A', remote: false, authenticated: false });

    // Card B mounts before card A unmounts - same React tree, sibling tabs.
    const cardB = render(
      <PublicationSnapshotProvider planSnapshot={planB} publicationId="pub-B" showcaseRunId="showcase_B">
        <span>B</span>
      </PublicationSnapshotProvider>
    );

    // The latest publish wins.
    expect(getActivePublicPreview()).toEqual({ publicationId: 'pub-B', showcaseRunId: 'showcase_B', remote: false, authenticated: false });

    // Now card A unmounts. WITHOUT the guard, the cleanup would call
    // publishSnapshot({null,null,null}) and clobber B's state. With the guard
    // (storedState.publicationId === publishedId check), this is a no-op.
    act(() => { cardA.unmount(); });

    expect(getActivePublicPreview()).toEqual({ publicationId: 'pub-B', showcaseRunId: 'showcase_B', remote: false, authenticated: false });

    // Tearing down B clears the store.
    act(() => { cardB.unmount(); });
    expect(getActivePublicPreview()).toBeNull();
  });

  it('cleanup of the active publication clears the store', () => {
    const plan = { interfaces: [] };
    const card = render(
      <PublicationSnapshotProvider planSnapshot={plan} publicationId="pub-solo" showcaseRunId="showcase_solo">
        <span>only</span>
      </PublicationSnapshotProvider>
    );
    expect(getActivePublicPreview()).not.toBeNull();
    act(() => { card.unmount(); });
    expect(getActivePublicPreview()).toBeNull();
  });

  it('exposes remote=true so the gated showcase reads route through the cloud proxy (cloud-linked CE)', () => {
    // CE-cloud parity: a cloud-linked CE previews a CLOUD publication. The
    // provider publishes remote=true and getActivePublicPreview surfaces it, so
    // WorkflowRunManager / useInterfaces / useEpochStateViewing / WorkflowModeToggle
    // all forward it to the publication service (which hits /publications/remote/by-id/...).
    const card = render(
      <PublicationSnapshotProvider planSnapshot={{ interfaces: [] }} publicationId="pub-cloud" showcaseRunId="showcase_cloud" remote>
        <span>cloud</span>
      </PublicationSnapshotProvider>
    );
    expect(getActivePublicPreview()).toEqual({ publicationId: 'pub-cloud', showcaseRunId: 'showcase_cloud', remote: true, authenticated: false });
    act(() => { card.unmount(); });
    expect(getActivePublicPreview()).toBeNull();
  });

  it('defaults remote=false when the provider is mounted without the remote prop (cloud / authenticated app)', () => {
    const card = render(
      <PublicationSnapshotProvider planSnapshot={{ interfaces: [] }} publicationId="pub-local" showcaseRunId="showcase_local">
        <span>local</span>
      </PublicationSnapshotProvider>
    );
    expect(getActivePublicPreview()?.remote).toBe(false);
    act(() => { card.unmount(); });
  });

  it('exposes authenticated=true so the gated showcase render hits the receipt-gated AUTH\'D twin (acquirer/owner of a non-public pub)', () => {
    // A publisher-deleted (INACTIVE) or PRIVATE pub 403s on the anonymous
    // /by-id/.../showcase-render. The preview page loads it via the auth'd by-id
    // endpoint (receipt/owner bypass) and publishes authenticated=true, so
    // useInterfaceRender forwards it to getShowcaseRender({ authenticated:true })
    // -> /publications/{id}/showcase-render. Mutually exclusive with remote.
    const card = render(
      <PublicationSnapshotProvider planSnapshot={{ interfaces: [] }} publicationId="pub-acq" showcaseRunId="showcase_acq" authenticated>
        <span>acquired</span>
      </PublicationSnapshotProvider>
    );
    expect(getActivePublicPreview()).toEqual({ publicationId: 'pub-acq', showcaseRunId: 'showcase_acq', remote: false, authenticated: true });
    act(() => { card.unmount(); });
    expect(getActivePublicPreview()).toBeNull();
  });

  it('does NOT keep authenticated sticky when switching from an authed (INACTIVE) preview to an anonymous (ACTIVE) one', async () => {
    // Regression: a preview A read through the auth'd endpoint publishes
    // authenticated=true. Navigating to a still-public preview B (authenticated
    // absent -> false) must reset the flag, else B would needlessly hit the
    // auth'd showcase twin. The equality check in publishSnapshot must include
    // `authenticated`, and the latest publish wins.
    const cardA = render(
      <PublicationSnapshotProvider planSnapshot={{ interfaces: [] }} publicationId="pub-A" showcaseRunId="showcase_A" authenticated>
        <span>A</span>
      </PublicationSnapshotProvider>
    );
    expect(getActivePublicPreview()?.authenticated).toBe(true);

    render(
      <PublicationSnapshotProvider planSnapshot={{ interfaces: [] }} publicationId="pub-B" showcaseRunId="showcase_B">
        <span>B</span>
      </PublicationSnapshotProvider>
    );
    expect(getActivePublicPreview()).toEqual({ publicationId: 'pub-B', showcaseRunId: 'showcase_B', remote: false, authenticated: false });

    act(() => { cardA.unmount(); });
  });
});
