import { describe, it, expect } from 'vitest';
import {
  buildToolsConfigPayload,
  getGrant,
  getAccessMode,
  getFileAccessMode,
  GRANT_FAMILIES,
  type GrantFamily,
  type ResourceGrant,
} from '../toolsConfigAccess';

/**
 * Grant + read/write are the TWO independent access axes the backend models on
 * tools_config:
 *   axis 1 - `<family>Grant ∈ none|all|custom` (none=deny, all=unrestricted,
 *            custom=scoped to the `<family>:[...]` id list).
 *   axis 2 - `<family>AccessMode ∈ read|write` (default write), orthogonal.
 *
 * These tests pin the frontend reader/writer contract against the backend
 * authoritative defaults (absent grant ⇒ none, absent mode ⇒ write), and the
 * persistence-bug regression: buildToolsConfigPayload must emit accessMode for
 * BOTH read AND write (the pre-fix omit-on-write made a read→write toggle never
 * persist through the backend merge).
 */

/** The full input accepted by buildToolsConfigPayload - used to type dynamic-key merges. */
type PayloadInput = Parameters<typeof buildToolsConfigPayload>[0];
type GrantKey = 'workflowsGrant' | 'tablesGrant' | 'interfacesGrant' | 'agentsGrant' | 'applicationsGrant';
type ModeKey = 'workflowAccessMode' | 'tableAccessMode' | 'interfaceAccessMode' | 'agentAccessMode' | 'applicationAccessMode';

const base: PayloadInput = {
  mode: 'all',
  workflows: ['w1', 'w2'],
  tables: ['t1'],
  interfaces: ['i1'],
  agents: ['a1'],
  applications: ['app1'],
};

// (family → its grant input key, its access-mode key, its expected scope list)
const FAMILY_SPECS: { family: GrantFamily; grantKey: GrantKey; modeKey: ModeKey; list: (string | number)[] }[] = [
  { family: 'workflows', grantKey: 'workflowsGrant', modeKey: 'workflowAccessMode', list: ['w1', 'w2'] },
  { family: 'tables', grantKey: 'tablesGrant', modeKey: 'tableAccessMode', list: ['t1'] },
  { family: 'interfaces', grantKey: 'interfacesGrant', modeKey: 'interfaceAccessMode', list: ['i1'] },
  { family: 'agents', grantKey: 'agentsGrant', modeKey: 'agentAccessMode', list: ['a1'] },
  { family: 'applications', grantKey: 'applicationsGrant', modeKey: 'applicationAccessMode', list: ['app1'] },
];

/** Read a family's emitted scope list off the payload without an `any` cast. */
const scopeOf = (tc: ReturnType<typeof buildToolsConfigPayload>, family: GrantFamily): unknown =>
  (tc as Record<string, unknown>)[family];

describe('getGrant - per-family grant reader (axis 1)', () => {
  it('defaults to "none" when absent / null / unrecognized (backend-authoritative deny)', () => {
    for (const family of GRANT_FAMILIES) {
      expect(getGrant(null, family)).toBe('none');
      expect(getGrant(undefined, family)).toBe('none');
      expect(getGrant({}, family)).toBe('none');
      expect(getGrant({ [`${family}Grant`]: 'bogus' }, family)).toBe('none');
    }
  });

  it('reads back an explicit none / all / custom per family', () => {
    for (const { family, grantKey } of FAMILY_SPECS) {
      expect(getGrant({ [grantKey]: 'none' }, family)).toBe('none');
      expect(getGrant({ [grantKey]: 'all' }, family)).toBe('all');
      expect(getGrant({ [grantKey]: 'custom' }, family)).toBe('custom');
    }
  });

  it('keeps families independent (one grant does not bleed into another)', () => {
    const tc = { workflowsGrant: 'all', tablesGrant: 'custom' };
    expect(getGrant(tc, 'workflows')).toBe('all');
    expect(getGrant(tc, 'tables')).toBe('custom');
    expect(getGrant(tc, 'interfaces')).toBe('none');
    expect(getGrant(tc, 'agents')).toBe('none');
    expect(getGrant(tc, 'applications')).toBe('none');
  });
});

describe('getAccessMode - per-family read/write reader (axis 2)', () => {
  it('defaults to "write" when absent / null / unrecognized', () => {
    for (const family of GRANT_FAMILIES) {
      expect(getAccessMode(null, family)).toBe('write');
      expect(getAccessMode(undefined, family)).toBe('write');
      expect(getAccessMode({}, family)).toBe('write');
      expect(getAccessMode({ [`${family}AccessMode`]: 'bogus' }, family)).toBe('write');
    }
  });

  it('reads back an explicit read / write per family via its own *AccessMode key', () => {
    for (const { family, modeKey } of FAMILY_SPECS) {
      expect(getAccessMode({ [modeKey]: 'read' }, family)).toBe('read');
      expect(getAccessMode({ [modeKey]: 'write' }, family)).toBe('write');
    }
  });

  it('is orthogonal to the grant (a granted family can still be read-only)', () => {
    const tc = { workflowsGrant: 'all', workflowAccessMode: 'read' };
    expect(getGrant(tc, 'workflows')).toBe('all');
    expect(getAccessMode(tc, 'workflows')).toBe('read');
  });
});

