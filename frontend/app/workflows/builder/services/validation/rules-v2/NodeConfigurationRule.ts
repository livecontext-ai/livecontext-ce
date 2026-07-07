/**
 * NodeConfigurationRule - Validates node-specific structural configuration
 *
 * Validations:
 * #10  Step/MCP without tool ID (error)
 * #11  Decision without conditions (error)
 * #12  Loop without condition (error)
 * #13  Switch without expression or cases (error)
 *
 * #14  Required fields for core node types (error)
 * #15  MCP tool required parameters (error)
 */

import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import type { ValidationContext, ValidationIssue } from '../core/types';
import { BaseValidationRule } from './BaseValidationRule';
import { normalizeLabel } from '../../../utils/labelNormalizer';
import { getNodeType, isInterfaceNode, isCrudNode, isNoteNode } from '../core/nodeUtils';
import { nodeRegistry } from '../../../registry/nodeRegistry';

export class NodeConfigurationRule extends BaseValidationRule {
  readonly ruleName = 'NodeConfiguration' as const;
  readonly isCritical = true;
  readonly priority = 8;

  validate(context: ValidationContext): ReturnType<typeof this.buildResult> {
    const issues: ValidationIssue[] = [];
    const { nodes } = context;

    for (const node of nodes) {
      if (isNoteNode(node)) continue;

      const nodeType = getNodeType(node);
      const label = node.data?.label;
      const norm = label ? normalizeLabel(label) : null;
      const elementKey = norm ? `${nodeType}:${norm}` : `${nodeType}:${node.id}`;

      // #10: Step/MCP without tool ID
      if (nodeType === 'mcp' && !isInterfaceNode(node) && !isCrudNode(node)) {
        const toolData = node.data?.toolData;
        if (!toolData || (!toolData.toolId && !toolData.toolSlug)) {
          issues.push(
            this.createError(elementKey, 'mcp', 'Step id is required', {
              rule: 'step_missing_tool',
              nodeId: node.id,
            })
          );
        }
      }

      // #11: Decision without conditions
      if (nodeRegistry.isDecisionNode(node)) {
        this.validateDecision(node, elementKey, issues);
      }

      // #12: Loop without condition
      if (nodeRegistry.isLoopNode(node)) {
        this.validateLoop(node, elementKey, issues);
      }

      // #13: Switch without expression or cases
      if (nodeRegistry.isSwitchNode(node) || nodeRegistry.isOptionNode(node)) {
        this.validateSwitch(node, elementKey, issues);
      }

      // Approval: context template is recommended (WARNING, non-blocking). Without it
      // the approver sees no description of what they are approving; the run still works.
      if (nodeRegistry.isUserApprovalNode(node)) {
        this.validateApprovalContext(node, elementKey, issues);
      }

      // #14: Required fields for all core node types
      this.validateRequiredFields(node, elementKey, issues);

      // Optional-component availability (deployment-level, WARNING): browser_agent
      // nodes fail at run time and interface render toggles silently no-op when the
      // backing opt-in component is absent - surface it at build time.
      this.validateOptionalComponentAvailability(node, elementKey, context, issues);

      // #15: MCP tool required parameters
      if (nodeType === 'mcp' && !isInterfaceNode(node) && !isCrudNode(node)) {
        this.validateToolParameters(node, elementKey, issues);
      }
    }

    return this.buildResult(issues);
  }

