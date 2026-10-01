import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync } from 'fs';
import { join, relative, sep } from 'path';
import {
  alwaysRun, decide, fullRunArgs, graphHoles, isTestFile, parseNameStatus, parseShard, requireUsers, shardOf, tooLarge,
} from '../scripts/ci-vitest-select.mjs';
import { readSources } from '../scripts/ci-vitest.mjs';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'fs';
import { tmpdir } from 'os';

/**
 * The pull-request selection of the frontend vitest files (scripts/ci-vitest-select.mjs). A wrong
 * "related" lets a regression reach main unseen, a wrong "full" only costs time, so every rule that
 * turns a change into a full run is pinned here, with the realistic paths that trigger it.
 */

const FRONTEND_ROOT = join(__dirname, '..');
const m = (...paths: string[]) => paths.map((path) => ({ status: 'M', path }));

describe('decide: what a pull request runs', () => {
  it('a component change runs the tests related to it', () => {
    expect(decide(m('frontend/components/chat/NotificationBell.tsx')))
      .toEqual({ mode: 'related', related: ['components/chat/NotificationBell.tsx'] });
  });

  it('a changed test file is handed over too, so it runs itself', () => {
    const d = decide(m('frontend/components/chat/__tests__/NotificationBell.test.tsx'));
    expect(d).toEqual({ mode: 'related', related: ['components/chat/__tests__/NotificationBell.test.tsx'] });
  });

  it('a file outside frontend/ is handed over relative to frontend/ (a test may import it)', () => {
    expect(decide(m('shared/contracts/node-contracts.schema.json')))
      .toEqual({ mode: 'related', related: ['../shared/contracts/node-contracts.schema.json'] });
  });

  it.each([
    ['frontend/vitest.config.ts', 'vitest configuration'],
    ['frontend/vitest.setup.ts', 'vitest configuration'],
    ['frontend/vitest.stubs/server-only.ts', 'vitest stub'],
    ['frontend/package.json', 'dependencies'],
    ['frontend/package-lock.json', 'dependencies'],
    ['frontend/tsconfig.json', 'TypeScript'],
    ['frontend/next.config.mjs', 'build configuration'],
    ['frontend/messages/fr.json', 'locale file'],
    ['frontend/scripts/ci-vitest.mjs', 'selection itself'],
    ['frontend/scripts/ci-vitest-select.mjs', 'selection itself'],
    ['frontend/lib/__mocks__/apiClient.ts', 'shared mock'],
    ['frontend/lib/test-utils.tsx', 'shared test helper'],
    ['frontend/components/testUtils/render.tsx', 'shared test helper'],
    ['frontend/__tests__/fixtures/workflow.ts', 'shared test file that is not a test'],
    ['frontend/components/chat/__tests__/helpers.ts', 'shared test file that is not a test'],
    ['.github/workflows/ci.yml', 'CI workflows'],
    ['scripts/ci/backend_shards.py', 'CI scripts'],
    ['deploy/scripts/ci-select-jobs.sh', 'job selector'],
    ['package.json', 'root build file'],
  ])('%s runs the whole suite (%s)', (path, why) => {
    const d = decide([...m('frontend/components/chat/NotificationBell.tsx'), ...m(path)]);
    expect(d.mode).toBe('full');
    expect(d.reason).toContain(why);
    expect(d.reason).toContain(path);
  });

  it('a deleted source file runs the whole suite: the graph cannot find the tests still importing it', () => {
    const d = decide([{ status: 'D', path: 'frontend/lib/utils/old.ts' }]);
    expect(d.mode).toBe('full');
    expect(d.reason).toContain('deleted');
  });

  it('a deleted file outside frontend/ runs the whole suite too (a test may import it by a relative path)', () => {
    expect(decide([{ status: 'D', path: 'shared/contracts/gone.json' }]).mode).toBe('full');
  });

  it('a deleted backend file says nothing about the frontend suite', () => {
    expect(decide([{ status: 'D', path: 'backend/gateway/src/main/java/Old.java' }])).toEqual({ mode: 'related', related: [] });
  });

  it('a type change counts as a deletion', () => {
    expect(decide([{ status: 'T', path: 'frontend/lib/x.ts' }]).mode).toBe('full');
  });

  it('a deleted test is simply gone, nothing to run for it', () => {
    expect(decide([{ status: 'D', path: 'frontend/lib/__tests__/old.test.ts' }])).toEqual({ mode: 'related', related: [] });
  });

  it('an empty or missing diff runs the whole suite', () => {
    expect(decide([]).mode).toBe('full');
    expect(decide(null).mode).toBe('full');
  });
});

