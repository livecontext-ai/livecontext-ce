// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

/**
 * Templates live behind a button next to "Create", in a modal.
 *
 * The assertions that matter: nothing shows until the user asks for it, the
 * trigger disappears for a VIEWER, creating sends the top-level workflowId the
 * backend needs, the modal closes once the resource exists, and a double click
 * cannot create twice.
 */

const mocks = vi.hoisted(() => ({
  saveWorkflowPlan: vi.fn(),
  rememberWorkflowName: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: { saveWorkflowPlan: mocks.saveWorkflowPlan },
}));
vi.mock('@/lib/workflows/recentWorkflowNames', () => ({
  rememberWorkflowName: mocks.rememberWorkflowName,
}));
// The preview reuses the real workflow-card visuals; stub them so these tests
// stay about behaviour, not about icon rendering.
vi.mock('@/components/WorkflowNodeIcons', () => ({
  WorkflowNodeIcons: ({ nodeIcons }: { nodeIcons?: unknown[] }) => (
    <div data-testid="node-icons">{nodeIcons?.length ?? 0}</div>
  ),
}));
vi.mock('@/components/agents', () => ({
  AvatarDisplay: ({ avatarUrl }: { avatarUrl?: string }) => (
    <div data-testid="avatar">{avatarUrl}</div>
  ),
}));

import { TemplateGallery } from '../TemplateGallery';

function renderGallery(props: Partial<React.ComponentProps<typeof TemplateGallery>> = {}) {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as any}>
      <TemplateGallery kind="workflow" canMutate {...props} />
    </NextIntlClientProvider>,
  );
}

/** Clicks and flushes the resulting state updates (the handler is async). */
async function clickIt(element: HTMLElement) {
  await act(async () => {
    fireEvent.click(element);
  });
}

async function openGallery() {
  await clickIt(screen.getByRole('button', { name: enMessages.templates.gallery.browse }));
}

function useButton(title: string) {
  return screen.getByRole('button', {
    name: enMessages.templates.gallery.use.replace('{name}', title),
  });
}

async function useCard(title: string) {
  await clickIt(useButton(title));
}

