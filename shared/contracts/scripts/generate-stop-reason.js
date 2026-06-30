#!/usr/bin/env node

/**
 * Generates language-specific bindings for the AgentStopReason contract.
 *
 * Reads:  shared/contracts/agent-stop-reason.json   (source of truth)
 * Writes:
 *   - backend/agent-common/src/main/java/com/apimarketplace/agent/domain/AgentStopReason.java
 *   - mcp/bridge/lib/agentStopReason.js
 *   - frontend/types/agentStopReason.ts
 *
 * Also prints, on stdout, the i18n keys to merge into messages/{en,fr}.json.
 *
 * Usage:  node shared/contracts/scripts/generate-stop-reason.js
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const SCHEMA_PATH = path.join(ROOT, 'shared', 'contracts', 'agent-stop-reason.json');

const JAVA_OUT = path.join(
  ROOT,
  'backend',
  'agent-common',
  'src',
  'main',
  'java',
  'com',
  'apimarketplace',
  'agent',
  'domain',
  'AgentStopReason.java'
);
const JS_OUT = path.join(ROOT, 'mcp', 'bridge', 'lib', 'agentStopReason.js');
const TS_OUT = path.join(ROOT, 'frontend', 'types', 'agentStopReason.ts');

function load() {
  return JSON.parse(fs.readFileSync(SCHEMA_PATH, 'utf-8'));
}

function ensureDir(filePath) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
}

// ─── Java ────────────────────────────────────────────────────────────────────

function emitJava(schema) {
  const lines = [];
  lines.push('package com.apimarketplace.agent.domain;');
  lines.push('');
  lines.push('/**');
  lines.push(' * Reason why an agent execution terminated.');
  lines.push(' *');
  lines.push(' * <p><strong>GENERATED FILE - do not edit by hand.</strong>');
  lines.push(' * Source of truth: {@code shared/contracts/agent-stop-reason.json}.');
  lines.push(' * Re-run {@code node shared/contracts/scripts/generate-stop-reason.js} after editing the JSON.</p>');
  lines.push(' *');
  lines.push(' * <p>Each value carries a {@link TerminalCategory}:</p>');
  lines.push(' * <ul>');
  for (const [name, desc] of Object.entries(schema.terminalCategories)) {
    lines.push(` *   <li><b>${name}</b> - ${desc}</li>`);
  }
  lines.push(' * </ul>');
  lines.push(' */');
  lines.push('public enum AgentStopReason {');

  for (let i = 0; i < schema.values.length; i++) {
    const v = schema.values[i];
    const cat = v.terminal.toUpperCase();
    const desc = v.description.replace(/"/g, '\\"');
    const userVisible = v.userVisible.replace(/"/g, '\\"');
    const sep = i === schema.values.length - 1 ? ';' : ',';
    lines.push('');
    lines.push(`    /** ${v.description} */`);
    lines.push(
      `    ${v.name}(TerminalCategory.${cat}, "${userVisible}", "${desc}")${sep}`
    );
  }

  lines.push('');
  lines.push('    private final TerminalCategory terminal;');
  lines.push('    private final String userVisible;');
  lines.push('    private final String description;');
  lines.push('');
  lines.push('    AgentStopReason(TerminalCategory terminal, String userVisible, String description) {');
  lines.push('        this.terminal = terminal;');
  lines.push('        this.userVisible = userVisible;');
  lines.push('        this.description = description;');
  lines.push('    }');
  lines.push('');
  lines.push('    public TerminalCategory terminal() { return terminal; }');
  lines.push('    public String userVisible() { return userVisible; }');
  lines.push('    public String getDescription() { return description; }');
  lines.push('');
  lines.push('    /** True for COMPLETED, MAX_ITERATIONS, BUDGET_EXHAUSTED, LOOP_DETECTED, etc. - runs that produced usable output. */');
  lines.push('    public boolean isSuccessLike() { return terminal == TerminalCategory.SUCCESS; }');
  lines.push('');
  lines.push('    /** True when output is potentially incomplete (max iter, timeout, budget, loop, user stop). */');
  lines.push('    public boolean isPartial() { return terminal == TerminalCategory.PARTIAL; }');
  lines.push('');
  lines.push('    public boolean isFailure() { return terminal == TerminalCategory.FAILURE; }');
  lines.push('');
  lines.push('    /**');
  lines.push('     * Lenient parser: returns ERROR if the input does not match any enum value');
  lines.push('     * (instead of throwing). Use this when the value comes from an external source');
  lines.push('     * such as the bridge HTTP response.');
  lines.push('     */');
  lines.push('    public static AgentStopReason valueOfOrError(String name) {');
  lines.push('        if (name == null) return ERROR;');
  lines.push('        try {');
  lines.push('            return AgentStopReason.valueOf(name);');
  lines.push('        } catch (IllegalArgumentException e) {');
  lines.push('            return ERROR;');
  lines.push('        }');
  lines.push('    }');
  lines.push('');
  lines.push('    /** Three-bucket terminal classification driven by the contract. */');
  lines.push('    public enum TerminalCategory { SUCCESS, PARTIAL, FAILURE }');
  lines.push('}');
  lines.push('');

  return lines.join('\n');
}

// ─── JS (bridge) ─────────────────────────────────────────────────────────────

function emitJs(schema) {
  const lines = [];
  lines.push('// GENERATED FILE - do not edit by hand.');
  lines.push('// Source of truth: shared/contracts/agent-stop-reason.json');
  lines.push('// Re-run: node shared/contracts/scripts/generate-stop-reason.js');
  lines.push('');
  lines.push('export const TerminalCategory = Object.freeze({');
  lines.push('  SUCCESS: "SUCCESS",');
  lines.push('  PARTIAL: "PARTIAL",');
  lines.push('  FAILURE: "FAILURE",');
  lines.push('});');
  lines.push('');
  lines.push('export const AgentStopReason = Object.freeze({');
  for (const v of schema.values) {
    lines.push(`  ${v.name}: "${v.name}",`);
  }
  lines.push('});');
  lines.push('');
  lines.push('export const STOP_REASON_META = Object.freeze({');
  for (const v of schema.values) {
    const safeDesc = v.description.replace(/"/g, '\\"');
    const safeVis = v.userVisible.replace(/"/g, '\\"');
    lines.push(`  ${v.name}: Object.freeze({`);
    lines.push(`    name: "${v.name}",`);
    lines.push(`    terminal: "${v.terminal.toUpperCase()}",`);
    lines.push(`    userVisible: "${safeVis}",`);
    lines.push(`    description: "${safeDesc}",`);
    if (v.scopes) {
      lines.push(`    scopes: ${JSON.stringify(v.scopes)},`);
    }
    lines.push(`  }),`);
  }
  lines.push('});');
  lines.push('');
  lines.push('/** Returns ERROR for any unknown input. */');
  lines.push('export function parseStopReason(name) {');
  lines.push('  if (name && Object.prototype.hasOwnProperty.call(AgentStopReason, name)) {');
  lines.push('    return name;');
  lines.push('  }');
  lines.push('  return AgentStopReason.ERROR;');
  lines.push('}');
  lines.push('');
  lines.push('export function isSuccessLike(name) {');
  lines.push('  const meta = STOP_REASON_META[name];');
  lines.push('  return !!meta && meta.terminal === "SUCCESS";');
  lines.push('}');
  lines.push('');
  lines.push('export function isPartial(name) {');
  lines.push('  const meta = STOP_REASON_META[name];');
  lines.push('  return !!meta && meta.terminal === "PARTIAL";');
  lines.push('}');
  lines.push('');
  lines.push('export function isFailure(name) {');
  lines.push('  const meta = STOP_REASON_META[name];');
  lines.push('  return !!meta && meta.terminal === "FAILURE";');
  lines.push('}');
  lines.push('');

  return lines.join('\n');
}

// ─── TS (frontend) ───────────────────────────────────────────────────────────

function emitTs(schema) {
  const lines = [];
  lines.push('// GENERATED FILE - do not edit by hand.');
  lines.push('// Source of truth: shared/contracts/agent-stop-reason.json');
  lines.push('// Re-run: node shared/contracts/scripts/generate-stop-reason.js');
  lines.push('');
  lines.push('export const TerminalCategory = {');
  lines.push('  SUCCESS: "SUCCESS",');
  lines.push('  PARTIAL: "PARTIAL",');
  lines.push('  FAILURE: "FAILURE",');
  lines.push('} as const;');
  lines.push('export type TerminalCategory = (typeof TerminalCategory)[keyof typeof TerminalCategory];');
  lines.push('');
  lines.push('export const AgentStopReason = {');
  for (const v of schema.values) {
    lines.push(`  ${v.name}: "${v.name}",`);
  }
  lines.push('} as const;');
  lines.push('export type AgentStopReason = (typeof AgentStopReason)[keyof typeof AgentStopReason];');
  lines.push('');
  lines.push('export interface StopReasonMeta {');
  lines.push('  name: AgentStopReason;');
  lines.push('  terminal: TerminalCategory;');
  lines.push('  userVisible: string;');
  lines.push('  description: string;');
  lines.push('  scopes?: readonly string[];');
  lines.push('}');
  lines.push('');
  lines.push('export const STOP_REASON_META: Record<AgentStopReason, StopReasonMeta> = {');
  for (const v of schema.values) {
    const safeDesc = v.description.replace(/"/g, '\\"');
    const safeVis = v.userVisible.replace(/"/g, '\\"');
    lines.push(`  ${v.name}: {`);
    lines.push(`    name: "${v.name}",`);
    lines.push(`    terminal: "${v.terminal.toUpperCase()}",`);
    lines.push(`    userVisible: "${safeVis}",`);
    lines.push(`    description: "${safeDesc}",`);
    if (v.scopes) {
      lines.push(`    scopes: ${JSON.stringify(v.scopes)} as const,`);
    }
    lines.push(`  },`);
  }
  lines.push('};');
  lines.push('');
  lines.push('/** Returns ERROR for any unknown input. */');
  lines.push('export function parseStopReason(name: string | null | undefined): AgentStopReason {');
  lines.push('  if (name && Object.prototype.hasOwnProperty.call(AgentStopReason, name)) {');
  lines.push('    return name as AgentStopReason;');
  lines.push('  }');
  lines.push('  return AgentStopReason.ERROR;');
  lines.push('}');
  lines.push('');
  lines.push('export function isSuccessLike(name: AgentStopReason): boolean {');
  lines.push('  return STOP_REASON_META[name].terminal === "SUCCESS";');
  lines.push('}');
  lines.push('');
  lines.push('export function isPartial(name: AgentStopReason): boolean {');
  lines.push('  return STOP_REASON_META[name].terminal === "PARTIAL";');
  lines.push('}');
  lines.push('');
  lines.push('export function isFailure(name: AgentStopReason): boolean {');
  lines.push('  return STOP_REASON_META[name].terminal === "FAILURE";');
  lines.push('}');
  lines.push('');

  return lines.join('\n');
}

// ─── i18n ────────────────────────────────────────────────────────────────────

function emitI18n(schema) {
  const obj = { label: 'Stop reason' };
  for (const v of schema.values) {
    obj[v.name] = v.userVisible;
  }
  return JSON.stringify({ agentStopReason: obj }, null, 2);
}

// ─── main ────────────────────────────────────────────────────────────────────

function main() {
  const schema = load();

  const java = emitJava(schema);
  const js = emitJs(schema);
  const ts = emitTs(schema);
  const i18n = emitI18n(schema);

  ensureDir(JAVA_OUT);
  ensureDir(JS_OUT);
  ensureDir(TS_OUT);

  fs.writeFileSync(JAVA_OUT, java);
  fs.writeFileSync(JS_OUT, js);
  fs.writeFileSync(TS_OUT, ts);

  console.log('[generate-stop-reason] wrote', JAVA_OUT);
  console.log('[generate-stop-reason] wrote', JS_OUT);
  console.log('[generate-stop-reason] wrote', TS_OUT);
  console.log('');
  console.log('--- i18n keys (merge into frontend/messages/{en,fr}.json under top level) ---');
  console.log(i18n);
}

main();
