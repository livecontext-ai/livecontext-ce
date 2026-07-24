/**
 * Consistency test: ensures WORKFLOW_ACTION_ICONS (activity feed)
 * derives its icons from NODE_ICON_REGISTRY (canvas source of truth).
 *
 * If this test fails, update ACTION_TO_REGISTRY_KEY in shared.tsx.
 */

import { describe, it, expect } from 'vitest';
import { WORKFLOW_ACTION_ICONS } from '../components/nodes/shared';
import { NODE_ICON_REGISTRY } from '../data/nodeVisuals';

// Actions that are manually defined (graph/lifecycle operations),
// not derived from NODE_ICON_REGISTRY.
const MANUAL_ACTIONS = new Set([
  'connect', 'add_edge', 'disconnect', 'add_mcp',
  'init', 'finish', 'validate', 'execute', 'modify', 'remove', 'help',
]);

// The known set of backend add_* actions that create nodes.
// Update this list when new node types are added to the backend.
const EXPECTED_NODE_ACTIONS = [
  'add_trigger',
  'add_agent',
  'add_guardrail',
  'add_classify',
  'add_decision',
  'add_switch',
  'add_loop',
  'add_split',
  'add_fork',
  'add_merge',
  'merge',
  'add_option',
  'add_aggregate',
  'add_transform',
  'add_mcp',
  'add_wait',
  'add_exit',
  'add_response',
  'add_http_request',
  'add_download_file',
  'add_public_link',
  'add_media',
  'add_data_input',
  'add_approval',
  'add_interface',
  'add_code',
  'add_date_time',
  'add_xml',
  'add_compression',
  'add_rss',
  'add_convert_to_file',
  'add_extract_from_file',
  'add_compare_datasets',
  'add_sub_workflow',
  'add_respond_to_webhook',
  'add_send_email',
];

describe('WORKFLOW_ACTION_ICONS consistency with NODE_ICON_REGISTRY', () => {
  it('every non-graph action has a matching entry in WORKFLOW_ACTION_ICONS', () => {
    for (const action of EXPECTED_NODE_ACTIONS) {
      expect(
        WORKFLOW_ACTION_ICONS[action],
        `Missing WORKFLOW_ACTION_ICONS entry for "${action}". Add a mapping in ACTION_TO_REGISTRY_KEY.`
      ).toBeDefined();
    }
  });

  it('every non-graph action uses the same icon component as NODE_ICON_REGISTRY', () => {
    // Map from action back to registry key for verification
    const ACTION_TO_KEY: Record<string, string> = {
      add_trigger: 'triggers',
      add_agent: 'ai-agent',
      add_guardrail: 'guardrail',
      add_classify: 'classify',
      add_decision: 'decision',
      add_switch: 'switch',
      add_loop: 'loop',
      add_split: 'split',
      add_fork: 'fork',
      add_merge: 'merge',
      merge: 'merge',
      add_option: 'option',
      add_aggregate: 'aggregate',
      add_transform: 'transform',
      add_wait: 'wait',
      add_exit: 'exit',
      add_response: 'response',
      add_http_request: 'http_request',
      add_download_file: 'download_file',
      add_public_link: 'public_link',
      add_media: 'media',
      add_data_input: 'data_input',
      add_approval: 'user-approval',
      add_interface: 'interface',
      add_code: 'code',
      add_date_time: 'date_time',
      add_xml: 'xml',
      add_compression: 'compression',
      add_rss: 'rss',
      add_convert_to_file: 'convert_to_file',
      add_extract_from_file: 'extract_from_file',
      add_compare_datasets: 'compare_datasets',
      add_sub_workflow: 'sub_workflow',
      add_respond_to_webhook: 'respond_to_webhook',
      add_send_email: 'send_email',
    };

    for (const [action, registryKey] of Object.entries(ACTION_TO_KEY)) {
      const actionEntry = WORKFLOW_ACTION_ICONS[action];
      const registryEntry = NODE_ICON_REGISTRY[registryKey];

      expect(registryEntry, `NODE_ICON_REGISTRY missing key "${registryKey}" for action "${action}"`).toBeDefined();
      if (actionEntry && registryEntry) {
        expect(
          actionEntry.icon,
          `Icon mismatch for "${action}": WORKFLOW_ACTION_ICONS uses ${actionEntry.icon.displayName || actionEntry.icon.name} but NODE_ICON_REGISTRY["${registryKey}"] uses ${registryEntry.icon.displayName || registryEntry.icon.name}`
        ).toBe(registryEntry.icon);
      }
    }
  });

  it('manual actions are present', () => {
    for (const action of MANUAL_ACTIONS) {
      expect(WORKFLOW_ACTION_ICONS[action], `Missing manual action "${action}"`).toBeDefined();
    }
  });

  it('no WORKFLOW_ACTION_ICONS entries reference stale icons', () => {
    // Every non-graph entry should have an icon that exists in NODE_ICON_REGISTRY somewhere
    const allRegistryIcons = new Set(Object.values(NODE_ICON_REGISTRY).map(e => e.icon));

    for (const [action, config] of Object.entries(WORKFLOW_ACTION_ICONS)) {
      if (MANUAL_ACTIONS.has(action)) continue;
      expect(
        allRegistryIcons.has(config.icon as any),
        `WORKFLOW_ACTION_ICONS["${action}"] uses icon ${config.icon.displayName || config.icon.name} which is not in NODE_ICON_REGISTRY`
      ).toBe(true);
    }
  });
});
