#!/usr/bin/env node

/**
 * Contract Tests - Validates node-contracts.schema.json
 *
 * This script verifies:
 * 1. Schema structure is valid
 * 2. All nodes have required fields (id, name, category, parameters, outputs)
 * 3. All parameters and outputs have required fields (name, type)
 * 4. Prefixes are consistent with node types
 * 5. Output field names are valid identifiers
 * 6. No duplicate node IDs
 */

const fs = require('fs');
const path = require('path');

// ANSI colors
const GREEN = '\x1b[32m';
const RED = '\x1b[31m';
const YELLOW = '\x1b[33m';
const CYAN = '\x1b[36m';
const RESET = '\x1b[0m';
const BOLD = '\x1b[1m';

// Load schema
const schemaPath = path.join(__dirname, '..', 'node-contracts.schema.json');
let schema;

try {
  schema = JSON.parse(fs.readFileSync(schemaPath, 'utf8'));
} catch (e) {
  console.error(`${RED}✗ Failed to load schema: ${e.message}${RESET}`);
  process.exit(1);
}

// Test results
let passed = 0;
let failed = 0;
const failures = [];

function test(name, condition, message) {
  if (condition) {
    passed++;
    console.log(`  ${GREEN}✓${RESET} ${name}`);
  } else {
    failed++;
    failures.push({ name, message });
    console.log(`  ${RED}✗${RESET} ${name}`);
    if (message) {
      console.log(`    ${YELLOW}→ ${message}${RESET}`);
    }
  }
}

function testGroup(name, fn) {
  console.log(`\n${CYAN}${BOLD}${name}${RESET}`);
  console.log('─'.repeat(60));
  fn();
}

// ═══════════════════════════════════════════════════════════════════════════
// SCHEMA STRUCTURE TESTS
// ═══════════════════════════════════════════════════════════════════════════

testGroup('Schema Structure', () => {
  test('Schema has version field', schema.version !== undefined);
  test('Schema has lastUpdated field', schema.lastUpdated !== undefined);
  test('Schema has nodes array', Array.isArray(schema.nodes));
  test('Schema has at least 10 nodes', schema.nodes && schema.nodes.length >= 10,
    `Found ${schema.nodes?.length || 0} nodes`);
});

// ═══════════════════════════════════════════════════════════════════════════
// NODE DEFINITION TESTS
// ═══════════════════════════════════════════════════════════════════════════

testGroup('Node Definitions', () => {
  const nodeIds = new Set();

  for (const node of schema.nodes) {
    // Check required fields
    test(`Node "${node.id}" has id`, node.id !== undefined);
    test(`Node "${node.id}" has name`, node.name !== undefined);
    test(`Node "${node.id}" has category`, node.category !== undefined);
    test(`Node "${node.id}" has parameters array`, Array.isArray(node.parameters));
    test(`Node "${node.id}" has outputs array`, Array.isArray(node.outputs));

    // Check for duplicates
    if (nodeIds.has(node.id)) {
      test(`Node "${node.id}" is unique`, false, 'Duplicate node ID');
    } else {
      nodeIds.add(node.id);
    }
  }
});

// ═══════════════════════════════════════════════════════════════════════════
// PARAMETER TESTS
// ═══════════════════════════════════════════════════════════════════════════

testGroup('Parameter Definitions', () => {
  const validTypes = ['string', 'number', 'boolean', 'object', 'array', 'datetime', 'any'];

  for (const node of schema.nodes) {
    for (const param of node.parameters) {
      test(`${node.id}.${param.name} has name`, param.name !== undefined);
      test(`${node.id}.${param.name} has valid type`,
        validTypes.includes(param.type),
        `Type "${param.type}" not in ${validTypes.join(', ')}`);

      // Check name is valid identifier
      const isValidIdentifier = /^[a-zA-Z_][a-zA-Z0-9_]*$/.test(param.name);
      test(`${node.id}.${param.name} is valid identifier`, isValidIdentifier,
        `"${param.name}" is not a valid identifier`);
    }
  }
});

// ═══════════════════════════════════════════════════════════════════════════
// OUTPUT TESTS
// ═══════════════════════════════════════════════════════════════════════════

testGroup('Output Definitions', () => {
  const validTypes = ['string', 'number', 'boolean', 'object', 'array', 'datetime', 'any', 'text'];

  for (const node of schema.nodes) {
    for (const output of node.outputs) {
      test(`${node.id}.${output.name} has name`, output.name !== undefined);
      test(`${node.id}.${output.name} has valid type`,
        validTypes.includes(output.type),
        `Type "${output.type}" not in ${validTypes.join(', ')}`);

      // Check name is valid identifier (allowing underscores like _webhookMethod)
      // Also allow dynamic template patterns like {field.name} which are documentation patterns
      const isDynamicPattern = output.name.startsWith('{') && output.name.endsWith('}');
      const isValidIdentifier = isDynamicPattern || /^_?[a-zA-Z][a-zA-Z0-9_]*$/.test(output.name);
      test(`${node.id}.${output.name} is valid identifier or dynamic pattern`, isValidIdentifier,
        `"${output.name}" is not a valid identifier or dynamic pattern`);
    }
  }
});