describe('buildToolsConfigPayload - per-family grant emission (axis 1)', () => {
  it('emits every family grant, defaulting absent ⇒ "none"', () => {
    const payload = buildToolsConfigPayload({ ...base });
    expect(payload.workflowsGrant).toBe('none');
    expect(payload.tablesGrant).toBe('none');
    expect(payload.interfacesGrant).toBe('none');
    expect(payload.agentsGrant).toBe('none');
    expect(payload.applicationsGrant).toBe('none');
  });

  it('emits an explicit grant verbatim for each of none / all / custom, per family', () => {
    for (const grant of ['none', 'all', 'custom'] as ResourceGrant[]) {
      const payload = buildToolsConfigPayload({
        ...base,
        workflowsGrant: grant,
        tablesGrant: grant,
        interfacesGrant: grant,
        agentsGrant: grant,
        applicationsGrant: grant,
      });
      expect(payload.workflowsGrant).toBe(grant);
      expect(payload.tablesGrant).toBe(grant);
      expect(payload.interfacesGrant).toBe(grant);
      expect(payload.agentsGrant).toBe(grant);
      expect(payload.applicationsGrant).toBe(grant);
    }
  });

  it('emits the family id list as the scope ONLY when its grant is "custom"', () => {
    for (const { family, grantKey, list } of FAMILY_SPECS) {
      const custom = buildToolsConfigPayload({ ...base, [grantKey]: 'custom' });
      // custom → the real id list is the scope
      expect(scopeOf(custom, family)).toEqual(list);
    }
  });

  it('emits an EMPTY list (placeholder) for "all" and "none" - a stale selection never leaks', () => {
    for (const { family, grantKey } of FAMILY_SPECS) {
      const all = buildToolsConfigPayload({ ...base, [grantKey]: 'all' });
      const none = buildToolsConfigPayload({ ...base, [grantKey]: 'none' });
      // The user still has ids selected (base.<family> is non-empty), but grant
      // all/none means the list is irrelevant - emit [] so the grant is the SoT.
      expect(scopeOf(all, family)).toEqual([]);
      expect(scopeOf(none, family)).toEqual([]);
    }
  });

  it('emits [] for a "custom" grant with no selected ids (scoped-to-nothing is faithful)', () => {
    const payload = buildToolsConfigPayload({
      ...base,
      workflows: [],
      workflowsGrant: 'custom',
    });
    expect(payload.workflowsGrant).toBe('custom');
    expect(payload.workflows).toEqual([]);
  });
});

describe('buildToolsConfigPayload - accessMode persistence bug (axis 2)', () => {
  it('emits accessMode for BOTH "read" AND "write" so a read→write toggle persists', () => {
    // Every *AccessMode key the builder accepts - the 5 grant families PLUS the
    // separate skill axis (skill has a R/W mode but no grant family).
    const allModeKeys: (ModeKey | 'skillAccessMode' | 'fileAccessMode')[] = [...FAMILY_SPECS.map(s => s.modeKey), 'skillAccessMode', 'fileAccessMode'];
    for (const modeKey of allModeKeys) {
      const read = buildToolsConfigPayload({ ...base, [modeKey]: 'read' });
      const write = buildToolsConfigPayload({ ...base, [modeKey]: 'write' });
      // The pre-fix code emitted only on 'read' → write was silently dropped → the
      // backend merge kept the stored 'read'. Now BOTH are emitted explicitly.
      expect((read as Record<string, unknown>)[modeKey]).toBe('read');
      expect((write as Record<string, unknown>)[modeKey]).toBe('write');
    }
  });

  it('omits an accessMode entirely when the caller passes no value (no spurious key)', () => {
    const payload = buildToolsConfigPayload({ ...base });
    expect('workflowAccessMode' in payload).toBe(false);
    expect('tableAccessMode' in payload).toBe(false);
    expect('interfaceAccessMode' in payload).toBe(false);
    expect('agentAccessMode' in payload).toBe(false);
    expect('applicationAccessMode' in payload).toBe(false);
    expect('skillAccessMode' in payload).toBe(false);
    expect('fileAccessMode' in payload).toBe(false);
  });

  it('getFileAccessMode: defaults to write (absent/null/bogus), reads read/write explicitly', () => {
    // Files default to write (full access) for backward-compat - mirrors the backend
    // ToolAccessControl default (absent fileAccessMode = write).
    expect(getFileAccessMode(null)).toBe('write');
    expect(getFileAccessMode(undefined)).toBe('write');
    expect(getFileAccessMode({})).toBe('write');
    expect(getFileAccessMode({ fileAccessMode: 'bogus' })).toBe('write');
    expect(getFileAccessMode({ fileAccessMode: 'read' })).toBe('read');
    expect(getFileAccessMode({ fileAccessMode: 'write' })).toBe('write');
  });

  it('expresses the previously-impossible "all + read/write" combination', () => {
    const payload = buildToolsConfigPayload({
      ...base,
      workflowsGrant: 'all',
      workflowAccessMode: 'read',
    });
    // grant=all (unrestricted) AND mode=read (read-only) - the two axes are
    // independent, so this combination must round-trip through the readers.
    expect(payload.workflowsGrant).toBe('all');
    expect(payload.workflowAccessMode).toBe('read');
    expect(getGrant(payload, 'workflows')).toBe('all');
    expect(getAccessMode(payload, 'workflows')).toBe('read');
  });
});