beforeEach(() => {
  mocks.saveWorkflowPlan.mockResolvedValue({ workflowId: 'server-id-1' });
  Object.assign(globalThis.crypto, { randomUUID: () => 'local-uuid-1' });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('TemplateGallery trigger', () => {
  it('shows only a button, with no cards, until it is opened', () => {
    renderGallery();
    expect(screen.getByRole('button', { name: enMessages.templates.gallery.browse })).toBeInTheDocument();
    expect(screen.queryByText(enMessages.templates.workflow.helloWorkflow.title)).toBeNull();
  });

  it('renders nothing at all for a caller who cannot create', () => {
    const { container } = renderGallery({ canMutate: false });
    expect(container).toBeEmptyDOMElement();
  });

  it('reveals the templates once opened', async () => {
    renderGallery();
    await openGallery();
    expect(screen.getByText(enMessages.templates.workflow.helloWorkflow.title)).toBeInTheDocument();
    expect(screen.getByText(enMessages.templates.workflow.errorHandler.title)).toBeInTheDocument();
  });

  it('shows agent templates when asked for the agent kind', async () => {
    renderGallery({ kind: 'agent' });
    await openGallery();
    expect(screen.getByText(enMessages.templates.agent.basicAssistant.title)).toBeInTheDocument();
    // Workflow templates must not leak into the agent gallery.
    expect(screen.queryByText(enMessages.templates.workflow.helloWorkflow.title)).toBeNull();
  });
});

describe('TemplateGallery card previews', () => {
  it('previews a workflow with its node icons, one per node in the plan', async () => {
    renderGallery();
    await openGallery();
    // hello-workflow is a manual trigger plus one transform.
    const counts = screen.getAllByTestId('node-icons').map((n) => n.textContent);
    expect(counts).toContain('2');
    expect(screen.queryByTestId('avatar')).toBeNull();
  });

  it('previews an agent with its avatar', async () => {
    renderGallery({ kind: 'agent' });
    await openGallery();
    const avatars = screen.getAllByTestId('avatar').map((n) => n.textContent);
    expect(avatars).toContain('preset:purple');
    expect(screen.queryByTestId('node-icons')).toBeNull();
  });
});

describe('TemplateGallery workflow instantiation', () => {
  it('sends workflowId as a TOP-LEVEL field and reports the id the server echoed', async () => {
    const onWorkflowCreated = vi.fn();
    renderGallery({ onWorkflowCreated });
    await openGallery();

    await useCard(enMessages.templates.workflow.helloWorkflow.title);

    await waitFor(() => expect(mocks.saveWorkflowPlan).toHaveBeenCalledTimes(1));
    const body = mocks.saveWorkflowPlan.mock.calls[0][0];

    // Without this top-level field the backend mints its own UUID and the
    // redirect lands on a 404 builder.
    expect(body.workflowId).toBe('local-uuid-1');
    expect(typeof body.planJson).toBe('string');

    const plan = JSON.parse(body.planJson);
    expect(plan.id).toBeUndefined();
    expect(plan.name).toBe(enMessages.templates.workflow.helloWorkflow.title);
    // Notes must arrive translated, not as raw key paths.
    expect(plan.notes.every((n: any) => !n.text.startsWith('templates.'))).toBe(true);
    expect(plan.notes[0].text).toBe(enMessages.templates.workflow.helloWorkflow.notes.trigger.text);

    // The server's id wins over the locally generated one.
    await waitFor(() => expect(onWorkflowCreated).toHaveBeenCalledWith('server-id-1'));
  });

  it('closes the modal once the workflow exists', async () => {
    renderGallery();
    await openGallery();
    await useCard(enMessages.templates.workflow.helloWorkflow.title);

    await waitFor(() =>
      expect(screen.queryByText(enMessages.templates.workflow.helloWorkflow.description)).toBeNull(),
    );
  });

  it('suffixes the name when one is already taken', async () => {
    renderGallery({ existingNames: [enMessages.templates.workflow.helloWorkflow.title] });
    await openGallery();

    await useCard(enMessages.templates.workflow.helloWorkflow.title);

    await waitFor(() => expect(mocks.saveWorkflowPlan).toHaveBeenCalled());
    const plan = JSON.parse(mocks.saveWorkflowPlan.mock.calls[0][0].planJson);
    expect(plan.name).toBe(`${enMessages.templates.workflow.helloWorkflow.title} (2)`);
  });

  it('creates only once when the card is clicked twice in a row', async () => {
    let resolveSave: (v: unknown) => void = () => {};
    mocks.saveWorkflowPlan.mockReturnValue(
      new Promise((resolve) => {
        resolveSave = resolve;
      }),
    );
    renderGallery();
    await openGallery();

    // Hold on to the element: once the first click lands, the button relabels
    // to "Creating..." and disables, so it can no longer be found by its
    // original name. Clicking the same node twice is the honest simulation of
    // an impatient double click.
    const button = useButton(enMessages.templates.workflow.helloWorkflow.title);
    await clickIt(button);
    expect(button).toBeDisabled();
    await clickIt(button);

    resolveSave({ workflowId: 'server-id-1' });
    await waitFor(() => expect(mocks.saveWorkflowPlan).toHaveBeenCalledTimes(1));
  });

  it('reports the failure and keeps the modal open when the save fails', async () => {
    const onError = vi.fn();
    const onWorkflowCreated = vi.fn();
    mocks.saveWorkflowPlan.mockRejectedValue(new Error('boom'));
    renderGallery({ onError, onWorkflowCreated });
    await openGallery();

    await useCard(enMessages.templates.workflow.helloWorkflow.title);

    await waitFor(() => expect(onError).toHaveBeenCalledWith('boom'));
    expect(onWorkflowCreated).not.toHaveBeenCalled();
    // Staying open lets the user retry without reopening.
    expect(screen.getByText(enMessages.templates.workflow.helloWorkflow.description)).toBeInTheDocument();
  });
});

describe('TemplateGallery agent selection', () => {
  it('hands a prefilled agent to the parent WITHOUT creating anything', async () => {
    const onAgentTemplateSelected = vi.fn();
    renderGallery({ kind: 'agent', onAgentTemplateSelected });
    await openGallery();

    await useCard(enMessages.templates.agent.dataAnalyst.title);

    await waitFor(() => expect(onAgentTemplateSelected).toHaveBeenCalledTimes(1));
    const prefilled = onAgentTemplateSelected.mock.calls[0][0];

    expect(prefilled.name).toBe(enMessages.templates.agent.dataAnalyst.title);
    expect(prefilled.systemPrompt).toBe(enMessages.templates.agent.dataAnalyst.systemPrompt);
    // No id, or CreateAgentModal would open in edit mode against a nonexistent agent.
    expect(prefilled.id).toBeUndefined();
    // The i18n key must have been resolved away.
    expect(prefilled.systemPromptKey).toBeUndefined();
    expect(prefilled.toolsConfig.tablesGrant).toBe('all');
    expect(prefilled.toolsConfig.webSearch).toBe(false);

    expect(mocks.saveWorkflowPlan).not.toHaveBeenCalled();
  });
});