// ═══════════════════════════════════════════════════════════════════════════
// PREFIX CONVENTION TESTS
// ═══════════════════════════════════════════════════════════════════════════

testGroup('Prefix Conventions', () => {
  const categoryPrefixes = {
    'trigger': 'trigger:',
    'ai': ['agent:', 'guardrail:', 'classify:'],
    'control': ['decision:', 'loop:', 'foreach:', 'merge:'],
    'action': 'mcp:',
    'crud': 'mcp:'
  };

  // Test that trigger nodes exist
  const triggerNodes = schema.nodes.filter(n => n.category === 'trigger');
  test('Has trigger category nodes', triggerNodes.length > 0,
    `Found ${triggerNodes.length} trigger nodes`);

  // Test that AI nodes exist
  const aiNodes = schema.nodes.filter(n => n.category === 'ai');
  test('Has AI category nodes', aiNodes.length > 0,
    `Found ${aiNodes.length} AI nodes`);

  // Test that control flow nodes exist
  const controlNodes = schema.nodes.filter(n => n.category === 'control_flow');
  test('Has control_flow category nodes', controlNodes.length > 0,
    `Found ${controlNodes.length} control_flow nodes`);
});

// ═══════════════════════════════════════════════════════════════════════════
// SPECIFIC NODE CONTRACT TESTS
// ═══════════════════════════════════════════════════════════════════════════

testGroup('Manual Trigger Contract', () => {
  const manual = schema.nodes.find(n => n.id === 'manual-trigger');
  test('Manual trigger exists', manual !== undefined);

  if (manual) {
    const outputNames = manual.outputs.map(o => o.name);
    // snake_case matches ManualTriggerResolver + ManualTriggerNodeSpec + V11 seed
    // node_type_documentation. Interface variable_mapping references like
    // {{trigger:start.output.triggered_by}} depend on this exact naming.
    test('Has triggered_at output', outputNames.includes('triggered_at'));
    test('Has triggered_by output', outputNames.includes('triggered_by'));
    test('Has data output', outputNames.includes('data'));
    test('Has count output', outputNames.includes('count'));
  }
});

testGroup('Webhook Trigger Contract', () => {
  const webhook = schema.nodes.find(n => n.id === 'webhook-trigger');
  test('Webhook trigger exists', webhook !== undefined);

  if (webhook) {
    const outputNames = webhook.outputs.map(o => o.name);
    test('Has triggered_at output', outputNames.includes('triggered_at'));
    test('Has triggered_by output', outputNames.includes('triggered_by'));
    test('Has _webhookMethod output', outputNames.includes('_webhookMethod'),
      `Found outputs: ${outputNames.join(', ')}`);
  }
});

testGroup('Schedule Trigger Contract', () => {
  const schedule = schema.nodes.find(n => n.id === 'schedule-trigger');
  test('Schedule trigger exists', schedule !== undefined);

  if (schedule) {
    const outputNames = schedule.outputs.map(o => o.name);
    test('Has triggered_at output', outputNames.includes('triggered_at'));
    test('Has triggered_by output', outputNames.includes('triggered_by'));
    test('Has execution_count output', outputNames.includes('execution_count'));
    test('Has next_execution output', outputNames.includes('next_execution'));
    test('Has cron output', outputNames.includes('cron'));
    test('Has timezone output', outputNames.includes('timezone'));
    test('Has scheduleId output', outputNames.includes('scheduleId'));
  }
});

testGroup('Chat Trigger Contract', () => {
  const chat = schema.nodes.find(n => n.id === 'chat-trigger');
  test('Chat trigger exists', chat !== undefined);
  if (chat) {
    const outputNames = chat.outputs.map(o => o.name);
    test('Has triggered_at output', outputNames.includes('triggered_at'));
    test('Has triggered_by output', outputNames.includes('triggered_by'));
  }
});

testGroup('Form Trigger Contract', () => {
  const form = schema.nodes.find(n => n.id === 'form-trigger');
  test('Form trigger exists', form !== undefined);
  if (form) {
    const outputNames = form.outputs.map(o => o.name);
    test('Has triggered_at output', outputNames.includes('triggered_at'));
    test('Has triggered_by output', outputNames.includes('triggered_by'));
  }
});

