/**
 * Which agent tab is pinned, and which must not be.
 *
 * `pinned` means "the user may not close this": no X, no 3-dot menu, no Close and
 * no Delete entry, and the tab survives navigation. That is right for exactly one
 * agent tab - the one on the agent's OWN conversation, where the panel is part of
 * the page rather than something the user opened.
 *
 * On the agents page it was wrong twice over. Opening an agent from the list is a
 * look, so the tab has to be closable; and because it was pinned, browsing the
 * list silently accumulated tabs that could not be dismissed. Deleting the agent
 * then left a pinned tab for an agent that no longer existed (the deletion
 * broadcast closes it now, but the tab should never have been unclosable).
 *
 * This is a claim about the CALL SITES, which no component test holds: each of
 * these files renders a different page, and what matters is the flag each passes.
 */
import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = process.cwd();
const read = (rel: string) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

/** The `openTab({...})` literal that carries `id: \`agent-${...}\`` in a file. */
function agentTabLiteral(source: string): string {
  const start = source.indexOf('openTab({');
  expect(start, 'expected an openTab({ ... }) call').toBeGreaterThan(-1);
  const end = source.indexOf('});', start);
  expect(end, 'expected the openTab call to be closed').toBeGreaterThan(start);
  const literal = source.slice(start, end);
  expect(literal).toMatch(/id: `agent-\$\{/);
  return literal;
}

describe('agent side-panel tabs: pinned only where the page owns the panel', () => {
  it('the agents list opens a CLOSABLE tab', () => {
    const literal = agentTabLiteral(read('components/AgentTable.tsx'));
    expect(literal).not.toMatch(/pinned:\s*true/);
  });

  it('the agents metrics tab opens a CLOSABLE tab - same page, same rule', () => {
    const literal = agentTabLiteral(read('components/agent-fleet/AgentMetricsDashboard.tsx'));
    expect(literal).not.toMatch(/pinned:\s*true/);
  });

  it('the agent conversation KEEPS its pin - that panel belongs to the page', () => {
    // The one place a pin is the right answer, and the reason the two above are
    // not a blanket "never pin an agent tab".
    const source = read('lib/sidePanel/agentConfigPanelTab.tsx');
    expect(source).toMatch(/pinned:\s*true/);
    expect(source).toMatch(/scope: \['\/app\/c\/\*'\]/);
  });

  it('both agents-page tabs stay scoped to /app/agent, so they still drop on leaving it', () => {
    // Unpinning changes who may close the tab, not where it lives: without the
    // scope an unpinned tab would follow the user onto unrelated sections.
    expect(agentTabLiteral(read('components/AgentTable.tsx'))).toContain("scope: ['/app/agent']");
    expect(agentTabLiteral(read('components/agent-fleet/AgentMetricsDashboard.tsx'))).toContain("scope: ['/app/agent']");
  });
});
