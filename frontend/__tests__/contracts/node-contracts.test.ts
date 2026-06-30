/**
 * Frontend Node Contract Tests
 *
 * These tests verify that:
 * 1. Generated TypeScript interfaces match the schema
 * 2. Output field names are correct for each node type
 * 3. Frontend can create valid plans with correct references
 */

import { describe, it, expect, beforeAll } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';
import { normalizeLabel } from '../../app/workflows/builder/utils/labelNormalizer';

// Load schema
const schemaPath = path.resolve(__dirname, '../../../shared/contracts/node-contracts.schema.json');
let schema: any;

beforeAll(() => {
  const schemaContent = fs.readFileSync(schemaPath, 'utf8');
  schema = JSON.parse(schemaContent);
});

describe('Node Contracts - Schema Alignment', () => {
  // ═══════════════════════════════════════════════════════════════
  // TRIGGER OUTPUT CONTRACTS
  // ═══════════════════════════════════════════════════════════════

  describe('Trigger Outputs', () => {
    it('webhook trigger should have correct outputs', () => {
      const webhook = schema.nodes.find((n: any) => n.id === 'webhook-trigger');
      expect(webhook).toBeDefined();

      const outputNames = webhook.outputs.map((o: any) => o.name);
      expect(outputNames).toContain('{body_field}');
      expect(outputNames).toContain('{query_param}');
      expect(outputNames).toContain('_webhookMethod');
      expect(outputNames).toContain('_webhookTimestamp');
      expect(outputNames).toContain('trigger_id');
    });

    it('schedule trigger should have correct outputs', () => {
      const schedule = schema.nodes.find((n: any) => n.id === 'schedule-trigger');
      expect(schedule).toBeDefined();

      const outputNames = schedule.outputs.map((o: any) => o.name);
      expect(outputNames).toContain('triggered_at');
      expect(outputNames).toContain('execution_count');
      expect(outputNames).toContain('scheduleId');
    });

    it('chat trigger should have correct outputs', () => {
      const chat = schema.nodes.find((n: any) => n.id === 'chat-trigger');
      expect(chat).toBeDefined();

      const outputNames = chat.outputs.map((o: any) => o.name);
      expect(outputNames).toContain('message');
      // Snake_case per the canonical schema (2026-05-10 alignment): the
      // earlier camelCase `extractedMessage` no longer exists in
      // shared/contracts/node-contracts.schema.json.
      expect(outputNames).toContain('extracted_message');
      expect(outputNames).toContain('triggered_at');
    });

    it('form trigger should have correct outputs', () => {
      const form = schema.nodes.find((n: any) => n.id === 'form-trigger');
      expect(form).toBeDefined();

      const outputNames = form.outputs.map((o: any) => o.name);
      expect(outputNames).toContain('submitted_at');
      expect(outputNames).toContain('form_data');
      // Schema uses the {field_name} placeholder convention (underscore),
      // not {field.name} dotted syntax - aligned 2026-05-10.
      expect(outputNames).toContain('{field_name}');
    });

    it('table/datasource trigger should have correct outputs', () => {
      const table = schema.nodes.find((n: any) => n.id === 'tables-trigger');
      expect(table).toBeDefined();

      // Event-driven trigger (one run per row created/updated/deleted)
      const outputNames = table.outputs.map((o: any) => o.name);
      expect(outputNames).toContain('row');
      expect(outputNames).toContain('event_type');
      expect(outputNames).toContain('row_id');
      expect(outputNames).toContain('datasource_id');
    });
  });

  // ═══════════════════════════════════════════════════════════════
  // AI NODE OUTPUT CONTRACTS
  // ═══════════════════════════════════════════════════════════════

  describe('AI Node Outputs', () => {
    it('agent should have correct outputs with tool_calls as number', () => {
      const agent = schema.nodes.find((n: any) => n.id === 'ai-agent');
      expect(agent).toBeDefined();

      const outputNames = agent.outputs.map((o: any) => o.name);
      expect(outputNames).toContain('response');
      expect(outputNames).toContain('model');
      expect(outputNames).toContain('provider');
      expect(outputNames).toContain('tokens_used');
      expect(outputNames).toContain('iterations');
      expect(outputNames).toContain('tool_calls');
      expect(outputNames).toContain('durationMs');

      // Verify tool_calls is number, not array
      const toolCalls = agent.outputs.find((o: any) => o.name === 'tool_calls');
      expect(toolCalls.type).toBe('number');
    });

    it('guardrail should have correct outputs', () => {
      const guardrail = schema.nodes.find((n: any) => n.id === 'guardrail');
      expect(guardrail).toBeDefined();

      const outputNames = guardrail.outputs.map((o: any) => o.name);
      expect(outputNames).toContain('passed');
      expect(outputNames).toContain('violations');
      expect(outputNames).toContain('details');
      expect(outputNames).toContain('sanitized');
      expect(outputNames).toContain('model');
      expect(outputNames).toContain('provider');
    });

    it('classify should have correct outputs', () => {
      const classify = schema.nodes.find((n: any) => n.id === 'classify');
      expect(classify).toBeDefined();

      const outputNames = classify.outputs.map((o: any) => o.name);
      expect(outputNames).toContain('selected_category');
      expect(outputNames).toContain('confidence');
      expect(outputNames).toContain('model');
      expect(outputNames).toContain('provider');
    });
  });

  // ═══════════════════════════════════════════════════════════════
  // CONTROL FLOW OUTPUT CONTRACTS
  // ═══════════════════════════════════════════════════════════════

  describe('Control Flow Outputs', () => {
    it('decision should have correct outputs', () => {
      const decision = schema.nodes.find((n: any) => n.id === 'decision');
      expect(decision).toBeDefined();

      const outputNames = decision.outputs.map((o: any) => o.name);
      expect(outputNames).toContain('selected_branch');
      expect(outputNames).toContain('evaluations');
    });

    it('loop should have correct outputs and use loopCondition parameter', () => {
      const loop = schema.nodes.find((n: any) => n.id === 'loop');
      expect(loop).toBeDefined();

      const outputNames = loop.outputs.map((o: any) => o.name);
      expect(outputNames).toContain('iteration');
      expect(outputNames).toContain('maxIterations');
      expect(outputNames).toContain('terminated');

      // Verify parameter is loopCondition, not "condition"
      const paramNames = loop.parameters.map((p: any) => p.name);
      expect(paramNames).toContain('loopCondition');
      expect(paramNames).not.toContain('condition');
    });

    it('split should have correct outputs', () => {
      const split = schema.nodes.find((n: any) => n.id === 'split');
      expect(split).toBeDefined();

      const outputNames = split.outputs.map((o: any) => o.name);
      expect(outputNames).toContain('current_item');
      expect(outputNames).toContain('current_index');
      expect(outputNames).toContain('item_count');
      expect(outputNames).toContain('split_id');
      expect(outputNames).toContain('split_strategy');
      expect(outputNames).toContain('exit_reason');
      expect(outputNames).toContain('terminated');
    });

    it('merge should have correct outputs', () => {
      const merge = schema.nodes.find((n: any) => n.id === 'merge');
      expect(merge).toBeDefined();

      const outputNames = merge.outputs.map((o: any) => o.name);
      expect(outputNames).toContain('{strategy_output}');
    });
  });

  // ═══════════════════════════════════════════════════════════════
  // ACTION NODE OUTPUT CONTRACTS
  // ═══════════════════════════════════════════════════════════════

  describe('Action Node Outputs', () => {
    it('mcp-tool should have correct outputs', () => {
      const mcpTool = schema.nodes.find((n: any) => n.id === 'mcp-tool');
      expect(mcpTool).toBeDefined();

      const outputNames = mcpTool.outputs.map((o: any) => o.name);
      expect(outputNames).toContain('{dynamicSchema}');
    });

    it('transform should have correct outputs', () => {
      const transform = schema.nodes.find((n: any) => n.id === 'transform');
      expect(transform).toBeDefined();

      // Transform outputs are dynamic based on mappings
      const outputNames = transform.outputs.map((o: any) => o.name);
      expect(outputNames).toContain('{mapping.label}');
    });

    it('wait should have correct outputs', () => {
      const wait = schema.nodes.find((n: any) => n.id === 'wait');
      expect(wait).toBeDefined();

      const outputNames = wait.outputs.map((o: any) => o.name);
      expect(outputNames).toContain('waited_ms');
      expect(outputNames).toContain('status');
      expect(outputNames).toContain('started_at');
      expect(outputNames).toContain('completed_at');
      expect(outputNames).toContain('duration_ms');
      expect(outputNames).toContain('expires_at');
    });
  });
});

