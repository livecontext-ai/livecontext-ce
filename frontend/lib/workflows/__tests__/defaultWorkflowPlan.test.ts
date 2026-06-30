import { describe, expect, it } from 'vitest';
import { createEmptyWorkflowPlan } from '../defaultWorkflowPlan';

describe('createEmptyWorkflowPlan', () => {
  it('creates an empty workflow plan - no trigger imposed, the builder empty-canvas UI proposes them', () => {
    expect(createEmptyWorkflowPlan({
      id: 'workflow-1',
      name: 'New workflow',
      description: 'Ready for builder',
    })).toEqual({
      id: 'workflow-1',
      name: 'New workflow',
      description: 'Ready for builder',
      triggers: [],
      mcps: [],
      edges: [],
    });
  });

  it('regression: uses the "mcps" steps key so the builder loader accepts the plan (a "steps" key fails isValidPlan and skips the import)', () => {
    const plan = createEmptyWorkflowPlan({ id: 'workflow-1', name: 'New workflow' });

    // Mirrors useWorkflowLoader's isValidPlan check - the exact gate that
    // silently skipped the import when the plan carried "steps" instead.
    const isValidPlan = Array.isArray(plan.triggers)
      && Array.isArray((plan as Record<string, unknown>).mcps)
      && Array.isArray(plan.edges);

    expect(isValidPlan).toBe(true);
    expect('steps' in plan).toBe(false);
  });
});
