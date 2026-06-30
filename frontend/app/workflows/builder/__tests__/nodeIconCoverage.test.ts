/**
 * Node icon coverage - verifies every BuilderNodeClass has a resolvable icon
 * via resolveNodeIcon (canvas / left palette path) and that the chat
 * activity-feed action mapping renders an icon for every backend add_* action.
 *
 * If this test fails after adding a new node:
 *   1. Add an entry to NODE_ICON_REGISTRY in nodeVisuals.ts (keyed by node id)
 *      OR set kind to one already in NODE_ICON_REGISTRY.
 *   2. Add an `add_<type>` mapping in ACTION_TO_REGISTRY_KEY in shared.tsx.
 */

import { describe, it, expect } from 'vitest';
import { NODE_CLASSES } from '../nodes/nodeClasses';
import { resolveNodeIcon, NODE_ICON_REGISTRY } from '../data/nodeVisuals';
import { WORKFLOW_ACTION_ICONS } from '../components/nodes/shared';

// Signature of DEFAULT_ICON_ENTRY in nodeVisuals.ts (gray Cpu fallback).
const DEFAULT_ICON_BG = 'bg-gray-100 dark:bg-gray-800 text-gray-500';

describe('Node icon coverage - every BuilderNodeClass resolves to a real icon', () => {
  it(`all ${NODE_CLASSES.length} node classes resolve to a non-default icon`, () => {
    const offenders: string[] = [];

    for (const klass of NODE_CLASSES) {
      const entry = resolveNodeIcon(klass.id, klass.kind, klass.family);
      if (entry.iconBg === DEFAULT_ICON_BG) {
        offenders.push(`${klass.id} (kind=${klass.kind}, family=${klass.family})`);
      }
    }

    expect(
      offenders,
      `These node classes fall back to the default Cpu icon - add a NODE_ICON_REGISTRY entry or fix kind:\n  ${offenders.join('\n  ')}`,
    ).toEqual([]);
  });

  it('every node class id (or its kind) is reachable in NODE_ICON_REGISTRY', () => {
    const missing: string[] = [];
    for (const klass of NODE_CLASSES) {
      const reachable =
        NODE_ICON_REGISTRY[klass.id] !== undefined ||
        NODE_ICON_REGISTRY[klass.kind] !== undefined ||
        klass.kind === 'loop' ||
        klass.kind === 'mcp' ||
        klass.kind === 'tool';
      if (!reachable) missing.push(`${klass.id} (kind=${klass.kind})`);
    }
    expect(missing, `No registry hit for: ${missing.join(', ')}`).toEqual([]);
  });
});

describe('Backend add_<type> actions used by the agent → activity feed icons', () => {
  // EXHAUSTIVE list of `type` values the backend accepts in workflow(action='add_node', type=...).
  // Sourced from WorkflowBuilderProvider.executeAddNode switch + creators in tools/workflow/builder/creators/.
  // Update this list when adding a new node type to the backend dispatcher.
  const BACKEND_ADD_TYPES = [
    // Triggers
    'manual', 'webhook', 'schedule', 'chat', 'form', 'table', 'workflow', 'error',
    // Agents
    'agent', 'guardrail', 'classify',
    // Control flow / branching
    'decision', 'switch', 'split', 'fork', 'merge', 'loop', 'exit', 'option',
    // Core utility
    'transform', 'wait', 'download_file', 'http_request', 'response',
    'aggregate', 'approval', 'data_input', 'set', 'html_extract',
    'code', 'sub_workflow', 'respond_to_webhook', 'send_email',
    // Data processing
    'filter', 'sort', 'limit', 'remove_duplicates', 'summarize',
    'date_time', 'crypto_jwt', 'xml', 'compression', 'rss',
    'convert_to_file', 'extract_from_file', 'compare_datasets',
    // Interface
    'interface',
    // Table CRUD (canonical forms - aliases like create_row also accepted by backend)
    'insert_row', 'update_row', 'read_rows', 'delete_row', 'find_rows', 'create_column',
  ];

  for (const type of BACKEND_ADD_TYPES) {
    it(`add_${type} renders an icon in the activity feed`, () => {
      const entry = WORKFLOW_ACTION_ICONS[`add_${type}`];
      expect(
        entry,
        `Missing WORKFLOW_ACTION_ICONS["add_${type}"]. Add it to ACTION_TO_REGISTRY_KEY in shared.tsx.`,
      ).toBeDefined();
    });
  }
});