// ═══════════════════════════════════════════════════════════════
// OUTPUT REFERENCE PATTERN TESTS
// ═══════════════════════════════════════════════════════════════

describe('Output Reference Patterns', () => {
  it('should generate correct trigger reference patterns', () => {
    // Test reference pattern generation - use centralized normalizeLabel
    const triggerLabel = 'My Webhook';
    const normalizedLabelValue = normalizeLabel(triggerLabel);
    const triggerRef = `trigger:${normalizedLabelValue}`;

    expect(triggerRef).toBe('trigger:my_webhook');

    // Test output reference with unified pattern: {{type:label.output.field}}
    const payloadRef = `{{${triggerRef}.output.payload}}`;
    expect(payloadRef).toBe('{{trigger:my_webhook.output.payload}}');
  });

  it('should generate correct mcp reference patterns', () => {
    // Use centralized normalizeLabel
    const stepLabel = 'Fetch Data';
    const normalizedLabelValue = normalizeLabel(stepLabel);
    const mcpRef = `mcp:${normalizedLabelValue}`;

    expect(mcpRef).toBe('mcp:fetch_data');

    const responseRef = `{{${mcpRef}.output.response}}`;
    expect(responseRef).toBe('{{mcp:fetch_data.output.response}}');
  });

  it('should generate correct agent reference patterns', () => {
    // Use centralized normalizeLabel
    const agentLabel = 'Data Analyzer';
    const normalizedLabelValue = normalizeLabel(agentLabel);
    const agentRef = `agent:${normalizedLabelValue}`;

    expect(agentRef).toBe('agent:data_analyzer');

    // Unified pattern: {{type:label.output.field}}
    const responseRef = `{{${agentRef}.output.response}}`;
    expect(responseRef).toBe('{{agent:data_analyzer.output.response}}');
  });

  it('should generate correct control flow reference patterns', () => {
    // Decision - unified pattern: {{type:label.output.field}}
    const decisionRef = 'core:check_status';
    const branchRef = `{{${decisionRef}.output.selected_branch}}`;
    expect(branchRef).toBe('{{core:check_status.output.selected_branch}}');

    // Loop - unified pattern
    const loopRef = 'core:process_items';
    const iterationRef = `{{${loopRef}.output.iteration}}`;
    expect(iterationRef).toBe('{{core:process_items.output.iteration}}');

    // Split - unified pattern
    const splitRef = 'core:each_item';
    const itemsRef = `{{${splitRef}.output.items}}`;
    expect(itemsRef).toBe('{{core:each_item.output.items}}');
  });
});