describe('alwaysRun: the files the import graph cannot see', () => {
  it('flags a test that reads files, lists a directory, shells out or reads raw text', () => {
    const tests = {
      'a/fs.test.ts': "import { readFileSync } from 'fs';",
      'a/nodefs.test.ts': "import fs from 'node:fs/promises';",
      'a/child.test.ts': "import { execFileSync } from 'child_process';",
      'a/dirname.test.ts': 'const p = join(__dirname, "..");',
      'a/glob.test.ts': "const m = import.meta.glob('./x/*.ts');",
      'a/raw.test.ts': "import css from '../x.css?raw';",
      'a/cwd.test.ts': 'const root = process.cwd();',
      'a/plain.test.tsx': "import { render } from '@testing-library/react';",
    };
    const out = alwaysRun(tests);
    expect(Object.keys(out).sort()).toEqual([
      'a/child.test.ts', 'a/cwd.test.ts', 'a/dirname.test.ts', 'a/fs.test.ts', 'a/glob.test.ts', 'a/nodefs.test.ts', 'a/raw.test.ts',
    ]);
  });

  it('flags the guards by name even when they only import', () => {
    const out = alwaysRun({
      '__tests__/i18n-locale-parity.test.ts': "import en from '@/messages/en.json';",
      '__tests__/contracts/node-contracts.test.ts': "import x from './x';",
      'lib/__tests__/sidePanelWiring.test.ts': '',
      'x/__tests__/ci-anything.test.ts': '',
      'x/__tests__/Button.test.tsx': '',
    });
    expect(Object.keys(out).sort()).toEqual([
      '__tests__/contracts/node-contracts.test.ts', '__tests__/i18n-locale-parity.test.ts',
      'lib/__tests__/sidePanelWiring.test.ts', 'x/__tests__/ci-anything.test.ts',
    ]);
  });

  it('on the real suite, holds the parity, contract and CI guards, and a minority of the files', () => {
    const tests: Record<string, string> = {};
    const walk = (dir: string) => {
      for (const e of readdirSync(dir, { withFileTypes: true })) {
        if (['node_modules', '.next', 'dist', 'coverage', 'e2e'].includes(e.name)) continue;
        const p = join(dir, e.name);
        if (e.isDirectory()) walk(p);
        else if (isTestFile(e.name)) tests[relative(FRONTEND_ROOT, p).split(sep).join('/')] = readFileSync(p, 'utf8');
      }
    };
    walk(FRONTEND_ROOT);
    const out = alwaysRun(tests);
    for (const guard of ['__tests__/ci-vitest-paths.test.ts', '__tests__/i18n-locale-parity.test.ts',
      '__tests__/contracts/node-contracts.test.ts', '__tests__/ci-vitest-select.test.ts']) {
      expect(out[guard], guard).toBeDefined();
    }
    const share = Object.keys(out).length / Object.keys(tests).length;
    expect(share).toBeGreaterThan(0.02);
    expect(share).toBeLessThan(0.4);
  });
});

