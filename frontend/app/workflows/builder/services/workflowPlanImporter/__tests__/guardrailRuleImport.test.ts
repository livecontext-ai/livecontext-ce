import { describe, it, expect } from 'vitest';

import { createAgentNodes } from '../AgentNodeCreator';

// The backend now checks keyword and competitor rules EXACTLY against their config. A rule the
// builder tool wrote as {ruleId: description} used to be imported with its description copied
// into keywordsExpression, which would have turned "Block spam messages" into a literal keyword.
describe('guardrail rule import', () => {
  it('keeps a {ruleId: description} rule as a description-only rule, judged by the model', () => {
    const { nodes } = createAgentNodes([
      {
        label: 'Screen',
        type: 'guardrail',
        rules: { spam: 'Block spam messages', competitor: 'Do not mention rivals' },
      } as any,
    ], 0, 0, 0);

    const rules = (nodes[0].data as any).guardrailRules;
    expect(rules).toHaveLength(2);
    expect(rules[0].type).toBe('keyword_filter');
    expect(rules[0].config).toEqual({ description: 'Block spam messages' });
    expect(rules[1].type).toBe('competitor_mention');
    expect(rules[1].config).toEqual({ description: 'Do not mention rivals' });
  });

  it('fills the type defaults for a typed rule that has a real config', () => {
    const { nodes } = createAgentNodes([
      {
        label: 'Screen',
        type: 'guardrail',
        guardrailRules: [{ id: 'r1', type: 'regex_pattern', action: 'block', config: { pattern: '^ok$' } }],
      } as any,
    ], 0, 0, 0);

    const rule = (nodes[0].data as any).guardrailRules[0];
    expect(rule.config).toEqual({ pattern: '^ok$', mode: 'require' });
  });
});