// ═══════════════════════════════════════════════════════════════
// PLAN STRUCTURE TESTS
// ═══════════════════════════════════════════════════════════════

describe('Plan Structure', () => {
  it('should create valid plan with trigger and step', () => {
    // Unified expression pattern: {{type:label.output.field}}
    const plan = {
      id: 'test-plan',
      tenant_id: 'test-tenant',
      triggers: [
        {
          id: 'webhook-1',
          label: 'My Webhook',
          type: 'webhook',
          strategy: 'single'
        }
      ],
      mcps: [
        {
          id: 'api-call-1',
          label: 'Process Data',
          input: {
            data: '{{trigger:my_webhook.output.payload}}'
          }
        }
      ],
      edges: [
        {
          from: 'trigger:my_webhook',
          to: 'mcp:process_data'
        }
      ]
    };

    expect(plan.triggers).toHaveLength(1);
    expect(plan.mcps).toHaveLength(1);
    expect(plan.edges).toHaveLength(1);
    expect(plan.mcps[0].input.data).toBe('{{trigger:my_webhook.output.payload}}');
  });

  it('should create valid plan with decision node', () => {
    // V2 format: conditions in cores, edges use simple from/to with ports
    const plan = {
      id: 'test-plan',
      tenant_id: 'test-tenant',
      triggers: [],
      mcps: [],
      cores: [
        {
          id: 'decision-1',
          label: 'Check Status',
          type: 'decision',
          decisionConditions: [
            {
              branchLabel: 'Success',
              expression: '{{mcp:api_call.output.status}} == 200'
            },
            {
              branchLabel: 'Error',
              expression: '{{mcp:api_call.output.status}} >= 400'
            }
          ]
        }
      ],
      edges: [
        // V2: Simple edges with ports for decision branches
        { from: 'mcp:api_call', to: 'core:check_status' },
        { from: 'core:check_status:if', to: 'mcp:handle_success' },
        { from: 'core:check_status:else', to: 'mcp:handle_error' }
      ]
    };

    expect(plan.cores).toHaveLength(1);
    expect(plan.cores[0].decisionConditions).toHaveLength(2);
    // V2: edges are simple from/to, no nested if/then/else
    expect(plan.edges).toHaveLength(3);
  });

  it('should create valid plan with loop node using loopCondition', () => {
    // Unified expression pattern: {{type:label.output.field}}
    const plan = {
      id: 'test-plan',
      tenant_id: 'test-tenant',
      triggers: [],
      mcps: [],
      cores: [
        {
          id: 'loop-1',
          label: 'Process Items',
          type: 'loop',
          loopCondition: '{{core:process_items.output.iteration}} < 10',
          maxIterations: 100
        }
      ],
      edges: []
    };

    expect(plan.cores).toHaveLength(1);
    expect(plan.cores[0].loopCondition).toBe('{{core:process_items.output.iteration}} < 10');
    // Verify we don't use "condition" (old name)
    expect((plan.cores[0] as any).condition).toBeUndefined();
  });
});