describe('requireUsers: the import edges vitest does not follow', () => {
  it('names the sources that require() a local module, and only those', () => {
    const out = requireUsers({
      'components/a/actions.ts': "const { X } = require('@/components/app/X');",
      'lib/b.ts': "const y = require('./y');",
      'lib/c.ts': "const up = require('../c');",
      'lib/pkg.ts': "const lodash = require('lodash');",
      'lib/plain.ts': "import { x } from './x';",
      'lib/__tests__/t.test.ts': "const x = require('./x');",
    });
    expect(out).toEqual(['components/a/actions.ts', 'lib/b.ts', 'lib/c.ts']);
  });

  const SOURCES = {
    'app/og/image.tsx': "import { readFile } from 'node:fs/promises';",
    'lib/glob.ts': "const m = import.meta.glob('./x/*.ts');",
    'lib/req.ts': "const y = require('./y');",
    'components/fleet/actions.ts': "const { P } = require('@/components/app/Panel'); const { Q } = require('../q/index');",
    'lib/path-only.ts': "import path from 'path';",
    'lib/plain.ts': "import { x } from './x';",
    'lib/__tests__/reads.test.ts': "import fs from 'fs';",
  };

  it('graphHoles hands over, on every change, the require() users and the sources that read files, never a plain import', () => {
    // Regression (audit of 2026-09-30): the require() users were narrowed to "the change touches a
    // required module", which missed a change to anything the required module imports in turn.
    expect(graphHoles(SOURCES)).toEqual(['app/og/image.tsx', 'components/fleet/actions.ts', 'lib/glob.ts', 'lib/req.ts']);
  });

  it('finds the real lazily-required side panel actions and always hands them over', () => {
    const file = 'components/agent-fleet/fleetSidePanelActions.ts';
    const text = readFileSync(join(FRONTEND_ROOT, file), 'utf8');
    expect(requireUsers({ [file]: text })).toEqual([file]);
    expect(graphHoles({ [file]: text })).toEqual([file]);
  });
});

describe('readSources: the tree the always-run scan walks', () => {
  it('skips Playwright only at the root, and build output and dependencies anywhere', () => {
    const root = mkdtempSync(join(tmpdir(), 'ci-vitest-'));
    try {
      for (const p of ['e2e/pw.test.ts', 'lib/e2e/nested.test.ts', 'lib/node_modules/x.test.ts', 'dist/d.test.ts', 'lib/ok.test.ts']) {
        mkdirSync(join(root, p, '..'), { recursive: true });
        writeFileSync(join(root, p), '');
      }
      expect(Object.keys(readSources(isTestFile, root)).sort()).toEqual(['lib/e2e/nested.test.ts', 'lib/ok.test.ts']);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });
});

describe('the shard split and the size cap', () => {
  it('splits a selection into disjoint shards that together cover it', () => {
    const files = ['c.test.ts', 'a.test.ts', 'e.test.ts', 'b.test.ts', 'd.test.ts'];
    const one = shardOf(files, 1, 2);
    const two = shardOf(files, 2, 2);
    expect(one.filter((f) => two.includes(f))).toEqual([]);
    expect([...one, ...two].sort()).toEqual([...files].sort());
    expect(shardOf(['only.test.ts'], 2, 2)).toEqual([]);
  });

  it('runs everything when the selection is over half the suite, or the suite looks empty', () => {
    expect(tooLarge(10, 100)).toBe(false);
    expect(tooLarge(50, 100)).toBe(false);
    expect(tooLarge(51, 100)).toBe(true);
    expect(tooLarge(0, 0)).toBe(true);
  });

  it('reads only an exact --shard=<i>/<n>', () => {
    expect(parseShard('--shard=1/2')).toEqual([1, 2]);
    expect(parseShard('--shard=2/2')).toEqual([2, 2]);
    for (const bad of ['--shard=3/2', '--shard=0/2', '--shard=1', 'shard=1/2', undefined]) {
      expect(parseShard(bad as string), String(bad)).toBeNull();
    }
    expect(fullRunArgs('2/2')).toEqual(['run', '--shard=2/2']);
    // The fallback of a shard whose selection broke: the whole suite, no shard, no filter.
    expect(fullRunArgs(null)).toEqual(['run']);
  });

  it('reads git name-status output, tabs in paths and CRLF included', () => {
    expect(parseNameStatus('M\tfrontend/a.ts\r\nD\tnotes/b c.md\nA\tx\ty.ts\n')).toEqual([
      { status: 'M', path: 'frontend/a.ts' },
      { status: 'D', path: 'notes/b c.md' },
      { status: 'A', path: 'x\ty.ts' },
    ]);
  });
});
