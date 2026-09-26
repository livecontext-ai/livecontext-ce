/**
 * Wiring guard: every workflow canvas owns its reading direction.
 *
 * The canvas direction used to be ONE app-wide value. Opening a sub-workflow in the side
 * panel (its node's bottom button) seeded that value from the CHILD's plan, which
 * re-oriented the parent canvas behind it; the parent's next Save or Run then stamped the
 * wrong direction into its plan for good. The fix is structural - `WorkflowBuilder` mounts a
 * `WorkflowCanvasDirectionScope` around itself - and nothing behavioural fails if a later
 * refactor drops it (a single canvas still renders fine), so the source is asserted, the way
 * `MeasuredLayoutSync.wiring` asserts its own. The scope's behaviour is covered by
 * WorkflowLayoutDirectionContext.test.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

// Line endings normalised: the checkout may be CRLF, and the body is cut at a closing brace
// alone on its line.
const source = readFileSync(
  join(process.cwd(), 'app/workflows/builder/components/WorkflowBuilder.tsx'),
  'utf8',
).replace(/\r\n/g, '\n');

/** The exported component alone, up to its closing brace. */
function exportedBody(): string {
  const exported = source.slice(source.indexOf('export function WorkflowBuilder('));
  const end = exported.indexOf('\n}\n');
  expect(end, 'could not find the end of WorkflowBuilder').toBeGreaterThan(0);
  return exported.slice(0, end);
}

describe('WorkflowBuilder direction scope', () => {
  it('wraps the whole builder in its own canvas direction scope', () => {
    const body = exportedBody();

    expect(body).toContain('<WorkflowCanvasDirectionScope>');
    expect(body).toContain('<WorkflowBuilderCanvas {...props} />');
  });

  it('keeps every direction read inside the scope: the exported component reads none itself', () => {
    expect(exportedBody()).not.toContain('useWorkflowLayoutDirection');
  });
});
