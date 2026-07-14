import { describe, expect, it } from 'vitest';

import enMessages from '@/messages/en.json';
import { AVATAR_TOOLS, AVATAR_TOOL_IDS, getAvatarTool } from '../avatarTools';

// The tool-id list lives in FOUR hand-synced places: this registry, the backend
// AgentService.AVATAR_TOOLS allow-list, the agent tool's `avatar` param description,
// and the avatarPicker.tools i18n block. The Java side pins list<->description
// (AgentToolsProviderTest); these pin registry<->i18n and the id format the backend
// regex ('[a-z0-9-]+') and frontend URLSearchParams round-trip both rely on.
describe('avatarTools - registry invariants', () => {
  it('ids are unique lowercase slugs (the only shape the backend regex accepts)', () => {
    const ids = AVATAR_TOOLS.map((t) => t.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const id of ids) {
      expect(id).toMatch(/^[a-z0-9-]+$/);
    }
    expect(AVATAR_TOOL_IDS.size).toBe(ids.length);
  });

  it('registry ids and en.json tool labels are in exact 1:1 sync', () => {
    const ids = AVATAR_TOOLS.map((t) => t.id).sort();
    const labelKeys = Object.keys(
      (enMessages as { avatarPicker: { tools: Record<string, string> } }).avatarPicker.tools,
    ).sort();
    expect(labelKeys).toEqual(ids);
  });

  it('every tool has a renderable icon component', () => {
    for (const tool of AVATAR_TOOLS) {
      expect(tool.Icon, `tool '${tool.id}' must have an icon`).toBeTruthy();
    }
  });

  it('getAvatarTool resolves known ids and returns null for unknown/absent', () => {
    expect(getAvatarTool('wrench')?.id).toBe('wrench');
    expect(getAvatarTool('sword')).toBeNull();
    expect(getAvatarTool(null)).toBeNull();
    expect(getAvatarTool(undefined)).toBeNull();
  });
});
