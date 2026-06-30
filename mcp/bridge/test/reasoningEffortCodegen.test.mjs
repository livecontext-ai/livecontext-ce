/**
 * Drift guard for the reasoning-effort codegen. Fails if any committed binding
 * (Java / JS / TS) no longer matches what the generator emits from
 * shared/contracts/reasoning-effort.json - i.e. someone hand-edited a GENERATED
 * file, or edited the JSON without re-running the generator. Non-mutating: it
 * re-emits in memory and compares to disk, it does NOT write.
 *
 * Run: node --test (part of `npm test` in mcp/bridge).
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const gen = require('../../../shared/contracts/scripts/generate-reasoning-effort.js');

const schema = gen.load();
const derived = gen.derive(schema);

const normalizeNewlines = (value) => value.replace(/\r\n/g, '\n');

const cases = [
  ['ReasoningEffort.java', gen.JAVA_OUT, gen.emitJava(schema, derived)],
  ['reasoningEffort.mjs', gen.JS_OUT, gen.emitJs(schema, derived)],
  ['reasoningEffort.ts', gen.TS_OUT, gen.emitTs(schema, derived)],
];

for (const [label, outPath, expected] of cases) {
  test(`generated ${label} is in sync with reasoning-effort.json (re-run generate-reasoning-effort.js if this fails)`, () => {
    const onDisk = readFileSync(outPath, 'utf-8');
    assert.equal(normalizeNewlines(onDisk), normalizeNewlines(expected));
  });
}