  /**
   * Warns when a node depends on an OPTIONAL deployment component reported absent
   * by the capabilities endpoint. Warning (not error): the workflow stays saveable
   * and runnable elsewhere (publish/clone onto an install that has the component).
   * Unknown capabilities (context field absent: loading, fetch error, older
   * backend) emit nothing - never a possibly-false warning.
   */
  private validateOptionalComponentAvailability(
    node: Node<BuilderNodeData>,
    elementKey: string,
    context: ValidationContext,
    issues: ValidationIssue[]
  ): void {
    const caps = context.featureCapabilities;
    if (!caps) return;

    const d = node.data as any;

    if (nodeRegistry.isBrowserAgentNode(node) && !caps.browserAgent) {
      issues.push(this.createWarning(elementKey, 'agent',
        'Browser agent is not enabled on this installation - this node will fail at run time. An administrator can enable the optional browser-agent component or link the installation to the cloud.',
        { rule: 'browser_agent_component_unavailable', nodeId: node.id }));
    }

    if (isInterfaceNode(node) && !caps.screenshotRenderer) {
      const iface = d?.interfaceData ?? {};
      if (iface.generateScreenshot === true || iface.generatePdf === true) {
        issues.push(this.createWarning(elementKey, 'interface',
          'Screenshot/PDF generation is enabled but the optional renderer component is not running on this installation - the screenshot/pdf outputs will be absent. An administrator can enable the renderer component.',
          { rule: 'interface_renderer_unavailable', nodeId: node.id }));
      }
    }
  }

