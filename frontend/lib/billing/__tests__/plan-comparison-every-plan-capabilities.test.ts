import { describe, expect, it } from 'vitest';
import { PLAN_FEATURE_KEYS, CAPABILITY_KEYS } from '../pricing-constants';
import { buildPlanComparison, COMPARISON_PLAN_IDS } from '../plan-comparison';
import { planFeatureLabels } from '../planFeatureLabels';
import en from '@/messages/en.json';

/**
 * Capabilities shipped on EVERY plan that the pricing grid did not mention: two-factor
 * authentication (cloud), chat channels for agents and approvals, alerts in the app and in a
 * connected chat, the built-in MCP server, and the agent toolkit (memory, per-agent credit caps,
 * approvals, sub-workflows, agenda, studio). None of them is plan-gated in code, so the grid must
 * list them on Free too, or a visitor reads them as paid extras.
 */
const EVERY_PLAN = ['agentToolkit', 'chatChannels', 'channelAlerts', 'mcpServer', 'twoFactor'];

const EXPECTED_SECTION: Record<string, string> = {
  agentToolkit: 'building',
  mcpServer: 'building',
  chatChannels: 'collaboration',
  channelAlerts: 'usage',
  twoFactor: 'security',
};

describe('capabilities included on every plan are priced as such', () => {
  it.each(EVERY_PLAN)('%s is listed on all five plans', (key) => {
    for (const plan of COMPARISON_PLAN_IDS) {
      expect(PLAN_FEATURE_KEYS[plan], `${plan} must list ${key}`).toContain(key);
    }
  });

  it.each(EVERY_PLAN)('%s is a capability key, so the tier-coherence test covers it', (key) => {
    expect(CAPABILITY_KEYS).toContain(key);
  });

  it.each(EVERY_PLAN)('%s is filed in its own section, not appended to the last one', (key) => {
    const section = buildPlanComparison().find((s) => s.rows.some((row) => row.id === key));
    expect(section?.id).toBe(EXPECTED_SECTION[key]);
  });

  it.each(EVERY_PLAN)('%s carries an explanation on the plan card', (key) => {
    const features = (en as any).pricing.planCards.features;
    const tCards = (k: string) => {
      const value = k.split('.').reduce((o: any, part) => o?.[part], (en as any).pricing.planCards);
      return typeof value === 'string' ? value : k;
    };
    const line = planFeatureLabels('free', {
      tCards,
      tPricing: (k: string) => k,
      credits: '5,000',
      creditFacts: {},
    }).find((l) => l.startsWith(features[key]));
    expect(line, `the Free card has no ${key} line`).toBeDefined();
    expect(line).toBe(`${features[key]}||${features[`${key}Tooltip`]}`);
  });
});
