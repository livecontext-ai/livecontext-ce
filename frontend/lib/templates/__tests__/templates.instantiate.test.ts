/**
 * Instantiation of a template that carries an interface.
 *
 * A plan references an interface by the UUID of a real entity, and NOTHING
 * validates that id when the plan is saved: a bogus one saves with HTTP 200 and
 * the interface renders empty on the first run, with no error anywhere. So the
 * entity has to be created first and its real id injected, and a failure has to
 * abort loudly rather than produce a workflow that looks fine and is not.
 */

import { createTranslator } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import enMessages from '@/messages/en.json';

const mocks = vi.hoisted(() => ({
  saveWorkflowPlan: vi.fn(),
  createInterface: vi.fn(),
  rememberWorkflowName: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    saveWorkflowPlan: mocks.saveWorkflowPlan,
    createInterface: mocks.createInterface,
  },
}));
vi.mock('@/lib/workflows/recentWorkflowNames', () => ({
  rememberWorkflowName: mocks.rememberWorkflowName,
}));

import { instantiateWorkflowTemplate } from '../instantiate';
import { getTemplates } from '../index';
import type { Translate } from '../hydrate';
import type { WorkflowTemplate } from '../types';

const t = createTranslator({ locale: 'en', messages: enMessages as any }) as unknown as Translate;

async function loadInterfaceTemplate(): Promise<WorkflowTemplate> {
  const entry = getTemplates('workflow').find((e) => e.meta.slug === 'interface-page')!;
  return (await entry.load()) as WorkflowTemplate;
}

/** The plan actually sent to the backend. */
function savedPlan() {
  return JSON.parse(mocks.saveWorkflowPlan.mock.calls[0][0].planJson);
}

beforeEach(() => {
  mocks.saveWorkflowPlan.mockResolvedValue({ workflowId: 'server-wf-1' });
  mocks.createInterface.mockResolvedValue({ id: 'real-interface-uuid' });
  Object.assign(globalThis.crypto, { randomUUID: () => 'local-uuid-1' });
});

afterEach(() => vi.clearAllMocks());

describe('instantiating a template that carries an interface', () => {
  it('creates the interface entity BEFORE saving the plan', async () => {
    const template = await loadInterfaceTemplate();
    await instantiateWorkflowTemplate(template, t, 'My page');

    expect(mocks.createInterface).toHaveBeenCalledTimes(1);
    expect(mocks.createInterface.mock.invocationCallOrder[0]).toBeLessThan(
      mocks.saveWorkflowPlan.mock.invocationCallOrder[0],
    );
  });

  it('sends the interface content, resolved and non-empty', async () => {
    const template = await loadInterfaceTemplate();
    await instantiateWorkflowTemplate(template, t, 'My page');

    const payload = mocks.createInterface.mock.calls[0][0];
    expect(payload.htmlTemplate).toContain('{{headline}}');
    expect(payload.cssTemplate.length).toBeGreaterThan(0);
    expect(payload.interfaceType).toBe('html');
    expect(payload.isPublic).toBe(false);
    // The description is an i18n key in the file and must arrive translated.
    expect(payload.description.startsWith('templates.')).toBe(false);
  });

  it('injects the REAL entity id into the plan', async () => {
    const template = await loadInterfaceTemplate();
    await instantiateWorkflowTemplate(template, t, 'My page');

    const iface = savedPlan().interfaces[0];
    expect(iface.id).toBe('real-interface-uuid');
    // The placeholder must never reach the backend: it would save fine and
    // then render nothing at run time.
    expect(iface.id).not.toBe('__template_interface__');
  });

  it('strips the inline content so it is not persisted as dead weight', async () => {
    const template = await loadInterfaceTemplate();
    await instantiateWorkflowTemplate(template, t, 'My page');

    const iface = savedPlan().interfaces[0];
    const leftovers = Object.keys(iface).filter((k) => k.startsWith('_template_'));
    expect(leftovers).toEqual([]);
  });

  it('keeps the edge pointing at the interface, which is keyed by LABEL not id', async () => {
    const template = await loadInterfaceTemplate();
    await instantiateWorkflowTemplate(template, t, 'My page');

    // Injecting the UUID must not disturb the graph: edges resolve by label.
    expect(savedPlan().edges).toContainEqual({
      from: 'core:build_page_data',
      to: 'interface:result_page',
    });
  });

  it('aborts without saving a workflow when the interface cannot be created', async () => {
    mocks.createInterface.mockRejectedValue(new Error('interface service down'));
    const template = await loadInterfaceTemplate();

    await expect(instantiateWorkflowTemplate(template, t, 'My page')).rejects.toThrow(
      'interface service down',
    );
    // A workflow saved here would point at a nonexistent interface and fail silently.
    expect(mocks.saveWorkflowPlan).not.toHaveBeenCalled();
  });

  it('aborts when the interface service answers without an id', async () => {
    mocks.createInterface.mockResolvedValue({});
    const template = await loadInterfaceTemplate();

    await expect(instantiateWorkflowTemplate(template, t, 'My page')).rejects.toThrow();
    expect(mocks.saveWorkflowPlan).not.toHaveBeenCalled();
  });

  it('creates no interface for a template that has none', async () => {
    const entry = getTemplates('workflow').find((e) => e.meta.slug === 'hello-workflow')!;
    await instantiateWorkflowTemplate((await entry.load()) as WorkflowTemplate, t, 'Plain');

    expect(mocks.createInterface).not.toHaveBeenCalled();
    expect(mocks.saveWorkflowPlan).toHaveBeenCalledTimes(1);
  });
});