  /**
   * Validates required fields for all core node types.
   * Each node type has specific fields that must be non-empty.
   */
  private validateRequiredFields(
    node: Node<BuilderNodeData>,
    elementKey: string,
    issues: ValidationIssue[]
  ): void {
    const d = node.data as any;
    const kind = d?.kind;
    if (!kind) return;

    // Data processing nodes: input expression required
    const dataInputMap: Record<string, string> = {
      filter: 'filterInput', sort: 'sortInput', limit: 'limitInput',
      remove_duplicates: 'dedupInput', summarize: 'summarizeInput',
    };
    if (dataInputMap[kind]) {
      this.requireField(d, dataInputMap[kind], 'Input data is required - specify the items to process', 'data_node_missing_input', node.id, elementKey, issues);
    }

    // XML: input required
    if (kind === 'xml') {
      this.requireField(d, 'xmlInput', 'Input data is required', 'xml_missing_input', node.id, elementKey, issues);
    }
    // Compression: input required
    if (kind === 'compression') {
      this.requireField(d, 'compressionInput', 'Input data is required', 'compression_missing_input', node.id, elementKey, issues);
    }
    // ConvertToFile: value required
    if (kind === 'convert_to_file') {
      this.requireField(d, 'convertValue', 'Data source is required', 'convert_missing_value', node.id, elementKey, issues);
    }
    // ExtractFromFile: value required
    if (kind === 'extract_from_file') {
      this.requireField(d, 'extractValue', 'File content or URL is required', 'extract_missing_value', node.id, elementKey, issues);
    }
    // CompareDatasets: inputA and inputB required
    if (kind === 'compare_datasets') {
      this.requireField(d, 'compareInputA', 'Dataset A is required', 'compare_missing_input_a', node.id, elementKey, issues);
      this.requireField(d, 'compareInputB', 'Dataset B is required', 'compare_missing_input_b', node.id, elementKey, issues);
    }
    // RSS: url required
    if (kind === 'rss') {
      this.requireField(d, 'rssUrl', 'Feed URL is required', 'rss_missing_url', node.id, elementKey, issues);
    }
    // Code: code content required
    if (kind === 'code') {
      this.requireField(d, 'codeContent', 'Code is required', 'code_missing_content', node.id, elementKey, issues);
    }
    // HttpRequest: url required
    if (kind === 'http_request') {
      const httpData = d?.httpRequestData;
      if (!httpData?.url || httpData.url.trim() === '') {
        issues.push(this.createError(elementKey, 'core', 'URL is required', { rule: 'http_missing_url', nodeId: node.id }));
      }
    }
    // DownloadFile: url required
    if (kind === 'download_file') {
      this.requireField(d, 'downloadUrl', 'URL is required', 'download_missing_url', node.id, elementKey, issues);
    }
    // SendEmail: to and subject required
    if (kind === 'send_email') {
      this.requireField(d, 'emailTo', 'Recipient email is required', 'email_missing_to', node.id, elementKey, issues);
      this.requireField(d, 'emailSubject', 'Subject is required', 'email_missing_subject', node.id, elementKey, issues);
    }
    // EmailInbox: any action other than 'none' needs messageUid; move also needs targetFolder
    if (kind === 'email_inbox') {
      const action = d?.emailAction;
      if (action && action !== 'none') {
        this.requireField(d, 'emailMessageUid', 'Message UID is required for this action', 'inbox_missing_uid', node.id, elementKey, issues);
        if (action === 'move') {
          this.requireField(d, 'emailTargetFolder', 'Target folder is required for the move action', 'inbox_missing_target', node.id, elementKey, issues);
        }
      }
    }
    // Split: list required
    if (nodeRegistry.isSplitNode(node)) {
      this.requireField(d, 'list', 'List expression is required', 'split_missing_list', node.id, elementKey, issues);
    }
    // Set: at least one assignment required
    if (kind === 'set') {
      const assignments = d?.setAssignments;
      if (!Array.isArray(assignments) || assignments.length === 0) {
        issues.push(this.createError(elementKey, 'core',
          'Set node must have at least one assignment',
          { rule: 'set_missing_assignments', nodeId: node.id }));
      }
    }
    // HtmlExtract: sourceHtml + at least one field required
    if (kind === 'html_extract') {
      this.requireField(d, 'htmlExtractSource', 'Source HTML is required', 'html_extract_missing_source', node.id, elementKey, issues);
      const fields = d?.htmlExtractFields;
      if (!Array.isArray(fields) || fields.length === 0) {
        issues.push(this.createError(elementKey, 'core',
          'HTML Extract node must have at least one field',
          { rule: 'html_extract_missing_fields', nodeId: node.id }));
      }
    }
    // Agent (reasoning): prompt always required, model only for inline agents
    if (kind === 'reasoning' || d?.agentType === 'agent') {
      this.requireParamExpression(d, 'prompt', 'Prompt is required', 'agent_missing_prompt', node.id, elementKey, issues);
      if (!d?.agentConfigId) {
        this.requireParamExpression(d, 'model', 'Model is required', 'agent_missing_model', node.id, elementKey, issues);
      }
    }
    // Guardrail: input text and at least one rule required
    if (kind === 'guardrail' || d?.agentType === 'guardrail') {
      this.requireParamExpression(d, 'guardrailParams', 'Input text is required', 'guardrail_missing_input', node.id, elementKey, issues);
      const rules = d?.guardrailRules;
      if (!Array.isArray(rules) || rules.length === 0) {
        issues.push(this.createError(elementKey, 'agent',
          'Guardrail must have at least one rule',
          { rule: 'guardrail_missing_rules', nodeId: node.id }));
      }
    }
    // Classify: prompt and at least two categories required
    if (kind === 'classify' || d?.agentType === 'classify') {
      this.requireParamExpression(d, 'prompt', 'Prompt is required', 'classify_missing_prompt', node.id, elementKey, issues);
      const categories = d?.classifyCategories;
      if (!Array.isArray(categories) || categories.length < 2) {
        issues.push(this.createError(elementKey, 'agent',
          'Classify must have at least two categories',
          { rule: 'classify_missing_categories', nodeId: node.id }));
      }
    }
    // Browser Agent: natural-language goal ('task') is required so the
    // backend agent has something to execute. Model defaults are resolved
    // server-side, so we don't require it here unless the agent ever
    // becomes inline-configured (matches Agent's `agentConfigId` carve-out).
    if (kind === 'browser_agent' || d?.agentType === 'browser_agent') {
      this.requireParamExpression(d, 'task', 'Task is required', 'browser_agent_missing_task', node.id, elementKey, issues);
    }
    // Response: message required
    if (kind === 'output' || kind === 'response') {
      this.requireField(d, 'responseMessage', 'Response message is required', 'response_missing_message', node.id, elementKey, issues);
    }
    // Task: operation-specific required fields
    if (kind === 'task') {
      const op = d?.taskOperation;
      if (op === 'create_task') {
        this.requireField(d, 'taskTitle', 'Task title is required for create', 'task_missing_title', node.id, elementKey, issues);
        // taskContext is optional but must be a JSON object - stepProcessor
        // silently drops anything else, so flag it here instead.
        const ctxJson = d?.taskContextJson;
        if (typeof ctxJson === 'string' && ctxJson.trim() !== '') {
          let invalid = false;
          try {
            const parsed = JSON.parse(ctxJson);
            invalid = parsed === null || typeof parsed !== 'object' || Array.isArray(parsed);
          } catch {
            invalid = true;
          }
          if (invalid) {
            issues.push(this.createError(elementKey, 'core',
              'Task context must be a valid JSON object',
              { rule: 'task_invalid_task_context', nodeId: node.id }));
          }
        }
      }
      if (op === 'get_task' || op === 'update_task' || op === 'delete_task') {
        this.requireField(d, 'taskTaskId', 'Task ID is required', 'task_missing_task_id', node.id, elementKey, issues);
      }
    }
    // SSH: command required (host comes from credential)
    if (kind === 'ssh') {
      this.requireField(d, 'sshCommand', 'Command is required', 'ssh_missing_command', node.id, elementKey, issues);
    }
    // SFTP: remotePath required (host comes from credential)
    if (kind === 'sftp') {
      this.requireField(d, 'sftpRemotePath', 'Remote path is required', 'sftp_missing_remote_path', node.id, elementKey, issues);
    }
    // Database: query required (host/databaseName come from credential)
    if (kind === 'database') {
      this.requireField(d, 'dbQuery', 'SQL query is required', 'db_missing_query', node.id, elementKey, issues);
    }
  }

