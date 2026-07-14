import { describe, it, expect, beforeEach } from 'vitest';
import { WorkflowValidator } from '../WorkflowValidator';
import {
  makeStepWithCredentials,
  makeTriggerNode,
  makeEmailInboxNode,
  makeEdge,
  resetNodeCounter,
  resetEdgeCounter,
} from './test-helpers';

describe('WorkflowValidator cache key invalidation', () => {
  beforeEach(() => {
    WorkflowValidator.clearCache();
    resetNodeCounter();
    resetEdgeCounter();
  });

  it('should invalidate cache when selectedCredentialId changes', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const step = makeStepWithCredentials('Slack Call', {
      id: 'step-1',
      credentials: [{ name: 'token', isRequired: true }],
      selectedCredentialId: null,
      integration: 'Slack',
    });
    const edge = makeEdge('trigger-1', 'step-1');

    // First validation: no credential → warning expected
    const result1 = WorkflowValidator.validate([trigger, step], [edge]);
    const credWarnings1 = Object.values(result1.issuesByElement)
      .flat()
      .filter(i => i.context?.rule === 'missing_credential');
    expect(credWarnings1).toHaveLength(1);

    // Update credential on the step
    const stepWithCred = makeStepWithCredentials('Slack Call', {
      id: 'step-1',
      credentials: [{ name: 'token', isRequired: true }],
      selectedCredentialId: 42,
      integration: 'Slack',
    });

    // Second validation: credential set → no warning (cache must have been invalidated)
    const result2 = WorkflowValidator.validate([trigger, stepWithCred], [edge]);
    const credWarnings2 = Object.values(result2.issuesByElement)
      .flat()
      .filter(i => i.context?.rule === 'missing_credential');
    expect(credWarnings2).toHaveLength(0);
  });

  it('should invalidate cache when credentialSource flips to platform', () => {
    // Regression: flipping the inspector toggle from user → platform while no
    // user credential was selected left a stale "not connected" warning because
    // the cache key hashed only selectedCredentialId.
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const step = makeStepWithCredentials('Gmail Call', {
      id: 'step-1',
      credentials: [{ name: 'oauth', isRequired: true }],
      selectedCredentialId: null,
      integration: 'gmail',
    });
    const edge = makeEdge('trigger-1', 'step-1');

    const result1 = WorkflowValidator.validate([trigger, step], [edge]);
    const warnings1 = Object.values(result1.issuesByElement)
      .flat()
      .filter(i => i.context?.rule === 'missing_credential');
    expect(warnings1).toHaveLength(1);

    const stepOnPlatform = makeStepWithCredentials('Gmail Call', {
      id: 'step-1',
      credentials: [{ name: 'oauth', isRequired: true }],
      selectedCredentialId: null,
      integration: 'gmail',
      credentialSource: 'platform',
      platformCredentialId: 42,
    });

    const result2 = WorkflowValidator.validate([trigger, stepOnPlatform], [edge]);
    const warnings2 = Object.values(result2.issuesByElement)
      .flat()
      .filter(i => i.context?.rule === 'missing_credential');
    expect(warnings2).toHaveLength(0);
  });

  it('should invalidate cache when imapCredentialId changes on an email_inbox node', () => {
    // Regression: the node signature hashed smtp/ssh/sftp/db credential ids but
    // omitted imapCredentialId, so selecting an IMAP credential on an otherwise
    // unchanged graph could serve the stale "not connected" warning from cache.
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const inbox = makeEmailInboxNode('Read Unread', { id: 'inbox-1', imapCredentialId: null });
    const edge = makeEdge('trigger-1', 'inbox-1');

    const result1 = WorkflowValidator.validate([trigger, inbox], [edge]);
    const warnings1 = Object.values(result1.issuesByElement)
      .flat()
      .filter(i => i.context?.rule === 'missing_credential');
    expect(warnings1).toHaveLength(1);

    const inboxWithCred = makeEmailInboxNode('Read Unread', { id: 'inbox-1', imapCredentialId: 42 });
    const result2 = WorkflowValidator.validate([trigger, inboxWithCred], [edge]);
    const warnings2 = Object.values(result2.issuesByElement)
      .flat()
      .filter(i => i.context?.rule === 'missing_credential');
    expect(warnings2).toHaveLength(0);
  });

  it('should return cached result when nothing changes', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const step = makeStepWithCredentials('Step', {
      id: 'step-1',
      credentials: [{ name: 'key', isRequired: true }],
      selectedCredentialId: 5,
      integration: 'API',
    });
    const edge = makeEdge('trigger-1', 'step-1');
    const nodes = [trigger, step];
    const edges = [edge];

    const result1 = WorkflowValidator.validate(nodes, edges);
    const result2 = WorkflowValidator.validate(nodes, edges);

    // Same object reference = cache hit
    expect(result1).toBe(result2);
  });

  it('should invalidate cache when featureCapabilities resolve on an unchanged graph', () => {
    // Regression: the capabilities query resolves AFTER the first validation of an
    // unchanged graph (null → {browserAgent:false}). Without the capability signature
    // in the cache key, the stale "no warning" result would be served forever.
    const browserNode = {
      id: 'n-browse',
      type: 'agentNode',
      position: { x: 0, y: 0 },
      data: {
        id: 'browser-agent',
        kind: 'browser_agent',
        label: 'Browse',
        paramExpressions: { task: 'Open example.com' },
      },
    } as any;
    const nodes = [makeTriggerNode('Start', 'trigger-1'), browserNode];
    const edges = [makeEdge('trigger-1', 'n-browse')];

    // Capabilities unknown → no availability warning
    const result1 = WorkflowValidator.validate(nodes, edges, undefined, false, undefined, undefined);
    const warnings1 = Object.values(result1.issuesByElement)
      .flat()
      .filter(i => i.context?.rule === 'browser_agent_component_unavailable');
    expect(warnings1).toHaveLength(0);

    // Capabilities resolve to "browser agent missing" → warning must appear (cache miss)
    const result2 = WorkflowValidator.validate(nodes, edges, undefined, false, undefined,
      { screenshotRenderer: true, browserAgent: false, webSearch: false });
    const warnings2 = Object.values(result2.issuesByElement)
      .flat()
      .filter(i => i.context?.rule === 'browser_agent_component_unavailable');
    expect(warnings2).toHaveLength(1);
    expect(warnings2[0].severity).toBe('warning');
  });
});
