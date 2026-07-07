import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import type { ApprovalDelegation, BuilderNodeData } from '../../types';
import { generateWorkflowPlan } from '../workflowPlanGenerator';
import { NodeCreationService } from '../../services/workflowPlanImporter/NodeCreationService';

/**
 * Exporter tests for the user approval `approval.delegation` block.
 *
 * The delegation section is optional: the exporter must emit it ONLY when the
 * author actually selected a channel (approvalDelegation.channel non-blank) and
 * must stay byte-identical for approval nodes without delegation. The importer
 * (NodeCreationService) maps the block back to node.data.approvalDelegation, so
 * export -> import -> export must be lossless.
 */

function approvalNode(id: string, delegation?: ApprovalDelegation, extra: Record<string, unknown> = {}): Node<BuilderNodeData> {
  return {
    id,
    type: 'userApprovalNode',
    position: { x: 100, y: 100 },
    data: {
      id,
      label: 'Manager Review',
      kind: 'approval',
      approvalContextTemplate: 'Approve {{trigger:form.output.amount}}?',
      ...(delegation ? { approvalDelegation: delegation } : {}),
      ...extra,
    } as BuilderNodeData,
  };
}

function approvalBlockOf(plan: any): any {
  const core = plan.cores!.find((c: any) => c.type === 'approval');
  expect(core).toBeDefined();
  return core.approval;
}

describe('workflowPlanGenerator - approval.delegation emission', () => {
  it('emits the full delegation block when a channel is configured', () => {
    const plan = generateWorkflowPlan(
      [approvalNode('approval-1', {
        channel: 'telegram',
        credentialId: 123,
        chatId: '{{trigger:form.output.chat_id}}',
        messageTemplate: 'Approve {{trigger:form.output.amount}}?',
        allowedUserIds: ['12345678', '87654321'],
      })],
      []
    );

    expect(approvalBlockOf(plan).delegation).toEqual({
      channel: 'telegram',
      credentialId: 123,
      chatId: '{{trigger:form.output.chat_id}}',
      messageTemplate: 'Approve {{trigger:form.output.amount}}?',
      allowedUserIds: ['12345678', '87654321'],
    });
  });

  it('omits delegation entirely when the node has no approvalDelegation', () => {
    const plan = generateWorkflowPlan([approvalNode('approval-1')], []);
    expect(JSON.stringify(plan)).not.toContain('delegation');
  });

  it('omits delegation when the channel is blank (section toggled off mid-edit)', () => {
    const plan = generateWorkflowPlan(
      [approvalNode('approval-1', { channel: '' as any, credentialId: 1, chatId: '-100' })],
      []
    );
    expect(JSON.stringify(plan)).not.toContain('delegation');
  });

  it('omits blank optional fields but keeps the channel', () => {
    const plan = generateWorkflowPlan(
      [approvalNode('approval-1', {
        channel: 'telegram',
        chatId: '   ',
        messageTemplate: '',
        allowedUserIds: [],
      })],
      []
    );

    expect(approvalBlockOf(plan).delegation).toEqual({ channel: 'telegram' });
  });

  it('filters non-string and blank entries out of allowedUserIds', () => {
    const plan = generateWorkflowPlan(
      [approvalNode('approval-1', {
        channel: 'telegram',
        credentialId: 7,
        chatId: '-100123',
        allowedUserIds: ['12345678', '', '  ', 42 as any, null as any],
      })],
      []
    );

    expect(approvalBlockOf(plan).delegation.allowedUserIds).toEqual(['12345678']);
  });

  it('round-trips losslessly: export -> import -> export produces the same approval block', () => {
    const first = generateWorkflowPlan(
      [approvalNode('approval-1', {
        channel: 'telegram',
        credentialId: 123,
        chatId: '-100123456789',
        messageTemplate: 'Please approve',
        allowedUserIds: ['12345678'],
      }, { requiredApprovals: 1, approvalTimeoutMs: 3600000 })],
      []
    );
    const firstCore = first.cores!.find((c: any) => c.type === 'approval')!;

    // Import the exported core node back into builder node data
    const imported = (NodeCreationService as any).createCoreNodesInline([firstCore], [], 100, 100).nodes[0];
    expect(imported.data.approvalDelegation).toEqual({
      channel: 'telegram',
      credentialId: 123,
      chatId: '-100123456789',
      messageTemplate: 'Please approve',
      allowedUserIds: ['12345678'],
    });

    // Re-export the imported node: the approval block must be identical
    const second = generateWorkflowPlan([imported], []);
    const secondCore = second.cores!.find((c: any) => c.type === 'approval')!;
    expect(JSON.stringify(secondCore.approval)).toBe(JSON.stringify(firstCore.approval));
  });
});
