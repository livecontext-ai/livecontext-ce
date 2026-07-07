import { describe, it, expect, beforeEach } from 'vitest';
import { NodeConfigurationRule } from '../rules-v2/NodeConfigurationRule';
import {
  makeTriggerNode,
  makeStepNode,
  makeIncompleteStepNode,
  makeDecisionNode,
  makeLoopNode,
  makeOptionNode,
  makeEdge,
  buildContext,
  resetNodeCounter,
  resetEdgeCounter,
} from './test-helpers';

describe('NodeConfigurationRule', () => {
  let rule: NodeConfigurationRule;

  beforeEach(() => {
    rule = new NodeConfigurationRule();
    resetNodeCounter();
    resetEdgeCounter();
  });

  it('should have correct metadata', () => {
    expect(rule.ruleName).toBe('NodeConfiguration');
    expect(rule.isCritical).toBe(true);
    expect(rule.priority).toBe(8);
  });

  // ===================== Approval context template (warning) =====================

  describe('Approval - context template (warning, non-blocking)', () => {
    const makeApprovalNode = (template?: string) =>
      ({
        id: 'n-approval',
        type: 'userApprovalNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'user-approval',
          label: 'Manager Review',
          ...(template !== undefined ? { approvalContextTemplate: template } : {}),
        },
      }) as any;

    it('warns (not errors) when contextTemplate is missing', () => {
      const ctx = buildContext([makeApprovalNode()], []);
      const result = rule.validate(ctx);

      const issues = result.issues.filter((i) => i.context?.rule === 'approval_missing_context_template');
      expect(issues).toHaveLength(1);
      expect(issues[0].severity).toBe('warning');
    });

    it('does not warn when contextTemplate is set', () => {
      const ctx = buildContext([makeApprovalNode('Approve {{trigger:form.output.amount}}?')], []);
      const result = rule.validate(ctx);

      expect(
        result.issues.filter((i) => i.context?.rule === 'approval_missing_context_template'),
      ).toHaveLength(0);
    });

    it('warns when contextTemplate is blank', () => {
      const ctx = buildContext([makeApprovalNode('   ')], []);
      const result = rule.validate(ctx);

      expect(
        result.issues.filter((i) => i.context?.rule === 'approval_missing_context_template'),
      ).toHaveLength(1);
    });
  });

  // ===================== Approval delegation (warnings, non-blocking) =====================

  describe('Approval - external channel delegation (warnings, non-blocking)', () => {
    const makeDelegatedApprovalNode = (data: Record<string, unknown>) =>
      ({
        id: 'n-approval-delegated',
        type: 'userApprovalNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'user-approval',
          label: 'Manager Review',
          approvalContextTemplate: 'Approve?',
          ...data,
        },
      }) as any;

    // 'approval_delegation_missing_credential' stays listed although the rule was
    // REMOVED (missing credential is a valid configuration: the backend falls back to
    // the user's own Telegram credential). The absence assertions below double as a
    // removal regression guard: the rule id must never fire again.
    const delegationRules = [
      'approval_delegation_missing_credential',
      'approval_delegation_missing_chat_id',
      'approval_delegation_multi_approvals',
    ];

    it('emits NO delegation issue when delegation is not configured', () => {
      const ctx = buildContext([makeDelegatedApprovalNode({})], []);
      const result = rule.validate(ctx);

      expect(
        result.issues.filter((i) => delegationRules.includes(i.context?.rule as string)),
      ).toHaveLength(0);
    });

    it('emits NO delegation issue when the delegation object has a blank channel (section toggled off mid-edit)', () => {
      const ctx = buildContext(
        [makeDelegatedApprovalNode({ approvalDelegation: { channel: '', chatId: '-100' } })],
        [],
      );
      const result = rule.validate(ctx);

      expect(
        result.issues.filter((i) => delegationRules.includes(i.context?.rule as string)),
      ).toHaveLength(0);
    });

    it('REGRESSION (missing-credential rule removed): emits NO finding when delegation has no credential (backend falls back to the user\'s own Telegram credential)', () => {
      const ctx = buildContext(
        [makeDelegatedApprovalNode({ approvalDelegation: { channel: 'telegram', chatId: '-100123' } })],
        [],
      );
      const result = rule.validate(ctx);

      expect(
        result.issues.filter((i) => i.context?.rule === 'approval_delegation_missing_credential'),
      ).toHaveLength(0);
      // The shape is otherwise fully valid: no delegation issue at all.
      expect(
        result.issues.filter((i) => delegationRules.includes(i.context?.rule as string)),
      ).toHaveLength(0);
    });

    it('warns (not errors) when delegation is enabled with a blank chat ID', () => {
      const ctx = buildContext(
        [makeDelegatedApprovalNode({ approvalDelegation: { channel: 'telegram', credentialId: 42, chatId: '   ' } })],
        [],
      );
      const result = rule.validate(ctx);

      const issues = result.issues.filter((i) => i.context?.rule === 'approval_delegation_missing_chat_id');
      expect(issues).toHaveLength(1);
      expect(issues[0].severity).toBe('warning');
    });

    it('warns when delegation is enabled and requiredApprovals > 1 (channel counts as ONE approver decision)', () => {
      const ctx = buildContext(
        [makeDelegatedApprovalNode({
          requiredApprovals: 2,
          approvalDelegation: { channel: 'telegram', credentialId: 42, chatId: '-100123' },
        })],
        [],
      );
      const result = rule.validate(ctx);

      const issues = result.issues.filter((i) => i.context?.rule === 'approval_delegation_multi_approvals');
      expect(issues).toHaveLength(1);
      expect(issues[0].severity).toBe('warning');
    });

    it('does NOT warn about multi approvals when requiredApprovals is 1', () => {
      const ctx = buildContext(
        [makeDelegatedApprovalNode({
          requiredApprovals: 1,
          approvalDelegation: { channel: 'telegram', credentialId: 42, chatId: '-100123' },
        })],
        [],
      );
      const result = rule.validate(ctx);

      expect(
        result.issues.filter((i) => i.context?.rule === 'approval_delegation_multi_approvals'),
      ).toHaveLength(0);
    });

    it('emits no delegation issue for a fully configured delegation', () => {
      const ctx = buildContext(
        [makeDelegatedApprovalNode({
          approvalDelegation: {
            channel: 'telegram',
            credentialId: 42,
            chatId: '{{trigger:form.output.chat_id}}',
            allowedUserIds: ['12345678'],
          },
        })],
        [],
      );
      const result = rule.validate(ctx);

      expect(
        result.issues.filter((i) => delegationRules.includes(i.context?.rule as string)),
      ).toHaveLength(0);
    });

    it('emits ONLY the missing-chat-id warning for a bare enabled delegation (no credential warning: credential is optional)', () => {
      const ctx = buildContext(
        [makeDelegatedApprovalNode({ approvalDelegation: { channel: 'telegram' } })],
        [],
      );
      const result = rule.validate(ctx);

      expect(result.issues.filter((i) => i.context?.rule === 'approval_delegation_missing_credential')).toHaveLength(0);
      expect(result.issues.filter((i) => i.context?.rule === 'approval_delegation_missing_chat_id')).toHaveLength(1);
      expect(result.hasErrors).toBe(false);
    });
  });

  // ===================== #10: Step without tool ID =====================

  describe('#10 - Step without tool ID', () => {
    it('should error when step has no tool data', () => {
      const step = makeIncompleteStepNode('Broken Step');
      const ctx = buildContext([step], []);
      const result = rule.validate(ctx);

      const toolIssues = result.issues.filter((i) => i.context?.rule === 'step_missing_tool');
      expect(toolIssues).toHaveLength(1);
      expect(toolIssues[0].severity).toBe('error');
    });

    it('should pass when step has valid tool data', () => {
      const step = makeStepNode('Valid Step', { toolId: 'my-tool' });
      const ctx = buildContext([step], []);
      const result = rule.validate(ctx);

      const toolIssues = result.issues.filter((i) => i.context?.rule === 'step_missing_tool');
      expect(toolIssues).toHaveLength(0);
    });
  });

  // ===================== #11: Decision without conditions =====================

  describe('#11 - Decision without conditions', () => {
    it('should error when decision has no conditions', () => {
      const decision = makeDecisionNode('Check', []);
      const ctx = buildContext([decision], []);
      const result = rule.validate(ctx);

      const condIssues = result.issues.filter((i) => i.context?.rule === 'decision_no_conditions');
      expect(condIssues).toHaveLength(1);
      expect(condIssues[0].severity).toBe('error');
    });

    it('should error when decision has no "if" condition', () => {
      const decision = makeDecisionNode('Check', [
        { id: 'c1', type: 'else', label: 'ELSE' },
      ]);
      const ctx = buildContext([decision], []);
      const result = rule.validate(ctx);

      const noIfIssues = result.issues.filter((i) => i.context?.rule === 'decision_no_if');
      expect(noIfIssues).toHaveLength(1);
    });

    it('should error when decision has multiple "if" conditions', () => {
      const decision = makeDecisionNode('Check', [
        { id: 'c1', type: 'if', label: 'IF 1', expression: 'x > 1' },
        { id: 'c2', type: 'if', label: 'IF 2', expression: 'x > 2' },
      ]);
      const ctx = buildContext([decision], []);
      const result = rule.validate(ctx);

      const multiIfIssues = result.issues.filter((i) => i.context?.rule === 'decision_multiple_if');
      expect(multiIfIssues).toHaveLength(1);
    });

    it('should error when decision condition expression is empty', () => {
      const decision = makeDecisionNode('Check', [
        { id: 'c1', type: 'if', label: 'IF', expression: '' },
      ]);
      const ctx = buildContext([decision], []);
      const result = rule.validate(ctx);

      const emptyIssues = result.issues.filter((i) => i.context?.rule === 'decision_empty_condition');
      expect(emptyIssues).toHaveLength(1);
    });

    it('should pass when decision has valid conditions', () => {
      const decision = makeDecisionNode('Check', [
        { id: 'c1', type: 'if', label: 'IF', expression: 'x > 5' },
        { id: 'c2', type: 'else', label: 'ELSE' },
      ]);
      const ctx = buildContext([decision], []);
      const result = rule.validate(ctx);

      const condIssues = result.issues.filter((i) =>
        ['decision_no_conditions', 'decision_no_if', 'decision_empty_condition'].includes(
          i.context?.rule as string
        )
      );
      expect(condIssues).toHaveLength(0);
    });
  });

  // ===================== #12: Loop without condition =====================

  describe('#12 - Loop without condition', () => {
    it('should error when loop has no condition', () => {
      const loop = makeLoopNode('My Loop', '');
      const ctx = buildContext([loop], []);
      const result = rule.validate(ctx);

      const loopIssues = result.issues.filter((i) => i.context?.rule === 'loop_no_condition');
      expect(loopIssues).toHaveLength(1);
      expect(loopIssues[0].severity).toBe('error');
    });

    it('should pass when loop has valid condition', () => {
      const loop = makeLoopNode('My Loop', 'i < 10');
      const ctx = buildContext([loop], []);
      const result = rule.validate(ctx);

      const loopIssues = result.issues.filter((i) => i.context?.rule === 'loop_no_condition');
      expect(loopIssues).toHaveLength(0);
    });
  });

  // ===================== #13: Switch without expression/cases =====================

  describe('#13 - Switch/Option without expression or cases', () => {
    it('should error when option node has no choices', () => {
      const option = makeOptionNode('My Option', []);
      const ctx = buildContext([option], []);
      const result = rule.validate(ctx);

      const optIssues = result.issues.filter((i) => i.context?.rule === 'option_no_choices');
      expect(optIssues).toHaveLength(1);
    });

    it('should error when option choice has empty label', () => {
      const option = makeOptionNode('My Option', [
        { id: 'ch1', label: '' },
      ]);
      const ctx = buildContext([option], []);
      const result = rule.validate(ctx);

      const labelIssues = result.issues.filter((i) => i.context?.rule === 'option_empty_choice_label');
      expect(labelIssues).toHaveLength(1);
    });

    it('should pass when option has valid choices', () => {
      const option = makeOptionNode('My Option', [
        { id: 'ch1', label: 'Choice A' },
        { id: 'ch2', label: 'Choice B' },
      ]);
      const ctx = buildContext([option], []);
      const result = rule.validate(ctx);

      const optIssues = result.issues.filter((i) =>
        ['option_no_choices', 'option_empty_choice_label'].includes(i.context?.rule as string)
      );
      expect(optIssues).toHaveLength(0);
    });
  });

  // ===================== Mixed scenarios =====================

  // ===================== Agent/Guardrail/Classify required fields =====================

  describe('#14 - Agent required fields', () => {
    it('should error when inline agent is missing prompt and model', () => {
      const agentNode = {
        id: 'agent-test-1',
        type: 'flowNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'ai-agent-test-1',
          label: 'My Agent',
          kind: 'reasoning' as const,
          paramExpressions: {},
        },
      } as any;
      const ctx = buildContext([agentNode], []);
      const result = rule.validate(ctx);
      const agentIssues = result.issues.filter(
        (i) => i.context?.rule === 'agent_missing_prompt' || i.context?.rule === 'agent_missing_model'
      );
      expect(agentIssues).toHaveLength(2);
    });

    it('should not error when inline agent has prompt and model', () => {
      const agentNode = {
        id: 'agent-test-2',
        type: 'flowNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'ai-agent-test-2',
          label: 'My Agent',
          kind: 'reasoning' as const,
          paramExpressions: { prompt: 'Do stuff', model: 'gpt-4' },
        },
      } as any;
      const ctx = buildContext([agentNode], []);
      const result = rule.validate(ctx);
      const agentIssues = result.issues.filter(
        (i) => i.context?.rule === 'agent_missing_prompt' || i.context?.rule === 'agent_missing_model'
      );
      expect(agentIssues).toHaveLength(0);
    });

    it('should still require prompt but skip model when agent uses agentConfigId', () => {
      const agentNode = {
        id: 'agent-test-3',
        type: 'flowNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'ai-agent-test-3',
          label: 'Saved Agent',
          kind: 'reasoning' as const,
          agentConfigId: 'config-123',
          paramExpressions: {},
        },
      } as any;
      const ctx = buildContext([agentNode], []);
      const result = rule.validate(ctx);
      const promptIssues = result.issues.filter((i) => i.context?.rule === 'agent_missing_prompt');
      const modelIssues = result.issues.filter((i) => i.context?.rule === 'agent_missing_model');
      expect(promptIssues).toHaveLength(1);
      expect(modelIssues).toHaveLength(0);
    });

    it('should error when guardrail is missing input and rules', () => {
      const guardrailNode = {
        id: 'guardrail-test-1',
        type: 'guardrailNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'guardrail-test-1',
          label: 'My Guard',
          kind: 'guardrail' as const,
          paramExpressions: {},
        },
      } as any;
      const ctx = buildContext([guardrailNode], []);
      const result = rule.validate(ctx);
      const guardIssues = result.issues.filter(
        (i) => i.context?.rule === 'guardrail_missing_input' || i.context?.rule === 'guardrail_missing_rules'
      );
      expect(guardIssues).toHaveLength(2);
    });

    it('should error when classify is missing prompt and has fewer than 2 categories', () => {
      const classifyNode = {
        id: 'classify-test-1',
        type: 'classifyNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'classify-test-1',
          label: 'My Classify',
          kind: 'classify' as const,
          paramExpressions: {},
          classifyCategories: [{ id: 'c1', label: 'Cat1' }],
        },
      } as any;
      const ctx = buildContext([classifyNode], []);
      const result = rule.validate(ctx);
      const classifyIssues = result.issues.filter(
        (i) => i.context?.rule === 'classify_missing_prompt' || i.context?.rule === 'classify_missing_categories'
      );
      expect(classifyIssues).toHaveLength(2);
    });
  });

  describe('mixed scenarios', () => {
    it('should validate multiple issues in one pass', () => {
      const badDecision = makeDecisionNode('D1', []);
      const badLoop = makeLoopNode('L1', '');
      const incompleteStep = makeIncompleteStepNode('Bad Step');
      const ctx = buildContext([badDecision, badLoop, incompleteStep], []);
      const result = rule.validate(ctx);

      expect(result.hasErrors).toBe(true);
      expect(result.issues.length).toBeGreaterThanOrEqual(3);
    });
  });

  // ===================== #14: Required-field contract for core node kinds =====================
  // Pins the frontend layer of the 3-layer required-field contract
  // (NodeConfigurationRule.validateRequiredFields). Each node kind below has a
  // field the backend execute() also requires; if the frontend check is dropped
  // these tests catch the regression.

  /** Builds a flowNode whose `kind` drives validateRequiredFields. */
  const coreNode = (kind: string, extra: Record<string, unknown> = {}): any => {
    const id = `cfg-${kind}`;
    return {
      id,
      type: 'flowNode',
      position: { x: 0, y: 0 },
      data: { id, label: `${kind} node`, kind, ...extra },
    };
  };

  const rulesFor = (node: any, rule: string) => {
    const ctx = buildContext([node], []);
    return new NodeConfigurationRule().validate(ctx).issues.filter((i) => i.context?.rule === rule);
  };

  describe('#14 - required-field contract (single field)', () => {
    const singleFieldCases = [
      { kind: 'filter', rule: 'data_node_missing_input', valid: { filterInput: '{{items}}' } },
      { kind: 'xml', rule: 'xml_missing_input', valid: { xmlInput: '<root/>' } },
      { kind: 'compression', rule: 'compression_missing_input', valid: { compressionInput: '{{bytes}}' } },
      { kind: 'convert_to_file', rule: 'convert_missing_value', valid: { convertValue: '{{data}}' } },
      { kind: 'extract_from_file', rule: 'extract_missing_value', valid: { extractValue: '{{file}}' } },
      { kind: 'rss', rule: 'rss_missing_url', valid: { rssUrl: 'https://example.com/feed.xml' } },
      { kind: 'code', rule: 'code_missing_content', valid: { codeContent: 'return 1;' } },
      { kind: 'http_request', rule: 'http_missing_url', valid: { httpRequestData: { url: 'https://api.example.com' } } },
      { kind: 'download_file', rule: 'download_missing_url', valid: { downloadUrl: 'https://example.com/file.pdf' } },
      { kind: 'ssh', rule: 'ssh_missing_command', valid: { sshCommand: 'ls -la' } },
      { kind: 'sftp', rule: 'sftp_missing_remote_path', valid: { sftpRemotePath: '/var/data' } },
      { kind: 'database', rule: 'db_missing_query', valid: { dbQuery: 'SELECT 1' } },
      { kind: 'output', rule: 'response_missing_message', valid: { responseMessage: 'All done' } },
      { kind: 'browser_agent', rule: 'browser_agent_missing_task', valid: { task: 'Open the homepage' } },
    ];

    it.each(singleFieldCases)(
      'errors for "$kind" when the required field is empty and passes once it is set ($rule)',
      ({ kind, rule, valid }) => {
        const missing = rulesFor(coreNode(kind), rule);
        expect(missing).toHaveLength(1);
        expect(missing[0].severity).toBe('error');

        const filled = rulesFor(coreNode(kind, valid), rule);
        expect(filled).toHaveLength(0);
      }
    );
  });

  describe('#14 - required-field contract (multi field / conditional)', () => {
    it('requires both recipient and subject for a send_email node', () => {
      const missing = new NodeConfigurationRule().validate(buildContext([coreNode('send_email')], []));
      const toMissing = missing.issues.filter((i) => i.context?.rule === 'email_missing_to');
      const subjMissing = missing.issues.filter((i) => i.context?.rule === 'email_missing_subject');
      expect(toMissing).toHaveLength(1);
      expect(subjMissing).toHaveLength(1);

      const filled = new NodeConfigurationRule().validate(
        buildContext([coreNode('send_email', { emailTo: 'a@b.com', emailSubject: 'Hi' })], [])
      );
      expect(
        filled.issues.filter((i) => ['email_missing_to', 'email_missing_subject'].includes(i.context?.rule as string))
      ).toHaveLength(0);
    });

    it('requires both datasets for a compare_datasets node', () => {
      const result = new NodeConfigurationRule().validate(buildContext([coreNode('compare_datasets')], []));
      expect(result.issues.filter((i) => i.context?.rule === 'compare_missing_input_a')).toHaveLength(1);
      expect(result.issues.filter((i) => i.context?.rule === 'compare_missing_input_b')).toHaveLength(1);
    });

    it('requires a source and at least one field for an html_extract node', () => {
      const empty = new NodeConfigurationRule().validate(buildContext([coreNode('html_extract')], []));
      expect(empty.issues.filter((i) => i.context?.rule === 'html_extract_missing_source')).toHaveLength(1);
      expect(empty.issues.filter((i) => i.context?.rule === 'html_extract_missing_fields')).toHaveLength(1);

      const valid = new NodeConfigurationRule().validate(
        buildContext(
          [coreNode('html_extract', { htmlExtractSource: '<html/>', htmlExtractFields: [{ name: 'title', selector: 'h1' }] })],
          []
        )
      );
      expect(
        valid.issues.filter((i) =>
          ['html_extract_missing_source', 'html_extract_missing_fields'].includes(i.context?.rule as string)
        )
      ).toHaveLength(0);
    });

    it('requires at least one assignment for a set node', () => {
      const empty = new NodeConfigurationRule().validate(buildContext([coreNode('set', { setAssignments: [] })], []));
      expect(empty.issues.filter((i) => i.context?.rule === 'set_missing_assignments')).toHaveLength(1);

      const valid = new NodeConfigurationRule().validate(
        buildContext([coreNode('set', { setAssignments: [{ field: 'x', value: '1' }] })], [])
      );
      expect(valid.issues.filter((i) => i.context?.rule === 'set_missing_assignments')).toHaveLength(0);
    });

    it('requires a list expression for a split node', () => {
      const splitNode: any = {
        id: 'cfg-split',
        type: 'splitNode',
        position: { x: 0, y: 0 },
        data: { id: 'cfg-split', label: 'Split', kind: 'split' },
      };
      const empty = new NodeConfigurationRule().validate(buildContext([splitNode], []));
      expect(empty.issues.filter((i) => i.context?.rule === 'split_missing_list')).toHaveLength(1);

      const withList: any = { ...splitNode, data: { ...splitNode.data, list: '{{items}}' } };
      const valid = new NodeConfigurationRule().validate(buildContext([withList], []));
      expect(valid.issues.filter((i) => i.context?.rule === 'split_missing_list')).toHaveLength(0);
    });

    it('requires a message UID only for an active email_inbox action and a target folder only for move', () => {
      // action 'none' needs nothing
      const none = new NodeConfigurationRule().validate(buildContext([coreNode('email_inbox', { emailAction: 'none' })], []));
      expect(
        none.issues.filter((i) => ['inbox_missing_uid', 'inbox_missing_target'].includes(i.context?.rule as string))
      ).toHaveLength(0);

      // 'archive' needs a UID but no target folder
      const archive = new NodeConfigurationRule().validate(
        buildContext([coreNode('email_inbox', { emailAction: 'archive' })], [])
      );
      expect(archive.issues.filter((i) => i.context?.rule === 'inbox_missing_uid')).toHaveLength(1);
      expect(archive.issues.filter((i) => i.context?.rule === 'inbox_missing_target')).toHaveLength(0);

      // 'move' needs both a UID and a target folder
      const move = new NodeConfigurationRule().validate(
        buildContext([coreNode('email_inbox', { emailAction: 'move' })], [])
      );
      expect(move.issues.filter((i) => i.context?.rule === 'inbox_missing_uid')).toHaveLength(1);
      expect(move.issues.filter((i) => i.context?.rule === 'inbox_missing_target')).toHaveLength(1);
    });

    it('requires a title for create_task and a task id for get/update/delete operations', () => {
      const create = new NodeConfigurationRule().validate(
        buildContext([coreNode('task', { taskOperation: 'create_task' })], [])
      );
      expect(create.issues.filter((i) => i.context?.rule === 'task_missing_title')).toHaveLength(1);

      const get = new NodeConfigurationRule().validate(
        buildContext([coreNode('task', { taskOperation: 'get_task' })], [])
      );
      expect(get.issues.filter((i) => i.context?.rule === 'task_missing_task_id')).toHaveLength(1);

      const getValid = new NodeConfigurationRule().validate(
        buildContext([coreNode('task', { taskOperation: 'get_task', taskTaskId: 'task-123' })], [])
      );
      expect(getValid.issues.filter((i) => i.context?.rule === 'task_missing_task_id')).toHaveLength(0);
    });

    it('flags create_task context that is not a JSON object but accepts a valid object', () => {
      const base = { taskOperation: 'create_task', taskTitle: 'Do it' };

      const arrayCtx = new NodeConfigurationRule().validate(
        buildContext([coreNode('task', { ...base, taskContextJson: '[1, 2, 3]' })], [])
      );
      expect(arrayCtx.issues.filter((i) => i.context?.rule === 'task_invalid_task_context')).toHaveLength(1);

      const malformedCtx = new NodeConfigurationRule().validate(
        buildContext([coreNode('task', { ...base, taskContextJson: 'not json' })], [])
      );
      expect(malformedCtx.issues.filter((i) => i.context?.rule === 'task_invalid_task_context')).toHaveLength(1);

      const objectCtx = new NodeConfigurationRule().validate(
        buildContext([coreNode('task', { ...base, taskContextJson: '{"priority": "high"}' })], [])
      );
      expect(objectCtx.issues.filter((i) => i.context?.rule === 'task_invalid_task_context')).toHaveLength(0);

      // empty string is treated as "no context" and must not error
      const emptyCtx = new NodeConfigurationRule().validate(
        buildContext([coreNode('task', { ...base, taskContextJson: '' })], [])
      );
      expect(emptyCtx.issues.filter((i) => i.context?.rule === 'task_invalid_task_context')).toHaveLength(0);
    });
  });

  // ===================== Optional-component availability (warnings) =====================

  describe('Optional-component availability (deployment-level warnings)', () => {
    const makeBrowserAgentNode = () =>
      ({
        id: 'n-browse',
        type: 'agentNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'browser-agent',
          kind: 'browser_agent',
          label: 'Browse',
          paramExpressions: { task: 'Open example.com' },
        },
      }) as any;

    const makeInterfaceNode = (interfaceData: Record<string, unknown>) =>
      ({
        id: 'n-iface',
        type: 'interfaceNode',
        position: { x: 0, y: 0 },
        data: { id: 'interface-1', label: 'Results UI', interfaceData },
      }) as any;

    const withCaps = (
      nodes: any[],
      caps?: { screenshotRenderer: boolean; browserAgent: boolean; webSearch: boolean },
    ) => ({ ...buildContext(nodes, []), featureCapabilities: caps });

    it('warns on a browser_agent node when the browser stack is unavailable', () => {
      const result = rule.validate(
        withCaps([makeBrowserAgentNode()], { screenshotRenderer: true, browserAgent: false, webSearch: false })
      );

      const issues = result.issues.filter((i) => i.context?.rule === 'browser_agent_component_unavailable');
      expect(issues).toHaveLength(1);
      expect(issues[0].severity).toBe('warning');
    });

    it('does not warn on a browser_agent node when the stack is available', () => {
      const result = rule.validate(
        withCaps([makeBrowserAgentNode()], { screenshotRenderer: true, browserAgent: true, webSearch: true })
      );

      expect(result.issues.filter((i) => i.context?.rule === 'browser_agent_component_unavailable')).toHaveLength(0);
    });

    it('warns on an interface node with generateScreenshot when the renderer is unavailable', () => {
      const result = rule.validate(
        withCaps(
          [makeInterfaceNode({ generateScreenshot: true })],
          { screenshotRenderer: false, browserAgent: true, webSearch: true },
        )
      );

      const issues = result.issues.filter((i) => i.context?.rule === 'interface_renderer_unavailable');
      expect(issues).toHaveLength(1);
      expect(issues[0].severity).toBe('warning');
    });

    it('warns on an interface node with generatePdf when the renderer is unavailable', () => {
      const result = rule.validate(
        withCaps(
          [makeInterfaceNode({ generatePdf: true })],
          { screenshotRenderer: false, browserAgent: true, webSearch: true },
        )
      );

      expect(result.issues.filter((i) => i.context?.rule === 'interface_renderer_unavailable')).toHaveLength(1);
    });

    it('does not warn on an interface node with NO render toggle even when the renderer is unavailable', () => {
      const result = rule.validate(
        withCaps(
          [makeInterfaceNode({})],
          { screenshotRenderer: false, browserAgent: true, webSearch: true },
        )
      );

      expect(result.issues.filter((i) => i.context?.rule === 'interface_renderer_unavailable')).toHaveLength(0);
    });

    it('emits NO availability warning when capabilities are unknown (loading / fetch error / older backend)', () => {
      const result = rule.validate(
        withCaps([makeBrowserAgentNode(), makeInterfaceNode({ generateScreenshot: true })], undefined)
      );

      expect(result.issues.filter((i) =>
        i.context?.rule === 'browser_agent_component_unavailable'
        || i.context?.rule === 'interface_renderer_unavailable',
      )).toHaveLength(0);
    });
  });
});