  private requireField(
    data: any, fieldKey: string, message: string, rule: string,
    nodeId: string, elementKey: string, issues: ValidationIssue[]
  ): void {
    const value = data?.[fieldKey];
    if (!value || (typeof value === 'string' && value.trim() === '')) {
      issues.push(this.createError(elementKey, 'core', message, { rule, nodeId }));
    }
  }

  /**
   * Checks that a paramExpressions field is non-empty.
   * Falls back to the direct data field if paramExpressions doesn't have it.
   */
  private requireParamExpression(
    data: any, fieldKey: string, message: string, rule: string,
    nodeId: string, elementKey: string, issues: ValidationIssue[]
  ): void {
    const expr = data?.paramExpressions?.[fieldKey];
    const direct = data?.[fieldKey];
    const value = expr || direct;
    if (!value || (typeof value === 'string' && value.trim() === '')) {
      issues.push(this.createError(elementKey, 'agent', message, { rule, nodeId }));
    }
  }

  /**
   * Approval context template is recommended (WARNING, never blocking). The approver
   * relies on it to know what they are approving; the run still proceeds without it.
   */
  private validateApprovalContext(
    node: Node<BuilderNodeData>,
    elementKey: string,
    issues: ValidationIssue[]
  ): void {
    const template = (node.data as any)?.approvalContextTemplate;
    if (!template || (typeof template === 'string' && template.trim() === '')) {
      issues.push(
        this.createWarning(
          elementKey,
          'core',
          'Approval context is recommended so reviewers see what they are approving',
          { rule: 'approval_missing_context_template', nodeId: node.id }
        )
      );
    }
  }

