import { describe, it, expect } from 'vitest';
import { getToolDescription, getToolIconType } from '../activityGrouping';

// The unified `credential` tool (actions: list / variables / set_variable /
// require) replaced the old request_credential in the activity feed. These
// tests lock in the per-action labels so the feed says WHAT the agent is doing
// ("Set variable $vars.api_url", not a bare "credential"), the legacy
// request_credential alias defaulting to the require label, and the 'key' icon
// mapping for both tool names.

describe('getToolDescription - credential action labels', () => {
  it('action=list → "List connected services"', () => {
    expect(getToolDescription('credential', JSON.stringify({ action: 'list' })))
      .toBe('List connected services');
  });

  it('action=variables → "List workflow variables"', () => {
    expect(getToolDescription('credential', JSON.stringify({ action: 'variables' })))
      .toBe('List workflow variables');
  });

  it('action=set_variable shows the $vars.<name> reference', () => {
    expect(getToolDescription('credential', JSON.stringify({ action: 'set_variable', name: 'api_url', value: 'https://x' })))
      .toBe('Set variable $vars.api_url');
  });

  it('action=set_variable truncates a long variable name at 20 chars', () => {
    const long = 'a_very_long_variable_name_over_twenty';
    const out = getToolDescription('credential', JSON.stringify({ action: 'set_variable', name: long }))!;
    expect(out).toBe(`Set variable $vars.${long.substring(0, 20)}…`);
  });

  it('action=set_variable without a name falls back to the generic label', () => {
    expect(getToolDescription('credential', JSON.stringify({ action: 'set_variable', value: 'x' })))
      .toBe('Set workflow variable');
  });

  it('action=require lists the requested services', () => {
    expect(getToolDescription('credential', JSON.stringify({ action: 'require', services: ['gmail', 'slack'], reason: 'send mail' })))
      .toBe('Request credential: gmail, slack');
  });

  it('action=require truncates a long service list at 25 chars', () => {
    const out = getToolDescription('credential', JSON.stringify({ action: 'require', services: ['googleanalytics', 'googlesheets'] }))!;
    expect(out.startsWith('Request credential: ')).toBe(true);
    expect(out.endsWith('…')).toBe(true);
  });

  it('action=require without services falls back to the generic label', () => {
    expect(getToolDescription('credential', JSON.stringify({ action: 'require' })))
      .toBe('Request credential');
  });

  it('unknown or missing action falls back to "Credentials"', () => {
    expect(getToolDescription('credential', JSON.stringify({ action: 'rotate' })))
      .toBe('Credentials');
    expect(getToolDescription('credential', JSON.stringify({})))
      .toBe('Credentials');
  });

  it('tool name matching is case-insensitive (bridge may capitalize)', () => {
    expect(getToolDescription('Credential', JSON.stringify({ action: 'variables' })))
      .toBe('List workflow variables');
  });
});

describe('getToolDescription - legacy request_credential alias', () => {
  it('no action defaults to the require label (the alias only ever required credentials)', () => {
    expect(getToolDescription('request_credential', JSON.stringify({ services: ['gmail'], reason: 'x' })))
      .toBe('Request credential: gmail');
  });

  it('no action and no services → generic require label, never "Credentials"', () => {
    expect(getToolDescription('request_credential', JSON.stringify({ reason: 'x' })))
      .toBe('Request credential');
  });

  it('an explicit action on the legacy name is still honored (same dispatch as credential)', () => {
    expect(getToolDescription('request_credential', JSON.stringify({ action: 'variables' })))
      .toBe('List workflow variables');
  });
});

describe('getToolIconType - credential tools map to the key icon', () => {
  it("'credential' → 'key'", () => {
    expect(getToolIconType('credential')).toBe('key');
  });

  it("legacy 'request_credential' → 'key'", () => {
    expect(getToolIconType('request_credential')).toBe('key');
  });

  it("'get_connected_services' → 'key'", () => {
    expect(getToolIconType('get_connected_services')).toBe('key');
  });
});
