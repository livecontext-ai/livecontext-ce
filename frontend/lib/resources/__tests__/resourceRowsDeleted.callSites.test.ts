/**
 * Each list subscribes with ITS OWN kind and ITS OWN rows.
 *
 * `useResourceRowsDeleted(kind, rows, setRows, refetch)` takes four arguments that
 * all have to agree, and nothing makes them. Pass the interfaces array under the
 * kind 'workflow' and it type-checks, lints clean, renders fine, and produces the
 * exact failure this whole feature exists to abolish: a row that never goes away,
 * with nothing red anywhere and no error to search for.
 *
 * That is a claim about the CALL SITES, which the hook's own suite cannot hold:
 * it proves the mechanism against a synthetic list, and would keep passing while
 * all four tables were wired to the wrong kind. Each table is also far too
 * entangled to mount for the sake of four arguments.
 */
import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = process.cwd();

/** Every list that reacts to deletions, with the shape its call must have. */
const LISTS = [
  { file: 'components/AgentTable.tsx', kind: 'agent', rows: 'agents', setRows: 'setAgents', refetch: 'fetchAgents' },
  { file: 'components/WorkflowTable.tsx', kind: 'workflow', rows: 'workflows', setRows: 'setWorkflows', refetch: 'fetchWorkflows' },
  { file: 'components/InterfaceTable.tsx', kind: 'interface', rows: 'interfaces', setRows: 'setInterfaces', refetch: 'fetchInterfaces' },
  { file: 'components/DataSourceTable.tsx', kind: 'datasource', rows: 'dataSources', setRows: 'setDataSources', refetch: 'fetchDataSources' },
] as const;

const read = (rel: string) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

/** The arguments of the file's `useResourceRowsDeleted(...)` call, in order. */
function callArgs(source: string): string[] {
  const open = source.indexOf('useResourceRowsDeleted(');
  expect(open, 'expected a useResourceRowsDeleted call').toBeGreaterThan(-1);
  const close = source.indexOf(')', open);
  expect(close, 'expected the call to be closed').toBeGreaterThan(open);
  return source
    .slice(open + 'useResourceRowsDeleted('.length, close)
    .split(',')
    .map(a => a.trim().replace(/^'|'$/g, ''));
}

describe('every resource list subscribes with its own kind and its own rows', () => {
  it.each(LISTS)('$file', ({ file, kind, rows, setRows, refetch }) => {
    expect(callArgs(read(file))).toEqual([kind, rows, setRows, refetch]);
  });

  it('covers every list that has one, so a new table cannot be added silently', () => {
    // A resource list that forgets to subscribe is the original bug. Finding one
    // that this file does not name means either wiring it up or, if it genuinely
    // has no rows to drop, saying so here.
    const wired = LISTS.map(l => l.file).sort();
    const found: string[] = [];
    for (const entry of fs.readdirSync(path.join(ROOT, 'components'), { withFileTypes: true })) {
      if (!entry.isFile() || !/Table\.tsx$/.test(entry.name)) continue;
      const rel = `components/${entry.name}`;
      if (read(rel).includes('useResourceRowsDeleted(')) found.push(rel);
    }
    expect(found.sort()).toEqual(wired);
  });

  it('names a kind the broadcast can actually carry', () => {
    // A typo'd kind matches no event and the row never goes; the union would not
    // catch it, because the argument is compared against a string literal.
    const declared = read('lib/resources/resourceDeleted.ts');
    for (const { kind } of LISTS) {
      expect(declared).toContain(`| '${kind}'`);
    }
  });
});
