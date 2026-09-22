// @vitest-environment jsdom
/**
 * The info control's WIRING on each card surface: is it handed the fields it needs?
 *
 * <p>Asserted over the SOURCE, the way `WorkflowTable.metaRow` is and for the same reason:
 * rendering either list means standing up folders, favorites, pagination, publication status
 * and four API clients to make a claim about one button. What matters here is not how the row
 * looks - it is that the control is wired to the fields it needs, cannot squeeze the row, and
 * cannot take the click that opens the card.
 */
import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

function read(file: string): string {
  return fs.readFileSync(path.join(process.cwd(), file), 'utf8');
}

/** The `<ResourceInfoPopover …/>` element in a file, as written. */
function popoverElement(source: string, file: string): string {
  const start = source.indexOf('<ResourceInfoPopover');
  expect(start, `${file} no longer renders the info control`).toBeGreaterThan(-1);
  const end = source.indexOf('/>', start);
  expect(end, `${file}'s info control is not self-closing`).toBeGreaterThan(start);
  return source.slice(start, end + 2);
}

const HOSTS = [
  // The workflow card is here first because it was the one surface with no wiring guard, while
  // being the only one that also passes an editor loader: deleting both props left every test
  // in the change green, and only a live stack would have noticed.
  { name: 'workflow card', file: 'components/WorkflowTable.tsx', owner: 'w.tenantId', editors: true },
  { name: 'agent card', file: 'components/AgentTable.tsx', owner: 'agent.tenantId', editors: false },
  { name: 'interface card', file: 'components/InterfaceTable.tsx', owner: 'intf.tenantId', editors: false },
];

describe.each(HOSTS)('$name', ({ file, owner, editors }) => {
  const source = read(file);
  const element = popoverElement(source, file);

  it('attributes the resource to its owner, not to nobody', () => {
    // Without an owner id the popover falls back to an anonymous creation date - a card that
    // renders "Created 3 days ago" where a person should be, with nothing failing anywhere.
    expect(element).toContain(`ownerId={${owner}}`);
  });

  it('carries both timestamps', () => {
    expect(element).toContain('createdAt=');
    expect(element).toContain('updatedAt=');
  });

  it('refuses to shrink, so a 28px control cannot lose its square on a narrow card', () => {
    expect(element).toMatch(/className="[^"]*shrink-0/);
  });

  it(editors ? 'offers the edit history it keeps' : 'offers no editors list, having no edit history', () => {
    // A workflow records who saved each plan version; a table, an agent and a page record
    // nothing of the sort, and a heading over an empty list there would say nobody has ever
    // edited them - a claim none of them holds the answer to.
    if (editors) {
      expect(element).toContain('loadEditors');
    } else {
      expect(element).not.toContain('loadEditors');
    }
  });
});

describe('every card host', () => {
  it('lets the popover stop the click itself rather than each card guarding it', () => {
    // The control stops propagation internally (pinned in ResourceInfoPopover.test.tsx), which
    // is what keeps a click on it from opening the card behind it. This asserts no host has
    // gone and wrapped it in its own handler, which would be a second, divergent answer.
    for (const { file } of HOSTS) {
      const element = popoverElement(read(file), file);
      expect(element, `${file} wraps the control in its own click handling`).not.toContain('onClick=');
    }
  });
});
