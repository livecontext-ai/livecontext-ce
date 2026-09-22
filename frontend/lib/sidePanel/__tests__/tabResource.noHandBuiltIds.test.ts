/**
 * The bug this module exists for came from a tab id built in one place and read
 * in another, each with its own idea of the format. Ten producers built workflow
 * tab ids by hand; one of them decorated with `builder-` and "Go to page" turned
 * that into /app/workflow/builder-<uuid>, a workflow that does not exist.
 *
 * Centralising them is only worth something if the eleventh cannot appear
 * quietly, so this scans the source instead of trusting review. It is the guard
 * an audit of the fix asked for after finding a producer the migration had
 * missed (a second `workflowOpenSubWorkflow` listener), which handed the same
 * sub-workflow two tabs.
 */
import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const ROOT = path.resolve(__dirname, '../../..');
const SCANNED = ['app', 'components', 'contexts', 'hooks', 'lib'];

/** Template literals building a workflow or application tab id inline. */
const HAND_BUILT = /`(workflow|application)-(builder-|run-)?\$\{/;

/**
 * Reading an id back by cutting the prefix off. The reported bug needed BOTH
 * halves: a producer that decorated the id and a reader that assumed it had not
 * been decorated. Guarding only the producers would leave the half that actually
 * built the broken URL free to come back.
 *
 * Scoped to the two kinds this module owns builders for, like HAND_BUILT. The
 * `interface-` and `agent-` spellings are also ReactFlow node ids on the builder
 * and fleet canvases (`interface-<id>--2`, `agent-<id>`), a namespace the side
 * panel never sees; matching those would drown the signal in unrelated code.
 */
const HAND_READ = /[.](replace|slice)\(\s*['`](workflow|application)-/;

/**
 * Same-shaped strings that are NOT side-panel tab ids, so the panel never parses
 * them. Listed one by one, with the reason: an allow-list is a decision, and the
 * point of this test is that such a decision cannot be made silently.
 */
const NOT_TAB_IDS: Record<string, string> = {
  // Cache key for the workflow loader, keyed by run or by workflow.
  'app/workflows/builder/hooks/useWorkflowLoader.ts:230': 'loader cache key',
  // "Analyze" badge in the chat composer, keyed by resource and position.
  'hooks/useChatPageStateV3.ts:259': 'chat analyze badge',
};

/** The module that owns the format, and tests that deliberately spell ids out. */
function relative(file: string): string {
  return path.relative(ROOT, file).split(path.sep).join('/');
}

function isAllowedFile(file: string): boolean {
  const rel = relative(file);
  return rel === 'lib/sidePanel/tabResource.ts'
    || rel.includes('__tests__/')
    || rel.endsWith('.test.ts')
    || rel.endsWith('.test.tsx');
}

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === '.next') continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (/[.]tsx?$/.test(entry.name)) out.push(full);
  }
  return out;
}

/** Every scanned line, as "<rel>:<lineNo>" plus its text. */
function scan(match: RegExp): string[] {
  const offenders: string[] = [];
  for (const dir of SCANNED) {
    for (const file of walk(path.join(ROOT, dir))) {
      if (isAllowedFile(file)) continue;
      const rel = relative(file);
      fs.readFileSync(file, 'utf8').split(/\r?\n/).forEach((line, i) => {
        const at = `${rel}:${i + 1}`;
        if (match.test(line) && !(at in NOT_TAB_IDS)) offenders.push(`${at}: ${line.trim()}`);
      });
    }
  }
  return offenders;
}

describe('side-panel tab ids are built and read in one place', () => {
  it('has no workflow or application tab id built inline outside tabResource', () => {
    // Use workflowPanelTabId() / applicationPanelTabId() from lib/sidePanel/tabResource
    // instead, so the id stays readable by parseTabResource and getTabResourceUrl.
    expect(scan(HAND_BUILT)).toEqual([]);
  });

  it('has no tab id read back by cutting its prefix off', () => {
    // Use parseTabResource(id)?.id: a prefix cut cannot see decoration, which is
    // how 'workflow-builder-<uuid>' became the workflow id 'builder-<uuid>'.
    expect(scan(HAND_READ)).toEqual([]);
  });

  it('names every exemption by line, so one cannot cover a whole file', () => {
    for (const at of Object.keys(NOT_TAB_IDS)) {
      const [rel, lineNo] = at.split(':');
      const line = fs.readFileSync(path.join(ROOT, rel), 'utf8').split(/\r?\n/)[Number(lineNo) - 1];
      // A moved or deleted exemption must be re-justified, not silently kept.
      expect(line, `stale exemption ${at} (${NOT_TAB_IDS[at]})`).toBeDefined();
      expect(HAND_BUILT.test(line) || HAND_READ.test(line), `exemption ${at} no longer matches`).toBe(true);
    }
  });
});