  private validateDecision(
    node: Node<BuilderNodeData>,
    elementKey: string,
    issues: ValidationIssue[]
  ): void {
    const conditions = node.data?.decisionConditions;

    if (!conditions || conditions.length === 0) {
      issues.push(
        this.createError(elementKey, 'core', 'Decision node must have at least one condition', {
          rule: 'decision_no_conditions',
          nodeId: node.id,
        })
      );
      return;
    }

    const ifConditions = conditions.filter((c) => c.type === 'if');
    if (ifConditions.length === 0) {
      issues.push(
        this.createError(elementKey, 'core', "Decision node must have an 'if' condition", {
          rule: 'decision_no_if',
          nodeId: node.id,
        })
      );
    } else if (ifConditions.length > 1) {
      issues.push(
        this.createError(elementKey, 'core', "Decision node can only have one 'if' condition", {
          rule: 'decision_multiple_if',
          nodeId: node.id,
        })
      );
    }

    conditions.forEach((condition, index) => {
      if (condition.type !== 'else' && (!condition.expression || condition.expression.trim() === '')) {
        issues.push(
          this.createError(
            elementKey,
            'core',
            `Condition ${index + 1} (${condition.type || 'unknown'}) is empty`,
            {
              rule: 'decision_empty_condition',
              nodeId: node.id,
              conditionIndex: index,
            }
          )
        );
      }
    });
  }

  private validateLoop(
    node: Node<BuilderNodeData>,
    elementKey: string,
    issues: ValidationIssue[]
  ): void {
    const loopCondition = node.data?.whileCondition ?? node.data?.loopCondition;

    if (!loopCondition || loopCondition.trim() === '') {
      issues.push(
        this.createError(elementKey, 'core', 'Loop node must have a loop condition', {
          rule: 'loop_no_condition',
          nodeId: node.id,
        })
      );
    }
  }

  private validateSwitch(
    node: Node<BuilderNodeData>,
    elementKey: string,
    issues: ValidationIssue[]
  ): void {
    const data = node.data as any;

    // Option node (optionChoices)
    if (nodeRegistry.isOptionNode(node)) {
      const choices = data?.optionChoices;
      if (!choices || choices.length === 0) {
        issues.push(
          this.createError(elementKey, 'core', 'Option node must have at least one choice', {
            rule: 'option_no_choices',
            nodeId: node.id,
          })
        );
        return;
      }
      choices.forEach((choice: any, index: number) => {
        if (!choice.label || choice.label.trim() === '') {
          issues.push(
            this.createError(elementKey, 'core', `Choice ${index + 1} is missing a label`, {
              rule: 'option_empty_choice_label',
              nodeId: node.id,
              choiceIndex: index,
            })
          );
        }
      });
      return;
    }

    // Switch node (switchExpression + switchCases)
    const switchExpression = data?.switchExpression;
    if (!switchExpression || switchExpression.trim() === '') {
      issues.push(
        this.createError(elementKey, 'core', 'Switch expression is missing', {
          rule: 'switch_no_expression',
          nodeId: node.id,
        })
      );
    }

    const cases = data?.switchCases;
    if (!cases || cases.length === 0) {
      issues.push(
        this.createError(elementKey, 'core', 'Switch must have at least one case', {
          rule: 'switch_no_cases',
          nodeId: node.id,
        })
      );
    }
  }

  /**
   * Validates MCP tool required parameters are filled in.
   */
  private validateToolParameters(
    node: Node<BuilderNodeData>,
    elementKey: string,
    issues: ValidationIssue[]
  ): void {
    const data = node.data as any;
    const toolData = data?.toolData;
    if (!toolData) return;

    const parameters = toolData.parameters;
    if (!Array.isArray(parameters) || parameters.length === 0) return;

    const paramExpressions = data.paramExpressions || {};

    for (const param of parameters) {
      const isRequired = param.isRequired === true || param.required === true;
      const paramName = param.name || param.id;
      if (!paramName) continue;

      const expression = paramExpressions[paramName];
      if (isRequired && (!expression || (typeof expression === 'string' && expression.trim() === ''))) {
        issues.push(
          this.createError(elementKey, 'mcp', `Required parameter "${paramName}" is missing`, {
            rule: 'tool_missing_required_param',
            nodeId: node.id,
            parameter: paramName,
          })
        );
      }
    }
  }
}
