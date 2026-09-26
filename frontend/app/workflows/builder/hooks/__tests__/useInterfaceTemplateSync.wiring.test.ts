/**
 * Both canvas node kinds that render an interface keep a copy of its page. The logic
 * that keeps that copy current used to be duplicated in each, and both copies filled it
 * only when empty; it now lives in one hook. This pins that both use it, and that neither
 * lets it write on a run canvas (a run shows the page the run froze).
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const read = (p: string) => readFileSync(join(process.cwd(), 'app/workflows/builder', p), 'utf8');

describe('useInterfaceTemplateSync wiring', () => {
  it.each([
    ['components/nodes/InterfacePreviewNode.tsx', /enabled:\s*!isRunMode,/],
    ['components/nodes/FlowNode.tsx', /enabled:\s*isInterfaceNode && !isRunMode,/],
  ])('%s syncs through the shared hook, never on a run canvas', (file, gate) => {
    const source = read(file);
    expect(source).toContain('useInterfaceTemplateSync({');
    expect(source).toMatch(gate);
    // The old per-component copy, which only ever filled an EMPTY template.
    expect(source).not.toContain('loadedTemplateForRef');
  });
});