testGroup('Tables Trigger Contract', () => {
  const tbl = schema.nodes.find(n => n.id === 'tables-trigger');
  test('Tables trigger exists', tbl !== undefined);
  if (tbl) {
    const outputNames = tbl.outputs.map(o => o.name);
    test('Has triggered_at output', outputNames.includes('triggered_at'));
    test('Has triggered_by output', outputNames.includes('triggered_by'));
  }
});

testGroup('Workflows Trigger Contract', () => {
  const wf = schema.nodes.find(n => n.id === 'workflows-trigger');
  test('Workflows trigger exists', wf !== undefined);
  if (wf) {
    const outputNames = wf.outputs.map(o => o.name);
    test('Has triggered_at output', outputNames.includes('triggered_at'));
    test('Has triggered_by output', outputNames.includes('triggered_by'));
  }
});

testGroup('Agent Node Contract', () => {
  const agent = schema.nodes.find(n => n.id === 'ai-agent');
  test('AI Agent exists', agent !== undefined);

  if (agent) {
    const outputNames = agent.outputs.map(o => o.name);
    test('Has response output', outputNames.includes('response'));
    test('Has model output', outputNames.includes('model'));
    test('Has provider output', outputNames.includes('provider'));
    test('Has tokens_used output', outputNames.includes('tokens_used'));
    test('Has iterations output', outputNames.includes('iterations'));
    test('Has tool_calls output', outputNames.includes('tool_calls'));

    // Verify tool_calls is number, not array
    const toolCallsOutput = agent.outputs.find(o => o.name === 'tool_calls');
    test('tool_calls is type number (count)',
      toolCallsOutput && toolCallsOutput.type === 'number',
      `Expected number, found ${toolCallsOutput?.type}`);
  }
});

testGroup('Decision Node Contract', () => {
  const decision = schema.nodes.find(n => n.id === 'decision');
  test('Decision node exists', decision !== undefined);

  if (decision) {
    const outputNames = decision.outputs.map(o => o.name);
    test('Has selected_branch output', outputNames.includes('selected_branch'));
    test('Has evaluations output', outputNames.includes('evaluations'));
  }
});

testGroup('Loop Node Contract', () => {
  const loop = schema.nodes.find(n => n.id === 'loop');
  test('Loop node exists', loop !== undefined);

  if (loop) {
    const outputNames = loop.outputs.map(o => o.name);
    test('Has iteration output', outputNames.includes('iteration'));
    test('Has maxIterations output', outputNames.includes('maxIterations'));
    test('Has terminated output', outputNames.includes('terminated'));
    test('Has enter_body output', outputNames.includes('enter_body'));
    test('Has selected_path output', outputNames.includes('selected_path'));

    // Verify loopCondition parameter (not "condition")
    const paramNames = loop.parameters.map(p => p.name);
    test('Has loopCondition parameter', paramNames.includes('loopCondition'),
      `Found params: ${paramNames.join(', ')}`);
  }
});

testGroup('Split Node Contract', () => {
  const split = schema.nodes.find(n => n.id === 'split');
  test('Split node exists', split !== undefined);

  if (split) {
    const outputNames = split.outputs.map(o => o.name);
    test('Has current_item output', outputNames.includes('current_item'));
    test('Has current_index output', outputNames.includes('current_index'));
    test('Has item_count output', outputNames.includes('item_count'));
    test('Has split_id output', outputNames.includes('split_id'));
    test('Has spawn_reason output', outputNames.includes('spawn_reason'));
    test('Has terminated output', outputNames.includes('terminated'));
  }
});

testGroup('Merge Node Contract', () => {
  const merge = schema.nodes.find(n => n.id === 'merge');
  test('Merge node exists', merge !== undefined);

  if (merge) {
    const outputNames = merge.outputs.map(o => o.name);
    test('Has dynamic strategy output', outputNames.includes('{strategy_output}'));
  }
});

// ═══════════════════════════════════════════════════════════════════════════
// SUMMARY
// ═══════════════════════════════════════════════════════════════════════════

console.log('\n' + '═'.repeat(60));
console.log(`${BOLD}CONTRACT TEST RESULTS${RESET}`);
console.log('═'.repeat(60));
console.log(`${GREEN}✓ Passed: ${passed}${RESET}`);
console.log(`${RED}✗ Failed: ${failed}${RESET}`);

if (failures.length > 0) {
  console.log(`\n${RED}${BOLD}Failures:${RESET}`);
  for (const f of failures) {
    console.log(`  - ${f.name}`);
    if (f.message) console.log(`    ${YELLOW}${f.message}${RESET}`);
  }
}

console.log();

// Exit with appropriate code
process.exit(failed > 0 ? 1 : 0);
