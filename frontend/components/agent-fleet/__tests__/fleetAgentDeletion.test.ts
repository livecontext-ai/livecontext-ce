/**
 * Which agent node may be deleted, and the case that makes the rule necessary.
 *
 * The canvas gives two verbs two homes: the edge between two agents unlinks them,
 * the node deletes the agent. That is coherent on the fleet canvas, where every
 * node is one of the workspace's agents.
 *
 * It is a trap on the single-agent canvas in the side panel. `useSingleAgentFleet`
 * walks the sub-agent tree and calls `buildAgentGraph` for every agent it reaches,
 * and each call pushes an `agent-<id>` node - so a sub-agent there is, by node id,
 * indistinguishable from the subject. Without this rule the red trash on a
 * sub-agent deleted a collaborator from the account while the user believed they
 * were detaching it from the agent whose panel they had open.
 */
import { describe, it, expect } from 'vitest';
import { agentIdFromNodeId, canDeleteAgentNode } from '../fleetAgentDeletion';

const SUBJECT = 'a0000000-0000-4000-8000-000000000001';
const SUB_AGENT = 'a0000000-0000-4000-8000-000000000002';

/** A member who may mutate this workspace. The permission is a separate axis. */
const ALLOWED = { canMutate: true };

describe('agentIdFromNodeId', () => {
  it('reads the agent out of an agent node', () => {
    expect(agentIdFromNodeId(`agent-${SUBJECT}`)).toBe(SUBJECT);
  });

  it('refuses the aggregator, which only LOOKS like one', () => {
    // `agg-agent-<id>` is the "Resources (N)" consolidation chip. It carries an
    // agent id and is not an agent.
    expect(agentIdFromNodeId(`agg-agent-${SUBJECT}`)).toBeNull();
  });

  it('refuses a resource node and a bare prefix', () => {
    expect(agentIdFromNodeId(`res-${SUBJECT}-tool-slack:post`)).toBeNull();
    expect(agentIdFromNodeId('agent-')).toBeNull();
  });
});

describe('canDeleteAgentNode on the fleet canvas', () => {
  it('allows every agent node - the canvas IS the workspace\'s agents', () => {
    expect(canDeleteAgentNode(`agent-${SUBJECT}`, ALLOWED)).toBe(true);
    expect(canDeleteAgentNode(`agent-${SUB_AGENT}`, ALLOWED)).toBe(true);
  });

  it('allows nothing that is not an agent node', () => {
    expect(canDeleteAgentNode(`res-${SUBJECT}-tool-slack:post`, ALLOWED)).toBe(false);
    expect(canDeleteAgentNode(`agg-agent-${SUBJECT}`, ALLOWED)).toBe(false);
    expect(canDeleteAgentNode(`provider-${SUBJECT}-slack`, ALLOWED)).toBe(false);
    expect(canDeleteAgentNode(`category-${SUBJECT}-tool`, ALLOWED)).toBe(false);
    expect(canDeleteAgentNode(`folder-${SUBJECT}-f1`, ALLOWED)).toBe(false);
  });
});

describe('canDeleteAgentNode on the single-agent canvas', () => {
  it('allows the subject the panel is about', () => {
    expect(canDeleteAgentNode(`agent-${SUBJECT}`, { ...ALLOWED, singleAgentId: SUBJECT })).toBe(true);
  });

  it('REFUSES a sub-agent, which renders as a top-level agent node here', () => {
    // The defect this rule exists for. Detaching a sub-agent stays where it was:
    // on the edge between the two.
    expect(canDeleteAgentNode(`agent-${SUB_AGENT}`, { ...ALLOWED, singleAgentId: SUBJECT })).toBe(false);
  });
});

describe('canDeleteAgentNode on a snapshot', () => {
  it('refuses everything, including the subject', () => {
    // A published snapshot is somebody else's agent. Edit mode is already off
    // there, so this is the second lock rather than the only one.
    expect(canDeleteAgentNode(`agent-${SUBJECT}`, { ...ALLOWED, snapshotMode: true })).toBe(false);
    expect(canDeleteAgentNode(`agent-${SUBJECT}`, { ...ALLOWED, singleAgentId: SUBJECT, snapshotMode: true })).toBe(false);
  });
});

describe('canDeleteAgentNode and the permission axis', () => {
  it('refuses a member who may not mutate the workspace', () => {
    // Edit mode is already forced off for a VIEWER, so this is a second lock. It
    // is here because this is the one canvas action that destroys an
    // account-level resource rather than unhooking one from an agent.
    expect(canDeleteAgentNode(`agent-${SUBJECT}`, { canMutate: false })).toBe(false);
    expect(canDeleteAgentNode(`agent-${SUBJECT}`, { canMutate: false, singleAgentId: SUBJECT })).toBe(false);
  });

  it('refuses a caller that never said, rather than assuming yes', () => {
    expect(canDeleteAgentNode(`agent-${SUBJECT}`, {})).toBe(false);
  });
});
