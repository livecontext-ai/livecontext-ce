import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { buildTriggerShortcutSelection, TRIGGER_SHORTCUTS } from '../triggerShortcuts';
import { matchNodeClass } from '../../nodes/nodeClasses';

describe('application trigger compositions', () => {
  it.each(TRIGGER_SHORTCUTS)('$id uses a real catalog operation and wires its parameters', (definition) => {
    // Read the source catalog so a renamed operation or parameter breaks this test.
    const seed = JSON.parse(readFileSync(resolve(process.cwd(), '../scripts/api-migrations', `${definition.apiSlug}.json`), 'utf8'));
    const operation = seed.endpoints.find((endpoint: { name: string }) => endpoint.name === definition.toolSlug);
    expect(operation).toBeDefined();
    const parameters = operation.params;
    const apiSlug = definition.apiName.toLowerCase().replaceAll(' ', '-');
    const toolSlug = `${apiSlug}-${definition.toolSlug.replaceAll('_', '-')}-catalog`;
    const catalog = {
      api: { slug: apiSlug, apiName: seed.apiName, description: seed.apiDescription, iconSlug: seed.iconSlug },
      tool: { slug: toolSlug, name: operation.name, description: operation.description, method: operation.method, toolId: 'catalog-uuid' },
      details: { parameters, credentials: [{ credentialName: apiSlug, isRequired: true }], responses: [] },
    };
    const selection = buildTriggerShortcutSelection(definition, {
      description: 'Application trigger', triggerLabel: 'Événement reçu',
    }, catalog);
    expect(selection.nodes).toHaveLength(2);
    expect(selection.connections).toEqual([{ sourceIndex: 0, targetIndex: 1 }]);
    const [trigger, action] = selection.nodes.map(({ item }) => ({ ...item.initialData, ...item }));
    expect(matchNodeClass(trigger)?.id).toBe(definition.mode === 'polling' ? 'schedule-trigger' : 'webhook-trigger');
    expect(trigger).not.toHaveProperty('apiData');
    expect(matchNodeClass(action)?.id).toBe('mcp-tool');
    expect(action.toolData).toMatchObject({ toolSlug, toolId: 'catalog-uuid', apiSlug, parameters });
    expect(action.toolData?.parameters?.length).toBeGreaterThan(0);
    for (const [name, value] of Object.entries(action.paramExpressions || {})) {
      expect(parameters.map((parameter: { name: string }) => parameter.name)).toContain(name);
      if (definition.mode === 'webhook') {
        expect(value).toBe(`{{trigger:evenement_recu.output.payload.${definition.payloadParams[name]}}}`);
      }
    }
    if (definition.mode === 'polling') {
      expect(trigger.scheduleTriggerData?.cronExpression).toBe(definition.cron);
    }
    expect(() => buildTriggerShortcutSelection(definition, {
      description: 'Application trigger', triggerLabel: 'Incoming event',
    }, { ...catalog, details: { parameters: [] } })).toThrow('Catalog parameters do not match');
  });
});
