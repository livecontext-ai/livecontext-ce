import { describe, expect, it } from 'vitest';
import { collectPlanToolIdentifiers } from '../customApiPlanRefs';

/**
 * Twin of publication-service's `CustomApiPublishGuardTest` collection cases: the
 * publish modal must warn about exactly what the backend would refuse, so this walk
 * has to find the same identifiers.
 */
describe('collectPlanToolIdentifiers', () => {
  it('collects mcp node ids', () => {
    const plan = {
      mcps: [
        { id: 'github/get-user' },
        { id: 'my-api/do-thing' },
        { label: 'no id here' },
      ],
    };

    expect(collectPlanToolIdentifiers(plan)).toEqual(['github/get-user', 'my-api/do-thing']);
  });

  it("collects an agent's explicit tool grant in its canonical apiSlug:toolSlug form", () => {
    const plan = {
      agents: [{ agentConfigId: 'a-1', toolsConfig: { mode: 'custom', tools: ['my-api:do-thing'] } }],
    };

    expect(collectPlanToolIdentifiers(plan)).toEqual(['my-api:do-thing']);
  });

  it('collects a legacy grant stored as a raw api_tools.id UUID', () => {
    const plan = {
      agents: [{ toolsConfig: { tools: ['11111111-2222-3333-4444-555555555555'] } }],
    };

    expect(collectPlanToolIdentifiers(plan)).toEqual(['11111111-2222-3333-4444-555555555555']);
  });

  it("does not collect a plan's normalised mcp:<label> refs (they sit outside toolsConfig)", () => {
    // The raw agent node lists its plan-local tools under a top-level `tools` key as
    // "mcp:<label>"; the mcp node they point at is already collected from `mcps[]`.
    const plan = {
      mcps: [{ id: 'my-api/do-thing', label: 'Call it' }],
      agents: [{ agentConfigId: 'a-1', tools: ['mcp:call_it'] }],
    };

    expect(collectPlanToolIdentifiers(plan)).toEqual(['my-api/do-thing']);
  });

  it('collects tool grants from the publish-time agent snapshot key too', () => {
    const plan = {
      agents: [{ _snapshot_agent_toolsConfig: { tools: ['my-api/do-thing'] } }],
    };

    expect(collectPlanToolIdentifiers(plan)).toEqual(['my-api/do-thing']);
  });

  it('tolerates the object form of a tool grant ({id} / {toolSlug})', () => {
    const plan = {
      agent: { toolsConfig: { tools: [{ id: 'my-api/do-thing' }, { toolSlug: 'do-other' }] } },
    };

    expect(collectPlanToolIdentifiers(plan)).toEqual(['my-api/do-thing', 'do-other']);
  });

  it('walks nested plans: sub-workflow snapshots are covered', () => {
    const plan = {
      mcps: [{ id: 'root-api/a' }],
      _snapshot_subworkflows: {
        'wf-child': { mcps: [{ id: 'child-api/b' }] },
      },
    };

    expect(collectPlanToolIdentifiers(plan)).toEqual(['root-api/a', 'child-api/b']);
  });

  it('deduplicates and trims, and drops blank ids', () => {
    const plan = {
      mcps: [{ id: ' my-api/do-thing ' }, { id: 'my-api/do-thing' }, { id: '   ' }, { id: null }],
    };

    expect(collectPlanToolIdentifiers(plan)).toEqual(['my-api/do-thing']);
  });

  it('returns nothing for a plan with no tool reference', () => {
    expect(collectPlanToolIdentifiers({ triggers: [{ type: 'manual' }], cores: [] })).toEqual([]);
  });

  it('is safe on null, undefined and primitives', () => {
    expect(collectPlanToolIdentifiers(null)).toEqual([]);
    expect(collectPlanToolIdentifiers(undefined)).toEqual([]);
    expect(collectPlanToolIdentifiers('not a plan')).toEqual([]);
    expect(collectPlanToolIdentifiers(42)).toEqual([]);
  });

  it('ignores a non-array mcps value instead of throwing', () => {
    expect(collectPlanToolIdentifiers({ mcps: { id: 'my-api/do-thing' } })).toEqual([]);
  });
});
