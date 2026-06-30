/**
 * @vitest-environment node
 *
 * Frontend half of the FE↔BE parity guard for the trigger-kind ↔ NodeIcon
 * mapping.
 *
 * The Java side has `WorkflowIconExtractorParityTest` (orchestrator-service +
 * publication-service) pinning the SAME 8-entry canonical map. If you change
 * one side, all three must move together. This test catches the TS half.
 */
import { describe, it, expect } from 'vitest';
import { KIND_TO_NODE_ICON_KEY, TRIGGER_KIND_ORDER } from '../dashboard.service';

describe('KIND_TO_NODE_ICON_KEY - FE↔BE parity', () => {
  it('has exactly 8 entries (one per backend TriggerType enum value)', () => {
    expect(Object.keys(KIND_TO_NODE_ICON_KEY)).toHaveLength(8);
  });

  it('covers the 8 canonical TriggerType keys', () => {
    expect(Object.keys(KIND_TO_NODE_ICON_KEY).sort()).toEqual(
      ['CHAT', 'DATASOURCE', 'ERROR', 'FORM', 'MANUAL', 'SCHEDULE', 'WEBHOOK', 'WORKFLOW']
    );
  });

  it('values match the 8 canonical nodeIds, including the documented quirks', () => {
    // The two naming quirks (datasource→tables, workflow→workflows) come from
    // the legacy node-registry naming and MUST stay stable - the workflow
    // builder palette is keyed on these strings.
    expect(KIND_TO_NODE_ICON_KEY).toEqual({
      SCHEDULE: 'schedule-trigger',
      WEBHOOK: 'webhook-trigger',
      MANUAL: 'manual-trigger',
      CHAT: 'chat-trigger',
      FORM: 'form-trigger',
      DATASOURCE: 'tables-trigger',
      WORKFLOW: 'workflows-trigger',
      ERROR: 'error-trigger',
    });
  });

  it('TRIGGER_KIND_ORDER contains each TriggerType exactly once', () => {
    expect(TRIGGER_KIND_ORDER).toHaveLength(8);
    const unique = new Set(TRIGGER_KIND_ORDER);
    expect(unique.size).toBe(8);
    // And every key in the map is reachable via the order list.
    for (const key of Object.keys(KIND_TO_NODE_ICON_KEY)) {
      expect(TRIGGER_KIND_ORDER).toContain(key as keyof typeof KIND_TO_NODE_ICON_KEY);
    }
  });
});
